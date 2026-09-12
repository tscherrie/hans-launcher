package ai.hans.standard.notifications

import ai.hans.standard.phone.consent.AtomicPersistentAndroidConsentStore
import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import ai.hans.standard.phone.notifications.NotificationEventKind
import ai.hans.standard.phone.notifications.NotificationInboxEvent
import ai.hans.standard.phone.notifications.NotificationInboxQuerySource
import ai.hans.standard.phone.publicapi.CalendarInstance
import ai.hans.standard.profile.AtomicFileUserProfileStorage
import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.DnsResolver
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.os.CancellationSignal
import android.provider.CalendarContract
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal sealed interface ReadOnlyEnrichmentResult<out T> {
    data class Available<T>(val value: T) : ReadOnlyEnrichmentResult<T>
    data object Unavailable : ReadOnlyEnrichmentResult<Nothing>
}

internal fun interface NotificationEnrichmentCancellation {
    fun isCancelled(): Boolean

    companion object {
        val NONE = NotificationEnrichmentCancellation { false }
    }
}

internal fun interface NotificationHttpsMetadataSource {
    fun read(
        notification: UntrustedNotificationEnvelope,
        cancellation: NotificationEnrichmentCancellation,
    ): ReadOnlyEnrichmentResult<List<NotificationLinkMetadata>>
}

internal fun interface ConfirmedNotificationProfileSource {
    fun read(): ReadOnlyEnrichmentResult<String>
}

internal fun interface NotificationCalendarContextSource {
    fun read(
        notification: UntrustedNotificationEnvelope,
        entities: List<String>,
        cancellation: NotificationEnrichmentCancellation,
    ): ReadOnlyEnrichmentResult<List<NotificationCalendarContext>>
}

internal fun interface RecentNotificationContextSource {
    fun read(
        notification: UntrustedNotificationEnvelope,
        entities: List<String>,
        cancellation: NotificationEnrichmentCancellation,
    ): ReadOnlyEnrichmentResult<List<RecentNotificationContext>>
}

internal class RootFreeNotificationEnrichmentProvider(
    private val httpsMetadata: NotificationHttpsMetadataSource,
    private val confirmedProfile: ConfirmedNotificationProfileSource,
    private val calendar: NotificationCalendarContextSource,
    private val recentNotifications: RecentNotificationContextSource,
    /** Deterministic lifecycle-race seam; production leaves it empty. */
    private val beforeOperationRegistration: () -> Unit = {},
) : NotificationEnrichmentProvider, PreemptibleNotificationEnrichmentProvider, Closeable {
    private val activeOperation = AtomicReference<ProviderEnrichmentOperation?>(null)
    private val lifecycleLock = Any()
    @Volatile private var closed = false

    override fun enrich(
        notification: UntrustedNotificationEnvelope,
        plan: RestrictedNotificationTriagePlan.Enrich,
    ): NotificationEnrichmentEvidence {
        val operation = ProviderEnrichmentOperation()
        beforeOperationRegistration()
        synchronized(lifecycleLock) {
            if (closed || !activeOperation.compareAndSet(null, operation)) {
                return NotificationEnrichmentEvidence(unavailableAdapters = plan.adapters)
            }
        }
        return try {
            enrichActive(notification, plan, operation)
        } finally {
            activeOperation.compareAndSet(operation, null)
        }
    }

    private fun enrichActive(
        notification: UntrustedNotificationEnvelope,
        plan: RestrictedNotificationTriagePlan.Enrich,
        operation: ProviderEnrichmentOperation,
    ): NotificationEnrichmentEvidence {
        var links = emptyList<NotificationLinkMetadata>()
        var profile: String? = null
        var nearbyCalendar = emptyList<NotificationCalendarContext>()
        var recent = emptyList<RecentNotificationContext>()
        val unavailable = linkedSetOf<NotificationEnrichmentAdapterKind>()

        plan.adapters.forEach { adapter ->
            if (operation.isCancelled()) {
                return NotificationEnrichmentEvidence(unavailableAdapters = plan.adapters)
            }
            when (adapter) {
                NotificationEnrichmentAdapterKind.HTTPS_METADATA ->
                    when (val result = runCatching { httpsMetadata.read(notification, operation) }
                        .getOrDefault(ReadOnlyEnrichmentResult.Unavailable)) {
                        is ReadOnlyEnrichmentResult.Available -> if (result.value.isEmpty()) {
                            unavailable += adapter
                        } else {
                            links = result.value
                        }
                        ReadOnlyEnrichmentResult.Unavailable -> unavailable += adapter
                    }
                NotificationEnrichmentAdapterKind.CONFIRMED_PROFILE ->
                    when (val result = runCatching(confirmedProfile::read)
                        .getOrDefault(ReadOnlyEnrichmentResult.Unavailable)) {
                        is ReadOnlyEnrichmentResult.Available -> profile = result.value
                        ReadOnlyEnrichmentResult.Unavailable -> unavailable += adapter
                    }
                NotificationEnrichmentAdapterKind.CALENDAR ->
                    when (val result = runCatching {
                        calendar.read(notification, plan.entities, operation)
                    }
                        .getOrDefault(ReadOnlyEnrichmentResult.Unavailable)) {
                        is ReadOnlyEnrichmentResult.Available -> if (result.value.isEmpty()) {
                            unavailable += adapter
                        } else {
                            nearbyCalendar = result.value
                        }
                        ReadOnlyEnrichmentResult.Unavailable -> unavailable += adapter
                    }
                NotificationEnrichmentAdapterKind.RECENT_NOTIFICATIONS ->
                    when (val result = runCatching {
                        recentNotifications.read(notification, plan.entities, operation)
                    }
                        .getOrDefault(ReadOnlyEnrichmentResult.Unavailable)) {
                        is ReadOnlyEnrichmentResult.Available -> if (result.value.isEmpty()) {
                            unavailable += adapter
                        } else {
                            recent = result.value
                        }
                        ReadOnlyEnrichmentResult.Unavailable -> unavailable += adapter
                    }
            }
        }
        if (operation.isCancelled()) {
            return NotificationEnrichmentEvidence(unavailableAdapters = plan.adapters)
        }
        val projectedProfile = profile?.relevantProfileExcerpt(plan.entities)
        if (
            NotificationEnrichmentAdapterKind.CONFIRMED_PROFILE in plan.adapters &&
            projectedProfile == null
        ) {
            unavailable += NotificationEnrichmentAdapterKind.CONFIRMED_PROFILE
        }
        return NotificationEnrichmentEvidence(
            linkMetadata = links.take(MAX_LINKS),
            confirmedProfileSummary = projectedProfile,
            nearbyCalendar = nearbyCalendar.take(MAX_CALENDAR_EVENTS),
            recentNotifications = recent.takeLast(MAX_RECENT_NOTIFICATIONS),
            unavailableAdapters = unavailable,
            authorityLease = authorityLeaseFor(plan),
        )
    }

    private fun authorityLeaseFor(
        plan: RestrictedNotificationTriagePlan.Enrich,
    ): NotificationEnrichmentAuthorityLease {
        val sources = buildList {
            if (NotificationEnrichmentAdapterKind.HTTPS_METADATA in plan.adapters) {
                (httpsMetadata as? NotificationEnrichmentAuthoritySource)?.let { add(it) }
            }
            if (NotificationEnrichmentAdapterKind.CALENDAR in plan.adapters) {
                (calendar as? NotificationEnrichmentAuthoritySource)?.let { add(it) }
            }
        }
        if (sources.isEmpty()) return NotificationEnrichmentAuthorityLease.ALWAYS
        return NotificationEnrichmentAuthorityLease {
            sources.all { source -> runCatching(source::isAuthorizedNow).getOrDefault(false) }
        }
    }

    override fun preemptCurrent() {
        activeOperation.get()?.cancel()
        (httpsMetadata as? PreemptibleNotificationEnrichmentProvider)?.preemptCurrent()
        (calendar as? PreemptibleNotificationEnrichmentProvider)?.preemptCurrent()
        (recentNotifications as? PreemptibleNotificationEnrichmentProvider)?.preemptCurrent()
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            activeOperation.get()?.cancel()
        }
        preemptCurrent()
        listOf(httpsMetadata, confirmedProfile, calendar, recentNotifications).forEach { source ->
            runCatching { (source as? Closeable)?.close() }
        }
    }

    private class ProviderEnrichmentOperation : NotificationEnrichmentCancellation {
        private val cancelled = AtomicBoolean(false)
        override fun isCancelled(): Boolean = cancelled.get()
        fun cancel() {
            cancelled.set(true)
        }
    }

    private fun String.relevantProfileExcerpt(entities: List<String>): String? {
        val terms = highInformationContextTerms(entities, MAX_PROFILE_TERMS)
        if (terms.isEmpty()) return null
        val matches = PROFILE_SENTENCE.split(this)
            .asSequence()
            .map { NotificationTriageBounds.boundedText(it, MAX_PROFILE_SENTENCE_BYTES) }
            .filter(String::isNotBlank)
            .filter { sentence ->
                val sentenceTerms = ENTITY_TERM.findAll(sentence.lowercase(Locale.ROOT))
                    .map(MatchResult::value)
                    .toSet()
                terms.any(sentenceTerms::contains)
            }
            .take(MAX_PROFILE_SENTENCES)
            .joinToString(". ")
        return NotificationTriageBounds.boundedText(matches, MAX_PROFILE_BYTES)
            .takeIf(String::isNotBlank)
    }

    private companion object {
        const val MAX_LINKS = 1
        const val MAX_PROFILE_BYTES = 4 * 1_024
        const val MAX_PROFILE_TERMS = 8
        const val MAX_PROFILE_SENTENCES = 4
        const val MAX_PROFILE_SENTENCE_BYTES = 1_024
        const val MAX_CALENDAR_EVENTS = 12
        const val MAX_RECENT_NOTIFICATIONS = 6
        val ENTITY_TERM = Regex("[\\p{L}\\p{N}]{4,}")
        val PROFILE_SENTENCE = Regex("[\\r\\n]+|(?<=[.!?])\\s+")
    }
}

