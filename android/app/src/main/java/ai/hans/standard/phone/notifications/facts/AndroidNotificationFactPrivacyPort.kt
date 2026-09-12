package ai.hans.standard.phone.notifications.facts

import ai.hans.standard.notifications.AtomicFileNotificationTriageStorage
import ai.hans.standard.notifications.HansNotificationExclusionPolicy
import ai.hans.standard.notifications.NotificationTriageQueue
import ai.hans.standard.phone.notifications.AtomicNotificationPrivacyPurgeFence
import ai.hans.standard.phone.notifications.NotificationCaptureDecision
import ai.hans.standard.phone.notifications.NotificationPrivacyMutationCoordinator
import ai.hans.standard.phone.notifications.NotificationPrivacyPurgeFence
import ai.hans.standard.phone.notifications.NotificationPrivacyRepository
import android.content.Context

/** Normal access and privacy recovery observe the same existing Android-owned policy/fence. */
internal object AndroidNotificationFactArchiveFactory {
    fun create(
        context: Context,
        privacyRepository: NotificationPrivacyRepository = NotificationPrivacyRepository(context),
        purgeFence: NotificationPrivacyPurgeFence = AtomicNotificationPrivacyPurgeFence(context),
    ): NotificationFactRepository = AndroidNotificationFactRepository(
        context = context.applicationContext,
        privacySnapshot = {
            val status = privacyRepository.status()
            NotificationFactExternalPrivacy(
                policyAvailable = status.policyAvailable,
                purgeRequired = purgeFence.isRequired(),
                excludedPackages = status.userExcludedPackages,
                protectedPackages = status.protectedPackages,
            )
        },
    )
}

/**
 * Production bridge between the archive's durable deletion intent and the atomic claim outbox.
 * Connections are operation-scoped; recreating an InboxStore does not leak SQLite handles.
 * Raw-inbox/announcement erasure is owned by NotificationInboxStore, not this adapter.
 */
internal class AndroidNotificationFactPrivacyPort(
    context: Context,
    privacyRepository: NotificationPrivacyRepository,
    privacyPurgeFence: NotificationPrivacyPurgeFence,
    private val openArchive: () -> NotificationFactRepository = {
        AndroidNotificationFactArchiveFactory.create(context, privacyRepository, privacyPurgeFence)
    },
    private val createQueue: () -> NotificationTriageQueue = {
        NotificationTriageQueue(
            storage = AtomicFileNotificationTriageStorage(context.applicationContext),
            exclusionPolicy = HansNotificationExclusionPolicy(
                ownPackageNames = setOf(context.applicationContext.packageName),
                additionalExclusion = {
                    privacyRepository.captureDecision(it) != NotificationCaptureDecision.Allowed
                },
            ),
        )
    },
) : NotificationFactPrivacyPort {
    override fun begin(request: NotificationFactPrivacyRequest): NotificationFactPrivacyBeginResult =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            runCatching { openArchive().use { it.beginPrivacy(request) } }.getOrElse {
                NotificationFactPrivacyBeginResult.Unavailable(NotificationFactUnavailableReason.IO_FAILURE)
            }
        }

    override fun pending(): List<NotificationFactPrivacyIntent> =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            // Unknown state must throw, not masquerade as no outstanding privacy work.
            openArchive().use { it.pendingIntents() }
        }

    override fun purgeCandidateOutbox(intent: NotificationFactPrivacyIntent): Boolean =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            runCatching {
                openArchive().use { archive ->
                    // In particular, an invented All object cannot authorize corrupt-queue repair.
                    if (archive.pendingIntents().none { it == intent }) return@use false
                    createQueue().purgeFactOutbox(intent.scope)
                }
            }.getOrDefault(false)
        }

    override fun ack(intent: NotificationFactPrivacyIntent): Boolean =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            runCatching {
                openArchive().use { archive -> archive.acknowledgePrivacy(intent) }
            }.getOrDefault(false)
        }

    /** Interactive fact/source tool only. Global/package changes stay in the inbox coordinator. */
    fun forgetScoped(request: NotificationFactPrivacyRequest): NotificationFactPrivacyBeginResult =
        synchronized(NotificationPrivacyMutationCoordinator.lock) {
            require(request.scope is NotificationFactPrivacyScope.Fact ||
                request.scope is NotificationFactPrivacyScope.Source)
            when (val result = begin(request)) {
                is NotificationFactPrivacyBeginResult.Completed -> result
                is NotificationFactPrivacyBeginResult.Pending -> {
                    // The original intent remains durable through any failed external purge/ACK.
                    val completed = purgeCandidateOutbox(result.intent) && ack(result.intent) &&
                        runCatching { pending().none {
                            it.storeEpoch == result.intent.storeEpoch &&
                                it.mutationId == result.intent.mutationId
                        } }.getOrDefault(false)
                    if (completed) NotificationFactPrivacyBeginResult.Completed(result.intent) else result
                }
                else -> result
            }
        }
}
