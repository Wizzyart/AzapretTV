#include <getopt.h>
#include <jni.h>
#include <setjmp.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "error.h"
#include "main.h"

extern int server_fd;
static int g_proxy_running = 0;

struct params default_params = {
        .await_int = 10,
        .ipv6 = 1,
        .resolve = 1,
        .udp = 1,
        .max_open = 512,
        .bfsize = 16384,
        .baddr = {
                .in6 = { .sin6_family = AF_INET6 }
        },
        .laddr = {
                .in = { .sin_family = AF_INET }
        },
        .debug = 0
};

void reset_params(void) {
    clear_params(NULL, NULL);
    params = default_params;
}

JNIEXPORT jint JNICALL
Java_app_azapret_tv_vpn_AzapretDpiProxy_jniStartProxy(JNIEnv *env, jobject thiz, jobjectArray args) {
    (void) thiz;
    if (g_proxy_running) return -1;

    int argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc(argc, sizeof(char *));
    if (!argv) return -1;

    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!arg) continue;
        const char *arg_str = (*env)->GetStringUTFChars(env, arg, 0);
        argv[i] = arg_str ? strdup(arg_str) : NULL;
        if (arg_str) (*env)->ReleaseStringUTFChars(env, arg, arg_str);
        (*env)->DeleteLocalRef(env, arg);
    }

    reset_params();
    g_proxy_running = 1;
    optind = 1;
    int result = main(argc, argv);
    g_proxy_running = 0;

    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    return result;
}

JNIEXPORT jint JNICALL
Java_app_azapret_tv_vpn_AzapretDpiProxy_jniStopProxy(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    if (!g_proxy_running || server_fd <= 2) return -1;
    shutdown(server_fd, SHUT_RDWR);
    g_proxy_running = 0;
    return 0;
}

JNIEXPORT jint JNICALL
Java_app_azapret_tv_vpn_AzapretDpiProxy_jniForceClose(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    if (!g_proxy_running || server_fd <= 2) return -1;
    if (close(server_fd) == -1) return -1;
    g_proxy_running = 0;
    return 0;
}
