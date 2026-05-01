/*
 * Copyright (C) termx contributors.
 * Licensed under the MIT license (see LICENSE at the repository root).
 *
 * Minimal pseudo-terminal launcher for mosh-client.
 *
 * mosh-client calls tcgetattr(STDIN_FILENO, ...) at startup to save
 * the terminal state for restoration on exit. Launching it with
 * ProcessBuilder wires stdin/stdout/stderr as pipes, so tcgetattr
 * returns ENOTTY ("Inappropriate ioctl for device") and mosh-client
 * bails out 150 ms in. Android's public `android.system.Os` API does
 * not expose openpty or forkpty, so we roll our own from /dev/ptmx.
 *
 * The flow mirrors what termux's terminal-emulator JNI does, minus
 * the bits we don't need (signal forwarding, permission tweaks):
 *
 *   1. open /dev/ptmx with O_CLOEXEC → master fd.
 *   2. grantpt(master) + unlockpt(master) to bless the slave side.
 *   3. ptsname_r(master) → slave device path.
 *   4. open(slave) without O_CLOEXEC so the child inherits it.
 *   5. ioctl(slave, TIOCSWINSZ, ...) with initial (rows, cols).
 *   6. fork(). Parent closes slave, returns master fd. Child does
 *      setsid → TIOCSCTTY → dup2(slave → 0/1/2) → close master → execve.
 *
 * We intentionally close fds 3..1024 in the child before execve so
 * the spawned mosh-client doesn't inherit any of the JVM's open
 * descriptors (Room database files, sshj sockets, etc.). Child-side
 * exit uses _exit so the JVM's atexit handlers don't fire twice.
 */

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <android/log.h>

#define LOG_TAG "termxpty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

/* ========================================================================= */
/* Helpers                                                                    */
/* ========================================================================= */

static int open_pty_pair(int rows, int cols, int *master_out, int *slave_out) {
    int master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) {
        LOGE("open(/dev/ptmx) failed: %s", strerror(errno));
        return -1;
    }
    if (grantpt(master) < 0) {
        LOGE("grantpt failed: %s", strerror(errno));
        close(master);
        return -1;
    }
    if (unlockpt(master) < 0) {
        LOGE("unlockpt failed: %s", strerror(errno));
        close(master);
        return -1;
    }

    char slave_name[128];
    if (ptsname_r(master, slave_name, sizeof(slave_name)) != 0) {
        LOGE("ptsname_r failed: %s", strerror(errno));
        close(master);
        return -1;
    }

    /* Slave fd must NOT have O_CLOEXEC — the child needs to inherit it. */
    int slave = open(slave_name, O_RDWR | O_NOCTTY);
    if (slave < 0) {
        LOGE("open(slave=%s) failed: %s", slave_name, strerror(errno));
        close(master);
        return -1;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(slave, TIOCSWINSZ, &ws);

    *master_out = master;
    *slave_out = slave;
    return 0;
}

/*
 * Convert a Java String[] into a malloc'd, NULL-terminated char**.
 * Returns NULL on allocation failure. `count_out` receives the number
 * of non-NULL entries (i.e. the input array length).
 */
static char **jstring_array_to_cstr_array(JNIEnv *env, jobjectArray jarr, int *count_out) {
    jsize len = (*env)->GetArrayLength(env, jarr);
    char **arr = (char **) calloc((size_t)(len + 1), sizeof(char *));
    if (arr == NULL) {
        *count_out = 0;
        return NULL;
    }
    for (jsize i = 0; i < len; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jarr, i);
        const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
        arr[i] = (utf != NULL) ? strdup(utf) : strdup("");
        (*env)->ReleaseStringUTFChars(env, s, utf);
        (*env)->DeleteLocalRef(env, s);
    }
    arr[len] = NULL;
    *count_out = (int) len;
    return arr;
}

static void free_cstr_array(char **arr, int count) {
    if (arr == NULL) return;
    for (int i = 0; i < count; i++) free(arr[i]);
    free(arr);
}

/* ========================================================================= */
/* JNI entry points                                                           */
/* ========================================================================= */