internal object AndroidRootFreeNotificationEnrichmentFactory {
    fun create(
        context: Context,
        inbox: NotificationInboxQuerySource,
    ): RootFreeNotificationEnrichmentProvider {
        val appContext = context.applicationContext
        val consentStore = AtomicPersistentAndroidConsentStore(
            java.io.File(
                appContext.noBackupFilesDir,
                AtomicPersistentAndroidConsentStore.FILE_NAME,
            ),
        )
        return RootFreeNotificationEnrichmentProvider(
            httpsMetadata = ConsentGatedNotificationHttpsMetadataSource(
                consentStore = consentStore,
                delegate = SafeHttpsNotificationMetadataSource(
                    networkSnapshotProvider = AndroidNotificationNetworkSnapshotProvider(
                        appContext,
                    ),
                ),
            ),
            confirmedProfile = ConfirmedNotificationProfileSource {
                val summary = runCatching {
                    AtomicFileUserProfileStorage(appContext).read().confirmedSummary
                }.getOrNull()?.let {
                    NotificationTriageBounds.boundedText(it, 4 * 1_024)
                }
                if (summary.isNullOrBlank()) {
                    ReadOnlyEnrichmentResult.Unavailable
                } else {
                    ReadOnlyEnrichmentResult.Available(summary)
                }
            },
            calendar = ConsentGatedNotificationCalendarSource(
                consentStore = consentStore,
                runtimePermissionGranted = {
                    appContext.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
                        PackageManager.PERMISSION_GRANTED
                },
                delegate = AndroidCancellableNotificationCalendarSource(appContext),
            ),
            recentNotifications = RecentNotificationContextSource { notification, entities, cancellation ->
                if (cancellation.isCancelled()) {
                    return@RecentNotificationContextSource ReadOnlyEnrichmentResult.Unavailable
                }
                val after = (notification.sourceSequence - RECENT_SCAN_WINDOW).coerceAtLeast(0)
                val events = runCatching { inbox.queryPage(after, RECENT_SCAN_LIMIT).events }
                    .getOrElse { return@RecentNotificationContextSource ReadOnlyEnrichmentResult.Unavailable }
                if (cancellation.isCancelled()) {
                    return@RecentNotificationContextSource ReadOnlyEnrichmentResult.Unavailable
                }
                val oldest = (notification.observedAtEpochMillis - RECENT_MAX_AGE_MILLIS)
                    .coerceAtLeast(0)
                ReadOnlyEnrichmentResult.Available(
                    selectRecentNotificationEvents(events, notification, entities, oldest)
                        .asSequence()
                        .map { event ->
                            RecentNotificationContext(
                                sourcePackage = NotificationTriageBounds.boundedText(
                                    event.snapshot.packageName,
                                    NotificationTriageBounds.MAX_PACKAGE_BYTES,
                                ),
                                observedAtEpochMillis = event.observedAtEpochMillis,
                                title = NotificationTriageBounds.boundedText(
                                    event.snapshot.title,
                                    NotificationTriageBounds.MAX_TITLE_BYTES,
                                ),
                                text = NotificationTriageBounds.boundedText(
                                    event.snapshot.text,
                                    1_024,
                                ),
                            )
                        }
                        .takeLastBounded(6),
                )
            },
        )
    }

    private fun <T> Sequence<T>.takeLastBounded(limit: Int): List<T> {
        val buffer = ArrayDeque<T>(limit)
        forEach { item ->
            if (buffer.size == limit) buffer.removeFirst()
            buffer.addLast(item)
        }
        return buffer.toList()
    }

    private const val RECENT_SCAN_WINDOW = 64L
    private const val RECENT_SCAN_LIMIT = 64
    private const val RECENT_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
}

/**
 * Direct public-origin previews are default-off because even a cookie-free metadata GET reveals
 * the device IP and request time. Setup/settings must grant this separate disclosure-specific
 * scope; the ordinary everyday Android bundle deliberately does not include it.
 */
