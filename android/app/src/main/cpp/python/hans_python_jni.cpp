#include <Python.h>
#include <jni.h>

#include <android/log.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <climits>
#include <cstring>
#include <cstdlib>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "hans_frozen_bootstrap.h"
#include "hans_json.h"
#include "hans_native_package_policy.h"
#include "hans_sha256.h"
#include "hans_stored_zip.h"

namespace {

constexpr char kLogTag[] = "HansPythonJNI";
constexpr char kBridgeClass[] =
    "ai/hans/standard/runtime/python/PythonNativeBridge";
constexpr char kSinkClass[] =
    "ai/hans/standard/runtime/python/PythonNativeEventSink";
constexpr char kExpectedAbi[] = "arm64-v8a";
constexpr char kExecuteSignature[] =
    "(Ljava/lang/String;Lai/hans/standard/runtime/python/PythonNativeEventSink;)"
    "Ljava/lang/String;";

JavaVM* g_java_vm = nullptr;
jclass g_sink_class = nullptr;
jmethodID g_event_method = nullptr;
jmethodID g_capability_method = nullptr;
jobject g_active_sink = nullptr;
std::mutex g_sink_mutex;

std::mutex g_interpreter_mutex;
PyThreadState* g_main_thread_state = nullptr;
PyObject* g_execute_json = nullptr;
bool g_initialized = false;
bool g_builtin_registered = false;
bool g_audit_hook_registered = false;
int g_stdlib_fd = -1;
int g_environment_fd = -1;
hans::archive::StoredZip g_stdlib_archive;
hans::archive::StoredZip g_environment_archive;
std::string g_stdlib_digest;
uint64_t g_stdlib_bytes = 0;
std::string g_bootstrap_result;
std::string g_active_request_id;
std::mutex g_request_mutex;
std::atomic<bool> g_cancel_requested{false};
std::string g_native_library_dir;

struct NativeModuleAuthorization {
  std::string module;
  std::string packaged_name;
  std::string canonical_path;

  bool operator==(const NativeModuleAuthorization& other) const {
    return module == other.module && packaged_name == other.packaged_name;
  }
};

// Accessed only while the interpreter mutex is held and the GIL is owned.
// The audit hook is process-wide, so an empty vector deliberately means that
// every catalogued third-party extension is denied for the current request.
std::vector<NativeModuleAuthorization> g_active_native_authorization;

bool HasActiveRequest() {
  std::lock_guard<std::mutex> lock(g_request_mutex);
  return !g_active_request_id.empty();
}

int EnforceNativeExtensionAudit(PyObject* arguments);

bool IsDeniedProcessAuditEvent(const char* event) {
  if (event == nullptr) return false;
  for (const char* denied : {
           "os.system",
           "os.fork",
           "os.forkpty",
           "os.exec",
           "os.posix_spawn",
           "subprocess.Popen",
           "ctypes.dlopen",
           "ctypes.dlsym",
           "ctypes.dlsym/handle",
       }) {
    if (std::strcmp(event, denied) == 0) return true;
  }
  return false;
}

int HansAuditHook(const char* event, PyObject* arguments, void*) {
  const bool active_request = HasActiveRequest();
  if (active_request && IsDeniedProcessAuditEvent(event)) {
    PyErr_Format(PyExc_PermissionError,
                 "Hans embedded Python denies process boundary %s", event);
    return -1;
  }
  if (event != nullptr && std::strcmp(event, "import") == 0 &&
      arguments != nullptr && PyTuple_Check(arguments) &&
      PyTuple_GET_SIZE(arguments) > 0) {
    if (EnforceNativeExtensionAudit(arguments) != 0) return -1;
    PyObject* module_name = PyTuple_GET_ITEM(arguments, 0);
    if (active_request && PyUnicode_Check(module_name) &&
        PyUnicode_CompareWithASCIIString(module_name, "_posixsubprocess") == 0 &&
        PyDict_GetItemString(PyImport_GetModuleDict(), "_posixsubprocess") ==
            nullptr) {
      PyErr_SetString(PyExc_PermissionError,
                      "Hans embedded Python denies process primitives");
      return -1;
    }
  }
  return 0;
}

void LogError(std::string_view message) {
  __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%.*s",
                      static_cast<int>(message.size()), message.data());
}

bool IsAbsolutePath(std::string_view path) {
  return !path.empty() && path.front() == '/' &&
         path.find('\0') == std::string_view::npos;
}

bool IsLowerSha256(std::string_view value) {
  if (value.size() != 64) return false;
  for (unsigned char character : value) {
    if ((character < '0' || character > '9') &&
        (character < 'a' || character > 'f')) {
      return false;
    }
  }
  return true;
}

bool IsDirectory(std::string_view path) {
  if (!IsAbsolutePath(path)) return false;
  struct stat metadata {};
  const std::string owned(path);
  return stat(owned.c_str(), &metadata) == 0 && S_ISDIR(metadata.st_mode);
}

bool DuplicateVerifiedArchive(
    int source_descriptor,
    uint64_t expected_bytes,
    std::string_view expected_digest,
    int* duplicate,
    std::string* error) {
  error->clear();
  if (source_descriptor < 0 || expected_bytes == 0 ||
      expected_bytes > 512ULL * 1024ULL * 1024ULL ||
      !IsLowerSha256(expected_digest)) {
    *error = "archive lease manifest is invalid";
    return false;
  }
  const int owned = fcntl(source_descriptor, F_DUPFD_CLOEXEC, 3);
  if (owned < 0) {
    *error = "cannot duplicate archive lease";
    return false;
  }
  std::string actual_digest;
  if (!hans::crypto::Sha256FileDescriptor(
          owned, expected_bytes, &actual_digest, error) ||
      actual_digest != expected_digest) {
    if (actual_digest != expected_digest) {
      *error = "archive digest does not match its lease manifest";
    }
    close(owned);
    return false;
  }
  *duplicate = owned;
  return true;
}

std::optional<std::string> CanonicalPath(std::string_view path) {
  if (!IsAbsolutePath(path)) return std::nullopt;
  const std::string owned(path);
  char* resolved = realpath(owned.c_str(), nullptr);
  if (resolved == nullptr) return std::nullopt;
  std::string result(resolved);
  std::free(resolved);
  return result;
}

const hans::python::native_package_policy::Entry* CatalogEntryForModule(
    std::string_view module) {
  using namespace hans::python::native_package_policy;
  for (size_t index = 0; index < kCatalogEntryCount; ++index) {
    if (module == kCatalogEntries[index].module) return &kCatalogEntries[index];
  }
  return nullptr;
}

const hans::python::native_package_policy::Entry* CatalogEntryForPackagedName(
    std::string_view packaged_name) {
  using namespace hans::python::native_package_policy;
  for (size_t index = 0; index < kCatalogEntryCount; ++index) {
    if (packaged_name == kCatalogEntries[index].packaged_name) {
      return &kCatalogEntries[index];
    }
  }
  return nullptr;
}

constexpr const char* kStandardExtensionModules[] = {
    "_asyncio",       "_bisect",       "_blake2",
    "_bz2",           "_codecs_cn",    "_codecs_hk",
    "_codecs_iso2022", "_codecs_jp",    "_codecs_kr",
    "_codecs_tw",     "_csv",          "_decimal",
    "_elementtree",   "_hashlib",      "_heapq",
    "_hmac",          "_interpchannels", "_interpqueues",
    "_interpreters",  "_json",         "_lsprof",
    "_lzma",          "_md5",          "_multibytecodec",
    "_pickle",        "_posixsubprocess", "_queue",
    "_random",        "_sha1",         "_sha2",
    "_sha3",          "_socket",       "_sqlite3",
    "_ssl",           "_statistics",   "_struct",
    "_zoneinfo",      "_zstd",         "array",
    "binascii",       "cmath",         "fcntl",
    "math",           "mmap",          "pyexpat",
    "resource",       "select",        "syslog",
    "termios",        "unicodedata",   "zlib",
};

std::string StandardPackagedName(std::string_view module) {
  std::string result("libhans_py_");
  for (char character : module) {
    if (character == '.') {
      result.append("__");
    } else {
      result.push_back(character);
    }
  }
  result.append(".so");
  return result;
}

const char* StandardModuleForName(std::string_view module) {
  for (const char* candidate : kStandardExtensionModules) {
    if (module == candidate) return candidate;
  }
  return nullptr;
}

const char* StandardModuleForPackagedName(std::string_view packaged_name) {
  for (const char* candidate : kStandardExtensionModules) {
    if (packaged_name == StandardPackagedName(candidate)) return candidate;
  }
  return nullptr;
}

std::string_view PathBaseName(std::string_view path) {
  const size_t separator = path.find_last_of('/');
  return separator == std::string_view::npos ? path : path.substr(separator + 1);
}

