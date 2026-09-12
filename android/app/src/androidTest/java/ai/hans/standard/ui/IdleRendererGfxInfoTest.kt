package ai.hans.standard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Parser contracts run without a clock or device mutation, alongside the ordinary Android suite. */
class IdleRendererGfxInfoTest {
    @Test
    fun usesWindowCountersRatherThanProcessAggregate() {
        val info = parse(raw())
        assertEquals(1234, info.pid)
        assertEquals(100L, info.statsSinceNanos)
        assertEquals(4L, info.totalFramesRendered)
        assertEquals(2, info.profileDataRows)
        assertEquals(2100L, info.lastFrameCompletedNanos)
    }

    @Test
    fun rejectsMissingEmptyOrUnobservedEvidence() {
        for (invalid in listOf(
            "",
            raw().replace("Window: $WINDOW", "Window: another.app/Activity"),
            raw().replace("[ai.hans.standard]", "[another.app]"),
            raw().replace("pid 1234", "pid 4321"),
            raw().replace("Total frames rendered: 4", "Total frames rendered: 0"),
            raw().replace("Stats since: 100ns", "Stats since: 0ns"),
            raw().replace("0,1000,1100,\n0,2000,2100,\n", ""),
            raw().replace("---PROFILEDATA---", ""),
        )) {
            assertThrows(IllegalStateException::class.java) { parse(invalid) }
        }
    }

    @Test
    fun rejectsMultipleWindowsOrProcessHeaders() {
        assertThrows(IllegalStateException::class.java) { parse(raw() + "\nWindow: $WINDOW") }
        assertThrows(IllegalStateException::class.java) {
            parse(raw() + "\n** Graphics info for pid 1234 [ai.hans.standard] **\n")
        }
    }

    @Test
    fun rejectsIncompleteAndUnknownProfileData() {
        for (invalid in listOf(
            raw().replace("FrameCompleted", "CompletionUnknown"),
            raw().replace("Flags,IntendedVsync,FrameCompleted,", "Flags,Flags,FrameCompleted,"),
            raw().replace("0,1000,1100,", "0,1000,"),
            raw().replace("0,1000,1100,", "0,1000,unknown,"),
            raw().replace("0,1000,1100,", "0,1000,0,"),
            raw().replace("0,1000,1100,", "0,0,1100,"),
            raw().replace("0,1000,1100,", "0,1000,9223372036854775807,"),
            raw().replace("Total frames rendered: 4", "Total frames rendered: 1"),
        )) {
            assertThrows(IllegalStateException::class.java) { parse(invalid) }
        }
    }

    @Test
    fun continuationRejectsResetsNewWindowsPidsAndRegressions() {
        val initial = parse(raw())
        for (invalid in listOf(
            initial.copy(pid = 4321),
            initial.copy(window = "$WINDOW-renamed"),
            initial.copy(statsSinceNanos = 101),
            initial.copy(totalFramesRendered = 3),
            initial.copy(lastFrameCompletedNanos = 2099),
        )) {
            assertThrows(IllegalStateException::class.java) { invalid.requireContinuation(initial) }
        }
        initial.requireContinuation(initial)
        initial.copy(totalFramesRendered = 7, lastFrameCompletedNanos = 4000)
            .requireContinuation(initial)
    }

    private fun parse(value: String) = IdleRendererGfxInfo.parse(value, 1234, "ai.hans.standard")

    @Test
    fun liveRecognizesExactlyOneReusedTailWithoutCountingItCompleted() {
        val info = parseLive(ring())
        assertEquals(120, info.profileDataRows)
        assertEquals(119, info.completedProfileDataRows)
        assertEquals(1, info.pendingReusedProfileDataRows)
        assertEquals(119L, info.totalFramesRendered)
        assertEquals(119_010L, info.lastFrameCompletedNanos)
    }

    @Test
    fun quietDefaultRejectsTheSamePendingTail() {
        assertThrows(IllegalStateException::class.java) { parse(ring()) }
    }

    @Test
    fun rejectsShortRingMiddleAndMultiplePendingRows() {
        for (invalid in listOf(
            ring(rows = listOf(PENDING)),
            ring(rows = completeRows().drop(1) + PENDING),
            ring(rows = listOf(PENDING) + completeRows()),
            ring(rows = completeRows().dropLast(1) + listOf(PENDING, PENDING)),
        )) assertThrows(IllegalStateException::class.java) { parseLive(invalid) }
    }