internal class ConsentGatedNotificationHttpsMetadataSource(
    private val consentStore: AtomicPersistentAndroidConsentStore,
    private val delegate: NotificationHttpsMetadataSource,
) : NotificationHttpsMetadataSource,
    NotificationEnrichmentAuthoritySource,
    PreemptibleNotificationEnrichmentProvider,
    Closeable {
    private val consent = PersistentAndroidConsentDescriptor.category(
        PersistentAndroidConsentScope.NOTIFICATION_LINK_METADATA,
    )

    override fun isAuthorizedNow(): Boolean = consentStore.contains(consent)

    override fun read(
        notification: UntrustedNotificationEnvelope,
        cancellation: NotificationEnrichmentCancellation,
    ): ReadOnlyEnrichmentResult<List<NotificationLinkMetadata>> {
        if (cancellation.isCancelled()) return ReadOnlyEnrichmentResult.Unavailable
        if (!isAuthorizedNow()) return ReadOnlyEnrichmentResult.Unavailable
        // Revocation is authoritative for the complete operation, not merely the next one.
        // The delegate polls this token at every DNS/redirect/transport boundary, so a revoked
        // disclosure can no longer publish metadata even if the request began while permitted.
        val consentAwareCancellation = NotificationEnrichmentCancellation {
            cancellation.isCancelled() || !isAuthorizedNow()
        }
        val result = delegate.read(notification, consentAwareCancellation)
        return if (consentAwareCancellation.isCancelled()) {
            ReadOnlyEnrichmentResult.Unavailable
        } else {
            result
        }
    }

    override fun preemptCurrent() {
        (delegate as? PreemptibleNotificationEnrichmentProvider)?.preemptCurrent()
    }

    override fun close() {
        preemptCurrent()
        runCatching { (delegate as? Closeable)?.close() }
    }
}

/**
 * Calendar authority is checked for the whole bounded read. Revoking either the durable category
 * or Android's runtime permission invalidates the in-flight cancellation token and suppresses the
 * result even if the provider happened to return at the same time.
 */
internal class ConsentGatedNotificationCalendarSource(
    private val consentStore: AtomicPersistentAndroidConsentStore,
    private val runtimePermissionGranted: () -> Boolean,
    private val delegate: NotificationCalendarContextSource,
) : NotificationCalendarContextSource,
    NotificationEnrichmentAuthoritySource,
    PreemptibleNotificationEnrichmentProvider,
    Closeable {
    private val consent = PersistentAndroidConsentDescriptor.category(
        PersistentAndroidConsentScope.READ_CALENDAR,
    )

    override fun isAuthorizedNow(): Boolean =
        consentStore.contains(consent) && runtimePermissionGranted()

    override fun read(
        notification: UntrustedNotificationEnvelope,
        entities: List<String>,
        cancellation: NotificationEnrichmentCancellation,
    ): ReadOnlyEnrichmentResult<List<NotificationCalendarContext>> {
        if (cancellation.isCancelled() || !isAuthorizedNow()) {
            return ReadOnlyEnrichmentResult.Unavailable
        }
        val consentAwareCancellation = NotificationEnrichmentCancellation {
            cancellation.isCancelled() || !isAuthorizedNow()
        }
        val result = delegate.read(notification, entities, consentAwareCancellation)
        return if (consentAwareCancellation.isCancelled()) {
            ReadOnlyEnrichmentResult.Unavailable
        } else {
            result
        }
    }

    override fun preemptCurrent() {
        (delegate as? PreemptibleNotificationEnrichmentProvider)?.preemptCurrent()
    }

    override fun close() {
        preemptCurrent()
        runCatching { (delegate as? Closeable)?.close() }
    }
}