bool LooksLikeSharedObject(std::string_view path) {
  const std::string_view base = PathBaseName(path);
  return (base.size() >= 3 && base.substr(base.size() - 3) == ".so") ||
         base.find(".so.") != std::string_view::npos;
}

bool PythonUnicodeView(PyObject* value, std::string_view* output) {
  if (!PyUnicode_Check(value)) return false;
  Py_ssize_t length = 0;
  const char* data = PyUnicode_AsUTF8AndSize(value, &length);
  if (data == nullptr || length < 0) return false;
  *output = std::string_view(data, static_cast<size_t>(length));
  return true;
}

int EnforceNativeExtensionAudit(PyObject* arguments) {
  PyObject* module_value = PyTuple_GET_ITEM(arguments, 0);
  std::string_view module;
  const bool has_module = PythonUnicodeView(module_value, &module);
  if (PyErr_Occurred()) return -1;

  PyObject* filename_value = PyTuple_GET_SIZE(arguments) > 1
                                 ? PyTuple_GET_ITEM(arguments, 1)
                                 : Py_None;
  std::string_view filename;
  const bool has_filename = filename_value != Py_None &&
                            PythonUnicodeView(filename_value, &filename);
  if (PyErr_Occurred()) return -1;

  const auto* module_entry =
      has_module ? CatalogEntryForModule(module) : nullptr;
  const auto* filename_entry = has_filename
                                   ? CatalogEntryForPackagedName(
                                         PathBaseName(filename))
                                   : nullptr;
  const char* standard_module =
      has_module ? StandardModuleForName(module) : nullptr;
  const char* standard_filename_module =
      has_filename
          ? StandardModuleForPackagedName(PathBaseName(filename))
          : nullptr;
  if (module_entry == nullptr && filename_entry == nullptr &&
      standard_module == nullptr && standard_filename_module == nullptr) {
    if (has_filename && LooksLikeSharedObject(filename)) {
      PyErr_SetString(PyExc_PermissionError,
                      "Hans denies unrecognized native shared objects");
      return -1;
    }
    return 0;
  }

  if (standard_module != nullptr || standard_filename_module != nullptr) {
    const char* expected_module = standard_module != nullptr
                                      ? standard_module
                                      : standard_filename_module;
    if (module_entry != nullptr || filename_entry != nullptr || !has_module ||
        module != expected_module ||
        (filename_value != Py_None && !has_filename) ||
        (standard_filename_module != nullptr &&
         std::strcmp(standard_filename_module, expected_module) != 0)) {
      PyErr_SetString(PyExc_PermissionError,
                      "Hans standard extension identity does not match");
      return -1;
    }
    if (has_filename) {
      const std::string expected_path =
          g_native_library_dir + "/" + StandardPackagedName(expected_module);
      const auto canonical_expected = CanonicalPath(expected_path);
      const auto canonical_actual = CanonicalPath(filename);
      if (!canonical_expected.has_value() || !canonical_actual.has_value() ||
          *canonical_expected != *canonical_actual) {
        PyErr_SetString(PyExc_PermissionError,
                        "Hans standard extension path is not signed");
        return -1;
      }
    }
    return 0;
  }

  // A direct ExtensionFileLoader/_imp.create_dynamic call can supply an alias
  // for a signed library. Bind both identity channels to the same catalog row.
  const auto* catalog_entry =
      module_entry != nullptr ? module_entry : filename_entry;
  if (!has_module || module != catalog_entry->module ||
      (filename_entry != nullptr && filename_entry != catalog_entry) ||
      (filename_value != Py_None && !has_filename)) {
    PyErr_SetString(
        PyExc_PermissionError,
        "Hans native extension module and packaged library do not match");
    return -1;
  }

  const NativeModuleAuthorization* authorization = nullptr;
  for (const auto& candidate : g_active_native_authorization) {
    if (candidate.module == catalog_entry->module &&
        candidate.packaged_name == catalog_entry->packaged_name) {
      authorization = &candidate;
      break;
    }
  }
  if (authorization == nullptr) {
    PyErr_Format(PyExc_PermissionError,
                 "Hans request has no authority for native extension %s",
                 catalog_entry->module);
    return -1;
  }

  // CPython first emits an import event with no filename and later another
  // event immediately before loading the extension. The identity-only event
  // is safe once the module is request-authorized; every supplied path must be
  // the exact canonical APK nativeLibraryDir member.
  if (has_filename) {
    const auto canonical = CanonicalPath(filename);
    if (!canonical.has_value() || *canonical != authorization->canonical_path) {
      PyErr_Format(PyExc_PermissionError,
                   "Hans native extension path is not authorized for %s",
                   catalog_entry->module);
      return -1;
    }
  }
  return 0;
}

std::string ReadinessFailure(std::string_view code, std::string_view detail) {
  std::string diagnostic("readiness ");
  diagnostic.append(code);
  diagnostic.append(": ");
  diagnostic.append(detail.substr(0, 2048));
  LogError(diagnostic);
  return std::string("{\"protocolVersion\":1,\"ready\":false,") +
         "\"pythonVersion\":null,\"abi\":null,\"stdlibDigest\":null," +
         "\"verifiedImports\":[],\"writableNativeImports\":false," +
         "\"errorCode\":" + hans::json::Quote(code) + ",\"detail\":" +
         hans::json::Quote(detail) + "}";
}

std::string ExecutionFailure(std::string_view request_id,
                             std::string_view status,
                             std::string_view code,
                             std::string_view detail) {
  return std::string("{\"protocolVersion\":1,\"requestId\":") +
         hans::json::Quote(request_id) + ",\"status\":" +
         hans::json::Quote(status) + ",\"value\":null,\"errorCode\":" +
         hans::json::Quote(code) + ",\"errorMessage\":" +
         hans::json::Quote(detail) +
         ",\"metrics\":{\"durationMs\":0,\"stdoutBytes\":0," +
         "\"stderrBytes\":0,\"eventCount\":0}}";
}

bool JavaStringToUtf8(JNIEnv* env, jstring input, std::string* output) {
  if (input == nullptr) return false;
  const jsize length = env->GetStringLength(input);
  const jchar* characters = env->GetStringChars(input, nullptr);
  if (characters == nullptr) return false;
  output->clear();
  output->reserve(static_cast<size_t>(length) * 3U);
  bool valid = true;
  for (jsize index = 0; index < length; ++index) {
    uint32_t codepoint = characters[index];
    if (codepoint >= 0xd800U && codepoint <= 0xdbffU) {
      if (index + 1 >= length || characters[index + 1] < 0xdc00U ||
          characters[index + 1] > 0xdfffU) {
        valid = false;
        break;
      }
      const uint32_t low = characters[++index];
      codepoint = 0x10000U + ((codepoint - 0xd800U) << 10U) +
                  (low - 0xdc00U);
    } else if (codepoint >= 0xdc00U && codepoint <= 0xdfffU) {
      valid = false;
      break;
    }
    if (codepoint <= 0x7fU) {
      output->push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ffU) {
      output->push_back(static_cast<char>(0xc0U | (codepoint >> 6U)));
      output->push_back(static_cast<char>(0x80U | (codepoint & 0x3fU)));
    } else if (codepoint <= 0xffffU) {
      output->push_back(static_cast<char>(0xe0U | (codepoint >> 12U)));
      output->push_back(static_cast<char>(0x80U | ((codepoint >> 6U) & 0x3fU)));
      output->push_back(static_cast<char>(0x80U | (codepoint & 0x3fU)));
    } else {
      output->push_back(static_cast<char>(0xf0U | (codepoint >> 18U)));
      output->push_back(static_cast<char>(0x80U | ((codepoint >> 12U) & 0x3fU)));
      output->push_back(static_cast<char>(0x80U | ((codepoint >> 6U) & 0x3fU)));
      output->push_back(static_cast<char>(0x80U | (codepoint & 0x3fU)));
    }
  }
  env->ReleaseStringChars(input, characters);
  return valid;
}

bool DecodeUtf8(std::string_view input, std::vector<jchar>* output) {
  output->clear();
  output->reserve(input.size());
  for (size_t position = 0; position < input.size();) {
    const uint8_t first = static_cast<uint8_t>(input[position++]);
    uint32_t codepoint = 0;
    size_t continuation_count = 0;
    if (first <= 0x7fU) {
      codepoint = first;
    } else if ((first & 0xe0U) == 0xc0U) {
      codepoint = first & 0x1fU;
      continuation_count = 1;
      if (codepoint < 2) return false;
    } else if ((first & 0xf0U) == 0xe0U) {
      codepoint = first & 0x0fU;
      continuation_count = 2;
    } else if ((first & 0xf8U) == 0xf0U) {
      codepoint = first & 0x07U;
      continuation_count = 3;
    } else {
      return false;
    }
    if (position + continuation_count > input.size()) return false;
    for (size_t index = 0; index < continuation_count; ++index) {
      const uint8_t continuation = static_cast<uint8_t>(input[position++]);
      if ((continuation & 0xc0U) != 0x80U) return false;
      codepoint = (codepoint << 6U) | (continuation & 0x3fU);
    }
    if ((continuation_count == 1 && codepoint < 0x80U) ||
        (continuation_count == 2 && codepoint < 0x800U) ||
        (continuation_count == 3 && codepoint < 0x10000U) ||
        codepoint > 0x10ffffU ||
        (codepoint >= 0xd800U && codepoint <= 0xdfffU)) {
      return false;
    }
    if (codepoint <= 0xffffU) {
      output->push_back(static_cast<jchar>(codepoint));
    } else {
      codepoint -= 0x10000U;
      output->push_back(static_cast<jchar>(0xd800U + (codepoint >> 10U)));
      output->push_back(static_cast<jchar>(0xdc00U + (codepoint & 0x3ffU)));
    }
  }
  return true;
}

