package ai.hans.standard.phone.notifications.facts

import ai.hans.standard.notifications.NotificationMemoryCandidate
import ai.hans.standard.notifications.NotificationMemoryKind
import ai.hans.standard.notifications.NotificationMemorySourceField
import ai.hans.standard.phone.notifications.NotificationPrivacyMutationCoordinator
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * A separate Android-owned SQLite archive. No Codex home/database/memory file is opened here.
 * No age eviction: capacity is a reported outcome, never permission to discard earlier facts.
 * The 128 MiB limit covers database pages; SQLite's temporary rollback journal needs extra space.
 */
class AndroidNotificationFactRepository(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
    private val capacity: NotificationFactCapacity = NotificationFactCapacity(),
    private val privacySnapshot: () -> NotificationFactExternalPrivacy = {
        NotificationFactExternalPrivacy.UNAVAILABLE
    },
    private val mutationMonitor: Any = NotificationPrivacyMutationCoordinator.lock,
    private val clock: () -> Long = System::currentTimeMillis,
) : NotificationFactRepository {
    private val databaseFile: File
    private val sealFile: File
    private var database: SQLiteDatabase? = null
    private var storeEpoch: String? = null
    private var closed = false
    private var unavailable: NotificationFactUnavailableReason? = null

    init {
        require(databaseName.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*\\.db")))
        databaseFile = context.applicationContext.getDatabasePath(databaseName)
        sealFile = File(databaseFile.parentFile, "$databaseName.archive-state")
    }

    override fun captureToken(packageName: String): NotificationArchiveCaptureToken? {
        requireArchivePackage(packageName)
        return try {
            access { db ->
                val privacy = readablePrivacy(db)
                if (!privacy.permits(packageName)) null else token(db, packageName)
            }
        } catch (_: NotificationFactArchiveUnavailableException) {
            null
        }
    }

    override fun canStage(batch: NotificationFactBatch): Boolean {
        val stable = batch.copy(validatedCandidates = batch.validatedCandidates.toList())
        return try {
            access(allowCreate = false) { db ->
                candidateRejection(db, stable, readablePrivacy(db)) == null
            }
        } catch (_: NotificationFactArchiveUnavailableException) {
            false
        } catch (_: SQLiteFullException) {
            false
        }
    }

    override fun commit(batch: NotificationFactBatch): NotificationFactCommitResult {
        // Copy and revalidate caller-owned collections before beginning any store work.
        val stable = batch.copy(validatedCandidates = batch.validatedCandidates.toList())
        return try {
            access { db -> transaction(db) {
                val privacy = readablePrivacy(db)
                candidateRejection(db, stable, privacy)?.let { reason ->
                    return@transaction NotificationFactCommitResult.Rejected(reason)
                }
                val factIds = stable.validatedCandidates.map {
                    NotificationFactIds.forCandidate(stable.packageName, stable.sourceRef, it)
                }
                val fingerprint = batchFingerprint(stable)
                readBatchReceipt(db, stable.batchId)?.let { receipt ->
                    return@transaction if (receipt.first == fingerprint) {
                        NotificationFactCommitResult.Replay(receipt.second)
                    } else {
                        NotificationFactCommitResult.Rejected(NotificationFactCommitResult.Reason.ID_CONFLICT)
                    }
                }
                db.rawQuery(
                    "SELECT source_revision,sequence FROM source_heads WHERE package_name=? AND source_ref=?",
                    arrayOf(stable.packageName, stable.sourceRef),
                ).use { cursor ->
                    if (cursor.moveToFirst() &&
                        (stable.sourceRevision <= cursor.getLong(0) || stable.sequence <= cursor.getLong(1))
                    ) {
                        return@transaction NotificationFactCommitResult.Rejected(
                            NotificationFactCommitResult.Reason.STALE_SOURCE,
                        )
                    }
                }
                stable.validatedCandidates.forEachIndexed { index, candidate ->
                    val id = factIds[index]
                    val existing = readFact(db, id)
                    // A later external statement is not authority to replace an owner's correction.
                    if (existing?.authority != NotificationFactAuthority.OWNER_CORRECTION) {
                        val fact = NotificationFact(
                            factId = id,
                            revision = existing?.revision?.let { Math.addExact(it, 1) } ?: 1,
                            kind = candidate.kind,
                            text = candidate.quote,
                            authority = NotificationFactAuthority.UNTRUSTED_NOTIFICATION_CLAIM,
                            packageName = stable.packageName,
                            sourceRef = stable.sourceRef,
                            sourceRevision = stable.sourceRevision,
                            sequence = stable.sequence,
                            observedAtEpochMillis = stable.observedAtEpochMillis,
                            extractorVersion = stable.extractorVersion,
                            evidence = candidate,
                        )
                        writeFact(db, fact)
                    }
                }
                db.insertWithOnConflict("source_heads", null, ContentValues().apply {
                    put("package_name", stable.packageName)
                    put("source_ref", stable.sourceRef)
                    put("source_revision", stable.sourceRevision)
                    put("sequence", stable.sequence)
                }, SQLiteDatabase.CONFLICT_REPLACE).requireInsert()
                db.insertOrThrow("batch_receipts", null, ContentValues().apply {
                    put("batch_id", stable.batchId)
                    put("fingerprint", fingerprint)
                    put("fact_ids", JSONArray(factIds).toString())
                })
                enforceCapacity(db, ordinaryWrite = true)
                NotificationFactCommitResult.Stored(factIds)
            } }
        } catch (_: CapacityReached) {
            NotificationFactCommitResult.CapacityExceeded
        } catch (_: SQLiteFullException) {
            NotificationFactCommitResult.CapacityExceeded
        } catch (error: NotificationFactArchiveUnavailableException) {
            NotificationFactCommitResult.Unavailable(error.reason)
        }
    }

    override fun query(query: NotificationFactQuery): NotificationFactQueryResult {
        val stable = query.copy(terms = query.terms.toList())
        val terms = stable.terms.flatMap(::keywordTerms).distinct()
        require(terms.size <= NotificationFactBounds.MAX_TERMS)
        require(stable.terms.isEmpty() || terms.isNotEmpty())
        return access { db ->
            val privacy = readablePrivacy(db)
            if (stable.packageName != null && !privacy.permits(stable.packageName)) {
                throw unavailable(NotificationFactUnavailableReason.EXTERNAL_PRIVACY_UNAVAILABLE)
            }
            val clauses = mutableListOf("observed_at>=?", "observed_at<=?", "package_name NOT LIKE 'ai.hans.%'")
            val args = mutableListOf(stable.sinceEpochMillis.toString(), stable.untilEpochMillis.toString())
            stable.packageName?.let { clauses += "package_name=?"; args += it }
            stable.sourceRef?.let { clauses += "source_ref=?"; args += it }
            stable.kind?.let { clauses += "kind=?"; args += it.wireName }
            val excluded = (privacy.excludedPackages + privacy.protectedPackages).toList()
            if (excluded.isNotEmpty()) {
                clauses += "package_name NOT IN (${placeholders(excluded.size)})"
                args += excluded
            }
            if (terms.isNotEmpty()) {
                clauses += "fact_id IN (SELECT fact_id FROM fact_terms WHERE term IN " +
                    "(${placeholders(terms.size)}) GROUP BY fact_id HAVING COUNT(DISTINCT term)=CAST(? AS INTEGER))"
                args += terms
                args += terms.size.toString()
            }
            val selected = mutableListOf<NotificationFact>()
            var truncated = false
            db.rawQuery(
                "SELECT * FROM facts WHERE ${clauses.joinToString(" AND ")} " +
                    "ORDER BY observed_at DESC,fact_id ASC LIMIT ${stable.limit + 1}",
                args.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    if (selected.size == stable.limit) { truncated = true; break }
                    val fact = cursor.readFact()
                    check(privacy.permits(fact.packageName))
                    val candidate = NotificationFactQueryResult(selected + fact, truncated = false)
                    if (candidate.encodedUtf8Bytes > stable.maxUtf8Bytes) { truncated = true; break }
                    selected += fact
                }
            }
            NotificationFactQueryResult(selected.toList(), truncated).also {
                check(it.encodedUtf8Bytes <= stable.maxUtf8Bytes)
            }
        }
    }

    override fun correct(correction: NotificationFactCorrection): NotificationFactCorrectionResult = try {
        access { db -> transaction(db) {
            val privacy = readablePrivacy(db)
            val fingerprint = archiveDigest(listOf("correct", correction.factId,
                correction.expectedRevision.toString(), correction.kind.wireName, correction.text))
            readOperation(db, correction.mutationId)?.let { operation ->
                return@transaction if (operation.kind == "correct" && operation.fingerprint == fingerprint) {
                    NotificationFactCorrectionResult.Replay(
                        checkNotNull(operation.factId), checkNotNull(operation.revision),
                    )
                } else {
                    NotificationFactCorrectionResult.Conflict(NotificationFactCorrectionResult.Reason.ID_CONFLICT)
                }
            }
            val existing = readFact(db, correction.factId)
                ?: return@transaction NotificationFactCorrectionResult.Conflict(
                    NotificationFactCorrectionResult.Reason.NOT_FOUND,
                )
            if (!privacy.permits(existing.packageName)) {
                return@transaction NotificationFactCorrectionResult.Conflict(
                    NotificationFactCorrectionResult.Reason.PACKAGE_NOT_ALLOWED,
                )
            }
            if (existing.revision != correction.expectedRevision) {
                return@transaction NotificationFactCorrectionResult.Conflict(
                    NotificationFactCorrectionResult.Reason.REVISION_CHANGED,
                )
            }
            val corrected = existing.copy(
                revision = Math.addExact(existing.revision, 1),
                kind = correction.kind,
                text = correction.text,
                authority = NotificationFactAuthority.OWNER_CORRECTION,
                correctedAtEpochMillis = clock(),
            )
            writeFact(db, corrected)
            db.insertOrThrow("operations", null, ContentValues().apply {
                put("mutation_id", correction.mutationId)
                put("operation_kind", "correct")
                put("fingerprint", fingerprint)
                put("status", "completed")
                put("fact_id", corrected.factId)
                put("revision", corrected.revision)
            })
            enforceCapacity(db, ordinaryWrite = true)
            NotificationFactCorrectionResult.Applied(corrected.factId, corrected.revision)
        } }
    } catch (_: CapacityReached) {
        NotificationFactCorrectionResult.CapacityExceeded
    } catch (_: SQLiteFullException) {
        NotificationFactCorrectionResult.CapacityExceeded
    } catch (error: NotificationFactArchiveUnavailableException) {
        NotificationFactCorrectionResult.Unavailable(error.reason)
    }

    override fun beginPrivacy(request: NotificationFactPrivacyRequest): NotificationFactPrivacyBeginResult = try {
        access { db -> transaction(db) {
            val fingerprint = archiveDigest(listOf("privacy", scopeJson(request.scope).toString()))
            readOperation(db, request.mutationId)?.let { operation ->
                if (operation.kind != "privacy" || operation.fingerprint != fingerprint) {
                    return@transaction NotificationFactPrivacyBeginResult.Conflict(
                        NotificationFactPrivacyBeginResult.Reason.ID_CONFLICT,
                    )
                }
                val intent = decodeIntent(checkNotNull(operation.intentJson))
                return@transaction if (operation.status == "pending") {
                    NotificationFactPrivacyBeginResult.Pending(intent, replay = true)
                } else {
                    NotificationFactPrivacyBeginResult.Completed(intent)
                }
            }
            if (pendingCount(db) >= NotificationFactBounds.MAX_PENDING_PRIVACY_INTENTS) {
                return@transaction NotificationFactPrivacyBeginResult.Conflict(
                    NotificationFactPrivacyBeginResult.Reason.TOO_MANY_PENDING,
                )
            }
            var affectedPackage: String? = null
            var affectedSource: String? = null
            when (val scope = request.scope) {
                NotificationFactPrivacyScope.All -> Unit
                is NotificationFactPrivacyScope.Package -> affectedPackage = scope.packageName
                is NotificationFactPrivacyScope.Source -> {
                    affectedPackage = scope.packageName
                    affectedSource = scope.sourceRef
                }
                is NotificationFactPrivacyScope.Fact -> {
                    val fact = readFact(db, scope.factId)
                        ?: return@transaction NotificationFactPrivacyBeginResult.Conflict(
                            NotificationFactPrivacyBeginResult.Reason.NOT_FOUND,
                        )
                    if (fact.revision != scope.expectedRevision) {
                        return@transaction NotificationFactPrivacyBeginResult.Conflict(
                            NotificationFactPrivacyBeginResult.Reason.REVISION_CHANGED,
                        )
                    }
                    affectedPackage = fact.packageName
                    affectedSource = fact.sourceRef
                }
            }
            val allGeneration = if (request.scope == NotificationFactPrivacyScope.All) {
                Math.addExact(allGeneration(db), 1).also { generation ->
                    check(db.update("archive_meta", ContentValues().apply {
                        put("all_generation", generation)
                    }, "id=1", null) == 1)
                }
            } else allGeneration(db)
            val packageGeneration = affectedPackage?.let { packageName ->
                val previous = packageGeneration(db, packageName)
                if (request.scope is NotificationFactPrivacyScope.Package) {
                    Math.addExact(previous, 1).also { generation ->
                        db.insertWithOnConflict("package_generations", null, ContentValues().apply {
                            put("package_name", packageName); put("generation", generation)
                        }, SQLiteDatabase.CONFLICT_REPLACE).requireInsert()
                    }
                } else previous
            }
            when (val scope = request.scope) {
                is NotificationFactPrivacyScope.Source -> db.insertWithOnConflict(
                    "source_tombstones", null, ContentValues().apply {
                        put("package_name", scope.packageName); put("source_ref", scope.sourceRef)
                    }, SQLiteDatabase.CONFLICT_IGNORE,
                )
                is NotificationFactPrivacyScope.Fact -> db.insertWithOnConflict(
                    "fact_tombstones", null, ContentValues().apply {
                        put("fact_id", scope.factId)
                        put("package_name", affectedPackage)
                        put("source_ref", affectedSource)
                    }, SQLiteDatabase.CONFLICT_IGNORE,
                )
                else -> Unit
            }
            val (where, args) = scopeWhere(request.scope)
            val removed = db.delete("facts", where, args)
            val intent = NotificationFactPrivacyIntent(
                storeEpoch = checkNotNull(storeEpoch),
                mutationId = request.mutationId,
                scope = request.scope,
                affectedPackageName = affectedPackage,
                affectedSourceRef = affectedSource,
                allGeneration = allGeneration,
                packageGeneration = packageGeneration,
                removedFacts = removed,
            )
            db.insertOrThrow("operations", null, ContentValues().apply {
                put("mutation_id", request.mutationId)
                put("operation_kind", "privacy")
                put("fingerprint", fingerprint)
                put("status", "pending")
                put("intent_json", encodeIntent(intent))
            })
            verifyScopeEmpty(db, request.scope)
            enforceCapacity(db, ordinaryWrite = false)
            NotificationFactPrivacyBeginResult.Pending(intent)
        } }
    } catch (_: CapacityReached) {
        NotificationFactPrivacyBeginResult.CapacityExceeded
    } catch (_: SQLiteFullException) {
        NotificationFactPrivacyBeginResult.CapacityExceeded
    } catch (error: NotificationFactArchiveUnavailableException) {
        NotificationFactPrivacyBeginResult.Unavailable(error.reason)
    }

    override fun pendingIntents(): List<NotificationFactPrivacyIntent> = access { db ->
        val pending = mutableListOf<NotificationFactPrivacyIntent>()
        db.rawQuery(
            "SELECT intent_json FROM operations WHERE operation_kind='privacy' AND status='pending' " +
                "ORDER BY mutation_id LIMIT ${NotificationFactBounds.MAX_PENDING_PRIVACY_INTENTS + 1}", null,
        ).use { cursor ->
            while (cursor.moveToNext()) pending += decodeIntent(cursor.getString(0))
        }
        check(pending.size <= NotificationFactBounds.MAX_PENDING_PRIVACY_INTENTS)
        pending.toList()
    }

    override fun acknowledgePrivacy(intent: NotificationFactPrivacyIntent): Boolean = try {
        access { db -> transaction(db) {
            if (intent.storeEpoch != storeEpoch) return@transaction false
            val operation = readOperation(db, intent.mutationId) ?: return@transaction false
            if (operation.kind != "privacy" || operation.intentJson == null ||
                decodeIntent(operation.intentJson) != intent
            ) return@transaction false
            // Historical idempotency receipt, not a claim that later authorized data is absent.
            if (operation.status == "completed") return@transaction true
            verifyScopeEmpty(db, intent.scope)
            check(allGeneration(db) >= intent.allGeneration)
            intent.affectedPackageName?.let {
                check(packageGeneration(db, it) >= checkNotNull(intent.packageGeneration))
            }
            check(db.update("operations", ContentValues().apply { put("status", "completed") },
                "mutation_id=? AND status='pending'", arrayOf(intent.mutationId)) == 1)
            true
        } }
    } catch (_: NotificationFactArchiveUnavailableException) {
        false
    } catch (_: SQLiteFullException) {
        false
    }

    override fun health(): NotificationFactArchiveHealth = try {
        access(allowCreate = false) { db ->
            val count = factCount(db)
            val bytes = usedDatabaseBytes(db)
            val pending = pendingCount(db)
            val external = externalPrivacy()
            val reason = when {
                !external.policyAvailable || external.purgeRequired ->
                    NotificationFactUnavailableReason.EXTERNAL_PRIVACY_UNAVAILABLE
                pending > 0 -> NotificationFactUnavailableReason.PRIVACY_RECOVERY_REQUIRED
                else -> null
            }
            NotificationFactArchiveHealth(
                available = reason == null,
                unavailableReason = reason,
                factCount = count,
                usedDatabaseBytes = bytes,
                pendingPrivacyIntents = pending,
                capacityExceeded = count >= capacity.maxFacts ||
                    bytes >= capacity.maxDatabaseBytes - capacity.privacyReserveBytes,
                capacity = capacity,
            )
        }
    } catch (error: NotificationFactArchiveUnavailableException) {
        NotificationFactArchiveHealth(false, error.reason, null, null, null, false, capacity)
    }

    override fun close() = synchronized(mutationMonitor) {
        closed = true
        database?.close()
        database = null
    }

    private fun readablePrivacy(db: SQLiteDatabase): NotificationFactExternalPrivacy {
        val privacy = externalPrivacy()
        if (!privacy.policyAvailable || privacy.purgeRequired) {
            throw unavailable(NotificationFactUnavailableReason.EXTERNAL_PRIVACY_UNAVAILABLE)
        }
        if (pendingCount(db) != 0) {
            throw unavailable(NotificationFactUnavailableReason.PRIVACY_RECOVERY_REQUIRED)
        }
        return privacy
    }

    private fun candidateRejection(
        db: SQLiteDatabase,
        batch: NotificationFactBatch,
        privacy: NotificationFactExternalPrivacy,
    ): NotificationFactCommitResult.Reason? = when {
        !privacy.permits(batch.packageName) -> NotificationFactCommitResult.Reason.PACKAGE_NOT_ALLOWED
        token(db, batch.packageName) != batch.token -> NotificationFactCommitResult.Reason.STALE_TOKEN
        sourceIsForgotten(db, batch.packageName, batch.sourceRef) || batch.validatedCandidates.any {
            factIsForgotten(db, NotificationFactIds.forCandidate(batch.packageName, batch.sourceRef, it))
        } -> NotificationFactCommitResult.Reason.TOMBSTONED
        else -> null
    }

    private fun externalPrivacy(): NotificationFactExternalPrivacy = try {
        privacySnapshot().let { value ->
            check(value.excludedPackages.size + value.protectedPackages.size <= 512)
            value.copy(excludedPackages = value.excludedPackages.toSet(),
                protectedPackages = value.protectedPackages.toSet())
        }
    } catch (_: Exception) {
        throw unavailable(NotificationFactUnavailableReason.EXTERNAL_PRIVACY_UNAVAILABLE)
    }

    private inline fun <T> access(
        allowCreate: Boolean = true,
        block: (SQLiteDatabase) -> T,
    ): T = synchronized(mutationMonitor) {
        if (closed) throw unavailable(NotificationFactUnavailableReason.CLOSED)
        unavailable?.let { throw unavailable(it) }
        try {
            block(openDatabase(allowCreate))
        } catch (error: NotificationFactArchiveUnavailableException) {
            throw error
        } catch (error: CapacityReached) {
            throw error
        } catch (error: SQLiteFullException) {
            throw error
        } catch (error: Exception) {
            val reason = if (error is SQLiteDatabaseCorruptException || error !is SQLiteException) {
                NotificationFactUnavailableReason.CORRUPT
            } else NotificationFactUnavailableReason.IO_FAILURE
            unavailable = reason
            throw unavailable(reason)
        }
    }

    private fun openDatabase(allowCreate: Boolean): SQLiteDatabase {
        database?.let { current ->
            if (!isRegular(databaseFile) || !isRegular(sealFile) || readSeal() != storeEpoch) {
                throw latch(NotificationFactUnavailableReason.UNINITIALIZED_OR_MISSING)
            }
            return current
        }
        val dbExists = Files.exists(databaseFile.toPath(), LinkOption.NOFOLLOW_LINKS)
        val sealExists = Files.exists(sealFile.toPath(), LinkOption.NOFOLLOW_LINKS)
        val pendingSeal = File(sealFile.path + ".new").exists() || File(sealFile.path + ".bak").exists()
        val fresh = !dbExists && !sealExists && !pendingSeal
        if (fresh && !allowCreate) {
            throw unavailable(NotificationFactUnavailableReason.UNINITIALIZED_OR_MISSING)
        }
        if (!fresh && (!dbExists || !sealExists || pendingSeal || !isRegular(databaseFile) || !isRegular(sealFile))) {
            throw latch(NotificationFactUnavailableReason.UNINITIALIZED_OR_MISSING)
        }
        val epoch = if (fresh) UUID.randomUUID().toString() else readSeal()
        if (fresh) {
            check(databaseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
            // A failed/interrupted first initialization leaves an explicit non-ready seal.
            writeSeal("initializing:$epoch")
        }
        val noDeleteHandler = DatabaseErrorHandler {
            throw SQLiteDatabaseCorruptException("notification_fact_archive_corrupt")
        }
        // Keyword normalization is explicit; do not create Android's locale metadata/collation table.
        val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or
            (if (fresh) SQLiteDatabase.CREATE_IF_NECESSARY else 0)
        val opened = SQLiteDatabase.openDatabase(databaseFile.path, null, flags, noDeleteHandler)
        try {
            if (!fresh) validateSchema(opened, epoch)
            opened.setForeignKeyConstraintsEnabled(true)
            opened.rawQuery("PRAGMA secure_delete=ON", null).use {
                check(it.moveToFirst() && it.getInt(0) == 1)
            }
            opened.rawQuery("PRAGMA journal_mode=DELETE", null).use {
                check(it.moveToFirst() && it.getString(0).equals("delete", ignoreCase = true))
            }
            opened.rawQuery("PRAGMA synchronous=FULL", null).use { it.moveToFirst() }
            val pageSize = scalar(opened, "PRAGMA page_size")
            opened.rawQuery("PRAGMA max_page_count=${capacity.maxDatabaseBytes / pageSize}", null).use {
                check(it.moveToFirst())
            }
            if (fresh) {
                transaction(opened) {
                    SCHEMA.forEach(opened::execSQL)
                    opened.insertOrThrow("archive_meta", null, ContentValues().apply {
                        put("id", 1); put("store_epoch", epoch); put("all_generation", 0)
                        put("schema_signature", SCHEMA_SIGNATURE)
                    })
                    opened.version = SCHEMA_VERSION
                    opened.execSQL("PRAGMA application_id=$APPLICATION_ID")
                }
                writeSeal(epoch)
                validateSchema(opened, epoch)
            }
            storeEpoch = epoch
            database = opened
            return opened
        } catch (error: Exception) {
            opened.close()
            throw error
        }
    }

    private fun validateSchema(db: SQLiteDatabase, epoch: String) {
        if (db.version != SCHEMA_VERSION || scalar(db, "PRAGMA application_id") != APPLICATION_ID.toLong()) {
            throw latch(NotificationFactUnavailableReason.INVALID_SCHEMA)
        }
        val tables = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        if (tables != EXPECTED_TABLES) throw latch(NotificationFactUnavailableReason.INVALID_SCHEMA)
        val declarations = mutableListOf<String>()
        db.rawQuery("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL", null).use { cursor ->
            while (cursor.moveToNext()) declarations += normalizedSql(cursor.getString(0))
        }
        if (archiveDigest(declarations.sorted()) != SCHEMA_SIGNATURE) {
            throw latch(NotificationFactUnavailableReason.INVALID_SCHEMA)
        }
        db.rawQuery("PRAGMA quick_check(1)", null).use {
            if (!it.moveToFirst() || it.getString(0) != "ok" || it.moveToNext()) {
                throw latch(NotificationFactUnavailableReason.CORRUPT)
            }
        }
        db.rawQuery("PRAGMA foreign_key_check", null).use {
            if (it.moveToFirst()) throw latch(NotificationFactUnavailableReason.CORRUPT)
        }
        db.rawQuery("SELECT store_epoch,all_generation,schema_signature FROM archive_meta", null).use {
            if (!it.moveToFirst() || it.getString(0) != epoch || it.getLong(1) < 0 ||
                it.getString(2) != SCHEMA_SIGNATURE || it.moveToNext()
            ) throw latch(NotificationFactUnavailableReason.INVALID_SCHEMA)
        }
    }

    private fun readSeal(): String {
        if (!isRegular(sealFile) || sealFile.length() !in 1..128) {
            throw latch(NotificationFactUnavailableReason.UNINITIALIZED_OR_MISSING)
        }
        val raw = String(Files.readAllBytes(sealFile.toPath()), StandardCharsets.US_ASCII)
        val prefix = "notification-facts-v1\n"
        if (!raw.startsWith(prefix) || !raw.endsWith('\n')) {
            throw latch(NotificationFactUnavailableReason.UNINITIALIZED_OR_MISSING)
        }
        val epoch = raw.removePrefix(prefix).removeSuffix("\n")
        if (runCatching { UUID.fromString(epoch).toString() == epoch }.getOrDefault(false).not()) {
            throw latch(NotificationFactUnavailableReason.UNINITIALIZED_OR_MISSING)
        }
        return epoch
    }

    private fun writeSeal(value: String) {
        val atomic = AtomicFile(sealFile)
        val output = atomic.startWrite()
        try {
            output.write("notification-facts-v1\n$value\n".toByteArray(StandardCharsets.US_ASCII))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun token(db: SQLiteDatabase, packageName: String) = NotificationArchiveCaptureToken(
        checkNotNull(storeEpoch), allGeneration(db), packageGeneration(db, packageName),
    )

    private fun allGeneration(db: SQLiteDatabase): Long = scalar(db, "SELECT all_generation FROM archive_meta WHERE id=1")

    private fun packageGeneration(db: SQLiteDatabase, packageName: String): Long = db.rawQuery(
        "SELECT generation FROM package_generations WHERE package_name=?", arrayOf(packageName),
    ).use { if (it.moveToFirst()) it.getLong(0).also { value -> check(value >= 0) } else 0 }

    private fun readFact(db: SQLiteDatabase, factId: String): NotificationFact? = db.rawQuery(
        "SELECT * FROM facts WHERE fact_id=?", arrayOf(factId),
    ).use { if (it.moveToFirst()) it.readFact() else null }

    private fun Cursor.readFact(): NotificationFact {
        fun text(column: String) = getString(getColumnIndexOrThrow(column))
        fun number(column: String) = getLong(getColumnIndexOrThrow(column))
        val evidence = NotificationMemoryCandidate(
            kind = NotificationMemoryKind.entries.first { it.wireName == text("evidence_kind") },
            sourceField = NotificationMemorySourceField.entries.first { it.wireName == text("source_field") },
            quote = text("quote"), startUtf16 = number("start_utf16").toIntExact(),
            endUtf16 = number("end_utf16").toIntExact(), sourceSha256 = text("source_sha256"),
        )
        return NotificationFact(
            factId = text("fact_id"), revision = number("revision"),
            kind = NotificationMemoryKind.entries.first { it.wireName == text("kind") },
            text = text("text"),
            authority = NotificationFactAuthority.entries.first { it.wireName == text("authority") },
            packageName = text("package_name"), sourceRef = text("source_ref"),
            sourceRevision = number("source_revision"), sequence = number("sequence"),
            observedAtEpochMillis = number("observed_at"), extractorVersion = text("extractor_version"),
            evidence = evidence,
            correctedAtEpochMillis = getColumnIndexOrThrow("corrected_at").let {
                if (isNull(it)) null else getLong(it)
            },
        ).also { check(it.factId == NotificationFactIds.forCandidate(it.packageName, it.sourceRef, evidence)) }
    }

    private fun writeFact(db: SQLiteDatabase, fact: NotificationFact) {
        val values = ContentValues().apply {
            put("fact_id", fact.factId); put("revision", fact.revision)
            put("kind", fact.kind.wireName); put("text", fact.text)
            put("authority", fact.authority.wireName); put("package_name", fact.packageName)
            put("source_ref", fact.sourceRef); put("source_revision", fact.sourceRevision)
            put("sequence", fact.sequence); put("observed_at", fact.observedAtEpochMillis)
            put("extractor_version", fact.extractorVersion)
            put("evidence_kind", fact.evidence.kind.wireName)
            put("source_field", fact.evidence.sourceField.wireName); put("quote", fact.evidence.quote)
            put("start_utf16", fact.evidence.startUtf16); put("end_utf16", fact.evidence.endUtf16)
            put("source_sha256", fact.evidence.sourceSha256)
            if (fact.correctedAtEpochMillis == null) putNull("corrected_at")
            else put("corrected_at", fact.correctedAtEpochMillis)
        }
        db.insertWithOnConflict("facts", null, values, SQLiteDatabase.CONFLICT_REPLACE).requireInsert()
        // REPLACE cascades old index entries. Corrected text alone is indexed, not the old claim.
        keywordTerms(fact.text).forEach { term ->
            db.insertOrThrow("fact_terms", null, ContentValues().apply {
                put("fact_id", fact.factId); put("term", term)
            })
        }
    }

    private fun readBatchReceipt(db: SQLiteDatabase, batchId: String): Pair<String, List<String>>? = db.rawQuery(
        "SELECT fingerprint,fact_ids FROM batch_receipts WHERE batch_id=?", arrayOf(batchId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val values = JSONArray(cursor.getString(1))
        check(values.length() in 1..NotificationFactBounds.MAX_CANDIDATES)
        cursor.getString(0) to List(values.length()) { index ->
            values.getString(index).also(::requireArchiveId)
        }
    }

    private data class Operation(
        val kind: String, val fingerprint: String, val status: String,
        val factId: String?, val revision: Long?, val intentJson: String?,
    )

    private fun readOperation(db: SQLiteDatabase, mutationId: String): Operation? = db.rawQuery(
        "SELECT operation_kind,fingerprint,status,fact_id,revision,intent_json FROM operations WHERE mutation_id=?",
        arrayOf(mutationId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else Operation(
            cursor.getString(0), cursor.getString(1), cursor.getString(2),
            if (cursor.isNull(3)) null else cursor.getString(3),
            if (cursor.isNull(4)) null else cursor.getLong(4),
            if (cursor.isNull(5)) null else cursor.getString(5),
        ).also { check(it.kind in setOf("correct", "privacy") && it.status in setOf("pending", "completed")) }
    }

    private fun sourceIsForgotten(db: SQLiteDatabase, packageName: String, sourceRef: String): Boolean = exists(
        db, "SELECT 1 FROM source_tombstones WHERE package_name=? AND source_ref=?", arrayOf(packageName, sourceRef),
    )

    private fun factIsForgotten(db: SQLiteDatabase, factId: String): Boolean = exists(
        db, "SELECT 1 FROM fact_tombstones WHERE fact_id=?", arrayOf(factId),
    )

    private fun verifyScopeEmpty(db: SQLiteDatabase, scope: NotificationFactPrivacyScope) {
        val (where, args) = scopeWhere(scope)
        db.rawQuery("SELECT COUNT(*) FROM facts" + (where?.let { " WHERE $it" } ?: ""), args).use {
            check(it.moveToFirst() && it.getLong(0) == 0L)
        }
        check(!exists(db, "SELECT 1 FROM fact_terms LEFT JOIN facts USING(fact_id) WHERE facts.fact_id IS NULL LIMIT 1"))
    }

    private fun scopeWhere(scope: NotificationFactPrivacyScope): Pair<String?, Array<String>?> = when (scope) {
        NotificationFactPrivacyScope.All -> null to null
        is NotificationFactPrivacyScope.Package -> "package_name=?" to arrayOf(scope.packageName)
        is NotificationFactPrivacyScope.Source -> "package_name=? AND source_ref=?" to arrayOf(scope.packageName, scope.sourceRef)
        is NotificationFactPrivacyScope.Fact -> "fact_id=?" to arrayOf(scope.factId)
    }

    private fun enforceCapacity(db: SQLiteDatabase, ordinaryWrite: Boolean) {
        val permittedBytes = capacity.maxDatabaseBytes - if (ordinaryWrite) capacity.privacyReserveBytes else 0
        if (factCount(db) > capacity.maxFacts || usedDatabaseBytes(db) > permittedBytes) throw CapacityReached()
    }

    private fun factCount(db: SQLiteDatabase): Int = scalar(db, "SELECT COUNT(*) FROM facts").toIntExact()
    private fun pendingCount(db: SQLiteDatabase): Int = scalar(
        db, "SELECT COUNT(*) FROM operations WHERE operation_kind='privacy' AND status='pending'",
    ).toIntExact()

    private fun usedDatabaseBytes(db: SQLiteDatabase): Long = Math.multiplyExact(
        scalar(db, "PRAGMA page_count") - scalar(db, "PRAGMA freelist_count"), scalar(db, "PRAGMA page_size"),
    )

    private fun scalar(db: SQLiteDatabase, query: String): Long = db.rawQuery(query, null).use {
        check(it.moveToFirst()); it.getLong(0)
    }

    private fun exists(db: SQLiteDatabase, query: String, args: Array<String>? = null): Boolean =
        db.rawQuery(query, args).use { it.moveToFirst() }

    private inline fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    private fun latch(reason: NotificationFactUnavailableReason): NotificationFactArchiveUnavailableException {
        unavailable = reason
        return unavailable(reason)
    }

    private fun unavailable(reason: NotificationFactUnavailableReason) = NotificationFactArchiveUnavailableException(reason)

    companion object {
        const val DEFAULT_DATABASE_NAME = "notification_facts.db"
        private const val SCHEMA_VERSION = 1
        private const val APPLICATION_ID = 0x48414631
        private val EXPECTED_TABLES = setOf("archive_meta", "package_generations", "source_heads", "facts",
            "fact_terms", "batch_receipts", "operations", "source_tombstones", "fact_tombstones")
        private val SCHEMA = listOf(
            "CREATE TABLE archive_meta(id INTEGER PRIMARY KEY CHECK(id=1),store_epoch TEXT NOT NULL," +
                "all_generation INTEGER NOT NULL CHECK(all_generation>=0),schema_signature TEXT NOT NULL)",
            "CREATE TABLE package_generations(package_name TEXT PRIMARY KEY,generation INTEGER NOT NULL CHECK(generation>=0))",
            "CREATE TABLE source_heads(package_name TEXT NOT NULL,source_ref TEXT NOT NULL," +
                "source_revision INTEGER NOT NULL CHECK(source_revision>0),sequence INTEGER NOT NULL CHECK(sequence>0)," +
                "PRIMARY KEY(package_name,source_ref))",
            "CREATE TABLE facts(fact_id TEXT PRIMARY KEY,revision INTEGER NOT NULL CHECK(revision>0)," +
                "kind TEXT NOT NULL,text TEXT NOT NULL,authority TEXT NOT NULL,package_name TEXT NOT NULL," +
                "source_ref TEXT NOT NULL,source_revision INTEGER NOT NULL CHECK(source_revision>0)," +
                "sequence INTEGER NOT NULL CHECK(sequence>0),observed_at INTEGER NOT NULL CHECK(observed_at>=0)," +
                "extractor_version TEXT NOT NULL,evidence_kind TEXT NOT NULL,source_field TEXT NOT NULL,quote TEXT NOT NULL," +
                "start_utf16 INTEGER NOT NULL,end_utf16 INTEGER NOT NULL,source_sha256 TEXT NOT NULL,corrected_at INTEGER)",
            "CREATE INDEX facts_source ON facts(package_name,source_ref)",
            "CREATE INDEX facts_time ON facts(observed_at DESC,fact_id)",
            "CREATE TABLE fact_terms(fact_id TEXT NOT NULL REFERENCES facts(fact_id) ON DELETE CASCADE," +
                "term TEXT NOT NULL,PRIMARY KEY(fact_id,term))",
            "CREATE INDEX fact_terms_lookup ON fact_terms(term,fact_id)",
            "CREATE TABLE batch_receipts(batch_id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,fact_ids TEXT NOT NULL)",
            "CREATE TABLE operations(mutation_id TEXT PRIMARY KEY,operation_kind TEXT NOT NULL CHECK(operation_kind IN ('correct','privacy'))," +
                "fingerprint TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('pending','completed'))," +
                "fact_id TEXT,revision INTEGER,intent_json TEXT)",
            "CREATE INDEX operations_pending_lookup ON operations(operation_kind,status)",
            "CREATE TABLE source_tombstones(package_name TEXT NOT NULL,source_ref TEXT NOT NULL,PRIMARY KEY(package_name,source_ref))",
            "CREATE TABLE fact_tombstones(fact_id TEXT PRIMARY KEY,package_name TEXT NOT NULL,source_ref TEXT NOT NULL)",
        )
        private val SCHEMA_SIGNATURE = archiveDigest(SCHEMA.map(::normalizedSql).sorted())
        private val WORD = Regex("[\\p{L}\\p{N}]+")

        private fun normalizedSql(value: String) = value.trim().replace(Regex("\\s+"), " ")

        private fun keywordTerms(value: String): List<String> = WORD.findAll(
            Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT),
        ).map { it.value }.filter { it.archiveUtf8Size() <= NotificationFactBounds.MAX_TERM_UTF8_BYTES }.distinct().toList()

        private fun batchFingerprint(batch: NotificationFactBatch): String = archiveDigest(
            listOf(batch.token.storeEpoch, batch.token.allGeneration.toString(), batch.token.packageGeneration.toString(),
                batch.packageName, batch.sourceRef, batch.sourceRevision.toString(), batch.sequence.toString(),
                batch.observedAtEpochMillis.toString(), batch.extractorVersion) +
                batch.validatedCandidates.flatMap { candidate -> listOf(candidate.kind.wireName,
                    candidate.sourceField.wireName, candidate.quote, candidate.startUtf16.toString(),
                    candidate.endUtf16.toString(), candidate.sourceSha256) },
        )

        private fun scopeJson(scope: NotificationFactPrivacyScope): JSONObject = when (scope) {
            NotificationFactPrivacyScope.All -> JSONObject().put("kind", "all")
            is NotificationFactPrivacyScope.Package -> JSONObject().put("kind", "package").put("packageName", scope.packageName)
            is NotificationFactPrivacyScope.Source -> JSONObject().put("kind", "source")
                .put("packageName", scope.packageName).put("sourceRef", scope.sourceRef)
            is NotificationFactPrivacyScope.Fact -> JSONObject().put("kind", "fact")
                .put("factId", scope.factId).put("expectedRevision", scope.expectedRevision)
        }

        private fun decodeScope(value: JSONObject): NotificationFactPrivacyScope = when (value.strictString("kind")) {
            "all" -> { value.requireKeys(setOf("kind")); NotificationFactPrivacyScope.All }
            "package" -> {
                value.requireKeys(setOf("kind", "packageName"))
                NotificationFactPrivacyScope.Package(value.strictString("packageName"))
            }
            "source" -> {
                value.requireKeys(setOf("kind", "packageName", "sourceRef"))
                NotificationFactPrivacyScope.Source(value.strictString("packageName"), value.strictString("sourceRef"))
            }
            "fact" -> {
                value.requireKeys(setOf("kind", "factId", "expectedRevision"))
                NotificationFactPrivacyScope.Fact(value.strictString("factId"), value.strictLong("expectedRevision"))
            }
            else -> throw IllegalArgumentException("unknown_archive_privacy_scope")
        }

        private fun encodeIntent(intent: NotificationFactPrivacyIntent): String = JSONObject()
            .put("schemaVersion", 1).put("storeEpoch", intent.storeEpoch).put("mutationId", intent.mutationId)
            .put("scope", scopeJson(intent.scope))
            .put("affectedPackageName", intent.affectedPackageName ?: JSONObject.NULL)
            .put("affectedSourceRef", intent.affectedSourceRef ?: JSONObject.NULL)
            .put("allGeneration", intent.allGeneration)
            .put("packageGeneration", intent.packageGeneration ?: JSONObject.NULL)
            .put("removedFacts", intent.removedFacts).toString()

        private fun decodeIntent(raw: String): NotificationFactPrivacyIntent {
            require(raw.archiveUtf8Size() <= 4_096)
            val value = JSONObject(raw)
            value.requireKeys(setOf("schemaVersion", "storeEpoch", "mutationId", "scope", "affectedPackageName",
                "affectedSourceRef", "allGeneration", "packageGeneration", "removedFacts"))
            require(value.strictLong("schemaVersion") == 1L)
            return NotificationFactPrivacyIntent(
                value.strictString("storeEpoch"), value.strictString("mutationId"), decodeScope(value.getJSONObject("scope")),
                value.optionalString("affectedPackageName"), value.optionalString("affectedSourceRef"),
                value.strictLong("allGeneration"),
                if (value.isNull("packageGeneration")) null else value.strictLong("packageGeneration"),
                value.strictLong("removedFacts").toIntExact(),
            )
        }

        private fun JSONObject.requireKeys(expected: Set<String>) { require(keys().asSequence().toSet() == expected) }
        private fun JSONObject.strictString(key: String): String = opt(key) as? String
            ?: throw IllegalArgumentException("invalid_archive_intent_string")
        private fun JSONObject.strictLong(key: String): Long = when (val value = opt(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("invalid_archive_intent_integer")
        }
        private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else strictString(key)
        private fun isRegular(file: File) = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(",")
        private fun Long.requireInsert() { check(this >= 0) }
        private fun Long.toIntExact(): Int = Math.toIntExact(this)
        private class CapacityReached : RuntimeException()
    }
}