/** A single bounded, cancellation-aware public-API calendar query; it never mutates a provider. */
internal class AndroidCancellableNotificationCalendarSource(
    private val context: Context,
) : NotificationCalendarContextSource, PreemptibleNotificationEnrichmentProvider, Closeable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-notification-calendar").apply { isDaemon = true }
    }
    private val active = AtomicReference<ActiveCalendarQuery?>(null)

    override fun read(
        notification: UntrustedNotificationEnvelope,
        entities: List<String>,
        cancellation: NotificationEnrichmentCancellation,
    ): ReadOnlyEnrichmentResult<List<NotificationCalendarContext>> {
        if (cancellation.isCancelled()) return ReadOnlyEnrichmentResult.Unavailable

        val signal = CancellationSignal()
        val future = FutureTask<List<CalendarInstance>> {
            queryCalendar(notification, signal)
        }
        val query = ActiveCalendarQuery(signal, future)
        if (!active.compareAndSet(null, query)) {
            signal.cancel()
            future.cancel(true)
            return ReadOnlyEnrichmentResult.Unavailable
        }
        if (cancellation.isCancelled()) {
            signal.cancel()
            future.cancel(true)
            active.compareAndSet(query, null)
            return ReadOnlyEnrichmentResult.Unavailable
        }
        executor.execute(future)
        var outcome: ReadOnlyEnrichmentResult<List<NotificationCalendarContext>> =
            ReadOnlyEnrichmentResult.Unavailable
        try {
            val deadline = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(CALENDAR_QUERY_TIMEOUT_MILLIS)
            while (true) {
                if (cancellation.isCancelled()) {
                    signal.cancel()
                    future.cancel(true)
                    break
                }
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) {
                    signal.cancel()
                    future.cancel(true)
                    break
                }
                try {
                    val events = future.get(
                        minOf(
                            remainingNanos,
                            TimeUnit.MILLISECONDS.toNanos(CALENDAR_AUTHORITY_RECHECK_MILLIS),
                        ),
                        TimeUnit.NANOSECONDS,
                    )
                    outcome = if (cancellation.isCancelled()) {
                        signal.cancel()
                        future.cancel(true)
                        ReadOnlyEnrichmentResult.Unavailable
                    } else {
                        ReadOnlyEnrichmentResult.Available(
                            projectCalendarContexts(events, entities),
                        )
                    }
                    break
                } catch (_: TimeoutException) {
                    // Recheck the consent-aware token until the one bounded query deadline.
                }
            }
        } catch (_: Exception) {
            outcome = ReadOnlyEnrichmentResult.Unavailable
        } finally {
            active.compareAndSet(query, null)
        }
        return outcome
    }

    override fun preemptCurrent() {
        active.get()?.let { query ->
            query.signal.cancel()
            query.future.cancel(true)
        }
    }

    override fun close() {
        preemptCurrent()
        executor.shutdownNow()
    }

    private fun queryCalendar(
        notification: UntrustedNotificationEnvelope,
        signal: CancellationSignal,
    ): List<CalendarInstance> {
        val start = (notification.observedAtEpochMillis - CALENDAR_LOOKBACK_MILLIS).coerceAtLeast(0)
        val end = notification.observedAtEpochMillis + CALENDAR_LOOKAHEAD_MILLIS
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
            ContentUris.appendId(builder, start)
            ContentUris.appendId(builder, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS,
        )
        return context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
            signal,
        )?.use { cursor ->
            val eventId = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val calendarId = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
            val title = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val location = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val begin = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDay = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val status = cursor.getColumnIndexOrThrow(CalendarContract.Instances.STATUS)
            buildList {
                while (cursor.moveToNext() && size < MAX_PROJECTED_CALENDAR_EVENTS) {
                    add(
                        CalendarInstance(
                            eventId = cursor.getLong(eventId).toString(),
                            calendarId = cursor.getLong(calendarId).toString(),
                            title = cursor.getString(title).orEmpty(),
                            location = cursor.getString(location).orEmpty(),
                            organizer = "",
                            beginEpochMillis = cursor.getLong(begin),
                            endEpochMillis = cursor.getLong(endIndex),
                            allDay = cursor.getInt(allDay) != 0,
                            status = cursor.getInt(status),
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    private data class ActiveCalendarQuery(
        val signal: CancellationSignal,
        val future: Future<List<CalendarInstance>>,
    )

    private companion object {
        const val CALENDAR_QUERY_TIMEOUT_MILLIS = 3_000L
        const val CALENDAR_AUTHORITY_RECHECK_MILLIS = 100L
        const val CALENDAR_LOOKBACK_MILLIS = 12L * 60L * 60L * 1_000L
        const val CALENDAR_LOOKAHEAD_MILLIS = 8L * 24L * 60L * 60L * 1_000L
    }
}

/**
 * Calendar data is minimized before it reaches the isolated synthesizer. Unrelated events expose
 * only busy intervals. Titles and locations survive solely when an exact, high-information entity
 * token from the classifier occurs in that event; generic topics, package-wide matching and
 * substring matching are forbidden.
 */
internal fun projectCalendarContexts(
    events: List<CalendarInstance>,
    entities: List<String>,
): List<NotificationCalendarContext> {
    val entityTerms = highInformationContextTerms(entities, MAX_CONTEXT_TERMS)
    return events.take(MAX_PROJECTED_CALENDAR_EVENTS).map { event ->
        val eventTerms = meaningfulContextTerms(listOf(event.title, event.location))
        val discloseDetails = entityTerms.isNotEmpty() && entityTerms.any(eventTerms::contains)
        val begin = event.beginEpochMillis.coerceAtLeast(0)
        NotificationCalendarContext(
            title = if (discloseDetails) {
                NotificationTriageBounds.boundedText(event.title, 512)
            } else {
                ""
            },
            location = if (discloseDetails) {
                NotificationTriageBounds.boundedText(event.location, 512)
            } else {
                ""
            },
            beginEpochMillis = begin,
            endEpochMillis = event.endEpochMillis.coerceAtLeast(begin),
            allDay = event.allDay,
        )
    }
}

/**
 * Recent full text is allowed only for one uninterrupted, exact conversation identity. Android's
 * notification key is merely a reusable slot, so key or package equality alone is never enough.
 */
internal fun isRelatedNotificationThread(
    event: NotificationInboxEvent,
    current: UntrustedNotificationEnvelope,
    entities: List<String>,
): Boolean {
    if (event.snapshot.androidKey != current.androidKey) return false
    val currentIdentity = normalizedConversationIdentity(current.title, current.subtext)
    val previousIdentity = normalizedConversationIdentity(
        event.snapshot.title,
        event.snapshot.subtext,
    )
    if (currentIdentity.isBlank() || currentIdentity != previousIdentity) return false
    val entityTerms = meaningfulIdentityTerms(entities)
    if (entityTerms.isEmpty()) return false
    val currentTerms = meaningfulIdentityTerms(listOf(current.title, current.subtext))
    val previousTerms = meaningfulIdentityTerms(listOf(event.snapshot.title, event.snapshot.subtext))
    return entityTerms.any { it in currentTerms && it in previousTerms }
}

internal fun selectRecentNotificationEvents(
    events: List<NotificationInboxEvent>,
    current: UntrustedNotificationEnvelope,
    entities: List<String>,
    oldestEpochMillis: Long,
): List<NotificationInboxEvent> {
    val prior = events.filter { it.sequence < current.sourceSequence }
    val currentKeyGenerationStart = prior.asSequence()
        .filter { it.snapshot.packageName == current.packageName }
        .filter { it.snapshot.androidKey == current.androidKey }
        .filter { it.kind == NotificationEventKind.REMOVED }
        .maxOfOrNull(NotificationInboxEvent::sequence)
    return prior.asSequence()
        .filter { it.kind != NotificationEventKind.REMOVED }
        .filter { it.observedAtEpochMillis >= oldestEpochMillis }
        .filter { it.snapshot.packageName == current.packageName }
        .filter { event ->
            val crossesRemovedSameKeyGeneration =
                event.snapshot.androidKey == current.androidKey &&
                    currentKeyGenerationStart != null &&
                    event.sequence <= currentKeyGenerationStart
            !crossesRemovedSameKeyGeneration &&
                isRelatedNotificationThread(event, current, entities)
        }
        .toList()
}

private fun meaningfulContextTerms(values: List<String>): Set<String> = values.asSequence()
    .flatMap { CONTEXT_TERM.findAll(it.lowercase(Locale.ROOT)).map(MatchResult::value) }
    .filter { it.length >= MIN_CONTEXT_TERM_LENGTH && it !in CONTEXT_STOPWORDS }
    .take(MAX_CONTEXT_TERMS)
    .toSet()

/**
 * Classifier entities are untrusted search hints. Low-information topic words can be grounded in a
 * notification while matching many unrelated private profile sentences or calendar entries, so
 * they are never sufficient authority to disclose details.
 */
private fun highInformationContextTerms(
    values: List<String>,
    maxTerms: Int,
): Set<String> = values.asSequence()
    .flatMap { CONTEXT_TERM.findAll(it.lowercase(Locale.ROOT)).map(MatchResult::value) }
    .filter { it.length >= MIN_CONTEXT_TERM_LENGTH }
    .filterNot(LOW_INFORMATION_CONTEXT_TERMS::contains)
    .take(maxTerms)
    .toSet()

private fun meaningfulIdentityTerms(values: List<String>): Set<String> =
    meaningfulContextTerms(values).filterNotTo(linkedSetOf(), CONVERSATION_TOPIC_TERMS::contains)

private fun normalizedConversationIdentity(title: String, subtext: String): String =
    listOf(title, subtext)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .take(MAX_CONVERSATION_IDENTITY_CHARACTERS)

private const val MAX_PROJECTED_CALENDAR_EVENTS = 12
private const val MIN_CONTEXT_TERM_LENGTH = 4
private const val MAX_CONTEXT_TERMS = 32
private const val MAX_CONVERSATION_IDENTITY_CHARACTERS = 256
private val CONTEXT_TERM = Regex("[\\p{L}\\p{N}]{4,}")
private val CONTEXT_STOPWORDS = setOf(
    "aber", "also", "dass", "eine", "einer", "eines", "oder", "this", "that", "the", "with",
    "from", "your", "message", "notification", "calendar", "event", "today", "tomorrow",
)
private val CONVERSATION_TOPIC_TERMS = setOf(
    "meeting", "rechnung", "invoice", "termin", "event", "projekt", "project", "update",
    "urgent", "dringend", "message", "nachricht", "email", "gmail", "whatsapp",
)
private val LOW_INFORMATION_CONTEXT_TERMS = CONTEXT_STOPWORDS + CONVERSATION_TOPIC_TERMS + setOf(
    "agenda", "appointment", "appointments", "arbeit", "aufgabe", "aufgaben",
    "besprechung", "besprechungen", "calendar", "calendars", "concert", "concerts",
    "erinnerung", "erinnerungen", "event", "events", "familie", "familien", "family",
    "families", "friend", "friends", "freunde", "gesundheit", "health", "hobbies", "hobby",
    "important", "information", "interesse", "interessen", "interest", "interests", "kalender",
    "kind", "kinder", "konzert", "konzerte", "meeting", "meetings", "message", "messages",
    "music", "musik", "nachricht", "nachrichten", "notification", "notifications", "parent",
    "parents", "people", "person", "preference", "preferences", "profile", "profil", "project",
    "projects", "projekt", "projekte", "reminder", "reminders", "reise", "reisen", "schedule",
    "schedules", "sport", "sports", "task", "tasks", "termin", "termine", "treffen", "update",
    "updates", "user", "users", "wichtige", "wichtig", "work", "aktualisierung",
    "aktualisierungen", "eltern", "nutzer", "vorliebe", "vorlieben",
)

internal data class ValidatedHttpsEndpoint(
    val url: HttpUrl,
    val pinnedAddresses: List<InetAddress>,
    /** Production only: sockets are bound to the exact network used for DNS validation. */
    val network: Network? = null,
)

internal data class NotificationNat64Prefix(
    val address: ByteArray,
    val prefixLength: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is NotificationNat64Prefix &&
            prefixLength == other.prefixLength &&
            address.contentEquals(other.address)

    override fun hashCode(): Int = 31 * prefixLength + address.contentHashCode()
}

internal data class NotificationNetworkSnapshot(
    val network: Network,
    val nat64Prefix: NotificationNat64Prefix?,
)

internal interface NotificationNetworkSnapshotProvider {
    fun capture(): NotificationNetworkSnapshot?
    fun isCurrent(snapshot: NotificationNetworkSnapshot): Boolean
}

internal class AndroidNotificationNetworkSnapshotProvider(
    context: Context,
) : NotificationNetworkSnapshotProvider {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    override fun capture(): NotificationNetworkSnapshot? {
        val network = connectivity.activeNetwork ?: return null
        val properties = connectivity.getLinkProperties(network) ?: return null
        return NotificationNetworkSnapshot(
            network = network,
            nat64Prefix = properties.nat64Prefix?.toNotificationPrefix(),
        )
    }

    override fun isCurrent(snapshot: NotificationNetworkSnapshot): Boolean =
        capture() == snapshot

    private fun IpPrefix.toNotificationPrefix() = NotificationNat64Prefix(
        address = address.address.copyOf(),
        prefixLength = prefixLength,
    )
}

internal data class BoundedMetadataHttpResponse(
    val statusCode: Int,
    val location: String?,
    val contentType: String?,
    val contentEncoding: String?,
    val declaredContentLength: Long?,
    val body: ByteArray,
)

internal fun interface NotificationDnsResolver {
    fun lookup(
        hostname: String,
        timeoutMillis: Long,
        cancellation: NotificationEnrichmentCancellation,
    ): List<InetAddress>
}

internal interface NetworkBoundNotificationDnsResolver : NotificationDnsResolver {
    fun lookupOnNetwork(
        network: Network,
        hostname: String,
        timeoutMillis: Long,
        cancellation: NotificationEnrichmentCancellation,
    ): List<InetAddress>
}

internal fun interface PinnedHttpsMetadataTransport {
    fun get(
        endpoint: ValidatedHttpsEndpoint,
        maximumBodyBytes: Int,
        timeoutMillis: Long,
        cancellation: NotificationEnrichmentCancellation,
    ): BoundedMetadataHttpResponse
}

internal class SafeHttpsNotificationMetadataSource(
    private val resolver: NotificationDnsResolver = AndroidCancellableNotificationDnsResolver(),
    private val transport: PinnedHttpsMetadataTransport = OkHttpPinnedMetadataTransport(),
    private val nanoTime: () -> Long = System::nanoTime,
    /** Deterministic test seam for the DNS-to-HTTP transition; production leaves it empty. */
    private val beforeTransportStart: () -> Unit = {},
    /** Null is an explicit JVM-test seam. Production always supplies an Android snapshot. */
    private val networkSnapshotProvider: NotificationNetworkSnapshotProvider? = null,
) : NotificationHttpsMetadataSource, PreemptibleNotificationEnrichmentProvider, Closeable {
    private val activeOperation = AtomicReference<ActiveEnrichmentOperation?>(null)
    private val lifecycleLock = Any()
    @Volatile private var closed = false

    internal fun read(
        notification: UntrustedNotificationEnvelope,
    ): ReadOnlyEnrichmentResult<List<NotificationLinkMetadata>> =
        read(notification, NotificationEnrichmentCancellation.NONE)

    override fun read(
        notification: UntrustedNotificationEnvelope,
        cancellation: NotificationEnrichmentCancellation,
    ): ReadOnlyEnrichmentResult<List<NotificationLinkMetadata>> {
        val operation = ActiveEnrichmentOperation(cancellation)
        synchronized(lifecycleLock) {
            if (closed || !activeOperation.compareAndSet(null, operation)) {
                return ReadOnlyEnrichmentResult.Unavailable
            }
        }
        return try {
            readActive(notification, operation)
        } finally {
            activeOperation.compareAndSet(operation, null)
        }
    }

    private fun readActive(
        notification: UntrustedNotificationEnvelope,
        operation: ActiveEnrichmentOperation,
    ): ReadOnlyEnrichmentResult<List<NotificationLinkMetadata>> {
        if (operation.isCancelled()) return ReadOnlyEnrichmentResult.Unavailable
        val networkSnapshot = networkSnapshotProvider?.capture()
        if (networkSnapshotProvider != null && networkSnapshot == null) {
            return ReadOnlyEnrichmentResult.Unavailable
        }
        val rawUrl = HTTPS_URL.find(
            listOf(notification.title, notification.text, notification.subtext).joinToString(" "),
        )?.value?.trimEnd(*TRAILING_URL_PUNCTUATION)
            ?: return ReadOnlyEnrichmentResult.Unavailable
        val initial = rawUrl.toHttpUrlOrNull()
            ?: return ReadOnlyEnrichmentResult.Unavailable
        var current = initial
        val visited = linkedSetOf<String>()
        val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_ENRICHMENT_TIMEOUT_MILLIS)
        repeat(MAX_REDIRECTS + 1) { hop ->
            if (operation.isCancelled()) return ReadOnlyEnrichmentResult.Unavailable
            val dnsBudget = remainingMillis(deadline) ?: return ReadOnlyEnrichmentResult.Unavailable
            if (!networkStillCurrent(networkSnapshot)) return ReadOnlyEnrichmentResult.Unavailable
            val endpoint = validateAndPin(current, dnsBudget, operation, networkSnapshot)
                ?: return ReadOnlyEnrichmentResult.Unavailable
            if (!visited.add(endpoint.url.toString())) return ReadOnlyEnrichmentResult.Unavailable
            val transportBudget = remainingMillis(deadline)
                ?: return ReadOnlyEnrichmentResult.Unavailable
            beforeTransportStart()
            if (operation.isCancelled()) return ReadOnlyEnrichmentResult.Unavailable
            if (!networkStillCurrent(networkSnapshot)) return ReadOnlyEnrichmentResult.Unavailable
            val response = runCatching {
                transport.get(endpoint, MAX_BODY_BYTES, transportBudget, operation)
            }.getOrNull()
                ?: return ReadOnlyEnrichmentResult.Unavailable
            if (operation.isCancelled()) return ReadOnlyEnrichmentResult.Unavailable
            if (!networkStillCurrent(networkSnapshot)) return ReadOnlyEnrichmentResult.Unavailable
            if (response.statusCode in REDIRECT_CODES) {
                if (hop == MAX_REDIRECTS) return ReadOnlyEnrichmentResult.Unavailable
                val location = response.location ?: return ReadOnlyEnrichmentResult.Unavailable
                current = endpoint.url.resolve(location)
                    ?: return ReadOnlyEnrichmentResult.Unavailable
                return@repeat
            }
            if (response.statusCode != 200) return ReadOnlyEnrichmentResult.Unavailable
            val mime = response.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (mime !in ALLOWED_MIME_TYPES) return ReadOnlyEnrichmentResult.Unavailable
            val charset = CONTENT_TYPE_CHARSET.find(response.contentType.orEmpty())
                ?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            if (charset != null && charset !in ALLOWED_CHARSETS) {
                return ReadOnlyEnrichmentResult.Unavailable
            }
            val encoding = response.contentEncoding?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (encoding.isNotEmpty() && encoding != "identity") {
                return ReadOnlyEnrichmentResult.Unavailable
            }
            if (
                response.declaredContentLength?.let { it < 0 || it > MAX_BODY_BYTES } == true ||
                response.body.size > MAX_BODY_BYTES
            ) return ReadOnlyEnrichmentResult.Unavailable
            val metadata = parseMetadata(endpoint.url, response.body)
                ?: return ReadOnlyEnrichmentResult.Unavailable
            return ReadOnlyEnrichmentResult.Available(listOf(metadata))
        }
        return ReadOnlyEnrichmentResult.Unavailable
    }

    override fun preemptCurrent() {
        activeOperation.get()?.cancel()
        // Mark the whole operation cancelled before touching stage-local children. A stage which
        // is just transitioning from DNS to HTTP can therefore never begin network I/O afterward.
        (transport as? PreemptibleNotificationEnrichmentProvider)?.preemptCurrent()
        (resolver as? PreemptibleNotificationEnrichmentProvider)?.preemptCurrent()
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            activeOperation.get()?.cancel()
        }
        preemptCurrent()
        runCatching { (transport as? Closeable)?.close() }
        runCatching { (resolver as? Closeable)?.close() }
    }

    private fun validateAndPin(
        url: HttpUrl,
        timeoutMillis: Long,
        operation: ActiveEnrichmentOperation,
        networkSnapshot: NotificationNetworkSnapshot?,
    ): ValidatedHttpsEndpoint? {
        if (operation.isCancelled()) return null
        if (
            url.scheme != "https" ||
            url.port != 443 ||
            url.username.isNotEmpty() ||
            url.password.isNotEmpty() ||
            url.host.isBlank() ||
            !PublicHostnamePolicy.isAllowed(url.host) ||
            !PreviewSafeUrlPolicy.isAllowed(url)
        ) return null
        val addresses = runCatching {
            if (networkSnapshot != null) {
                (resolver as? NetworkBoundNotificationDnsResolver)?.lookupOnNetwork(
                    networkSnapshot.network,
                    url.host,
                    timeoutMillis,
                    operation,
                ) ?: return null
            } else {
                resolver.lookup(url.host, timeoutMillis, operation)
            }
        }.getOrNull()
            ?.distinctBy { it.address.toList() }
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_DNS_ADDRESSES }
            ?: return null
        if (operation.isCancelled()) return null
        if (!networkStillCurrent(networkSnapshot)) return null
        if (addresses.any {
                !PublicNetworkAddressPolicy.isPublic(it, networkSnapshot?.nat64Prefix)
            }
        ) return null
        return ValidatedHttpsEndpoint(url, addresses, networkSnapshot?.network)
    }

    private fun networkStillCurrent(snapshot: NotificationNetworkSnapshot?): Boolean =
        snapshot == null || networkSnapshotProvider?.isCurrent(snapshot) == true

    private fun remainingMillis(deadline: Long): Long? {
        val remainingNanos = deadline - nanoTime()
        if (remainingNanos <= 0) return null
        return TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1)
    }

    private fun parseMetadata(url: HttpUrl, bytes: ByteArray): NotificationLinkMetadata? {
        val html = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull() ?: return null
        if ('\u0000' in html || !HTML_DOCUMENT_PREFIX.containsMatchIn(html)) return null
        val title = TITLE.find(html)?.groupValues?.get(1).orEmpty().toCleanMetadata()
        var description = ""
        var socialTitle = ""
        META.findAll(html).take(MAX_META_TAGS).forEach { match ->
            val attributes = ATTRIBUTES.findAll(match.value).associate { attribute ->
                attribute.groupValues[1].lowercase(Locale.ROOT) to attribute.groupValues[3]
            }
            val name = (attributes["property"] ?: attributes["name"])
                ?.lowercase(Locale.ROOT)
            val content = attributes["content"].orEmpty().toCleanMetadata()
            when (name) {
                "og:title", "twitter:title" -> if (socialTitle.isBlank()) socialTitle = content
                "description", "og:description", "twitter:description" ->
                    if (description.isBlank()) description = content
            }
        }
        val finalTitle = (socialTitle.ifBlank { title }).boundedMetadata(MAX_METADATA_TITLE_BYTES)
        val finalDescription = description.boundedMetadata(MAX_METADATA_DESCRIPTION_BYTES)
        if (finalTitle.isBlank() && finalDescription.isBlank()) return null
        return NotificationLinkMetadata(
            finalUrlOrigin = "https://${url.host}",
            title = finalTitle,
            description = finalDescription,
        )
    }

    private fun String.toCleanMetadata(): String = replace(TAGS, " ")
        .decodeBasicHtmlEntities()
        .let { NotificationTriageBounds.boundedText(it, MAX_METADATA_FIELD_BYTES) }

    private fun String.boundedMetadata(maxBytes: Int): String =
        NotificationTriageBounds.boundedText(this, maxBytes)

    private fun String.decodeBasicHtmlEntities(): String = replace(HTML_ENTITY) { match ->
        when (val entity = match.groupValues[1].lowercase(Locale.ROOT)) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos", "#39" -> "'"
            else -> decodeNumericEntity(entity) ?: " "
        }
    }

    private fun decodeNumericEntity(entity: String): String? {
        val codePoint = when {
            entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16)
            entity.startsWith('#') -> entity.drop(1).toIntOrNull()
            else -> null
        } ?: return null
        if (!Character.isValidCodePoint(codePoint) || Character.isISOControl(codePoint)) return null
        return String(Character.toChars(codePoint))
    }

    private companion object {
        const val MAX_REDIRECTS = 3
        const val TOTAL_ENRICHMENT_TIMEOUT_MILLIS = 10_000L
        const val MAX_BODY_BYTES = 128 * 1_024
        const val MAX_DNS_ADDRESSES = 16
        const val MAX_META_TAGS = 64
        const val MAX_METADATA_FIELD_BYTES = 2 * 1_024
        const val MAX_METADATA_TITLE_BYTES = 512
        const val MAX_METADATA_DESCRIPTION_BYTES = 1_024
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val ALLOWED_MIME_TYPES = setOf("text/html", "application/xhtml+xml")
        val ALLOWED_CHARSETS = setOf("utf-8", "utf8", "us-ascii")
        val CONTENT_TYPE_CHARSET = Regex("(?i)(?:^|;)\\s*charset\\s*=\\s*[\"']?([^;\"'\\s]+)")
        val HTTPS_URL = Regex("(?i)https://[^\\s<>\\\"']+")
        val TRAILING_URL_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
        val TITLE = Regex("(?is)<title(?:\\s[^>]*)?>(.*?)</title\\s*>")
        val META = Regex("(?is)<meta\\s+[^>]{0,4096}>")
        val ATTRIBUTES = Regex("(?is)([a-z_:][a-z0-9_:.\\-]*)\\s*=\\s*(['\"])(.*?)\\2")
        val TAGS = Regex("(?is)<[^>]{0,4096}>")
        val HTML_ENTITY = Regex("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|lt|gt|quot|apos|#39);")
        val HTML_DOCUMENT_PREFIX = Regex("(?is)^\\s*(?:\\uFEFF\\s*)?(?:<!doctype\\s+html|<html|<head|<\\?xml\\s)")
    }

    private class ActiveEnrichmentOperation(
        private val parent: NotificationEnrichmentCancellation,
    ) : NotificationEnrichmentCancellation {
        private val cancelled = AtomicBoolean(false)

        override fun isCancelled(): Boolean = cancelled.get() || parent.isCancelled()

        fun cancel() {
            cancelled.set(true)
        }
    }
}