jstring Utf8ToJavaString(JNIEnv* env, std::string_view input) {
  std::vector<jchar> decoded;
  if (!DecodeUtf8(input, &decoded) || decoded.size() > INT_MAX) {
    return nullptr;
  }
  return env->NewString(decoded.data(), static_cast<jsize>(decoded.size()));
}

class AttachedEnv {
 public:
  AttachedEnv() {
    if (g_java_vm == nullptr) return;
    void* raw = nullptr;
    const jint state = g_java_vm->GetEnv(&raw, JNI_VERSION_1_6);
    if (state == JNI_OK) {
      env_ = static_cast<JNIEnv*>(raw);
    } else if (state == JNI_EDETACHED &&
               g_java_vm->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
      attached_ = true;
    }
  }

  ~AttachedEnv() {
    if (attached_) g_java_vm->DetachCurrentThread();
  }

  JNIEnv* get() const { return env_; }

 private:
  JNIEnv* env_ = nullptr;
  bool attached_ = false;
};

jobject LocalActiveSink(JNIEnv* env) {
  std::lock_guard<std::mutex> lock(g_sink_mutex);
  return g_active_sink == nullptr ? nullptr : env->NewLocalRef(g_active_sink);
}

bool PythonUnicodeToUtf8(PyObject* value, std::string* output) {
  if (!PyUnicode_Check(value)) {
    PyErr_SetString(PyExc_TypeError, "Hans Android bridge expects a string");
    return false;
  }
  Py_ssize_t length = 0;
  const char* data = PyUnicode_AsUTF8AndSize(value, &length);
  if (data == nullptr || length < 0) return false;
  output->assign(data, static_cast<size_t>(length));
  return true;
}

PyObject* HansEmit(PyObject*, PyObject* value) {
  std::string event;
  if (!PythonUnicodeToUtf8(value, &event)) return nullptr;
  AttachedEnv attached;
  JNIEnv* env = attached.get();
  if (env == nullptr) {
    PyErr_SetString(PyExc_RuntimeError, "Android JVM is unavailable");
    return nullptr;
  }
  jobject sink = LocalActiveSink(env);
  if (sink == nullptr) {
    PyErr_SetString(PyExc_RuntimeError, "Android event sink is unavailable");
    return nullptr;
  }
  jstring encoded = Utf8ToJavaString(env, event);
  if (encoded == nullptr) {
    env->DeleteLocalRef(sink);
    PyErr_SetString(PyExc_ValueError, "event is not valid UTF-8");
    return nullptr;
  }
  env->CallVoidMethod(sink, g_event_method, encoded);
  env->DeleteLocalRef(encoded);
  env->DeleteLocalRef(sink);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    PyErr_SetString(PyExc_RuntimeError, "Android event sink rejected the event");
    return nullptr;
  }
  Py_RETURN_NONE;
}

PyObject* HansCall(PyObject*, PyObject* value) {
  std::string request;
  if (!PythonUnicodeToUtf8(value, &request)) return nullptr;
  AttachedEnv attached;
  JNIEnv* env = attached.get();
  if (env == nullptr) {
    PyErr_SetString(PyExc_RuntimeError, "Android JVM is unavailable");
    return nullptr;
  }
  jobject sink = LocalActiveSink(env);
  if (sink == nullptr) {
    PyErr_SetString(PyExc_RuntimeError, "Android capability sink is unavailable");
    return nullptr;
  }
  jstring encoded = Utf8ToJavaString(env, request);
  if (encoded == nullptr) {
    env->DeleteLocalRef(sink);
    PyErr_SetString(PyExc_ValueError, "capability request is not valid UTF-8");
    return nullptr;
  }
  auto response = static_cast<jstring>(
      env->CallObjectMethod(sink, g_capability_method, encoded));
  env->DeleteLocalRef(encoded);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    env->DeleteLocalRef(sink);
    PyErr_SetString(PyExc_RuntimeError, "Android capability broker failed");
    return nullptr;
  }
  std::string result;
  const bool converted = response != nullptr &&
                         JavaStringToUtf8(env, response, &result);
  if (response != nullptr) env->DeleteLocalRef(response);
  env->DeleteLocalRef(sink);
  if (!converted) {
    PyErr_SetString(PyExc_RuntimeError,
                    "Android capability broker returned no valid response");
    return nullptr;
  }
  return PyUnicode_DecodeUTF8(result.data(),
                              static_cast<Py_ssize_t>(result.size()), "strict");
}

PyObject* HansCancelled(PyObject*, PyObject*) {
  return PyBool_FromLong(g_cancel_requested.load(std::memory_order_acquire));
}

PyObject* HansProcessDenied(PyObject*, PyObject*) {
  PyErr_SetString(PyExc_PermissionError,
                  "Hans embedded Python denies process creation");
  return nullptr;
}

PyObject* HansArchiveLookup(PyObject*, PyObject* arguments) {
  const char* fullname = nullptr;
  Py_ssize_t fullname_size = 0;
  if (!PyArg_ParseTuple(arguments, "s#:_archive_lookup", &fullname,
                        &fullname_size)) {
    return nullptr;
  }
  if (fullname_size <= 0 || fullname_size > 512) Py_RETURN_NONE;
  std::string stem;
  stem.reserve(static_cast<size_t>(fullname_size) + 16);
  for (Py_ssize_t index = 0; index < fullname_size; ++index) {
    const unsigned char character = static_cast<unsigned char>(fullname[index]);
    if (character == '.') {
      stem.push_back('/');
    } else if ((character >= 'a' && character <= 'z') ||
               (character >= 'A' && character <= 'Z') ||
               (character >= '0' && character <= '9') || character == '_') {
      stem.push_back(static_cast<char>(character));
    } else {
      Py_RETURN_NONE;
    }
  }
  // Plugin implementation sources are intentionally not addressable through
  // the process-wide environment importer. They are exposed only by the
  // digest-bound, request-scoped Python finder after strict containment checks.
  constexpr std::string_view kPluginSourceStem = "__hans_plugin_source__";
  if (stem == kPluginSourceStem ||
      (stem.size() > kPluginSourceStem.size() &&
       stem.compare(0, kPluginSourceStem.size(), kPluginSourceStem) == 0 &&
       stem[kPluginSourceStem.size()] == '/')) {
    Py_RETURN_NONE;
  }
  std::string error;
  for (const auto& candidate : {
           std::pair<std::string, bool>{stem + "/__init__.py", true},
           {stem + ".py", false},
       }) {
    for (const auto& source : {
             std::pair<const hans::archive::StoredZip*, const char*>{
                 &g_stdlib_archive, "stdlib"},
             {&g_environment_archive, "environment"},
         }) {
      auto bytes = source.first->Read(candidate.first, 8U * 1024U * 1024U,
                                      &error);
      if (!error.empty()) {
        PyErr_SetString(PyExc_ImportError, error.c_str());
        return nullptr;
      }
      if (!bytes.has_value()) continue;
      PyObject* source_bytes = PyBytes_FromStringAndSize(
          reinterpret_cast<const char*>(bytes->data()),
          static_cast<Py_ssize_t>(bytes->size()));
      const std::string origin = std::string("hans-fd://") + source.second +
                                 "/" + candidate.first;
      PyObject* source_origin = PyUnicode_FromStringAndSize(
          origin.data(), static_cast<Py_ssize_t>(origin.size()));
      PyObject* package = PyBool_FromLong(candidate.second ? 1 : 0);
      if (source_bytes == nullptr || source_origin == nullptr || package == nullptr) {
        Py_XDECREF(source_bytes);
        Py_XDECREF(source_origin);
        Py_XDECREF(package);
        return nullptr;
      }
      PyObject* result = PyTuple_New(3);
      if (result == nullptr) {
        Py_DECREF(source_bytes);
        Py_DECREF(source_origin);
        Py_DECREF(package);
        return nullptr;
      }
      PyTuple_SET_ITEM(result, 0, source_bytes);
      PyTuple_SET_ITEM(result, 1, source_origin);
      PyTuple_SET_ITEM(result, 2, package);
      return result;
    }
  }
  Py_RETURN_NONE;
}

