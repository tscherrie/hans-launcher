#include <errno.h>
#include <jni.h>
#include <limits.h>
#include <string.h>
#include <unistd.h>

JNIEXPORT jint JNICALL
Java_ai_hans_standard_files_AndroidFileUnlink_nativeContract(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return 1;
}

JNIEXPORT jint JNICALL
Java_ai_hans_standard_files_AndroidFileUnlink_nativeUnlink(JNIEnv *env, jclass type, jbyteArray encoded) {
    (void)type;
    if (encoded == NULL) return EINVAL;
    jsize length = (*env)->GetArrayLength(env, encoded);
    if (length <= 0) return EINVAL;
    if (length >= PATH_MAX) return ENAMETOOLONG;
    char path[PATH_MAX];
    (*env)->GetByteArrayRegion(env, encoded, 0, length, (jbyte *)path);
    if ((*env)->ExceptionCheck(env)) return EINVAL;
    if (path[0] != '/' || memchr(path, '\0', (size_t)length) != NULL) return EINVAL;
    path[length] = '\0';
    // unlink cannot remove a directory. Never fall back to remove/rmdir or retry.
    if (unlink(path) == 0) return 0;
    return errno;
}