JNIEXPORT jint JNICALL
Java_dev_kuch_termx_libs_sshnative_impl_NativePty_forkExec(
    JNIEnv *env,
    jclass clazz,
    jstring jPath,
    jobjectArray jArgv,
    jobjectArray jEnvp,
    jint rows,
    jint cols,
    jintArray pidOut
) {
    (void) clazz;

    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    if (path == NULL) return -1;

    int argc = 0, envc = 0;
    char **argv = jstring_array_to_cstr_array(env, jArgv, &argc);
    char **envp = jstring_array_to_cstr_array(env, jEnvp, &envc);
    if (argv == NULL || envp == NULL) {
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        free_cstr_array(argv, argc);
        free_cstr_array(envp, envc);
        return -1;
    }

    int master = -1, slave = -1;
    if (open_pty_pair(rows, cols, &master, &slave) < 0) {
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        free_cstr_array(argv, argc);
        free_cstr_array(envp, envc);
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        close(master);
        close(slave);
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        free_cstr_array(argv, argc);
        free_cstr_array(envp, envc);
        return -1;
    }

    if (pid == 0) {
        /* ---- child ---- */
        /* Break away from the parent's controlling tty + session. */
        if (setsid() < 0) _exit(127);
        /* Make the slave our controlling tty. */
        if (ioctl(slave, TIOCSCTTY, 0) < 0) _exit(127);

        /* Wire stdio to the slave. */
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);

        if (slave > STDERR_FILENO) close(slave);
        close(master);

        /*
         * Close every non-stdio fd we might have inherited from the
         * JVM — Room db handles, sshj sockets, Binder pipes, etc.
         * mosh-client doesn't need any of them and an unexpected fd
         * leak could cause subtle misbehaviour.
         */
        for (int fd = 3; fd < 1024; fd++) close(fd);

        execve(path, argv, envp);
        /* execve only returns on error — the parent handles the status. */
        _exit(127);
    }

    /* ---- parent ---- */
    close(slave);

    (*env)->ReleaseStringUTFChars(env, jPath, path);
    free_cstr_array(argv, argc);
    free_cstr_array(envp, envc);

    jint pidAsInt = (jint) pid;
    (*env)->SetIntArrayRegion(env, pidOut, 0, 1, &pidAsInt);
    return master;
}

/*
 * TIOCSWINSZ on the master fd delivers SIGWINCH to the slave's
 * foreground process group automatically — no manual kill() needed.
 */
JNIEXPORT void JNICALL
Java_dev_kuch_termx_libs_sshnative_impl_NativePty_setWindowSize(
    JNIEnv *env,
    jclass clazz,
    jint fd,
    jint rows,
    jint cols
) {
    (void) env;
    (void) clazz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    if (ioctl((int) fd, TIOCSWINSZ, &ws) < 0) {
        LOGW("TIOCSWINSZ failed: %s", strerror(errno));
    }
}

/*
 * Block until `pid` exits. Returns the exit status decoded as:
 *   - WIFEXITED: the exit code (0..255)
 *   - WIFSIGNALED: 128 + signal number (matches POSIX shell convention)
 *   - any other case: -1
 *
 * Callers must invoke this on a dedicated thread — it blocks
 * indefinitely otherwise.
 */
JNIEXPORT jint JNICALL
Java_dev_kuch_termx_libs_sshnative_impl_NativePty_waitPid(
    JNIEnv *env,
    jclass clazz,
    jint pid
) {
    (void) env;
    (void) clazz;
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t) pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    if (r < 0) return -1;
    if (WIFEXITED(status))   return (jint) WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return (jint) (128 + WTERMSIG(status));
    return -1;
}

/* Send `signal` to `pid`. Returns 0 on success, -1 on error. */
JNIEXPORT jint JNICALL
Java_dev_kuch_termx_libs_sshnative_impl_NativePty_sendSignal(
    JNIEnv *env,
    jclass clazz,
    jint pid,
    jint signal
) {
    (void) env;
    (void) clazz;
    return (jint) kill((pid_t) pid, (int) signal);
}
