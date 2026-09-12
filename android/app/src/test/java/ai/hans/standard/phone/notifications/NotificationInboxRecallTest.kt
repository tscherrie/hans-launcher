package ai.hans.standard.phone.notifications

import ai.hans.standard.codex.DynamicToolCallParams
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationInboxRecallTest {
    @Test
    fun interactiveRecentRecallRedactsSecretsOmitsEveryUrlAndNeverExposesActionsOrKeys() {
        val source = FakeSource(
            listOf(
                event(
                    sequence = 1,
                    key = "private-android-key",
                    title = "Max",
                    text = "Konzert am Freitag auf https://events.example/ticket?id=public",
                ),
                event(
                    sequence = 2,
                    key = "private-android-key",
                    kind = NotificationEventKind.UPDATED,
                    title = "Max - verification code",
                    text = "Code 123456. Konzert am Freitag: events.example/ticket?token=secret",
                    actions = listOf(replyAction("Open https://private.example/reset?token=action")),
                ),
            ),
        )

        val result = execute(
            source,
            "recent",
            JSONObject().put("sinceEpochMillis", 0).toString(),
        )
        assertTrue(result.success)
        val output = JSONObject(result.contentText)
        assertEquals("recent", output.getString("mode"))
        assertTrue(output.getBoolean("scanComplete"))
        assertEquals(1, output.getJSONArray("events").length())
        val serialized = output.toString()
        assertFalse(serialized.contains("123456"))
        assertFalse(serialized.contains("private-android-key"))
        assertFalse(serialized.contains("action-result-key"))
        assertFalse(serialized.contains("events.example"))
        assertFalse(serialized.contains("private.example"))
        assertTrue(serialized.contains("LINK_OMITTED"))
        assertTrue(output.getJSONArray("events").getJSONObject(0).getBoolean("redactionApplied"))
    }

    @Test
    fun secretOnlyLatestUpdateSuppressesAnOlderHarmlessVersionAcrossPageBoundary() {
        val events = buildList {
            add(event(1, key = "target", title = "Max", text = "Treffen morgen um neun"))
            for (sequence in 2L..204L) {
                add(
                    event(
                        sequence,
                        key = "filler-$sequence",
                        title = "Quelle $sequence",
                        text = "Normale Information Nummer $sequence fuer den Verlauf",
                    ),
                )
            }
            add(
                event(
                    205,
                    key = "target",
                    kind = NotificationEventKind.UPDATED,
                    title = "Verification code",
                    text = "123456",
                ),
            )
        }
        val source = FakeSource(events)

        val result = NotificationInboxRecallRetriever(source, source).retrieve(
            NotificationInboxRecallQuery(
                mode = NotificationInboxRecallMode.RELEVANT,
                sinceEpochMillis = 0,
                terms = listOf("Treffen"),
            ),
        )

        assertTrue(result.scanComplete)
        assertTrue(result.events.isEmpty())
        assertTrue(source.pageCalls >= 2)
    }

    @Test
    fun customSchemesAndSchemeLessIpAddressesAreOmittedFromInteractiveRecall() {
        val source = FakeSource(
            listOf(
                event(
                    1,
                    title = "Mehrere Verweise zum Termin",
                    text = "mailto:alice@example.com tel:+491234567 market://details?id=x " +
                        "content://contacts/1 intent://scan/#Intent;scheme=zxing;end " +
                        "x:secret x://host/path C:\\private\\secret " +
                        "192.168.1.1/private [2001:db8::1]/secret 2001:db8::2/path",
                ),
            ),
        )

        val result = execute(
            source,
            "recent",
            JSONObject().put("sinceEpochMillis", 0).toString(),
        )

        assertTrue(result.success)
        val serialized = result.contentText
        listOf(
            "alice@example.com",
            "+491234567",
            "market://",
            "content://",
            "intent://",
            "x:secret",
            "x://host/path",
            "C:\\private\\secret",
            "192.168.1.1",
            "2001:db8::1",
            "2001:db8::2",
        ).forEach { forbidden -> assertFalse(forbidden, serialized.contains(forbidden)) }
        assertTrue(serialized.contains("LINK_OMITTED"))
    }

    @Test
    fun relevantRecallUsesNormalizedTermsAndExactAppAndTimeFilters() {
        val source = FakeSource(
            listOf(
                event(1, observedAt = 99, title = "Alt", text = "Konzert gestern"),
                event(
                    2,
                    observedAt = 150,
                    packageName = "com.example.chat",
                    title = "Ｍａｘ",
                    text = "ＫＯＮＺＥＲＴ am Freitag",
                ),
                event(
                    3,
                    observedAt = 160,
                    packageName = "com.other.chat",
                    title = "Max",
                    text = "Konzert am Samstag",
                ),
            ),
        )

        val result = NotificationInboxRecallRetriever(source, source).retrieve(
            NotificationInboxRecallQuery(
                mode = NotificationInboxRecallMode.RELEVANT,
                sinceEpochMillis = 100,
                untilEpochMillis = 155,
                sourcePackage = "com.example.chat",
                terms = listOf("konzert", "nichtvorhanden"),
            ),
        )

        assertEquals(1, result.events.size)
        assertEquals("Max", result.events.single().title)
        assertEquals(listOf("konzert"), result.events.single().matchedTerms)
    }

    @Test
    fun malformedCursorFailsWithoutReturningAnyPartialNotificationContent() {
        val source = object : EmptyManagementSource() {
            var calls = 0
            override fun queryPage(afterSequenceExclusive: Long, limit: Int): NotificationPage {
                calls += 1
                return if (calls == 1) {
                    NotificationPage(
                        events = listOf(event(1, text = "private-before-failure")),
                        nextAfterSequenceExclusive = 1,
                        hasMore = true,
                    )
                } else {
                    NotificationPage(emptyList(), nextAfterSequenceExclusive = 1, hasMore = true)
                }
            }
        }

        val result = execute(
            source,
            "recent",
            JSONObject().put("sinceEpochMillis", 0).toString(),
        )

        assertFalse(result.success)
        assertFalse(result.contentText.contains("private-before-failure"))
        assertEquals("notification_query_failed", JSONObject(result.contentText).getString("errorCode"))
    }

    @Test
    fun advancingEmptyPagesCannotCreateAnUnboundedRecallLoop() {
        val source = object : EmptyManagementSource() {
            var calls = 0
            override fun queryPage(afterSequenceExclusive: Long, limit: Int): NotificationPage {
                calls += 1
                return NotificationPage(
                    events = emptyList(),
                    nextAfterSequenceExclusive = afterSequenceExclusive + 1,
                    hasMore = true,
                )
            }
        }

        val result = execute(
            source,
            "recent",
            JSONObject().put("sinceEpochMillis", 0).toString(),
        )

        assertFalse(result.success)
        assertTrue(source.calls < 10)
    }

    @Test
    fun privacyChangeDuringScanFailsClosed() {
        val source = object : EmptyManagementSource() {
            var statusReads = 0
            override fun privacyStatus(): NotificationPrivacyStatus {
                statusReads += 1
                return status(excluded = if (statusReads == 1) emptySet() else setOf("com.example.chat"))
            }

            override fun queryPage(afterSequenceExclusive: Long, limit: Int) = NotificationPage(
                listOf(event(1, text = "must-not-escape")),
                1,
                false,
            )
        }

        val result = execute(
            source,
            "recent",
            JSONObject().put("sinceEpochMillis", 0).toString(),
        )

        assertFalse(result.success)
        assertFalse(result.contentText.contains("must-not-escape"))
    }

    @Test
    fun eventFromExcludedOrProtectedPackageFailsClosedEvenIfSourceMisbehaves() {
        listOf("com.excluded.chat", "ai.hans.hostile").forEach { packageName ->
            val source = object : EmptyManagementSource() {
                override fun privacyStatus() = status(excluded = setOf("com.excluded.chat"))

                override fun queryPage(afterSequenceExclusive: Long, limit: Int) = NotificationPage(
                    listOf(event(1, packageName = packageName, text = "must-not-escape")),
                    1,
                    false,
                )
            }

            val result = execute(
                source,
                "recent",
                JSONObject().put("sinceEpochMillis", 0).toString(),
            )
            assertFalse(packageName, result.success)
            assertFalse(result.contentText.contains("must-not-escape"))
        }
    }

    @Test
    fun resultCountAndTrueUtf8ContentBudgetAreEnforcedNewestFirst() {
        val source = FakeSource(
            (1L..10L).map { sequence ->
                event(
                    sequence,
                    observedAt = sequence,
                    key = "key-$sequence",
                    title = "Nachricht $sequence",
                    text = "Inhalt " + "ä".repeat(500),
                )
            },
        )

        val result = NotificationInboxRecallRetriever(source, source).retrieve(
            NotificationInboxRecallQuery(
                mode = NotificationInboxRecallMode.RECENT,
                sinceEpochMillis = 0,
                limit = 3,
                maxContentUtf8Bytes = 512,
            ),
        )

        assertTrue(result.truncated)
        assertTrue(result.contentUtf8Bytes <= 512)
        assertEquals(10, result.events.first().observedAtEpochMillis)
        assertTrue(result.events.size <= 3)
    }

    @Test
    fun rawPageAndDigestCallsAreRejectedWithoutQuerying() {
        val source = FakeSource(emptyList())

        listOf("page", "digest").forEach { tool ->
            val result = execute(source, tool, "{}")
            assertFalse(result.success)
            assertEquals(0, source.pageCalls)
        }
    }

    @Test
    fun relevantToolRejectsDuplicateAndOversizedTerms() {
        val source = FakeSource(emptyList())
        val duplicates = execute(
            source,
            "relevant",
            JSONObject()
                .put("sinceEpochMillis", 0)
                .put("terms", JSONArray(listOf("Konzert", "Konzert")))
                .toString(),
        )
        val oversized = execute(
            source,
            "relevant",
            JSONObject()
                .put("sinceEpochMillis", 0)
                .put("terms", JSONArray(listOf("ä".repeat(40))))
                .toString(),
        )

        assertFalse(duplicates.success)
        assertFalse(oversized.success)
        assertEquals(0, source.pageCalls)
    }

    private fun execute(
        source: NotificationInboxQuerySource,
        tool: String,
        arguments: String,
    ): ai.hans.standard.codex.DynamicToolExecutionResult {
        val executor = NotificationInboxDynamicToolExecutor(source, Executor { it.run() })
        var result: ai.hans.standard.codex.DynamicToolExecutionResult? = null
        executor.execute(
            DynamicToolCallParams(
                threadId = "thread",
                turnId = "turn",
                callId = "call",
                namespace = NotificationInboxDynamicToolCatalog.NAMESPACE,
                tool = tool,
                argumentsJson = arguments,
            ),
        ) { result = it }
        return requireNotNull(result)
    }

    private fun event(
        sequence: Long,
        observedAt: Long = sequence,
        packageName: String = "com.example.chat",
        key: String = "key-$sequence",
        kind: NotificationEventKind = NotificationEventKind.POSTED,
        title: String = "Max",
        text: String = "Nachricht zum Konzert",
        actions: List<NotificationActionMetadata> = emptyList(),
    ) = NotificationInboxEvent(
        sequence = sequence,
        kind = kind,
        observedAtEpochMillis = observedAt,
        removalReason = null,
        snapshot = NotificationSnapshot(
            packageName = packageName,
            androidKey = key,
            postTimeEpochMillis = observedAt,
            notificationWhenEpochMillis = observedAt,
            title = title,
            text = text,
            subtext = "Persoenlich",
            category = "message",
            channelId = "messages",
            ongoing = false,
            clearable = true,
            actions = actions,
        ),
    )

    private fun replyAction(title: String) = NotificationActionMetadata(
        index = 0,
        title = title,
        semanticAction = 0,
        isContextual = false,
        allowGeneratedReplies = true,
        authenticationRequired = false,
        hasActionIntent = true,
        remoteInputs = listOf(
            NotificationRemoteInputMetadata(
                resultKey = "action-result-key",
                label = "Reply with secret",
                choices = listOf("private-choice"),
                allowFreeFormInput = true,
                allowedDataTypes = emptyList(),
                editChoicesBeforeSending = 0,
            ),
        ),
    )

    private open class EmptyManagementSource :
        NotificationInboxQuerySource,
        NotificationInboxManagementSource {
        override fun queryPage(afterSequenceExclusive: Long, limit: Int) =
            NotificationPage(emptyList(), afterSequenceExclusive, false)

        override fun queryDigest(
            afterSequenceExclusive: Long,
            maxEvents: Int,
            maxUtf8Bytes: Int,
        ) = error("raw digest must never be called")

        override fun privacyStatus(): NotificationPrivacyStatus = status()

        override fun excludePackage(packageName: String): NotificationPrivacyMutation =
            error("management mutation must not run")
        override fun includePackage(packageName: String): NotificationPrivacyMutation =
            error("management mutation must not run")
        override fun setRetention(maxEvents: Int, maxAgeHours: Int): NotificationPrivacyMutation =
            error("management mutation must not run")
        override fun clearHistory(): NotificationHistoryClearResult =
            error("management mutation must not run")
        override fun exportPrivacySettings(): String =
            error("management mutation must not run")
        override fun importPrivacySettings(document: String): NotificationPrivacyMutation =
            error("management mutation must not run")
    }

    private class FakeSource(
        private val stored: List<NotificationInboxEvent>,
    ) : EmptyManagementSource() {
        var pageCalls = 0

        override fun queryPage(afterSequenceExclusive: Long, limit: Int): NotificationPage {
            pageCalls += 1
            val remaining = stored.filter { it.sequence > afterSequenceExclusive }
            val events = remaining.take(limit)
            val next = events.lastOrNull()?.sequence ?: afterSequenceExclusive
            return NotificationPage(events, next, remaining.size > events.size)
        }
    }

    private companion object {
        fun status(excluded: Set<String> = emptySet()) = NotificationPrivacyStatus(
            policyAvailable = true,
            protectedPackages = setOf("ai.hans.standard"),
            userExcludedPackages = excluded,
            retention = NotificationRetentionPolicy(),
        )
    }
}