PyObject* HansArchiveRead(PyObject*, PyObject* arguments) {
  const char* kind = nullptr;
  const char* member = nullptr;
  Py_ssize_t kind_size = 0;
  Py_ssize_t member_size = 0;
  if (!PyArg_ParseTuple(arguments, "s#s#:_archive_read", &kind, &kind_size,
                        &member, &member_size)) {
    return nullptr;
  }
  const std::string_view archive_kind(kind, static_cast<size_t>(kind_size));
  const hans::archive::StoredZip* archive = nullptr;
  if (archive_kind == "stdlib") {
    archive = &g_stdlib_archive;
  } else if (archive_kind == "environment") {
    archive = &g_environment_archive;
  } else {
    PyErr_SetString(PyExc_ValueError, "unknown Hans archive kind");
    return nullptr;
  }
  std::string error;
  auto bytes = archive->Read(
      std::string_view(member, static_cast<size_t>(member_size)),
      8U * 1024U * 1024U, &error);
  if (!error.empty()) {
    PyErr_SetString(PyExc_RuntimeError, error.c_str());
    return nullptr;
  }
  if (!bytes.has_value()) Py_RETURN_NONE;
  return PyBytes_FromStringAndSize(
      reinterpret_cast<const char*>(bytes->data()),
      static_cast<Py_ssize_t>(bytes->size()));
}

PyMethodDef kHansMethods[] = {
    {"emit", HansEmit, METH_O, "Emit one bounded event to the Android host."},
    {"call", HansCall, METH_O, "Call one allowlisted Android capability."},
    {"cancelled", HansCancelled, METH_NOARGS,
     "Return whether cooperative cancellation was requested."},
    {"_process_denied", HansProcessDenied, METH_VARARGS,
     "Non-removable process-creation boundary."},
    {"_archive_lookup", HansArchiveLookup, METH_VARARGS,
     "Find one Python source in verified descriptor archives."},
    {"_archive_read", HansArchiveRead, METH_VARARGS,
     "Read one named member from a verified descriptor archive."},
    {nullptr, nullptr, 0, nullptr},
};

PyModuleDef kHansModule = {
    PyModuleDef_HEAD_INIT,
    "_hans_android",
    "Private JNI bridge for Hans CPython.",
    -1,
    kHansMethods,
    nullptr,
    nullptr,
    nullptr,
    nullptr,
};

PyMODINIT_FUNC PyInit__hans_android() {
  return PyModule_Create(&kHansModule);
}

// CPython's Android build imports `_android_support` while
// Py_InitializeFromConfig is still running. The upstream Python implementation
// imports `threading` before our descriptor-backed stdlib finder can exist.
// Hans captures stdout/stderr per request in hans_runtime_bootstrap, so the
// platform logcat redirection is intentionally a dependency-free no-op during
// this narrow bootstrap window.
PyObject* HansAndroidInitStreams(PyObject*, PyObject*) {
  Py_RETURN_NONE;
}

PyMethodDef kHansAndroidSupportMethods[] = {
    {"init_streams", HansAndroidInitStreams, METH_VARARGS,
     "Leave Android streams untouched until Hans installs bounded writers."},
    {nullptr, nullptr, 0, nullptr},
};

PyModuleDef kHansAndroidSupportModule = {
    PyModuleDef_HEAD_INIT,
    "_android_support",
    "Minimal bootstrap hook for embedded CPython on Android.",
    -1,
    kHansAndroidSupportMethods,
    nullptr,
    nullptr,
    nullptr,
    nullptr,
};

PyMODINIT_FUNC PyInit__android_support() {
  return PyModule_Create(&kHansAndroidSupportModule);
}

std::string PythonErrorText(std::string_view fallback) {
  if (!PyErr_Occurred()) return std::string(fallback);
  PyObject* type = nullptr;
  PyObject* value = nullptr;
  PyObject* traceback = nullptr;
  PyErr_Fetch(&type, &value, &traceback);
  PyErr_NormalizeException(&type, &value, &traceback);
  std::string result(fallback);
  if (value != nullptr) {
    PyObject* rendered = PyObject_Str(value);
    if (rendered != nullptr) {
      Py_ssize_t length = 0;
      const char* data = PyUnicode_AsUTF8AndSize(rendered, &length);
      if (data != nullptr && length > 0) {
        result.assign(data, static_cast<size_t>(length));
      }
      Py_DECREF(rendered);
    }
  }
  Py_XDECREF(type);
  Py_XDECREF(value);
  Py_XDECREF(traceback);
  PyErr_Clear();
  if (result.size() > 4096) result.resize(4096);
  return result;
}

class NativeAuthorityJsonParser {
 public:
  explicit NativeAuthorityJsonParser(std::string_view input) : input_(input) {}

  bool Parse(std::vector<NativeModuleAuthorization>* authorization,
             std::string* error) {
    authorization->clear();
    std::vector<NativeModuleAuthorization> request_modules;
    std::vector<NativeModuleAuthorization> lease_modules;
    bool request_modules_seen = false;
    bool runtime_lease_seen = false;
    SkipWhitespace();
    if (!Consume('{')) return Fail("request JSON root must be an object", error);
    SkipWhitespace();
    if (!Consume('}')) {
      while (true) {
        std::string key;
        if (!ParseString(&key, error)) return false;
        SkipWhitespace();
        if (!Consume(':')) return Fail("expected colon after request field", error);
        SkipWhitespace();
        if (key == "allowedNativeModules") {
          if (request_modules_seen) {
            return Fail("duplicate request native authority", error);
          }
          request_modules_seen = true;
          if (!ParseModuleArray(&request_modules, error)) return false;
        } else if (key == "runtimeLease") {
          if (runtime_lease_seen) return Fail("duplicate runtime lease", error);
          runtime_lease_seen = true;
          if (!ParseRuntimeLease(&lease_modules, error)) return false;
        } else if (!SkipValue(0, error)) {
          return false;
        }
        SkipWhitespace();
        if (Consume('}')) break;
        if (!Consume(',')) return Fail("expected comma in request", error);
        SkipWhitespace();
      }
    }
    SkipWhitespace();
    if (position_ != input_.size()) {
      return Fail("trailing data after request JSON", error);
    }
    if (!request_modules_seen || !runtime_lease_seen) {
      return Fail("request native authority and runtime lease are required", error);
    }
    if (request_modules != lease_modules) {
      return Fail("request and runtime lease native authority differs", error);
    }
    *authorization = std::move(request_modules);
    return true;
  }

 private:
  bool ParseRuntimeLease(std::vector<NativeModuleAuthorization>* modules,
                         std::string* error) {
    if (!Consume('{')) return Fail("runtimeLease must be an object", error);
    bool modules_seen = false;
    SkipWhitespace();
    if (!Consume('}')) {
      while (true) {
        std::string key;
        if (!ParseString(&key, error)) return false;
        SkipWhitespace();
        if (!Consume(':')) return Fail("expected colon in runtimeLease", error);
        SkipWhitespace();
        if (key == "allowedNativeModules") {
          if (modules_seen) return Fail("duplicate lease native authority", error);
          modules_seen = true;
          if (!ParseModuleArray(modules, error)) return false;
        } else if (!SkipValue(0, error)) {
          return false;
        }
        SkipWhitespace();
        if (Consume('}')) break;
        if (!Consume(',')) return Fail("expected comma in runtimeLease", error);
        SkipWhitespace();
      }
    }
    return modules_seen || Fail("runtimeLease native authority is required", error);
  }

  bool ParseModuleArray(std::vector<NativeModuleAuthorization>* modules,
                        std::string* error) {
    modules->clear();
    if (!Consume('[')) return Fail("allowedNativeModules must be an array", error);
    SkipWhitespace();
    if (Consume(']')) return true;
    while (true) {
      if (modules->size() >= 32) {
        return Fail("too many allowed native modules", error);
      }
      NativeModuleAuthorization descriptor;
      if (!ParseModuleDescriptor(&descriptor, error)) return false;
      if (std::any_of(modules->begin(), modules->end(), [&](const auto& prior) {
            return prior.module == descriptor.module ||
                   prior.packaged_name == descriptor.packaged_name;
          })) {
        return Fail("duplicate native module descriptor", error);
      }
      modules->push_back(std::move(descriptor));
      SkipWhitespace();
      if (Consume(']')) return true;
      if (!Consume(',')) return Fail("expected comma in native authority", error);
      SkipWhitespace();
    }
  }