    @Test
    fun rejectsMissingProducerFieldsAndMalformedRows() {
        for (column in listOf("GpuCompleted", "SyncStart", "IssueDrawCommandsStart",
            "SwapBuffers", "SwapBuffersCompleted")) {
            assertThrows(IllegalStateException::class.java) {
                parseLive(ring().replace(column, "Unknown$column"))
            }
        }
        assertThrows(IllegalStateException::class.java) {
            parseLive(ring().replace(PENDING, "0,120000,1100,"))
        }
    }

    @Test
    fun rejectsContradictoryStaleCompletionAndCurrentOrdering() {
        val badRows = listOf(
            "1,120000,1100,1100,120001,120002,120003,120004,",
            "0,120000,1100,1101,120001,120002,120003,120004,",
            "0,119000,1100,1100,120001,120002,120003,120004,",
            "0,120000,1100,1100,119999,120002,120003,120004,",
            "0,120000,1100,1100,120002,120001,120003,120004,",
            "0,120000,1100,1100,120001,120003,120002,120004,",
            "0,120000,1100,1100,120001,120002,120004,120003,",
        )
        for (row in badRows) assertThrows(IllegalStateException::class.java) {
            parseLive(ring().replace(PENDING, row))
        }
    }

    @Test
    fun rejectsZeroNegativeSentinelAndUnknownPendingValues() {
        for (value in listOf("0", "-1", Long.MAX_VALUE.toString(), "unknown")) {
            for (position in 1..7) {
                val values = PENDING.removeSuffix(",").split(',').toMutableList()
                values[position] = value
                assertThrows(IllegalStateException::class.java) {
                    parseLive(ring().replace(PENDING, values.joinToString(",") + ","))
                }
            }
        }
    }

    @Test
    fun completedColumnsAndTotalsRemainStrictWithoutInflatingPendingCount() {
        val complete = parse(ring(rows = completeRows()))
        assertEquals(119, complete.completedProfileDataRows)
        assertEquals(0, complete.pendingReusedProfileDataRows)
        assertThrows(IllegalStateException::class.java) {
            parseLive(ring().replace("Total frames rendered: 119", "Total frames rendered: 118"))
        }
        val extra = raw().replace("FrameCompleted,", "FrameCompleted,Extra,")
            .replace("0,1000,1100,", "0,1000,1100,99,")
            .replace("0,2000,2100,", "0,2000,2100,99,")
        assertEquals(2, parse(extra).completedProfileDataRows)
    }

    private fun parseLive(value: String) = IdleRendererGfxInfo.parse(
        value, 1234, "ai.hans.standard", allowPendingReusedTail = true,
    )

    @Test
    fun recognizedSkippedTailHasIndependentCountAndStrictDefault() {
        val value = ring().replace(PENDING, SKIPPED)
        assertThrows(IllegalStateException::class.java) { parse(value) }
        assertThrows(IllegalStateException::class.java) { parseLive(value) }
        val info = parseSkipped(value)
        assertEquals(119, info.completedProfileDataRows)
        assertEquals(0, info.pendingReusedProfileDataRows)
        assertEquals(1, info.skippedReusedProfileDataRows)
        assertEquals(120, info.profileDataRows)
        assertEquals(119_010L, info.lastFrameCompletedNanos)
        assertThrows(IllegalStateException::class.java) { parseSkipped(ring()) }
    }

    @Test
    fun skippedMustBeExactlyLastAndUnmixed() {
        for (rows in listOf(listOf(SKIPPED), listOf(SKIPPED) + completeRows(),
            completeRows().dropLast(1) + listOf(SKIPPED, PENDING))) {
            assertThrows(IllegalStateException::class.java) {
                IdleRendererGfxInfo.parse(ring(rows), 1234, "ai.hans.standard", true, true)
            }
        }
        for (flag in listOf("0", "9", "10", "12", "-8")) {
            assertThrows(IllegalStateException::class.java) {
                parseSkipped(ring().replace(PENDING, SKIPPED.replaceFirst("8,", "$flag,")))
            }
        }
        for (flag in listOf("8", "9", "10", "12")) {
            assertThrows(IllegalStateException::class.java) {
                parseSkipped(raw().replace("0,1000,1100,", "$flag,1000,1100,"))
            }
        }
    }