internal class AndroidCancellableNotificationDnsResolver(
    private val resolver: DnsResolver = DnsResolver.getInstance(),
) : NetworkBoundNotificationDnsResolver, PreemptibleNotificationEnrichmentProvider {
    private val activeQuery = AtomicReference<ActiveDnsQuery?>(null)

    override fun lookup(
        hostname: String,
        timeoutMillis: Long,
        cancellation: NotificationEnrichmentCancellation,
    ): List<InetAddress> = lookupInternal(null, hostname, timeoutMillis, cancellation)

    override fun lookupOnNetwork(
        network: Network,
        hostname: String,
        timeoutMillis: Long,
        cancellation: NotificationEnrichmentCancellation,
    ): List<InetAddress> = lookupInternal(network, hostname, timeoutMillis, cancellation)

    private fun lookupInternal(
        network: Network?,
        hostname: String,
        timeoutMillis: Long,
        cancellation: NotificationEnrichmentCancellation,
    ): List<InetAddress> {
        require(timeoutMillis > 0)
        if (cancellation.isCancelled()) throw java.net.UnknownHostException("dns_cancelled")
        val signal = CancellationSignal()
        val latch = CountDownLatch(1)
        val query = ActiveDnsQuery(signal, latch)
        check(activeQuery.compareAndSet(null, query)) { "dns_query_already_active" }
        if (cancellation.isCancelled()) {
            query.cancellation.cancel()
            query.completion.countDown()
            activeQuery.compareAndSet(query, null)
            throw java.net.UnknownHostException("dns_cancelled")
        }
        val addresses = AtomicReference<List<InetAddress>?>(null)
        val failure = AtomicReference<DnsResolver.DnsException?>(null)
        return try {
            resolver.query(
                network,
                hostname,
                DnsResolver.FLAG_EMPTY,
                Executor { command -> command.run() },
                signal,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (rcode == 0) addresses.set(answer)
                        latch.countDown()
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        failure.set(error)
                        latch.countDown()
                    }
                },
            )
            if (!latch.await(timeoutMillis.coerceAtMost(MAX_DNS_TIMEOUT_MILLIS), TimeUnit.MILLISECONDS)) {
                signal.cancel()
                throw java.net.UnknownHostException("dns_timeout")
            }
            failure.get()?.let { throw java.net.UnknownHostException("dns_failed") }
            addresses.get()?.takeIf { it.isNotEmpty() }
                ?: throw java.net.UnknownHostException("dns_empty")
        } finally {
            activeQuery.compareAndSet(query, null)
        }
    }

    override fun preemptCurrent() {
        activeQuery.get()?.let { query ->
            query.cancellation.cancel()
            // AOSP DnsResolver cancellation deliberately does not invoke the callback.
            query.completion.countDown()
        }
    }

    private companion object {
        const val MAX_DNS_TIMEOUT_MILLIS = 3_000L
    }

    private data class ActiveDnsQuery(
        val cancellation: CancellationSignal,
        val completion: CountDownLatch,
    )
}

