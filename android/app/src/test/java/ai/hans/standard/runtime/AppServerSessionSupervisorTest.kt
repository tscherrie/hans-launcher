package ai.hans.standard.runtime

import android.os.IBinder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppServerSessionSupervisorTest {
    @Test
    fun blockedInitializeWriteDoesNotPreventStop() {
        Harness().use { harness ->
            val process = harness.processes.single()
            process.blockWrites.set(true)
            harness.supervisor.start(1, harness.callback)
            harness.callback.awaitState(1, AppServerSessionContract.STATE_STARTING)
            assertTrue(process.writeEntered.await(1, TimeUnit.SECONDS))

            harness.supervisor.stop(2, 1)

            assertTrue("Stop must destroy a process without waiting for its stdin", process.destroyed.await(1, TimeUnit.SECONDS))
            harness.callback.awaitState(1, AppServerSessionContract.STATE_STOPPED)
            assertFalse(harness.supervisor.hasReservedProcess())
        }
    }

    @Test
    fun blockedClientWriteDoesNotPreventStdoutDeliveryOrStop() {
        Harness().use { harness ->
            val process = harness.startReady()
            process.blockWrites.set(true)
            harness.sendClient(1, 1)
            assertTrue(process.writeEntered.await(1, TimeUnit.SECONDS))

            process.emit(JSONObject().put("method", "test/progress").put("params", JSONObject()))
            assertNotNull("Stdout delivery must remain live while stdin is blocked", harness.callback.frames.poll(1, TimeUnit.SECONDS))
            harness.supervisor.stop(2, 1)
            assertTrue(process.destroyed.await(1, TimeUnit.SECONDS))
            harness.callback.awaitState(1, AppServerSessionContract.STATE_STOPPED)
        }
    }

    @Test
    fun blockedClientWriteDoesNotPreventRestartOrDeliverQueuedOldGenerationFrames() {
        Harness(processCount = 2).use { harness ->
            val oldProcess = harness.startReady()
            oldProcess.blockWrites.set(true)
            harness.sendClient(1, 1)
            assertTrue(oldProcess.writeEntered.await(1, TimeUnit.SECONDS))
            harness.sendClient(1, 2)

            harness.supervisor.restart(2, 1)

            assertTrue("Restart must destroy the blocked generation", oldProcess.destroyed.await(1, TimeUnit.SECONDS))
            harness.callback.awaitState(2, AppServerSessionContract.STATE_READY)
            oldProcess.releaseWrite.countDown()
            assertTrue(oldProcess.outputClosed.await(1, TimeUnit.SECONDS))
            harness.sendClient(2, 1)
            val current = harness.processes[1]
            assertEquals("client-1", current.clientFrames.poll(1, TimeUnit.SECONDS)?.getString("id"))
            assertTrue(oldProcess.clientFrames.isEmpty())
            assertTrue(current.clientFrames.isEmpty())
        }
    }

    @Test
    fun blockingOutputCloseHappensAfterDestroyAndCannotDelayTerminalCallback() {
        Harness().use { harness ->
            val process = harness.startReady()
            process.blockClose.set(true)

            harness.supervisor.stop(2, 1)

            assertTrue("Destroy must precede close or flush", process.destroyed.await(1, TimeUnit.SECONDS))
            harness.callback.awaitState(1, AppServerSessionContract.STATE_STOPPED)
            assertTrue(process.closeEntered.await(1, TimeUnit.SECONDS))
            assertFalse(process.closedBeforeDestroy.get())
        }
    }

    @Test
    fun blockedOldReaderClosesCannotStarveTheReplacementGeneration() {
        Harness(processCount = 2).use { harness ->
            val oldProcess = harness.startReady()
            oldProcess.blockReaderCloses.set(true)

            harness.supervisor.restart(2, 1)

            assertTrue(oldProcess.destroyed.await(1, TimeUnit.SECONDS))
            assertTrue("Both retired IO owners must be held in close", oldProcess.readerClosesEntered.await(1, TimeUnit.SECONDS))
            harness.callback.awaitState(2, AppServerSessionContract.STATE_READY)
            harness.processes[1].emit(JSONObject().put("method", "test/replacement").put("params", JSONObject()))
            assertNotNull(harness.callback.frames.poll(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun blockedStdinQueueIsBoundedAndRejectsOverflowWithoutBlockingStop() {
        Harness().use { harness ->
            val process = harness.startReady()
            process.blockWrites.set(true)
            harness.sendClient(1, 1, payloadSize = 700_000)
            assertTrue(process.writeEntered.await(1, TimeUnit.SECONDS))
            harness.sendClient(1, 2, payloadSize = 700_000)
            harness.sendClient(1, 3, payloadSize = 700_000)
            harness.sendClient(1, 4, payloadSize = 700_000)

            val rejection = harness.callback.notices.poll(1, TimeUnit.SECONDS)
            assertNotNull("Outstanding stdin bytes must be bounded even when the first write stalls", rejection)
            assertEquals(AppServerSessionContract.NOTICE_CLIENT_FRAME_REJECTED, rejection?.code)
            harness.supervisor.stop(2, 1)
            assertTrue(process.destroyed.await(1, TimeUnit.SECONDS))
            harness.callback.awaitState(1, AppServerSessionContract.STATE_STOPPED)
        }
    }

    private class Harness(processCount: Int = 1) : AutoCloseable {
        val processes = List(processCount) { FakeProcess() }
        private val pendingProcesses = ArrayDeque(processes)
        val callback = Callback()
        private val executable = File(checkNotNull(System.getProperty("java.home")), "bin/java").canonicalFile
        private val directory = File(checkNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile
        private val directories = CodexRuntimeDirectories(directory, directory, directory, directory)
        val supervisor = AppServerSessionSupervisor(
            executableProvider = { executable },
            directoriesProvider = { directories },
            bootstrapProvider = { CodexRuntimeBootstrapReceipt(1, "test", "0".repeat(64), directory.path, true) },
            environmentProvider = { emptyMap() },
            argumentsProvider = { _, _ -> emptyList() },
            clientVersion = "test",
            expectedExecutableBytes = executable.length(),
            runtimePidProvider = { 123 },
            acquireProcessLease = { true },
            releaseProcessLease = {},
            processStarter = { pendingProcesses.removeFirst().apply { codexHome = directory.path } },
        )

        fun startReady(): FakeProcess {
            supervisor.start(1, callback)
            callback.awaitState(1, AppServerSessionContract.STATE_READY)
            return processes.first()
        }

        fun sendClient(generation: Long, sequence: Long, payloadSize: Int = 20_000) {
            val bytes = JSONObject().put("id", "client-$sequence").put("method", "test/request")
                .put("params", JSONObject().put("text", "x".repeat(payloadSize)))
                .toString().toByteArray(Charsets.UTF_8)
            val chunks = bytes.asList().chunked(AppServerSessionContract.MAX_BINDER_CHUNK_BYTES)
            chunks.forEachIndexed { index, chunk ->
                supervisor.acceptClientChunk(generation, sequence, index, chunks.size, bytes.size, chunk.toByteArray())
            }
        }

        override fun close() {
            processes.forEach {
                it.releaseWrite.countDown()
                it.releaseClose.countDown()
                it.releaseReaderCloses.countDown()
            }
            supervisor.shutdown()
        }
    }

    private class Callback : IAppServerSessionCallback {
        private val states = LinkedBlockingQueue<Pair<Long, Int>>()
        val frames = LinkedBlockingQueue<String>()
        val notices = LinkedBlockingQueue<Notice>()
        private val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "isBinderAlive", "pingBinder", "unlinkToDeath" -> true
                else -> null
            }
        } as IBinder

        override fun asBinder(): IBinder = binder

        override fun onSessionState(operationId: Long, generation: Long, eventSequence: Long, state: Int, runtimePid: Int, detail: String) {
            states.add(generation to state)
        }

        override fun onFrameChunk(generation: Long, eventSequence: Long, chunkIndex: Int, chunkCount: Int, totalBytes: Int, payload: ByteArray) {
            frames.add(payload.toString(Charsets.UTF_8))
        }

        override fun onTransportNotice(generation: Long, eventSequence: Long, code: Int, relatedSequence: Long, detail: String) {
            notices.add(Notice(code, relatedSequence))
        }

        fun awaitState(generation: Long, state: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            val seen = mutableListOf<Pair<Long, Int>>()
            while (true) {
                val remaining = deadline - System.nanoTime()
                val next = if (remaining > 0) states.poll(remaining, TimeUnit.NANOSECONDS) else null
                assertNotNull("Missing state $state for generation $generation; saw $seen", next)
                seen += next!!
                if (next == generation to state) return
            }
        }
    }

    private data class Notice(val code: Int, val sequence: Long)

    private class FakeProcess : Process() {
        var codexHome: String = ""
        val blockWrites = AtomicBoolean(false)
        val blockClose = AtomicBoolean(false)
        val blockReaderCloses = AtomicBoolean(false)
        val readerClosesEntered = CountDownLatch(2)
        val releaseReaderCloses = CountDownLatch(1)
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val outputClosed = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val closedBeforeDestroy = AtomicBoolean(false)
        val clientFrames = LinkedBlockingQueue<JSONObject>()
        private val alive = AtomicBoolean(true)
        private val stdout = QueueInputStream(blockReaderCloses, readerClosesEntered, releaseReaderCloses)
        private val stderr = QueueInputStream(blockReaderCloses, readerClosesEntered, releaseReaderCloses)
        private val received = ByteArrayOutputStream()
        private val stdin = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (blockWrites.get()) {
                    writeEntered.countDown()
                    awaitUninterruptibly(releaseWrite)
                }
                if (!alive.get()) throw IOException("Process terminated")
                for (index in offset until offset + length) {
                    val byte = bytes[index]
                    if (byte == '\n'.code.toByte()) {
                        val request = JSONObject(received.toString(Charsets.UTF_8.name()))
                        received.reset()
                        onRequest(request)
                    } else {
                        received.write(byte.toInt())
                    }
                }
            }

            override fun close() {
                if (alive.get()) closedBeforeDestroy.set(true)
                closeEntered.countDown()
                if (blockClose.get()) awaitUninterruptibly(releaseClose)
                outputClosed.countDown()
            }
        }

        fun emit(frame: JSONObject) = stdout.append((frame.toString() + "\n").toByteArray(Charsets.UTF_8))

        private fun onRequest(request: JSONObject) {
            when (request.optString("method")) {
                "initialize" -> emit(JSONObject().put("id", request.get("id")).put("result", JSONObject()
                    .put("userAgent", "test").put("codexHome", codexHome)
                    .put("platformFamily", "unix").put("platformOs", "linux")))
                "initialized" -> Unit
                "config/read" -> {
                    val config = JSONObject()
                    CodexAssistantProfile.strictArguments().drop(1).chunked(2).forEach { (_, setting) ->
                        val key = setting.substringBefore('=').split('.')
                        val value = JSONObject("{\"value\":${setting.substringAfter('=')}}").get("value")
                        var parent = config
                        key.dropLast(1).forEach { part ->
                            if (!parent.has(part)) parent.put(part, JSONObject())
                            parent = parent.getJSONObject(part)
                        }
                        parent.put(key.last(), value)
                    }
                    emit(JSONObject().put("id", request.get("id")).put("result", JSONObject().put("config", config)))
                }
                else -> clientFrames.add(request)
            }
        }

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun isAlive(): Boolean = alive.get()
        override fun waitFor(): Int { destroyed.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed.await(timeout, unit)
        override fun exitValue(): Int = if (alive.get()) throw IllegalThreadStateException() else 0
        override fun destroy() {
            alive.set(false)
            destroyed.countDown()
            stdout.end()
            stderr.end()
        }
    }

    private class QueueInputStream(
        private val blockClose: AtomicBoolean,
        private val closeEntered: CountDownLatch,
        private val releaseClose: CountDownLatch,
    ) : InputStream() {
        private val bytes = LinkedBlockingQueue<Int>()
        fun append(value: ByteArray) { value.forEach { bytes.add(it.toInt() and 0xff) } }
        override fun read(): Int = bytes.take()
        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val first = read()
            if (first < 0) return -1
            destination[offset] = first.toByte()
            var count = 1
            while (count < length) {
                val next = bytes.poll() ?: break
                if (next < 0) { bytes.offer(-1); break }
                destination[offset + count++] = next.toByte()
            }
            return count
        }
        fun end() { bytes.offer(-1) }
        override fun close() {
            closeEntered.countDown()
            if (blockClose.get()) awaitUninterruptibly(releaseClose)
            end()
        }
    }

    companion object {
        private fun awaitUninterruptibly(latch: CountDownLatch) {
            while (true) {
                try { latch.await(); return } catch (_: InterruptedException) { /* Model a native blocked write. */ }
            }
        }
    }
}
