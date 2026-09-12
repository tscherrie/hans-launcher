#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char cwd[PATH_MAX];
    const char *expected_runtime_dir = getenv("HANS_RUNTIME_DIR");
    const char *expected_tmp_dir = getenv("HANS_RUNTIME_TMP_DIR");
    const char *home = getenv("HOME");
    const char *tmp_dir = getenv("TMPDIR");

    if (argc != 2 || strcmp(argv[1], "--contract=1") != 0) {
        fprintf(stderr, "expected --contract=1\n");
        return 64;
    }
    if (getcwd(cwd, sizeof(cwd)) == NULL) {
        fprintf(stderr, "getcwd failed: %s\n", strerror(errno));
        return 74;
    }
    if (expected_runtime_dir == NULL || expected_tmp_dir == NULL ||
        home == NULL || tmp_dir == NULL ||
        strcmp(cwd, expected_runtime_dir) != 0 ||
        strcmp(home, expected_runtime_dir) != 0 ||
        strcmp(tmp_dir, expected_tmp_dir) != 0) {
        fprintf(
            stderr,
            "runtime environment is not app-private: cwd=%s expected_cwd=%s "
            "home=%s tmp=%s expected_tmp=%s\n",
            cwd,
            expected_runtime_dir == NULL ? "<null>" : expected_runtime_dir,
            home == NULL ? "<null>" : home,
            tmp_dir == NULL ? "<null>" : tmp_dir,
            expected_tmp_dir == NULL ? "<null>" : expected_tmp_dir);
        return 78;
    }

    if (printf(
            "HANS_NATIVE_PROBE_V1 pid=%ld ppid=%ld abi=arm64-v8a env=private\n",
            (long)getpid(),
            (long)getppid()) < 0) {
        fprintf(stderr, "stdout failed: %s\n", strerror(errno));
        return 74;
    }
    if (fflush(stdout) != 0) {
        fprintf(stderr, "stdout flush failed: %s\n", strerror(errno));
        return 74;
    }
    return 0;
}