internal class OkHttpPinnedMetadataTransport :
    PinnedHttpsMetadataTransport,
    PreemptibleNotificationEnrichmentProvider {
    private val activeCall = AtomicReference<Call?>(null)

    override fun get(
        endpoint: ValidatedHttpsEndpoint,
        maximumBodyBytes: Int,
        timeoutMillis: Long,
        cancellation: NotificationEnrichmentCancellation,
    ): BoundedMetadataHttpResponse {
        check(!cancellation.isCancelled()) { "metadata_fetch_cancelled" }
        val pinnedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (!hostname.equals(endpoint.url.host, ignoreCase = true)) {
                    throw java.net.UnknownHostException("unpinned_metadata_host")
                }
                return endpoint.pinnedAddresses
            }
        }
        val client = OkHttpClient.Builder()
            .dns(pinnedDns)
            .apply { endpoint.network?.let { socketFactory(it.socketFactory) } }
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(timeoutMillis.coerceAtMost(4_000L), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis.coerceAtMost(4_000L), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis.coerceAtMost(4_000L), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url(endpoint.url)
            .get()
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "Hans-Notification-Metadata/1")
            .build()
        val call = client.newCall(request)
        check(activeCall.compareAndSet(null, call)) { "metadata_fetch_already_active" }
        return try {
            if (cancellation.isCancelled()) {
                call.cancel()
                throw IllegalStateException("metadata_fetch_cancelled")
            }
            call.execute().use { response -> response.toBoundedMetadataResponse(maximumBodyBytes) }
        } finally {
            activeCall.compareAndSet(call, null)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    override fun preemptCurrent() {
        activeCall.get()?.cancel()
    }

    private fun Response.toBoundedMetadataResponse(maximumBodyBytes: Int): BoundedMetadataHttpResponse {
        val body = body
        val contentLength = body?.contentLength()?.takeIf { it >= 0 }
        if (contentLength != null && contentLength > maximumBodyBytes) {
            throw IllegalStateException("metadata_body_too_large")
        }
        val bytes = if (body == null) {
            ByteArray(0)
        } else {
            body.byteStream().use { input ->
                BoundedMetadataBodyReader.read(input, maximumBodyBytes)
            }
        }
        return BoundedMetadataHttpResponse(
            statusCode = code,
            location = header("Location"),
            contentType = header("Content-Type"),
            contentEncoding = header("Content-Encoding"),
            declaredContentLength = contentLength,
            body = bytes,
        )
    }
}