  bool ParseModuleDescriptor(NativeModuleAuthorization* descriptor,
                             std::string* error) {
    if (!Consume('{')) return Fail("native module descriptor must be an object", error);
    bool module_seen = false;
    bool packaged_seen = false;
    std::string module;
    std::string packaged_name;
    SkipWhitespace();
    if (Consume('}')) return Fail("native module descriptor is empty", error);
    while (true) {
      std::string key;
      if (!ParseString(&key, error)) return false;
      SkipWhitespace();
      if (!Consume(':')) return Fail("expected colon in native descriptor", error);
      SkipWhitespace();
      if (key == "module") {
        if (module_seen) return Fail("duplicate native module field", error);
        module_seen = true;
        if (!ParseString(&module, error)) {
          return Fail("native module must be a string", error);
        }
      } else if (key == "packagedName") {
        if (packaged_seen) return Fail("duplicate packagedName field", error);
        packaged_seen = true;
        if (!ParseString(&packaged_name, error)) {
          return Fail("native packagedName must be a string", error);
        }
      } else {
        return Fail("native module descriptor contains an unknown field", error);
      }
      SkipWhitespace();
      if (Consume('}')) break;
      if (!Consume(',')) return Fail("expected comma in native descriptor", error);
      SkipWhitespace();
    }
    if (!module_seen || !packaged_seen) {
      return Fail("native module descriptor fields are missing", error);
    }
    const auto* catalog_entry = CatalogEntryForModule(module);
    if (catalog_entry == nullptr || packaged_name != catalog_entry->packaged_name) {
      return Fail("native descriptor is absent from the signed APK policy", error);
    }
    const std::string requested_path = g_native_library_dir + "/" + packaged_name;
    const auto canonical_path = CanonicalPath(requested_path);
    if (!canonical_path.has_value() ||
        canonical_path->size() <= g_native_library_dir.size() ||
        canonical_path->compare(0, g_native_library_dir.size(),
                                g_native_library_dir) != 0 ||
        (*canonical_path)[g_native_library_dir.size()] != '/') {
      return Fail("signed native extension is absent from nativeLibraryDir", error);
    }
    *descriptor = NativeModuleAuthorization{
        std::move(module), std::move(packaged_name), *canonical_path};
    return true;
  }

  bool SkipValue(int depth, std::string* error) {
    if (depth > 64) return Fail("JSON nesting limit exceeded", error);
    SkipWhitespace();
    if (position_ >= input_.size()) return Fail("missing JSON value", error);
    if (input_[position_] == '"') {
      std::string ignored;
      return ParseString(&ignored, error);
    }
    if (input_[position_] == '{') {
      ++position_;
      SkipWhitespace();
      if (Consume('}')) return true;
      while (true) {
        std::string ignored;
        if (!ParseString(&ignored, error)) return false;
        SkipWhitespace();
        if (!Consume(':')) return Fail("expected colon in JSON object", error);
        if (!SkipValue(depth + 1, error)) return false;
        SkipWhitespace();
        if (Consume('}')) return true;
        if (!Consume(',')) return Fail("expected comma in JSON object", error);
        SkipWhitespace();
      }
    }
    if (input_[position_] == '[') {
      ++position_;
      SkipWhitespace();
      if (Consume(']')) return true;
      while (true) {
        if (!SkipValue(depth + 1, error)) return false;
        SkipWhitespace();
        if (Consume(']')) return true;
        if (!Consume(',')) return Fail("expected comma in JSON array", error);
        SkipWhitespace();
      }
    }
    for (std::string_view literal : {"true", "false", "null"}) {
      if (input_.substr(position_, literal.size()) == literal) {
        position_ += literal.size();
        return true;
      }
    }
    return SkipNumber(error);
  }

  bool SkipNumber(std::string* error) {
    if (Consume('-') && position_ >= input_.size()) {
      return Fail("invalid JSON number", error);
    }
    if (position_ >= input_.size()) return Fail("invalid JSON number", error);
    if (input_[position_] == '0') {
      ++position_;
      if (position_ < input_.size() &&
          input_[position_] >= '0' && input_[position_] <= '9') {
        return Fail("leading zero in JSON number", error);
      }
    } else if (input_[position_] >= '1' && input_[position_] <= '9') {
      while (position_ < input_.size() && input_[position_] >= '0' &&
             input_[position_] <= '9') {
        ++position_;
      }
    } else {
      return Fail("invalid JSON value", error);
    }
    if (Consume('.')) {
      const size_t digits = position_;
      while (position_ < input_.size() && input_[position_] >= '0' &&
             input_[position_] <= '9') {
        ++position_;
      }
      if (position_ == digits) return Fail("invalid JSON fraction", error);
    }
    if (position_ < input_.size() &&
        (input_[position_] == 'e' || input_[position_] == 'E')) {
      ++position_;
      if (position_ < input_.size() &&
          (input_[position_] == '+' || input_[position_] == '-')) {
        ++position_;
      }
      const size_t digits = position_;
      while (position_ < input_.size() && input_[position_] >= '0' &&
             input_[position_] <= '9') {
        ++position_;
      }
      if (position_ == digits) return Fail("invalid JSON exponent", error);
    }
    return true;
  }

  bool ParseString(std::string* output, std::string* error) {
    if (!Consume('"')) return Fail("expected JSON string", error);
    output->clear();
    while (position_ < input_.size()) {
      const unsigned char value =
          static_cast<unsigned char>(input_[position_++]);
      if (value == '"') return true;
      if (value < 0x20U) return Fail("control character in JSON string", error);
      if (value != '\\') {
        output->push_back(static_cast<char>(value));
        continue;
      }
      if (position_ >= input_.size()) return Fail("truncated JSON escape", error);
      const char escaped = input_[position_++];
      switch (escaped) {
        case '"': output->push_back('"'); break;
        case '\\': output->push_back('\\'); break;
        case '/': output->push_back('/'); break;
        case 'b': output->push_back('\b'); break;
        case 'f': output->push_back('\f'); break;
        case 'n': output->push_back('\n'); break;
        case 'r': output->push_back('\r'); break;
        case 't': output->push_back('\t'); break;
        case 'u': {
          uint32_t codepoint = 0;
          if (!ParseHex4(&codepoint, error)) return false;
          if (codepoint >= 0xd800U && codepoint <= 0xdbffU) {
            if (!Consume('\\') || !Consume('u')) {
              return Fail("unpaired high surrogate", error);
            }
            uint32_t low = 0;
            if (!ParseHex4(&low, error) || low < 0xdc00U || low > 0xdfffU) {
              return Fail("invalid low surrogate", error);
            }
            codepoint = 0x10000U + ((codepoint - 0xd800U) << 10U) +
                        (low - 0xdc00U);
          } else if (codepoint >= 0xdc00U && codepoint <= 0xdfffU) {
            return Fail("unpaired low surrogate", error);
          }
          AppendUtf8(codepoint, output);
          break;
        }
        default: return Fail("unsupported JSON escape", error);
      }
    }
    return Fail("unterminated JSON string", error);
  }

  bool ParseHex4(uint32_t* value, std::string* error) {
    if (position_ + 4 > input_.size()) return Fail("truncated unicode escape", error);
    uint32_t decoded = 0;
    for (int index = 0; index < 4; ++index) {
      const char character = input_[position_++];
      int digit = -1;
      if (character >= '0' && character <= '9') digit = character - '0';
      if (character >= 'a' && character <= 'f') digit = 10 + character - 'a';
      if (character >= 'A' && character <= 'F') digit = 10 + character - 'A';
      if (digit < 0) return Fail("invalid unicode escape", error);
      decoded = (decoded << 4U) | static_cast<uint32_t>(digit);
    }
    *value = decoded;
    return true;
  }

  static void AppendUtf8(uint32_t codepoint, std::string* output) {
    if (codepoint <= 0x7fU) {
      output->push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ffU) {
      output->push_back(static_cast<char>(0xc0U | (codepoint >> 6U)));
      output->push_back(static_cast<char>(0x80U | (codepoint & 0x3fU)));
    } else if (codepoint <= 0xffffU) {
      output->push_back(static_cast<char>(0xe0U | (codepoint >> 12U)));
      output->push_back(static_cast<char>(0x80U | ((codepoint >> 6U) & 0x3fU)));
      output->push_back(static_cast<char>(0x80U | (codepoint & 0x3fU)));
    } else {
      output->push_back(static_cast<char>(0xf0U | (codepoint >> 18U)));
      output->push_back(static_cast<char>(0x80U | ((codepoint >> 12U) & 0x3fU)));
      output->push_back(static_cast<char>(0x80U | ((codepoint >> 6U) & 0x3fU)));
      output->push_back(static_cast<char>(0x80U | (codepoint & 0x3fU)));
    }
  }

  void SkipWhitespace() {
    while (position_ < input_.size() &&
           (input_[position_] == ' ' || input_[position_] == '\t' ||
            input_[position_] == '\r' || input_[position_] == '\n')) {
      ++position_;
    }
  }

