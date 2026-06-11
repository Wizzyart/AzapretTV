#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define LOG_TAG "AzapretNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL
Java_app_azapret_tv_vpn_NativeProcess_startTun2Socks(
        JNIEnv *env,
        jobject thiz,
        jstring exe_path,
        jint tun_fd,
        jstring proxy_url,
        jstring log_path) {
    (void) thiz;

    const char *exe = (*env)->GetStringUTFChars(env, exe_path, 0);
    const char *proxy = (*env)->GetStringUTFChars(env, proxy_url, 0);
    const char *log = (*env)->GetStringUTFChars(env, log_path, 0);
    if (!exe || !proxy || !log) {
        if (exe) (*env)->ReleaseStringUTFChars(env, exe_path, exe);
        if (proxy) (*env)->ReleaseStringUTFChars(env, proxy_url, proxy);
        if (log) (*env)->ReleaseStringUTFChars(env, log_path, log);
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, exe_path, exe);
        (*env)->ReleaseStringUTFChars(env, proxy_url, proxy);
        (*env)->ReleaseStringUTFChars(env, log_path, log);
        return -1;
    }

    if (pid == 0) {
        int flags = fcntl(tun_fd, F_GETFD);
        if (flags >= 0) {
            fcntl(tun_fd, F_SETFD, flags & ~FD_CLOEXEC);
        }

        int log_fd = open(log, O_WRONLY | O_CREAT | O_APPEND, 0600);
        if (log_fd >= 0) {
            dup2(log_fd, STDOUT_FILENO);
            dup2(log_fd, STDERR_FILENO);
            if (log_fd > STDERR_FILENO) close(log_fd);
        }

        char fd_arg[64];
        snprintf(fd_arg, sizeof(fd_arg), "--device=fd://%d", tun_fd);

        execl(exe,
              exe,
              fd_arg,
              "--proxy", proxy,
              "--mtu", "1500",
              "--loglevel", "warn",
              (char *) NULL);

        LOGE("exec failed: %s", strerror(errno));
        _exit(127);
    }

    close(tun_fd);
    (*env)->ReleaseStringUTFChars(env, exe_path, exe);
    (*env)->ReleaseStringUTFChars(env, proxy_url, proxy);
    (*env)->ReleaseStringUTFChars(env, log_path, log);
    return (jint) pid;
}

JNIEXPORT void JNICALL
Java_app_azapret_tv_vpn_NativeProcess_stopProcess(JNIEnv *env, jobject thiz, jint pid) {
    (void) env;
    (void) thiz;
    if (pid <= 0) return;

    kill((pid_t) pid, SIGTERM);
    for (int i = 0; i < 20; i++) {
        int status = 0;
        pid_t result = waitpid((pid_t) pid, &status, WNOHANG);
        if (result == pid) return;
        usleep(50000);
    }
    kill((pid_t) pid, SIGKILL);
    waitpid((pid_t) pid, NULL, 0);
}

JNIEXPORT jboolean JNICALL
Java_app_azapret_tv_vpn_NativeProcess_isProcessAlive(JNIEnv *env, jobject thiz, jint pid) {
    (void) env;
    (void) thiz;
    if (pid <= 0) return JNI_FALSE;

    int status = 0;
    pid_t result = waitpid((pid_t) pid, &status, WNOHANG);
    if (result == 0) return JNI_TRUE;
    if (result == pid) return JNI_FALSE;

    if (kill((pid_t) pid, 0) == 0) return JNI_TRUE;
    return errno == EPERM ? JNI_TRUE : JNI_FALSE;
}