internal object BoundedMetadataBodyReader {
    fun read(input: java.io.InputStream, maximumBodyBytes: Int): ByteArray {
        require(maximumBodyBytes > 0)
        val output = ByteArrayOutputStream(minOf(maximumBodyBytes, 16 * 1_024))
        val buffer = ByteArray(8 * 1_024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maximumBodyBytes) {
                throw IllegalStateException("metadata_body_too_large")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

internal object PublicNetworkAddressPolicy {
    fun isPublic(
        address: InetAddress,
        activeNat64Prefix: NotificationNat64Prefix? = null,
    ): Boolean = when (address) {
        is Inet4Address -> isPublicV4(address.address)
        is Inet6Address -> isPublicV6(address.address, activeNat64Prefix)
        else -> false
    }

    private fun isPublicV4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val a = bytes[0].u8()
        val b = bytes[1].u8()
        return when {
            a == 0 || a == 10 || a == 127 -> false
            a == 100 && b in 64..127 -> false // CGNAT
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 0 -> false
            a == 192 && b == 168 -> false
            a == 192 && b == 88 && bytes[2].u8() == 99 -> false // 6to4 relay anycast
            a == 198 && b in 18..19 -> false
            a == 198 && b == 51 && bytes[2].u8() == 100 -> false
            a == 203 && b == 0 && bytes[2].u8() == 113 -> false
            a >= 224 -> false
            else -> true
        }
    }

    private fun isPublicV6(
        bytes: ByteArray,
        activeNat64Prefix: NotificationNat64Prefix?,
    ): Boolean {
        if (bytes.size != 16) return false
        if (bytes.all { it.toInt() == 0 }) return false
        if (bytes.dropLast(1).all { it.toInt() == 0 } && bytes.last().u8() == 1) return false
        if (activeNat64Prefix != null && matchesPrefix(
                bytes,
                activeNat64Prefix.address,
                activeNat64Prefix.prefixLength,
            )
        ) {
            // Android currently exposes a /96 NAT64 prefix. Any other prefix layout is rejected
            // rather than attempting an ambiguous RFC 6052 extraction.
            if (activeNat64Prefix.address.size != 16 || activeNat64Prefix.prefixLength != 96) {
                return false
            }
            return isPublicV4(bytes.copyOfRange(12, 16))
        }
        if (matchesPrefix(bytes, byteArrayOf(0xff.toByte()), 8)) return false // multicast
        if (matchesPrefix(bytes, byteArrayOf(0xfc.toByte()), 7)) return false // ULA fc00::/7
        if (matchesPrefix(bytes, byteArrayOf(0xfe.toByte(), 0x80.toByte()), 10)) return false
        if (matchesPrefix(bytes, byteArrayOf(0xfe.toByte(), 0xc0.toByte()), 10)) return false
        if (matchesPrefix(bytes, prefix("0100000000000000"), 64)) return false // 100::/64
        if (matchesPrefix(bytes, prefix("0064ff9b0000000000000000"), 96)) return false
        if (matchesPrefix(bytes, prefix("0064ff9b0001"), 48)) return false // local NAT64
        // IANA's 2001:0000::/23 is protocol/special-purpose space, not a blanket public
        // destination allocation. Individual entries are intentionally not preview-fetched.
        if (matchesPrefix(bytes, prefix("20010000"), 23)) return false
        if (matchesPrefix(bytes, prefix("200100020000"), 48)) return false // benchmarking
        if (matchesPrefix(bytes, prefix("20010010"), 28)) return false // ORCHID
        if (matchesPrefix(bytes, prefix("20010020"), 28)) return false // ORCHIDv2
        if (matchesPrefix(bytes, prefix("20010db8"), 32)) return false // documentation
        if (matchesPrefix(bytes, prefix("2620004f8000"), 48)) return false // AS112 special
        if (matchesPrefix(bytes, prefix("3fff00"), 20)) return false // documentation
        if (matchesPrefix(bytes, prefix("5f00"), 16)) return false // segment-routing SIDs
        if (matchesPrefix(bytes, prefix("2002"), 16)) return false // 6to4 embeds IPv4
        if (bytes.take(10).all { it.toInt() == 0 } && bytes[10].u8() == 0xff && bytes[11].u8() == 0xff) {
            return false // IPv4-mapped
        }
        if (bytes.take(12).all { it.toInt() == 0 }) return false // compatible/embedded IPv4
        // Positive IANA/RIR allocation registry snapshot. `2000::/3` is only an address-format
        // class; unallocated holes are rejected rather than treated as publicly reachable.
        return PUBLIC_NATIVE_IPV6_PREFIXES.any { (candidate, length) ->
            matchesPrefix(bytes, candidate, length)
        }
    }

    private fun matchesPrefix(address: ByteArray, prefix: ByteArray, prefixLength: Int): Boolean {
        if (prefixLength !in 0..128) return false
        val fullBytes = prefixLength / 8
        val partialBits = prefixLength % 8
        if (prefix.size < fullBytes + if (partialBits == 0) 0 else 1) return false
        if (address.size < fullBytes + if (partialBits == 0) 0 else 1) return false
        for (index in 0 until fullBytes) {
            if (address[index] != prefix[index]) return false
        }
        if (partialBits == 0) return true
        val mask = (0xff shl (8 - partialBits)) and 0xff
        return address[fullBytes].u8() and mask == prefix[fullBytes].u8() and mask
    }

    private fun prefix(hex: String): ByteArray {
        val normalized = if (hex.length % 2 == 0) hex else "${hex}0"
        return ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun Byte.u8(): Int = toInt() and 0xff

    private val PUBLIC_NATIVE_IPV6_PREFIXES = listOf(
        "20010200" to 23,
        "20010400" to 23,
        "20010600" to 23,
        "20010800" to 22,
        "20010c00" to 23,
        "20010e00" to 23,
        "20011200" to 23,
        "20011400" to 22,
        "20011800" to 23,
        "20011a00" to 23,
        "20011c00" to 22,
        "20012000" to 19,
        "20014000" to 23,
        "20014200" to 23,
        "20014400" to 23,
        "20014600" to 23,
        "20014800" to 23,
        "20014a00" to 23,
        "20014c00" to 23,
        "20015000" to 20,
        "20018000" to 19,
        "2001a000" to 20,
        "2001b000" to 20,
        "20030000" to 18,
        "2400" to 12,
        "2410" to 12,
        "2600" to 12,
        "26100000" to 23,
        "26200000" to 23,
        "2630" to 12,
        "2800" to 12,
        "2a00" to 12,
        "2a10" to 12,
        "2c00" to 12,
    ).map { (hex, length) -> prefix(hex) to length }
}

internal object PublicHostnamePolicy {
    fun isAllowed(host: String): Boolean {
        val normalized = host.trimEnd('.').lowercase(Locale.ROOT)
        if (normalized.isBlank() || normalized.length > 253) return false
        if (
            normalized == "localhost" ||
            normalized.endsWith(".localhost") ||
            normalized.endsWith(".local") ||
            normalized.endsWith(".internal") ||
            normalized.endsWith(".home") ||
            normalized.endsWith(".lan") ||
            normalized.endsWith(".test") ||
            normalized.endsWith(".invalid") ||
            normalized.endsWith(".example")
        ) return false
        return true
    }
}

/**
 * Notification previews never replay query/fragment/user tokens. A server still sees a normal
 * standards-defined safe GET and the device IP; callers must treat that bounded privacy cost as a
 * limitation of direct on-device metadata retrieval.
 */
internal object PreviewSafeUrlPolicy {
    fun isAllowed(url: HttpUrl): Boolean {
        if (url.toString().length > MAX_URL_CHARACTERS) return false
        if (url.query != null || url.fragment != null) return false
        if (url.encodedPath.length > MAX_PATH_CHARACTERS) return false
        val encodedSegments = url.encodedPathSegments
        val decodedSegments = url.pathSegments
        if (encodedSegments.size > MAX_PATH_SEGMENTS || decodedSegments.size != encodedSegments.size) {
            return false
        }
        return encodedSegments.indices.all { index ->
            val encoded = encodedSegments[index]
            val decoded = decodedSegments[index]
            encoded.length <= MAX_PATH_SEGMENT_CHARACTERS &&
                decoded.length <= MAX_PATH_SEGMENT_CHARACTERS &&
                // Encoded and double-encoded path material is never needed for a conservative
                // preview and can hide short/segmented identifiers from deterministic checks.
                encoded == decoded &&
                !TOKEN_LIKE_SEGMENT.containsMatchIn(encoded) &&
                !TOKEN_LIKE_SEGMENT.containsMatchIn(decoded) &&
                !ACTION_LIKE_SEGMENT.containsMatchIn(decoded)
        }
    }

    private const val MAX_URL_CHARACTERS = 2_048
    private const val MAX_PATH_CHARACTERS = 512
    private const val MAX_PATH_SEGMENTS = 12
    private const val MAX_PATH_SEGMENT_CHARACTERS = 96
    private val TOKEN_LIKE_SEGMENT = Regex("(?i)(?:^|[^a-z0-9])[a-z0-9_-]{28,}(?:$|[^a-z0-9])")
    private val ACTION_LIKE_SEGMENT = Regex(
        "(?i)(?:^|[-_.])(?:unsubscribe|confirm|cancel|accept|approve|auth|login|reset|" +
            "verify|verification|action|checkout|delete|remove|activate|redeem)(?:$|[-_.])",
    )
}