  bool Consume(char expected) {
    if (position_ >= input_.size() || input_[position_] != expected) return false;
    ++position_;
    return true;
  }

  bool Fail(std::string_view message, std::string* error) {
    *error = std::string(message);
    return false;
  }

  std::string_view input_;
  size_t position_ = 0;
};

bool ParseRequestNativeAuthorization(
    std::string_view request,
    std::vector<NativeModuleAuthorization>* authorization,
    std::string* error) {
  return NativeAuthorityJsonParser(request).Parse(authorization, error);
}

bool ImportRequiredModules(std::string* error) {
  for (const char* name : {"json", "asyncio", "sqlite3", "_ssl",
                           "_hans_android"}) {
    PyObject* module = PyImport_ImportModule(name);
    if (module == nullptr) {
      *error = std::string("required import failed for ") + name + ": " +
               PythonErrorText("unknown import error");
      return false;
    }
    Py_DECREF(module);
  }
  return true;
}

bool InstallProcessPrimitiveLockdown(std::string* error) {
  PyObject* bridge = PyImport_ImportModule("_hans_android");
  PyObject* denied = bridge == nullptr
                         ? nullptr
                         : PyObject_GetAttrString(bridge, "_process_denied");
  if (denied == nullptr || !PyCallable_Check(denied)) {
    *error = PythonErrorText("cannot load process denial primitive");
    Py_XDECREF(denied);
    Py_XDECREF(bridge);
    return false;
  }
  for (const auto& target : {
           std::pair<const char*, const char*>{"_posixsubprocess", "fork_exec"},
           {"subprocess", "_fork_exec"},
       }) {
    PyObject* module = PyImport_ImportModule(target.first);
    if (module == nullptr || !PyObject_HasAttrString(module, target.second) ||
        PyObject_SetAttrString(module, target.second, denied) != 0) {
      *error = std::string("cannot lock process primitive ") + target.first +
               "." + target.second + ": " +
               PythonErrorText("unknown process lockdown error");
      Py_XDECREF(module);
      Py_DECREF(denied);
      Py_DECREF(bridge);
      return false;
    }
    Py_DECREF(module);
  }
  Py_DECREF(denied);
  Py_DECREF(bridge);
  return true;
}

bool InstallDescriptorImporter(std::string* error) {
  auto source = g_stdlib_archive.Read(
      "hans_fd_importer.py", 1024U * 1024U, error);
  if (!source.has_value()) {
    if (error->empty()) *error = "descriptor importer is absent from stdlib";
    return false;
  }
  std::string source_text(source->begin(), source->end());
  PyObject* code = Py_CompileStringExFlags(
      source_text.c_str(), "hans-fd://stdlib/hans_fd_importer.py",
      Py_file_input, nullptr, -1);
  if (code == nullptr) {
    *error = PythonErrorText("cannot compile descriptor importer");
    return false;
  }
  PyObject* module = PyModule_New("hans_fd_importer");
  PyObject* modules = PyImport_GetModuleDict();
  if (module == nullptr ||
      PyModule_AddStringConstant(
          module, "__file__", "hans-fd://stdlib/hans_fd_importer.py") != 0 ||
      PyModule_AddStringConstant(module, "__package__", "") != 0 ||
      PyDict_SetItemString(modules, "hans_fd_importer", module) != 0) {
    *error = PythonErrorText("cannot create descriptor importer module");
    Py_DECREF(code);
    Py_XDECREF(module);
    return false;
  }
  PyObject* evaluated = PyEval_EvalCode(code, PyModule_GetDict(module),
                                        PyModule_GetDict(module));
  Py_DECREF(code);
  if (evaluated == nullptr) {
    *error = PythonErrorText("cannot initialize descriptor importer");
    PyDict_DelItemString(modules, "hans_fd_importer");
    Py_DECREF(module);
    return false;
  }
  Py_DECREF(evaluated);
  PyObject* install = PyObject_GetAttrString(module, "install");
  PyObject* finder = install == nullptr ? nullptr : PyObject_CallNoArgs(install);
  Py_XDECREF(install);
  Py_DECREF(module);
  if (finder == nullptr) {
    *error = PythonErrorText("cannot install descriptor importer");
    return false;
  }
  Py_DECREF(finder);
  return true;
}

bool CallBootstrapInstall(std::string_view native_library_dir,
                          std::string* error) {
  // The runtime dispatcher imports modules which require APK-packaged
  // extension modules. Install the tiny pure-Python native finder before
  // importing that dispatcher; otherwise bootstrap has a circular dependency
  // on the finder it is meant to install.
  PyObject* importer = PyImport_ImportModule("hans_native_importer");
  PyObject* importer_install = importer == nullptr
                                   ? nullptr
                                   : PyObject_GetAttrString(importer, "install");
  if (importer == nullptr || importer_install == nullptr ||
      !PyCallable_Check(importer_install)) {
    *error = PythonErrorText("cannot import Hans native extension finder");
    Py_XDECREF(importer_install);
    Py_XDECREF(importer);
    return false;
  }
  PyObject* native = PyUnicode_DecodeUTF8(
      native_library_dir.data(), static_cast<Py_ssize_t>(native_library_dir.size()),
      "strict");
  PyObject* finder = native == nullptr
                         ? nullptr
                         : PyObject_CallOneArg(importer_install, native);
  Py_DECREF(importer_install);
  Py_DECREF(importer);
  if (finder == nullptr) {
    *error = PythonErrorText("Hans native extension finder install failed");
    Py_XDECREF(native);
    return false;
  }
  Py_DECREF(finder);

  PyObject* module = PyImport_ImportModule("hans_runtime_bootstrap");
  if (module == nullptr) {
    *error = PythonErrorText("cannot import Hans runtime bootstrap");
    Py_DECREF(native);
    return false;
  }
  PyObject* install = PyObject_GetAttrString(module, "install");
  PyObject* execute = PyObject_GetAttrString(module, "execute_json");
  if (install == nullptr || execute == nullptr || !PyCallable_Check(install) ||
      !PyCallable_Check(execute)) {
    *error = PythonErrorText("Hans runtime bootstrap has no callable entrypoints");
    Py_XDECREF(install);
    Py_XDECREF(execute);
    Py_DECREF(module);
    Py_DECREF(native);
    return false;
  }
  PyObject* result = nullptr;
  if (native != nullptr) {
    result = PyObject_CallOneArg(install, native);
  }
  Py_XDECREF(native);
  Py_DECREF(install);
  if (result == nullptr) {
    *error = PythonErrorText("Hans runtime bootstrap install failed");
    Py_DECREF(execute);
    Py_DECREF(module);
    return false;
  }
  Py_DECREF(result);
  Py_XSETREF(g_execute_json, execute);
  Py_DECREF(module);
  return true;
}

int RaiseCancellation(void*) {
  if (!g_cancel_requested.load(std::memory_order_acquire)) return 0;
  PyErr_SetString(PyExc_KeyboardInterrupt, "Hans Python request cancelled");
  return -1;
}