    @Test
    fun skippedRejectsBadOldChainCurrentSyncAndMissingFields() {
        for (row in listOf(
            "8,120000,1100,1101,120001,1000,1001,1002,",
            "8,120000,1100,1100,119999,1000,1001,1002,",
            "8,120000,1100,1100,120001,0,1001,1002,",
            "8,120000,1100,1100,120001,1002,1001,1003,",
            "8,120000,1100,1100,120001,1000,1002,1001,",
            "8,120000,1100,1100,120001,1000,1001,1101,",
            "8,120000,1100,1100,9223372036854775807,1000,1001,1002,",
        )) assertThrows(IllegalStateException::class.java) {
            parseSkipped(ring().replace(PENDING, row))
        }
        for (column in listOf("GpuCompleted", "SyncStart", "IssueDrawCommandsStart",
            "SwapBuffers", "SwapBuffersCompleted")) {
            assertThrows(IllegalStateException::class.java) {
                parseSkipped(ring().replace(PENDING, SKIPPED).replace(column, "Unknown$column"))
            }
        }
    }

    @Test
    fun rawProfileHashPreservesWhitespaceAndEveryColumn() {
        val base = ring().replace(PENDING, SKIPPED)
        val hash = parseSkipped(base).profileDataSha256
        assertEquals(64, hash.length)
        assertEquals(hash, parseSkipped(base.replace("Total frames rendered: 999", "Total frames rendered: 998")).profileDataSha256)
        assertNotEquals(hash, parseSkipped(base.replace("---PROFILEDATA---\nFlags", "---PROFILEDATA---\n\nFlags")).profileDataSha256)
        assertNotEquals(hash, parseSkipped(base.replace("120001,1000", "120002,1000")).profileDataSha256)
        val extended = base.replace("Flags,IntendedVsync", "FrameTimelineVsyncId,UiTimestamp,Flags,IntendedVsync")
            .lineSequence().joinToString("\n") { line ->
                if (line.firstOrNull()?.isDigit() == true) "17,42,$line" else line
            }
        val original = parseSkipped(extended).profileDataSha256
        assertNotEquals(original, parseSkipped(extended.replace("17,42,8,", "18,42,8,")).profileDataSha256)
        assertNotEquals(original, parseSkipped(extended.replace("17,42,8,", "17,43,8,")).profileDataSha256)
    }

    private fun parseSkipped(value: String) = IdleRendererGfxInfo.parse(
        value, 1234, "ai.hans.standard", allowSkippedReusedTail = true,
    )

    private fun completeRows() = (1..119).map { index ->
        val t = index * 1000L
        "0,$t,${t + 10},${t + 10},${t + 1},${t + 2},${t + 3},${t + 4},"
    }

    private fun ring(rows: List<String> = completeRows() + PENDING): String =
        raw().replace("Total frames rendered: 4", "Total frames rendered: 119")
            .replace("Flags,IntendedVsync,FrameCompleted,",
                "Flags,IntendedVsync,FrameCompleted,GpuCompleted,SyncStart,IssueDrawCommandsStart,SwapBuffers,SwapBuffersCompleted,")
            .replace("0,1000,1100,\n0,2000,2100,", rows.joinToString("\n"))

    private fun raw(): String = """
        ** Graphics info for pid 1234 [ai.hans.standard] **
        Stats since: 90ns
        Total frames rendered: 999
        Window: $WINDOW
        Stats since: 100ns
        Total frames rendered: 4
        ---PROFILEDATA---
        Flags,IntendedVsync,FrameCompleted,
        0,1000,1100,
        0,2000,2100,
        ---PROFILEDATA---
    """.trimIndent() + "\n"

    companion object {
        private const val PENDING = "0,120000,1100,1100,120001,120002,120003,120004,"
        private const val SKIPPED = "8,120000,1100,1100,120001,1000,1001,1002,"
        private const val WINDOW = "ai.hans.standard/ai.hans.standard.ui.acceptance.ChatScreenIdleProbeActivity"
    }
}
