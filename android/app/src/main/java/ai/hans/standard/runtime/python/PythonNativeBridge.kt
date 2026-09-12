package ai.hans.standard.runtime.python

/**
 * JNI surface shared with `hans_python_jni.cpp`.
 *
 * RegisterNatives signatures:
 * - nativeBootstrap(Ljava/lang/String;)Ljava/lang/String;
 * - nativeExecute(Ljava/lang/String;Lai/hans/standard/runtime/python/PythonNativeEventSink;)Ljava/lang/String;
 * - nativeCancel(Ljava/lang/String;)Z
 * - nativeShutdown()V
 *
 * The sink signatures are:
 * - onNativeEvent(Ljava/lang/String;)V
 * - onNativeCapabilityRequest(Ljava/lang/String;)Ljava/lang/String;
 */
internal interface PythonNativeRuntime {
    fun bootstrap(configJson: String): String
    fun execute(requestJson: String, sink: PythonNativeEventSink): String
    fun cancel(requestId: String): Boolean
    fun shutdown()
}

internal interface PythonNativeEventSink {
    fun onNativeEvent(eventJson: String)
    fun onNativeCapabilityRequest(requestJson: String): String
}

internal object PythonNativeBridge : PythonNativeRuntime {
    init {
        System.loadLibrary("hans_python_jni")
    }

    @JvmStatic external fun nativeBootstrap(configJson: String): String
    @JvmStatic external fun nativeExecute(requestJson: String, sink: PythonNativeEventSink): String
    @JvmStatic external fun nativeCancel(requestId: String): Boolean
    @JvmStatic external fun nativeShutdown()

    override fun bootstrap(configJson: String): String = nativeBootstrap(configJson)
    override fun execute(requestJson: String, sink: PythonNativeEventSink): String =
        nativeExecute(requestJson, sink)
    override fun cancel(requestId: String): Boolean = nativeCancel(requestId)
    override fun shutdown() = nativeShutdown()
}