jstring NativeBootstrap(JNIEnv* env, jclass, jstring config_json) {
  std::string config;
  if (!JavaStringToUtf8(env, config_json, &config)) {
    return Utf8ToJavaString(
        env, ReadinessFailure("invalid_bootstrap_config",
                              "bootstrap config is not valid Unicode"));
  }
  std::lock_guard<std::mutex> interpreter_lock(g_interpreter_mutex);

  std::string native_library_dir;
  std::string expected_version;
  std::string expected_abi;
  std::string expected_digest;
  int64_t source_stdlib_fd = -1;
  int64_t expected_stdlib_bytes = -1;
  std::string error;
  for (const auto& field : {
           std::pair<std::string_view, std::string*>{
               "nativeLibraryDir", &native_library_dir},
           {"expectedPythonVersion", &expected_version},
           {"expectedAbi", &expected_abi},
           {"expectedStdlibDigest", &expected_digest},
       }) {
    if (!hans::json::GetRequiredString(config, field.first, field.second,
                                       &error)) {
      return Utf8ToJavaString(
          env, ReadinessFailure("invalid_bootstrap_config", error));
    }
  }
  if (!hans::json::GetRequiredInteger(config, "stdlibFd", &source_stdlib_fd,
                                      &error) ||
      !hans::json::GetRequiredInteger(config, "expectedStdlibBytes",
                                      &expected_stdlib_bytes, &error)) {
    return Utf8ToJavaString(
        env, ReadinessFailure("invalid_bootstrap_config", error));
  }
  if (expected_version != PY_VERSION || expected_abi != kExpectedAbi ||
      !IsLowerSha256(expected_digest) || source_stdlib_fd < 0 ||
      source_stdlib_fd > INT_MAX || expected_stdlib_bytes <= 0) {
    return Utf8ToJavaString(
        env, ReadinessFailure("runtime_identity_mismatch",
                              "signed runtime identity does not match JNI host"));
  }
  const auto canonical_native = CanonicalPath(native_library_dir);
  if (!canonical_native.has_value() || !IsDirectory(*canonical_native)) {
    return Utf8ToJavaString(
        env, ReadinessFailure("runtime_storage_unavailable",
                              "Python runtime paths are unavailable"));
  }
  if (g_initialized) {
    if (g_stdlib_digest == expected_digest &&
        g_stdlib_bytes == static_cast<uint64_t>(expected_stdlib_bytes) &&
        g_native_library_dir == *canonical_native) {
      return Utf8ToJavaString(env, g_bootstrap_result);
    }
    return Utf8ToJavaString(
        env, ReadinessFailure(
                 "runtime_identity_mismatch",
                 "initialized runtime differs from requested manifest"));
  }
  int verified_stdlib_fd = -1;
  if (!DuplicateVerifiedArchive(
          static_cast<int>(source_stdlib_fd),
          static_cast<uint64_t>(expected_stdlib_bytes), expected_digest,
          &verified_stdlib_fd, &error)) {
    return Utf8ToJavaString(
        env, ReadinessFailure("stdlib_verification_failed", error));
  }
  g_stdlib_fd = verified_stdlib_fd;
  if (!g_stdlib_archive.Open(
          g_stdlib_fd, static_cast<uint64_t>(expected_stdlib_bytes), &error)) {
    close(g_stdlib_fd);
    g_stdlib_fd = -1;
    return Utf8ToJavaString(
        env, ReadinessFailure("stdlib_verification_failed", error));
  }
  g_stdlib_digest = expected_digest;
  g_stdlib_bytes = static_cast<uint64_t>(expected_stdlib_bytes);

  if (!g_builtin_registered) {
    if (PyImport_AppendInittab("_hans_android", PyInit__hans_android) != 0 ||
        PyImport_AppendInittab("_android_support",
                              PyInit__android_support) != 0) {
      g_stdlib_archive.Reset();
      close(g_stdlib_fd);
      g_stdlib_fd = -1;
      g_stdlib_digest.clear();
      g_stdlib_bytes = 0;
      return Utf8ToJavaString(
          env, ReadinessFailure("runtime_bootstrap_failed",
                                "cannot register Android Python bridge"));
    }
    g_builtin_registered = true;
  }
  if (!g_audit_hook_registered) {
    if (PySys_AddAuditHook(HansAuditHook, nullptr) != 0) {
      g_stdlib_archive.Reset();
      close(g_stdlib_fd);
      g_stdlib_fd = -1;
      g_stdlib_digest.clear();
      g_stdlib_bytes = 0;
      return Utf8ToJavaString(
          env, ReadinessFailure("runtime_bootstrap_failed",
                                "cannot register native Python audit boundary"));
    }
    g_audit_hook_registered = true;
  }
  PyImport_FrozenModules = hans::python::kFrozenBootstrap;
  PyConfig python_config;
  PyConfig_InitIsolatedConfig(&python_config);
  python_config.use_environment = 0;
  python_config.user_site_directory = 0;
  python_config.write_bytecode = 0;
  python_config.install_signal_handlers = 0;
  python_config.parse_argv = 0;
  python_config.safe_path = 1;
  python_config.module_search_paths_set = 1;
  PyStatus status = PyConfig_SetBytesString(
      &python_config, &python_config.program_name, "hans-embedded-python");
  if (!PyStatus_Exception(status)) {
    status = PyConfig_SetString(
        &python_config, &python_config.filesystem_encoding, L"utf-8");
  }
  if (!PyStatus_Exception(status)) {
    status = PyConfig_SetString(
        &python_config, &python_config.filesystem_errors, L"surrogateescape");
  }
  if (!PyStatus_Exception(status)) {
    status = PyConfig_SetString(
        &python_config, &python_config.stdio_encoding, L"utf-8");
  }
  if (!PyStatus_Exception(status)) {
    status = PyConfig_SetString(
        &python_config, &python_config.stdio_errors, L"backslashreplace");
  }
  if (!PyStatus_Exception(status)) status = Py_InitializeFromConfig(&python_config);
  PyConfig_Clear(&python_config);
  if (PyStatus_Exception(status)) {
    const std::string detail = status.err_msg == nullptr
                                   ? "CPython initialization failed"
                                   : status.err_msg;
    close(g_stdlib_fd);
    g_stdlib_fd = -1;
    g_stdlib_archive.Reset();
    g_stdlib_digest.clear();
    g_stdlib_bytes = 0;
    return Utf8ToJavaString(
        env, ReadinessFailure("runtime_bootstrap_failed", detail));
  }
  // The C audit hook needs the canonical signed library root while the
  // descriptor importer and bootstrap dispatcher import standard extensions.
  g_native_library_dir = *canonical_native;
  const bool importer_installed = InstallDescriptorImporter(&error);
  if (!importer_installed) {
    error = "descriptor importer: " + error;
  }
  const bool dispatcher_installed =
      importer_installed && CallBootstrapInstall(*canonical_native, &error);
  if (importer_installed && !dispatcher_installed) {
    error = "dispatcher install: " + error;
  }
  const bool imports_verified =
      dispatcher_installed && ImportRequiredModules(&error);
  const bool process_lockdown_installed =
      imports_verified && InstallProcessPrimitiveLockdown(&error);
  if (!importer_installed || !dispatcher_installed || !imports_verified ||
      !process_lockdown_installed) {
    LogError(error);
    Py_XDECREF(g_execute_json);
    g_execute_json = nullptr;
    Py_FinalizeEx();
    g_audit_hook_registered = false;
    g_stdlib_archive.Reset();
    close(g_stdlib_fd);
    g_stdlib_fd = -1;
    g_stdlib_digest.clear();
    g_stdlib_bytes = 0;
    g_native_library_dir.clear();
    return Utf8ToJavaString(
        env, ReadinessFailure("required_import_failed", error));
  }
  g_bootstrap_result =
      std::string("{\"protocolVersion\":1,\"ready\":true,") +
      "\"pythonVersion\":" + hans::json::Quote(PY_VERSION) +
      ",\"abi\":\"arm64-v8a\",\"stdlibDigest\":" +
      hans::json::Quote(expected_digest) +
      ",\"verifiedImports\":[\"_hans_android\",\"_ssl\",\"asyncio\"," +
      "\"json\",\"sqlite3\"],\"writableNativeImports\":false," +
      "\"errorCode\":null,\"detail\":null}";
  g_native_library_dir = *canonical_native;
  g_initialized = true;
  g_cancel_requested.store(false, std::memory_order_release);
  g_main_thread_state = PyEval_SaveThread();
  return Utf8ToJavaString(env, g_bootstrap_result);
}

