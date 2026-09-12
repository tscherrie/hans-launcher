package ai.hans.standard.ui

import java.security.MessageDigest

/**
 * Reads actual, non-reset `dumpsys gfxinfo <pid> framestats` output. Window counters, not the
 * process-wide aggregate above them, are authoritative. Unknown/incomplete data is an error;
 * an absent renderer is never silently converted into zero frames.
 */
internal data class IdleRendererGfxInfo(
    val pid: Int,
    val window: String,
    val statsSinceNanos: Long,
    val totalFramesRendered: Long,
    val profileDataRows: Int,
    val completedProfileDataRows: Int,
    val pendingReusedProfileDataRows: Int,
    val skippedReusedProfileDataRows: Int,
    val profileDataSha256: String,
    val lastFrameCompletedNanos: Long,
) {
    fun requireContinuation(previous: IdleRendererGfxInfo) {
        check(pid == previous.pid) { "Renderer PID changed" }
        check(window == previous.window) { "Renderer window changed" }
        check(statsSinceNanos == previous.statsSinceNanos) { "Renderer counters were reset" }
        check(totalFramesRendered >= previous.totalFramesRendered) { "Renderer frame count regressed" }
        check(lastFrameCompletedNanos >= previous.lastFrameCompletedNanos) {
            "Renderer frame history regressed"
        }
    }

    companion object {
        const val ACTIVITY = "ai.hans.standard.ui.acceptance.ChatScreenIdleProbeActivity"
        const val MAX_BYTES = 1_048_576
        private val header = Regex("(?m)^\\s*\\*\\* Graphics info for pid ([0-9]+) \\[([^\\r\\n\\]]+)\\] \\*\\*\\s*$")
        private val windowLine = Regex("(?m)^Window: ([^\\r\\n]+)$")
        private val statsSince = Regex("(?m)^Stats since: ([0-9]+)ns\\s*$")
        private val frames = Regex("(?m)^Total frames rendered: ([0-9]+)\\s*$")

        fun parse(raw: String, expectedPid: Int, expectedPackage: String,
                  allowPendingReusedTail: Boolean = false,
                  allowSkippedReusedTail: Boolean = false): IdleRendererGfxInfo {
            check(raw.toByteArray(Charsets.UTF_8).size in 1..MAX_BYTES) { "Unbounded/empty gfxinfo" }
            val processHeaders = header.findAll(raw).toList()
            check(processHeaders.size == 1) { "Expected one process gfxinfo header" }
            val process = processHeaders.single()
            val pid = process.groupValues[1].toInt()
            check(pid == expectedPid && process.groupValues[2] == expectedPackage) {
                "Unexpected gfxinfo process identity"
            }
            val windows = windowLine.findAll(raw).toList()
            check(windows.size == 1) { "Expected exactly one renderer window" }
            val windowMatch = windows.single()
            val window = windowMatch.groupValues[1].trim()
            check(window.contains(ACTIVITY)) { "Unexpected renderer window" }
            val block = raw.substring(windowMatch.range.last + 1)
            val sinceMatches = statsSince.findAll(block).toList()
            val frameMatches = frames.findAll(block).toList()
            check(sinceMatches.size == 1 && frameMatches.size == 1) { "Missing/ambiguous window counters" }
            val since = sinceMatches.single().groupValues[1].toLong()
            val total = frameMatches.single().groupValues[1].toLong()
            check(since > 0 && total > 0) { "No observed window frames" }

            val profiles = block.split("---PROFILEDATA---")
            check(profiles.size == 3) { "Missing/ambiguous frame-profile block" }
            check(sinceMatches.single().range.first < profiles[0].length) { "Invalid counter position" }
            check(frameMatches.single().range.first < profiles[0].length) { "Invalid counter position" }
            val lines = profiles[1].lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            check(lines.size >= 2) { "No observed frame-profile rows" }
            val columns = csv(lines.first())
            check(columns.size == columns.distinct().size) { "Duplicate frame-profile columns" }
            val intendedIndex = columns.indexOf("IntendedVsync")
            val completedIndex = columns.indexOf("FrameCompleted")
            val flagsIndex = columns.indexOf("Flags")
            check(intendedIndex >= 0 && completedIndex >= 0 && flagsIndex >= 0) {
                "Unsupported frame-profile columns"
            }
            val rows = lines.drop(1).map { line ->
                val values = csv(line)
                check(values.size == columns.size) { "Incomplete frame-profile row" }
                val numbers = values.map { value ->
                    check(value.matches(Regex("-?[0-9]+"))) { "Non-numeric frame-profile value" }
                    value.toLong()
                }
                numbers
            }
            val completed = mutableListOf<Long>()
            var pending = 0
            var skipped = 0
            rows.forEachIndexed { index, numbers ->
                val intended = numbers[intendedIndex]
                val finished = numbers[completedIndex]
                check(numbers[flagsIndex] >= 0 && intended in 1 until Long.MAX_VALUE) {
                    "Invalid frame-profile identity"
                }
                if (numbers[flagsIndex] and 8L == 0L && finished >= intended && finished < Long.MAX_VALUE) {
                    completed.add(finished)
                } else {
                    // AOSP14 HWUI reuses the 120-slot ring without clearing completion fields.
                    // dumpFrames includes a started slot before its SurfaceStats completion callback.
                    // Recognize only that exact trailing pattern, never count it as completed.
                    val isPending = allowPendingReusedTail && numbers[flagsIndex] == 0L
                    val isSkipped = allowSkippedReusedTail && numbers[flagsIndex] == 8L
                    check((isPending || isSkipped) && rows.size == 120 && index == 119 &&
                        completed.size == 119 &&
                        finished in 1 until intended &&
                        rows.take(index).all { it[intendedIndex] < intended }) {
                        "Incomplete or invalid frame-profile evidence"
                    }
                    fun field(name: String): Long {
                        val position = columns.indexOf(name)
                        check(position >= 0) { "Missing reused-tail column: $name" }
                        return numbers[position]
                    }
                    check(field("GpuCompleted") == finished) { "Contradictory reused completion" }
                    if (isSkipped) {
                        val old = listOf(field("IssueDrawCommandsStart"), field("SwapBuffers"),
                            field("SwapBuffersCompleted"), finished)
                        check(old.all { it in 1 until intended } &&
                            old.zipWithNext().all { (left, right) -> left <= right } &&
                            field("SyncStart") in intended until Long.MAX_VALUE) {
                            "Invalid skipped-tail old/current timestamps"
                        }
                        skipped++
                    } else {
                        val current = listOf(intended, field("SyncStart"),
                            field("IssueDrawCommandsStart"), field("SwapBuffers"),
                            field("SwapBuffersCompleted"))
                        check(current.all { it in 1 until Long.MAX_VALUE } &&
                            current.zipWithNext().all { (left, right) -> left <= right }) {
                            "Invalid reused-tail current timestamps"
                        }
                        pending++
                    }
                }
            }
            check(completed.isNotEmpty() && completed.size.toLong() <= total) {
                "Completed frame rows exceed accumulated window frames or are absent"
            }
            return IdleRendererGfxInfo(pid, window, since, total, rows.size,
                completed.size, pending, skipped,
                MessageDigest.getInstance("SHA-256").digest(profiles[1].toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }, completed.max())
        }

        private fun csv(line: String): List<String> = line.removeSuffix(",").split(',').map(String::trim)
    }
}
