package ai.hans.standard.runtime.python

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PythonNativeExecutionEngineTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun readinessStreamsCapabilityAndFinalResultShareOneCorrelatedRun() {
        lateinit var engine: PythonNativeExecutionEngine
        val result = CountDownLatch(1)
        val chunks = mutableListOf<PythonStreamChunk>()
        val leaseClosed = AtomicBoolean(false)
        var final: PythonExecutionResult? = null
        val native = object : PythonNativeRuntime {
            override fun bootstrap(configJson: String): String = readyJson()
            override fun execute(requestJson: String, sink: PythonNativeEventSink): String {
                val requestId = JSONObject(requestJson).getString("requestId")
                sink.onNativeEvent(streamJson(requestId, 1, "stdout", "hello"))
                val capability = sink.onNativeCapabilityRequest(capabilityJson(requestId, 2))
                assertEquals("succeeded", JSONObject(capability).getString("status"))
                return PythonRuntimeContract.encodeResult(
                    PythonExecutionResult(requestId, PythonExecutionStatus.SUCCEEDED, "42"),
                )
            }
            override fun cancel(requestId: String) = true
            override fun shutdown() = Unit
        }
        engine = engine(native)
        val observer = object : PythonRuntimeObserver {
            override fun onState(operationId: Long, snapshot: PythonRuntimeSnapshot) = Unit
            override fun onResult(operationId: Long, resultJson: String) {
                final = PythonRuntimeContract.decodeResult(resultJson)
                result.countDown()
            }
            override fun onStream(operationId: Long, chunk: PythonStreamChunk): Boolean {
                chunks += chunk
                return true
            }
            override fun onCapabilityRequest(operationId: Long, request: PythonCapabilityRequest) {
                engine.completeCapability(
                    request.requestId,
                    request.sequence,
                    JSONObject()
                        .put("protocolVersion", 1)
                        .put("requestId", request.requestId)
                        .put("sequence", request.sequence)
                        .put("status", "succeeded")
                        .put("value", "ok")
                        .toString(),
                )
            }
        }
        val request = request(allowedCapabilities = setOf("location"))
        engine.execute(
            7,
            PythonRuntimeContract.encodeRequest(request, 1_000),
            executionLease(request) { leaseClosed.set(true) },
            observer,
        )

        assertTrue(result.await(5, TimeUnit.SECONDS))
        assertEquals(PythonExecutionStatus.SUCCEEDED, final?.status)
        assertEquals("hello", chunks.single().payload.toString(Charsets.UTF_8))
        assertTrue(leaseClosed.get())
        assertEquals(PythonRuntimePhase.READY, engine.snapshot().phase)
        engine.shutdown()
    }

    @Test
    fun invalidRequestClosesItsEnvironmentDescriptorBeforeReportingFailure() {
        val closed = AtomicBoolean(false)
        val result = CountDownLatch(1)
        val request = request(emptySet())
        val engine = engine(
            object : PythonNativeRuntime {
                override fun bootstrap(configJson: String) = readyJson()
                override fun execute(requestJson: String, sink: PythonNativeEventSink): String =
                    error("Native execution was not expected")
                override fun cancel(requestId: String) = true
                override fun shutdown() = Unit
            },
        )

        engine.execute(
            9,
            "{not-json",
            executionLease(request) { closed.set(true) },
            object : PythonRuntimeObserver {
                override fun onState(operationId: Long, snapshot: PythonRuntimeSnapshot) = Unit
                override fun onResult(operationId: Long, resultJson: String) {
                    assertEquals(
                        PythonExecutionStatus.INVALID_REQUEST,
                        PythonRuntimeContract.decodeResult(resultJson).status,
                    )
                    assertTrue(closed.get())
                    result.countDown()
                }
                override fun onStream(operationId: Long, chunk: PythonStreamChunk) = true
                override fun onCapabilityRequest(operationId: Long, request: PythonCapabilityRequest) = Unit
            },
        )

        assertTrue(result.await(5, TimeUnit.SECONDS))
        engine.shutdown()
    }

    @Test
    fun deniedCapabilityNeverReachesTheMainProcessGateway() {
        val result = CountDownLatch(1)
        var capabilityDelivered = false
        val native = object : PythonNativeRuntime {
            override fun bootstrap(configJson: String) = readyJson()
            override fun execute(requestJson: String, sink: PythonNativeEventSink): String {
                val id = JSONObject(requestJson).getString("requestId")
                val denied = sink.onNativeCapabilityRequest(capabilityJson(id, 1))
                assertEquals("capability_denied", JSONObject(denied).getString("errorCode"))
                return PythonRuntimeContract.encodeResult(
                    PythonExecutionResult(id, PythonExecutionStatus.CAPABILITY_DENIED),
                )
            }
            override fun cancel(requestId: String) = true
            override fun shutdown() = Unit
        }
        val engine = engine(native)
        val request = request(emptySet())
        engine.execute(
            1,
            PythonRuntimeContract.encodeRequest(request, 1_000),
            executionLease(request),
            object : PythonRuntimeObserver {
                override fun onState(operationId: Long, snapshot: PythonRuntimeSnapshot) = Unit
                override fun onResult(operationId: Long, resultJson: String) = result.countDown()
                override fun onStream(operationId: Long, chunk: PythonStreamChunk) = true
                override fun onCapabilityRequest(operationId: Long, request: PythonCapabilityRequest) {
                    capabilityDelivered = true
                }
            },
        )

        assertTrue(result.await(5, TimeUnit.SECONDS))
        assertFalse(capabilityDelivered)
        engine.shutdown()
    }

    @Test
    fun cancellationIsIdempotentAndReturnsWorkerToReady() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val native = object : PythonNativeRuntime {
            override fun bootstrap(configJson: String) = readyJson()
            override fun execute(requestJson: String, sink: PythonNativeEventSink): String {
                val id = JSONObject(requestJson).getString("requestId")
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return PythonRuntimeContract.encodeResult(
                    PythonExecutionResult(
                        id,
                        if (cancelled.get()) PythonExecutionStatus.CANCELLED
                        else PythonExecutionStatus.SUCCEEDED,
                    ),
                )
            }
            override fun cancel(requestId: String): Boolean {
                cancelled.set(true)
                release.countDown()
                return true
            }
            override fun shutdown() = Unit
        }
        val engine = engine(native)
        val request = request(emptySet())
        var resultStatus: PythonExecutionStatus? = null
        engine.execute(
            1,
            PythonRuntimeContract.encodeRequest(request, 1_000),
            executionLease(request),
            object : PythonRuntimeObserver {
                override fun onState(operationId: Long, snapshot: PythonRuntimeSnapshot) = Unit
                override fun onResult(operationId: Long, resultJson: String) {
                    resultStatus = PythonRuntimeContract.decodeResult(resultJson).status
                    completed.countDown()
                }
                override fun onStream(operationId: Long, chunk: PythonStreamChunk) = true
                override fun onCapabilityRequest(operationId: Long, request: PythonCapabilityRequest) = Unit
            },
        )

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(engine.cancel(request.requestId))
        assertFalse(engine.cancel("another-request"))
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(PythonExecutionStatus.CANCELLED, resultStatus)
        assertEquals(PythonRuntimePhase.READY, engine.snapshot().phase)
        engine.shutdown()
    }

    @Test
    fun cancellationClosesExecutionLeaseExactlyOnceBeforeReportingResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val leaseCloses = AtomicInteger()
        var closesObservedByResult = -1
        val native = object : PythonNativeRuntime {
            override fun bootstrap(configJson: String) = readyJson()

            override fun execute(requestJson: String, sink: PythonNativeEventSink): String {
                val id = JSONObject(requestJson).getString("requestId")
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return PythonRuntimeContract.encodeResult(
                    PythonExecutionResult(
                        requestId = id,
                        status = if (cancelled.get()) {
                            PythonExecutionStatus.CANCELLED
                        } else {
                            PythonExecutionStatus.SUCCEEDED
                        },
                    ),
                )
            }

            override fun cancel(requestId: String): Boolean {
                cancelled.set(true)
                release.countDown()
                return true
            }

            override fun shutdown() = Unit
        }
        val engine = engine(native)
        val request = request(emptySet())
        engine.execute(
            1,
            PythonRuntimeContract.encodeRequest(request, 1_000),
            executionLease(request) { leaseCloses.incrementAndGet() },
            object : PythonRuntimeObserver {
                override fun onState(operationId: Long, snapshot: PythonRuntimeSnapshot) = Unit

                override fun onResult(operationId: Long, resultJson: String) {
                    assertEquals(
                        PythonExecutionStatus.CANCELLED,
                        PythonRuntimeContract.decodeResult(resultJson).status,
                    )
                    closesObservedByResult = leaseCloses.get()
                    completed.countDown()
                }

                override fun onStream(operationId: Long, chunk: PythonStreamChunk) = true

                override fun onCapabilityRequest(
                    operationId: Long,
                    request: PythonCapabilityRequest,
                ) = Unit
            },
        )

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(engine.cancel(request.requestId))
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(1, closesObservedByResult)
        assertEquals(1, leaseCloses.get())
        assertEquals(PythonRuntimePhase.READY, engine.snapshot().phase)
        engine.shutdown()
    }

    @Test
    fun shutdownFinalizesOnTheSameSerialWorkerThatBootstrappedPython() {
        val ready = CountDownLatch(1)
        val shutDown = CountDownLatch(1)
        var bootstrapThread: Thread? = null
        var shutdownThread: Thread? = null
        val native = object : PythonNativeRuntime {
            override fun bootstrap(configJson: String): String {
                bootstrapThread = Thread.currentThread()
                return readyJson()
            }

            override fun execute(requestJson: String, sink: PythonNativeEventSink): String =
                error("Execution was not expected")

            override fun cancel(requestId: String) = true

            override fun shutdown() {
                shutdownThread = Thread.currentThread()
                shutDown.countDown()
            }
        }
        val engine = engine(native)
        engine.runReadinessGate(
            1,
            object : PythonRuntimeObserver {
                override fun onState(operationId: Long, snapshot: PythonRuntimeSnapshot) = Unit
                override fun onResult(operationId: Long, resultJson: String) = ready.countDown()
                override fun onStream(operationId: Long, chunk: PythonStreamChunk) = true
                override fun onCapabilityRequest(operationId: Long, request: PythonCapabilityRequest) = Unit
            },
        )

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        engine.shutdown()

        assertTrue(shutDown.await(5, TimeUnit.SECONDS))
        assertEquals(bootstrapThread, shutdownThread)
    }

    @Test
    fun nativeAuthorizedRequestRecyclesInterpreterBeforeAndAfterExecution() {
        val bootstraps = AtomicInteger()
        val shutdowns = AtomicInteger()
        val executions = AtomicInteger()
        val native = object : PythonNativeRuntime {
            override fun bootstrap(configJson: String): String {
                bootstraps.incrementAndGet()
                return readyJson()
            }

            override fun execute(requestJson: String, sink: PythonNativeEventSink): String {
                executions.incrementAndGet()
                val id = JSONObject(requestJson).getString("requestId")
                return PythonRuntimeContract.encodeResult(
                    PythonExecutionResult(id, PythonExecutionStatus.SUCCEEDED, "ok"),
                )
            }

            override fun cancel(requestId: String) = true

            override fun shutdown() {
                shutdowns.incrementAndGet()
            }
        }
        val engine = engine(native)

        fun run(allowedNativeModules: List<PythonAllowedNativeModule>) {
            val completed = CountDownLatch(1)
            val request = request(emptySet())
            engine.execute(
                operationId = executions.get().toLong() + 1,
                requestJson = PythonRuntimeContract.encodeRequest(request, 1_000),
                executionLease = executionLease(
                    request = request,
                    allowedNativeModules = allowedNativeModules,
                ),
                observer = object : PythonRuntimeObserver {
                    override fun onState(operationId: Long, snapshot: PythonRuntimeSnapshot) = Unit
                    override fun onResult(operationId: Long, resultJson: String) {
                        assertEquals(
                            PythonExecutionStatus.SUCCEEDED,
                            PythonRuntimeContract.decodeResult(resultJson).status,
                        )
                        completed.countDown()
                    }
                    override fun onStream(operationId: Long, chunk: PythonStreamChunk) = true
                    override fun onCapabilityRequest(
                        operationId: Long,
                        request: PythonCapabilityRequest,
                    ) = Unit
                },
            )
            assertTrue(completed.await(5, TimeUnit.SECONDS))
        }

        run(emptyList())
        assertEquals(1, bootstraps.get())
        assertEquals(0, shutdowns.get())

        run(
            listOf(
                PythonAllowedNativeModule(
                    module = "msgpack._cmsgpack",
                    packagedName = "libhans_py_msgpack___cmsgpack.so",
                ),
            ),
        )

        assertEquals(2, executions.get())
        assertEquals(3, bootstraps.get())
        assertEquals(2, shutdowns.get())
        assertEquals(PythonRuntimePhase.READY, engine.snapshot().phase)
        engine.shutdown()
    }

    private fun engine(native: PythonNativeRuntime): PythonNativeExecutionEngine {
        return PythonNativeExecutionEngine(
            bootstrapProvider = {
                PythonNativeBootstrapLease(
                    manifest = bootstrapManifest(),
                    descriptor = PythonOwnedFileDescriptor(101) {},
                    nativeLibraryDirectory = "/signed/native-libraries",
                )
            },
            nativeRuntimeProvider = { native },
            nowElapsedRealtimeMillis = { 1_000 },
            hardAbort = { error("Hard abort was not expected") },
            runtimePid = 42,
        )
    }

    private fun executionLease(
        request: PythonExecutionRequest,
        allowedNativeModules: List<PythonAllowedNativeModule> = emptyList(),
        onClose: () -> Unit = {},
    ) = PythonNativeExecutionLease(
        manifest = PythonRuntimeExecutionLeaseManifest(
            sessionNonce = SESSION_NONCE,
            requestId = request.requestId,
            environmentDigest = request.environmentDigest,
            environmentBytes = 22,
            allowedNativeModules = allowedNativeModules,
        ),
        descriptor = PythonOwnedFileDescriptor(202, onClose),
    )

    private fun bootstrapManifest() = PythonRuntimeBootstrapManifest(
        sessionNonce = SESSION_NONCE,
        expectedPythonVersion = "3.14.2",
        expectedAbi = "arm64-v8a",
        expectedStdlibDigest = "a".repeat(64),
        expectedStdlibBytes = 7,
    )

    private fun request(allowedCapabilities: Set<String>) = PythonExecutionRequest(
        requestId = "request-1",
        idempotencyKey = "idem-1",
        environmentDigest = "a".repeat(64),
        entrypoint = PythonEntrypoint(PythonEntrypointKind.CODE, source = "42"),
        argumentsJson = "{}",
        allowedCapabilities = allowedCapabilities,
        limits = PythonResourceLimits(61_000),
    )

    private fun readyJson() = JSONObject()
        .put("protocolVersion", 1)
        .put("ready", true)
        .put("pythonVersion", "3.14.2")
        .put("abi", "arm64-v8a")
        .put("stdlibDigest", "a".repeat(64))
        .put("verifiedImports", org.json.JSONArray(listOf("json", "asyncio", "sqlite3", "_ssl", "_hans_android")))
        .put("writableNativeImports", false)
        .toString()

    private fun streamJson(id: String, sequence: Long, stream: String, text: String) = JSONObject()
        .put("protocolVersion", 1)
        .put("type", "stream")
        .put("requestId", id)
        .put("sequence", sequence)
        .put("stream", stream)
        .put("text", text)
        .toString()

    private fun capabilityJson(id: String, sequence: Long) = JSONObject()
        .put("protocolVersion", 1)
        .put("type", "capability_request")
        .put("requestId", id)
        .put("sequence", sequence)
        .put("capability", JSONObject().put("name", "location").put("arguments", JSONObject()))
        .toString()

    private companion object {
        const val SESSION_NONCE =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