jstring NativeExecute(JNIEnv* env, jclass, jstring request_json, jobject sink) {
  std::string request;
  if (!JavaStringToUtf8(env, request_json, &request)) {
    return Utf8ToJavaString(
        env, ExecutionFailure("unknown", "INVALID_REQUEST", "invalid_request",
                              "request is not valid Unicode"));
  }
  std::string request_id;
  std::string parse_error;
  if (!hans::json::GetRequiredString(request, "requestId", &request_id,
                                     &parse_error)) {
    return Utf8ToJavaString(
        env, ExecutionFailure("unknown", "INVALID_REQUEST", "invalid_request",
                              parse_error));
  }
  std::string request_environment_digest;
  std::optional<std::string> runtime_lease;
  if (!hans::json::GetRequiredString(request, "environmentDigest",
                                     &request_environment_digest, &parse_error) ||
      !hans::json::GetOptionalObject(request, "runtimeLease", &runtime_lease,
                                    &parse_error) ||
      !runtime_lease.has_value()) {
    return Utf8ToJavaString(
        env, ExecutionFailure(request_id, "RUNTIME_CORRUPT",
                              "missing_runtime_lease", parse_error.empty()
                                  ? "verified environment lease is required"
                                  : parse_error));
  }
  std::string lease_digest;
  int64_t source_environment_fd = -1;
  int64_t environment_bytes = -1;
  if (!hans::json::GetRequiredString(*runtime_lease, "environmentDigest",
                                     &lease_digest, &parse_error) ||
      !hans::json::GetRequiredInteger(*runtime_lease, "environmentFd",
                                      &source_environment_fd, &parse_error) ||
      !hans::json::GetRequiredInteger(*runtime_lease, "environmentBytes",
                                      &environment_bytes, &parse_error) ||
      lease_digest != request_environment_digest ||
      source_environment_fd < 0 || source_environment_fd > INT_MAX ||
      environment_bytes <= 0) {
    return Utf8ToJavaString(
        env, ExecutionFailure(request_id, "RUNTIME_CORRUPT",
                              "invalid_runtime_lease", parse_error.empty()
                                  ? "environment lease does not match request"
                                  : parse_error));
  }
  std::lock_guard<std::mutex> interpreter_lock(g_interpreter_mutex);
  if (!g_initialized || g_main_thread_state == nullptr ||
      g_execute_json == nullptr) {
    return Utf8ToJavaString(
        env, ExecutionFailure(request_id, "RUNTIME_CORRUPT",
                              "runtime_not_initialized",
                              "CPython runtime is not initialized"));
  }
  if (sink == nullptr) {
    return Utf8ToJavaString(
        env, ExecutionFailure(request_id, "INVALID_REQUEST", "invalid_sink",
                              "Python event sink is required"));
  }
  std::string lease_error;
  int verified_environment_fd = -1;
  if (!DuplicateVerifiedArchive(
          static_cast<int>(source_environment_fd),
          static_cast<uint64_t>(environment_bytes), lease_digest,
          &verified_environment_fd, &lease_error)) {
    return Utf8ToJavaString(
        env, ExecutionFailure(request_id, "RUNTIME_CORRUPT",
                              "environment_verification_failed", lease_error));
  }
  g_environment_fd = verified_environment_fd;
  if (!g_environment_archive.Open(
          g_environment_fd, static_cast<uint64_t>(environment_bytes),
          &lease_error)) {
    close(g_environment_fd);
    g_environment_fd = -1;
    return Utf8ToJavaString(
        env, ExecutionFailure(request_id, "RUNTIME_CORRUPT",
                              "environment_verification_failed", lease_error));
  }
  jobject global_sink = env->NewGlobalRef(sink);
  if (global_sink == nullptr) {
    g_environment_archive.Reset();
    close(g_environment_fd);
    g_environment_fd = -1;
    return Utf8ToJavaString(
        env, ExecutionFailure(request_id, "RUNTIME_CORRUPT",
                              "sink_allocation_failed",
                              "cannot retain Python event sink"));
  }
  {
    std::lock_guard<std::mutex> sink_lock(g_sink_mutex);
    g_active_sink = global_sink;
  }
  {
    std::lock_guard<std::mutex> request_lock(g_request_mutex);
    g_active_request_id = request_id;
  }
  g_cancel_requested.store(false, std::memory_order_release);

  PyEval_RestoreThread(g_main_thread_state);
  g_main_thread_state = nullptr;
  g_active_native_authorization.clear();
  std::string response;
  std::vector<NativeModuleAuthorization> request_authorization;
  std::string authority_error;
  const bool authority_valid = ParseRequestNativeAuthorization(
      request, &request_authorization, &authority_error);
  PyObject* result = nullptr;
  if (authority_valid) {
    g_active_native_authorization = std::move(request_authorization);
    PyObject* encoded_request = PyUnicode_DecodeUTF8(
        request.data(), static_cast<Py_ssize_t>(request.size()), "strict");
    result = encoded_request == nullptr
                 ? nullptr
                 : PyObject_CallOneArg(g_execute_json, encoded_request);
    Py_XDECREF(encoded_request);
  } else {
    response = ExecutionFailure(
        request_id, "RUNTIME_CORRUPT", "invalid_native_authority",
        authority_error.empty()
            ? "request native authority could not be independently verified"
            : authority_error);
  }
  if (authority_valid &&
      (result == nullptr || !PythonUnicodeToUtf8(result, &response))) {
    response = ExecutionFailure(
        request_id, "RUNTIME_CORRUPT", "native_dispatch_failed",
        PythonErrorText("Python dispatcher failed outside its result envelope"));
  }
  Py_XDECREF(result);
  // The C audit boundary is request-scoped even when decoding or execution
  // fails. It must be empty before any other request can acquire the runtime.
  g_active_native_authorization.clear();
  PyErr_Clear();
  g_main_thread_state = PyEval_SaveThread();

  {
    std::lock_guard<std::mutex> request_lock(g_request_mutex);
    g_active_request_id.clear();
  }
  g_cancel_requested.store(false, std::memory_order_release);
  {
    std::lock_guard<std::mutex> sink_lock(g_sink_mutex);
    g_active_sink = nullptr;
  }
  env->DeleteGlobalRef(global_sink);
  g_environment_archive.Reset();
  close(g_environment_fd);
  g_environment_fd = -1;
  jstring java_response = Utf8ToJavaString(env, response);
  if (java_response == nullptr) {
    const std::string fallback = ExecutionFailure(
        request_id, "RUNTIME_CORRUPT", "invalid_native_result",
        "Python dispatcher returned invalid UTF-8");
    java_response = Utf8ToJavaString(env, fallback);
  }
  return java_response;
}

jboolean NativeCancel(JNIEnv* env, jclass, jstring request_id_string) {
  std::string request_id;
  if (!JavaStringToUtf8(env, request_id_string, &request_id)) return JNI_FALSE;
  {
    std::lock_guard<std::mutex> request_lock(g_request_mutex);
    if (request_id.empty() || request_id != g_active_request_id) {
      return JNI_FALSE;
    }
  }
  const bool was_requested =
      g_cancel_requested.exchange(true, std::memory_order_acq_rel);
  if (!was_requested && Py_AddPendingCall(RaiseCancellation, nullptr) != 0) {
    g_cancel_requested.store(false, std::memory_order_release);
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

void NativeShutdown(JNIEnv* env, jclass) {
  std::lock_guard<std::mutex> interpreter_lock(g_interpreter_mutex);
  if (!g_initialized) return;
  g_cancel_requested.store(true, std::memory_order_release);
  if (g_main_thread_state != nullptr) {
    PyEval_RestoreThread(g_main_thread_state);
    g_main_thread_state = nullptr;
  }
  Py_CLEAR(g_execute_json);
  const int result = Py_FinalizeEx();
  if (result < 0) LogError("Py_FinalizeEx reported an error");
  // CPython removes process audit hooks at finalization. Hans deliberately
  // recycles the interpreter around third-party native requests, so the next
  // bootstrap must install the non-removable boundary again.
  g_audit_hook_registered = false;
  g_initialized = false;
  g_bootstrap_result.clear();
  if (g_environment_fd >= 0) {
    g_environment_archive.Reset();
    close(g_environment_fd);
    g_environment_fd = -1;
  }
  if (g_stdlib_fd >= 0) {
    g_stdlib_archive.Reset();
    close(g_stdlib_fd);
    g_stdlib_fd = -1;
  }
  g_stdlib_digest.clear();
  g_stdlib_bytes = 0;
  g_native_library_dir.clear();
  g_active_native_authorization.clear();
  {
    std::lock_guard<std::mutex> request_lock(g_request_mutex);
    g_active_request_id.clear();
  }
  g_cancel_requested.store(false, std::memory_order_release);
  std::lock_guard<std::mutex> sink_lock(g_sink_mutex);
  if (g_active_sink != nullptr) {
    env->DeleteGlobalRef(g_active_sink);
    g_active_sink = nullptr;
  }
}

}  // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  g_java_vm = vm;
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
      env == nullptr) {
    return JNI_ERR;
  }
  jclass sink = env->FindClass(kSinkClass);
  if (sink == nullptr) return JNI_ERR;
  g_sink_class = static_cast<jclass>(env->NewGlobalRef(sink));
  env->DeleteLocalRef(sink);
  if (g_sink_class == nullptr) return JNI_ERR;
  g_event_method = env->GetMethodID(g_sink_class, "onNativeEvent",
                                    "(Ljava/lang/String;)V");
  g_capability_method = env->GetMethodID(
      g_sink_class, "onNativeCapabilityRequest",
      "(Ljava/lang/String;)Ljava/lang/String;");
  if (g_event_method == nullptr || g_capability_method == nullptr) return JNI_ERR;

  jclass bridge = env->FindClass(kBridgeClass);
  if (bridge == nullptr) return JNI_ERR;
  JNINativeMethod methods[] = {
      {const_cast<char*>("nativeBootstrap"),
       const_cast<char*>("(Ljava/lang/String;)Ljava/lang/String;"),
       reinterpret_cast<void*>(NativeBootstrap)},
      {const_cast<char*>("nativeExecute"), const_cast<char*>(kExecuteSignature),
       reinterpret_cast<void*>(NativeExecute)},
      {const_cast<char*>("nativeCancel"),
       const_cast<char*>("(Ljava/lang/String;)Z"),
       reinterpret_cast<void*>(NativeCancel)},
      {const_cast<char*>("nativeShutdown"), const_cast<char*>("()V"),
       reinterpret_cast<void*>(NativeShutdown)},
  };
  const jint registered = env->RegisterNatives(
      bridge, methods, static_cast<jint>(sizeof(methods) / sizeof(methods[0])));
  env->DeleteLocalRef(bridge);
  if (registered != JNI_OK) return JNI_ERR;
  return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK &&
      env != nullptr && g_sink_class != nullptr) {
    env->DeleteGlobalRef(g_sink_class);
  }
  g_sink_class = nullptr;
  g_java_vm = nullptr;
}
