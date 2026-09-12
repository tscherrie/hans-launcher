cmake_minimum_required(VERSION 3.22)
project(hans_python_jni LANGUAGES CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "Hans CPython JNI must be cross-compiled with the Android NDK")
endif()
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Hans CPython currently supports only arm64-v8a")
endif()
if(NOT DEFINED HANS_PYTHON_PREFIX)
    message(FATAL_ERROR "HANS_PYTHON_PREFIX is required")
endif()
if(NOT DEFINED HANS_PYTHON_OUTPUT_DIRECTORY)
    message(FATAL_ERROR "HANS_PYTHON_OUTPUT_DIRECTORY is required")
endif()

set(HANS_LIBPYTHON "${HANS_PYTHON_PREFIX}/lib/libpython3.14.so")
set(HANS_PYTHON_INCLUDE "${HANS_PYTHON_PREFIX}/include/python3.14")
set(HANS_NATIVE_PACKAGE_POLICY "${HANS_PYTHON_PREFIX}/include/hans_native_package_policy.h")
if(NOT EXISTS "${HANS_LIBPYTHON}" OR NOT EXISTS "${HANS_PYTHON_INCLUDE}/Python.h")
    message(FATAL_ERROR "Pinned staged CPython prefix is incomplete: ${HANS_PYTHON_PREFIX}")
endif()
if(NOT EXISTS "${HANS_NATIVE_PACKAGE_POLICY}")
    message(FATAL_ERROR "Pinned staged native-package policy is missing: ${HANS_NATIVE_PACKAGE_POLICY}")
endif()

add_library(hans_cpython SHARED IMPORTED GLOBAL)
set_target_properties(hans_cpython PROPERTIES IMPORTED_LOCATION "${HANS_LIBPYTHON}")

add_library(
    hans_python_jni
    SHARED
    hans_json.cpp
    hans_sha256.cpp
    hans_stored_zip.cpp
    hans_frozen_bootstrap.cpp
    hans_python_jni.cpp
)

target_compile_features(hans_python_jni PRIVATE cxx_std_17)
target_compile_options(
    hans_python_jni
    PRIVATE
    -Wall
    -Wextra
    -Werror
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -fstack-protector-strong
)
target_compile_definitions(hans_python_jni PRIVATE PY_SSIZE_T_CLEAN=1)
target_include_directories(
    hans_python_jni
    PRIVATE
    "${HANS_PYTHON_PREFIX}/include"
    "${HANS_PYTHON_INCLUDE}"
    "${CMAKE_CURRENT_SOURCE_DIR}"
)
target_link_options(
    hans_python_jni
    PRIVATE
    -Wl,--build-id=sha1
    -Wl,--no-undefined
    -Wl,-z,relro
    -Wl,-z,now
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)
target_link_libraries(hans_python_jni PRIVATE hans_cpython log android dl)

set_target_properties(
    hans_python_jni
    PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${HANS_PYTHON_OUTPUT_DIRECTORY}"
)
