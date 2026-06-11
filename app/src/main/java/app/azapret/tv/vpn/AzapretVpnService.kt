package app.azapret.tv.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import app.azapret.tv.MainActivity
import app.azapret.tv.R
import app.azapret.tv.config.AppSettings
import app.azapret.tv.diagnostics.AppLog
import io.github.romanvht.byedpi.core.TProxyService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AzapretVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tpwsProcess: Process? = null
    private var tpwsLogThread: Thread? = null
    private var dpiProxyThread: Thread? = null
    private var tun2socksPid: Int = -1
    private var hevConfigFile: File? = null
    private var logFile: File? = null
    private var activeEngine: Engine? = null
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val engine = activeEngine
            val tpwsAlive = if (engine == Engine.DPI) dpiProxyThread?.isAlive == true else isProcessAlive(tpwsProcess)
            val tunAlive = if (engine == Engine.DPI) true else NativeProcess.isProcessAlive(tun2socksPid)
            if (!tpwsAlive || !tunAlive) {
                setStatus("Watchdog: перезапуск обхода tpws=$tpwsAlive tun2socks=$tunAlive")
                stopVpn()
                handler.postDelayed({ startVpn() }, 1200)
                return
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    @Synchronized
    private fun startVpn() {
        try {
            if (running.get()) return
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())

            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            val hostlist = extractAsset("lists/youtube-hosts.txt", "youtube-hosts.txt")
            val logFile = File(filesDir, "azapret-tv.log")
            this.logFile = logFile
            logFile.appendText("\n=== start ${System.currentTimeMillis()} ===\n")
            val profileName = AppSettings.getBypassProfileName(this)
            setStatus("Запуск: ABI=$abi profile=$profileName")
            findInstalledYouTubePackage()?.let { setStatus("YouTube найден: $it") }

            if (isDpiProfile()) {
                activeEngine = Engine.DPI
                val dpiArgs = buildDpiArgs()
                setStatus("ENGINE_START DPI profile=$profileName")
                setStatus("dpi args: ${dpiArgs.drop(1).joinToString(" ")}")
                dpiProxyThread = Thread({
                    val code = AzapretDpiProxy.start(dpiArgs.toTypedArray())
                    runCatching { logFile.appendText("DPI proxy stopped code=$code\n") }
                }, "AzapretDpiProxy")
                dpiProxyThread?.start()
                Thread.sleep(350)
                if (dpiProxyThread?.isAlive != true) {
                    setStatus("Ошибка: DPI proxy не запустился")
                    stopVpn()
                    return
                }
                setStatus("DPI proxy запущен")
            } else {
                activeEngine = Engine.TPWS
                val tpws = nativeExecutable("libtpws.so")
                val tpwsArgs = buildTpwsArgs(tpws.absolutePath, hostlist.absolutePath)
                setStatus("ENGINE_START TPWS profile=$profileName")
                setStatus("tpws args: ${tpwsArgs.drop(1).joinToString(" ")}")
                tpwsProcess = ProcessBuilder(tpwsArgs)
                    .redirectErrorStream(true)
                    .start()
                tpwsLogThread = drainProcessOutput(tpwsProcess, logFile)

                Thread.sleep(250)
                if (!isProcessAlive(tpwsProcess)) {
                    setStatus("Ошибка: tpws не запустился")
                    stopVpn()
                    return
                }
                setStatus("tpws запущен")
            }

            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(1500)
                .addAddress("10.215.0.2", 32)
                .addRoute("0.0.0.0", 0)

            val allowedApps = addYouTubeOnlyApps(builder)
            if (allowedApps <= 0) {
                setStatus("Ошибка: YouTube/браузер не найден, VPN не запущен")
                stopVpn()
                return
            }
            setStatus("Режим VPN: только YouTube, apps=$allowedApps")

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                setStatus("Ошибка: Android не создал VPN-интерфейс")
                stopVpn()
                return
            }

            if (isDpiProfile()) {
                val fd = vpnInterface?.fd
                if (fd == null) {
                    setStatus("Ошибка: не получен TUN fd")
                    stopVpn()
                    return
                }
                val config = createHevConfig()
                hevConfigFile = config
                TProxyService.TProxyStartService(config.absolutePath, fd)
                running.set(dpiProxyThread?.isAlive == true)
            } else {
                val tun2socks = nativeExecutable("libtun2socks.so")
                val fd = vpnInterface?.detachFd()
                if (fd == null) {
                    setStatus("Ошибка: не получен TUN fd")
                    stopVpn()
                    return
                }
                vpnInterface = null
                tun2socksPid = NativeProcess.startTun2Socks(
                    tun2socks.absolutePath,
                    fd,
                    "socks5://127.0.0.1:1080",
                    logFile.absolutePath
                )
                running.set(isProcessAlive(tpwsProcess) && tun2socksPid > 0)
            }
            AppSettings.setVpnEnabled(this, running.get())
            if (running.get()) {
                setStatus("Подключено: YouTube идёт через Azapret")
                startWatchdog()
            } else {
                setStatus("Ошибка: tunnel не запустился")
                stopVpn()
            }
        } catch (e: Exception) {
            setStatus("Ошибка: ${e.javaClass.simpleName}: ${e.message ?: "без сообщения"}")
            stopVpn()
        }
    }

    @Synchronized
    private fun stopVpn() {
        handler.removeCallbacks(watchdog)
        activeEngine?.let { setStatus("ENGINE_STOP $it") }
        running.set(false)
        AppSettings.setVpnEnabled(this, false)
        if (tun2socksPid > 0) {
            NativeProcess.stopProcess(tun2socksPid)
        }
        tun2socksPid = -1
        runCatching { TProxyService.TProxyStopService() }
        runCatching { hevConfigFile?.delete() }
        hevConfigFile = null
        if (dpiProxyThread != null) {
            runCatching { AzapretDpiProxy.stop() }
            runCatching { AzapretDpiProxy.forceClose() }
            runCatching { dpiProxyThread?.join(1200) }
        }
        dpiProxyThread = null
        tpwsProcess?.destroy()
        tpwsProcess = null
        tpwsLogThread = null
        vpnInterface?.close()
        vpnInterface = null
        activeEngine = null
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun startWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun setStatus(status: String) {
        AppSettings.setLastStatus(this, status)
        AppLog.append(this, "VPN $status")
        runCatching { logFile?.appendText("$status\n") }
    }

    private fun extractAsset(assetPath: String, fileName: String): File {
        val file = File(filesDir, fileName)
        assets.open(assetPath).use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return file
    }

    private fun nativeExecutable(fileName: String): File {
        val file = File(applicationInfo.nativeLibraryDir, fileName)
        if (!file.exists()) throw IllegalStateException("native executable missing: $fileName")
        return file
    }

    private fun isProcessAlive(process: Process?): Boolean {
        if (process == null) return false
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun drainProcessOutput(process: Process?, outputFile: File): Thread? {
        if (process == null) return null
        return Thread({
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (outputFile.length() > MAX_NATIVE_LOG_BYTES) outputFile.writeText(outputFile.readText().takeLast(MAX_NATIVE_LOG_BYTES / 2))
                        outputFile.appendText("$line\n")
                    }
                }
            }
        }, "AzapretTpwsLog").apply { start() }
    }

    private fun isDpiProfile(): Boolean = AppSettings.getBypassProfile(this) in listOf(6, 7, 8, 10, 11, 12, 13, 14, 15)

    private fun buildTpwsArgs(tpwsPath: String, hostlistPath: String): List<String> {
        val base = mutableListOf(
            tpwsPath,
            "--socks",
            "--bind-addr=127.0.0.1",
            "--port=1080",
            "--hostlist=$hostlistPath"
        )
        when (AppSettings.getBypassProfile(this)) {
            1 -> base += listOf(
                "--split-pos=1",
                "--disorder=tls"
            )
            2 -> base += listOf(
                "--split-pos=2,sniext+1",
                "--disorder=tls"
            )
            3 -> base += listOf(
                "--split-pos=sniext+1",
                "--oob=tls"
            )
            4 -> base += listOf(
                "--mss=88",
                "--split-pos=1,midsld",
                "--disorder=tls"
            )
            9 -> base += listOf(
                "--split-pos=sniext+1",
                "--oob=tls"
            )
            else -> base += listOf(
                "--split-pos=1,midsld",
                "--disorder=tls"
            )
        }
        return base
    }

    private fun buildDpiArgs(): List<String> {
        val args = mutableListOf("ciadpi", "--ip", "127.0.0.1", "--port", "1080")
        when (AppSettings.getBypassProfile(this)) {
            7 -> args += listOf("-o1", "-a1", "-At,r,s", "-f-1", "-a1", "-At,r,s", "-d1", "-n", "www.youtube.com", "-Qr", "-f1", "-d1:11+sm", "-s1:11+sm", "-S", "-a1")
            8 -> args += listOf("-o1", "-r-5+se", "-a1", "-At,r,s", "-d1", "-n", "manifest.googlevideo.com", "-Qr", "-f-1", "-a1")
            10 -> args += listOf("-o1", "-a1", "-At,r,s", "-d1", "-n", "www.youtube.com", "-Qr", "-f-1", "-a1")
            11 -> args += listOf("-o1", "-r-5+se", "-a1", "-At,r,s", "-d1", "-n", "googlevideo.com", "-Qr", "-f-1", "-a1")
            12 -> args += listOf("-o1", "-a1", "-r-5+se")
            13 -> args += listOf("-o1", "-r-5+se", "-a1", "-At,r,s", "-d1", "-n", "googlevideo.com", "-n", "manifest.googlevideo.com", "-n", "www.youtube.com", "-Qr", "-f-1", "-a1")
            14 -> args += listOf("-Kt", "-H:youtube.com manifest.googlevideo.com googlevideo.com", "-o1", "-a1", "-r-5+se")
            15 -> args += listOf("-s1", "-q1", "-a1", "-At,r,s", "-f-1", "-r1+s", "-a1")
            else -> args += listOf("-o1", "-a1", "-r-5+se")
        }
        return args
    }

    private fun createHevConfig(): File {
        val config = File(cacheDir, "azapret-hev.yml")
        config.writeText(
            """
            tunnel:
              mtu: 1500
            misc:
              task-stack-size: 81920
            socks5:
              address: 127.0.0.1
              port: 1080
              udp: udp
            """.trimIndent()
        )
        return config
    }

    private fun addYouTubeOnlyApps(builder: Builder): Int {
        val youtubePackages = listOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube",
            "com.google.android.youtube.googletv",
            "com.google.android.youtube.oem",
            "com.google.android.apps.youtube.kids",
            "com.google.android.apps.youtube.music",
            "com.google.android.youtube.tvmusic",
            "com.yandex.tv.ytplayer"
        )
        val knownBrowserPackages = listOf(
            "com.yandex.browser",
            "com.yandex.browser.tv",
            "com.android.chrome",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "com.opera.browser",
            "com.opera.browser.beta",
            "com.opera.mini.native",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.vivaldi.browser",
            "com.duckduckgo.mobile.android"
        )
        val viewIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/"))
        val detectedBrowserPackages = packageManager.queryIntentActivities(viewIntent, 0)
            .mapNotNull { it.activityInfo?.packageName }
        val browserPackages = (knownBrowserPackages + detectedBrowserPackages).distinct()
        val helperPackages = mutableListOf(
            "com.google.android.gms",
            "com.google.android.apps.mediashell",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tv",
            "com.yandex.tv.videoplayer",
            "com.yandex.tv.player",
            "com.yandex.tv.media",
            "com.yandex.tv.webview",
            "com.yandex.tv.services",
            "com.yandex.tv.service",
            "com.yandex.tv.home",
            "com.yandex.tv.alice",
            "com.yandex.alice",
            "com.yandex.io.sdk",
            "ru.yandex.searchplugin"
        )
        val installedYoutube = youtubePackages.filter(::isPackageInstalled)
        val installedBrowsers = browserPackages.filter(::isPackageInstalled)
        if (installedYoutube.isEmpty()) {
            setStatus("VPN allowlist: пакет YouTube не найден, безопасный режим браузера")
            var browserOnlyAdded = 0
            for (pkg in installedBrowsers) {
                try {
                    builder.addAllowedApplication(pkg)
                    browserOnlyAdded++
                    setStatus("VPN allowlist: $pkg")
                } catch (_: Exception) {
                    // Browser package may be hidden by TV firmware.
                }
            }
            return browserOnlyAdded
        }

        var added = 0
        for (pkg in installedYoutube + installedBrowsers) {
            try {
                builder.addAllowedApplication(pkg)
                added++
                setStatus("VPN allowlist: $pkg")
            } catch (_: Exception) {
                // The package may be absent on this TV model.
            }
        }
        if (added > 0) {
            for (pkg in helperPackages.distinct()) {
                if (!isPackageInstalled(pkg)) continue
                try {
                    builder.addAllowedApplication(pkg)
                    added++
                    setStatus("VPN allowlist: $pkg")
                } catch (_: Exception) {
                    // Helper package may be absent or hidden on some TV firmware.
                }
            }
        }
        return added
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun findInstalledYouTubePackage(): String? {
        val packages = listOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube.googletv",
            "com.google.android.youtube.oem",
            "com.yandex.tv.ytplayer",
            "com.google.android.youtube"
        )
        for (pkg in packages) {
            try {
                val info = packageManager.getPackageInfo(pkg, 0)
                return "$pkg ${info.versionName ?: "unknown"}"
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private enum class Engine { TPWS, DPI }

    companion object {
        private const val ACTION_START = "app.azapret.tv.START"
        private const val ACTION_STOP = "app.azapret.tv.STOP"
        private const val CHANNEL_ID = "azapret_tv_vpn"
        private const val NOTIFICATION_ID = 2150
        private const val WATCHDOG_INTERVAL_MS = 15000L
        private const val MAX_NATIVE_LOG_BYTES = 96 * 1024

        private val running = AtomicBoolean(false)
        val isRunning: Boolean get() = running.get()

        fun start(context: Context) {
            val intent = Intent(context, AzapretVpnService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            running.set(false)
            AppSettings.setVpnEnabled(context, false)
            context.startService(Intent(context, AzapretVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
