package app.azapret.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.azapret.tv.config.AppSettings
import app.azapret.tv.diagnostics.AppLog
import app.azapret.tv.vpn.AzapretVpnService
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var profileButton: Button
    private lateinit var autoStartButton: Button
    private lateinit var openYoutubeButton: Button
    private lateinit var youtubeTestButton: Button
    private lateinit var applyBestButton: Button
    private lateinit var logButton: Button
    private lateinit var hintText: TextView
    private lateinit var debugText: TextView
    private var strategyTestOpened = false
    private var strategyTestHintActive = false
    private var strategyTestHintPulse = false
    private val strategyTestHintRunnable = object : Runnable {
        override fun run() {
            if (!::youtubeTestButton.isInitialized || strategyTestOpened) {
                strategyTestHintActive = false
                updateButtonBackgrounds()
                return
            }
            strategyTestHintActive = true
            strategyTestHintPulse = !strategyTestHintPulse
            updateButtonBackgrounds()
            youtubeTestButton.postDelayed(this, STRATEGY_HINT_PULSE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        clearLegacyAutoTestStatus()
        updateStatus()
        scheduleStrategyTestHint()
    }

    override fun onDestroy() {
        if (::youtubeTestButton.isInitialized) youtubeTestButton.removeCallbacks(strategyTestHintRunnable)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if ((AppSettings.isVpnEnabled(this) || AppSettings.isAutoStartEnabled(this)) && !AzapretVpnService.isRunning && VpnService.prepare(this) == null) {
            AzapretVpnService.start(this)
        }
        updateStatus()
    }

    private fun buildLayout(): View {
        val root = ScrollView(this).apply { background = bgDrawable() }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(56), dp(38), dp(56), dp(34))
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 36f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = getString(R.string.subtitle)
            textSize = 18f
            setTextColor(BLUE_SOFT)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }
        statusText = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        toggleButton = Button(this).apply {
            textSize = 21f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(dp(250), dp(250))
            gravity = Gravity.CENTER
            setTextColor(0xFF10131A.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
            background = circleButton(false)
            setOnFocusChangeListener { view, hasFocus ->
                view.animate().scaleX(if (hasFocus) 1.08f else 1f).scaleY(if (hasFocus) 1.08f else 1f).setDuration(120).start()
                updateButtonBackgrounds()
            }
            setOnClickListener { toggleVpn() }
        }
        hintText = TextView(this).apply {
            text = getString(R.string.tap_hint)
            textSize = 16f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 12)
        }
        debugText = TextView(this).apply {
            text = getString(R.string.debug_waiting)
            textSize = 14f
            setTextColor(0xFF8EA4BD.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        profileButton = createPillButton().apply { setOnClickListener { toggleBypassProfile() } }
        autoStartButton = createPillButton().apply {
            setOnClickListener { toggleAutoStart() }
        }
        openYoutubeButton = createPillButton().apply {
            text = getString(R.string.open_youtube)
            setOnClickListener { openYouTube() }
        }
        youtubeTestButton = createPillButton().apply {
            text = "Подобрать профиль"
            setOnClickListener { openStrategyTest() }
            setOnFocusChangeListener { view, hasFocus ->
                view.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(120).start()
                updateButtonBackgrounds()
            }
        }
        applyBestButton = createPillButton().apply {
            text = "Поставить найденную"
            setOnClickListener { applyBestStrategy() }
        }
        logButton = createPillButton().apply {
            text = getString(R.string.open_log)
            setOnClickListener { showLogDialog() }
        }
        val note = TextView(this).apply {
            text = getString(R.string.mvp_note)
            textSize = 15f
            setTextColor(0xFF75879C.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }

        panel.addView(title)
        panel.addView(subtitle)
        panel.addView(statusText)
        panel.addView(toggleButton)
        panel.addView(hintText)
        panel.addView(debugText)
        panel.addView(openYoutubeButton)
        panel.addView(youtubeTestButton)
        panel.addView(applyBestButton)
        panel.addView(profileButton)
        panel.addView(autoStartButton)
        panel.addView(logButton)
        panel.addView(note)
        root.addView(panel)
        return root
    }

    private fun createPillButton(): Button = Button(this).apply {
        textSize = 16f
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(dp(360), dp(54)).apply { topMargin = dp(9) }
        setPadding(dp(18), dp(8), dp(18), dp(8))
        setTextColor(Color.WHITE)
        background = roundedButton()
        isFocusable = true
        isFocusableInTouchMode = true
        setOnFocusChangeListener { view, hasFocus ->
            view.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(120).start()
            view.background = roundedButton(hasFocus)
        }
    }

    private fun toggleVpn() {
        if (AzapretVpnService.isRunning) {
            AzapretVpnService.stop(this)
            AppSettings.setVpnEnabled(this, false)
            updateStatus()
            refreshStatusSoon()
            return
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) startActivityForResult(prepareIntent, VPN_REQUEST_CODE) else startVpn()
    }

    @Deprecated("Deprecated by Android Activity API, acceptable for minimal TV MVP.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) startVpn()
        if (requestCode == AUTO_START_REQUEST_CODE && resultCode == RESULT_OK) enableAutoStart()
    }

    private fun startVpn() {
        AppLog.append(this, "UI start pressed profile=${AppSettings.getBypassProfileName(this)}")
        AppSettings.setVpnEnabled(this, true)
        AzapretVpnService.start(this)
        updateStatus()
        refreshStatusSoon()
    }

    private fun refreshStatusSoon() {
        toggleButton.postDelayed({ updateStatus() }, 1000)
        toggleButton.postDelayed({ updateStatus() }, 3000)
    }

    private fun scheduleStrategyTestHint() {
        youtubeTestButton.removeCallbacks(strategyTestHintRunnable)
        youtubeTestButton.postDelayed(strategyTestHintRunnable, STRATEGY_HINT_DELAY_MS)
    }

    private fun clearLegacyAutoTestStatus() {
        if (AppSettings.getLastStatus(this).startsWith("Первый запуск:")) {
            AppSettings.setLastStatus(this, "Выберите профиль или нажмите Подобрать профиль")
        }
    }

    private fun openStrategyTest() {
        strategyTestOpened = true
        strategyTestHintActive = false
        youtubeTestButton.removeCallbacks(strategyTestHintRunnable)
        updateButtonBackgrounds()
        startActivity(Intent(this, StrategyTestActivity::class.java))
    }

    private fun toggleBypassProfile() {
        val profiles = AppSettings.BYPASS_PROFILES
        val currentIndex = profiles.indexOf(AppSettings.getBypassProfile(this)).takeIf { it >= 0 } ?: 0
        val nextProfile = profiles[(currentIndex + 1) % profiles.size]
        AppSettings.setBypassProfile(this, nextProfile)
        AppSettings.setHardStep(this, 0)
        AppLog.append(this, "UI profile changed ${AppSettings.getBypassProfileName(this)}")
        if (AzapretVpnService.isRunning) {
            AzapretVpnService.stop(this)
            AppSettings.setVpnEnabled(this, true)
            profileButton.postDelayed({ AzapretVpnService.start(this) }, 1500)
            refreshStatusSoon()
        }
        updateStatus()
    }

    private fun applyBestStrategy() {
        val bestProfile = AppSettings.getBestTestProfile(this)
        if (bestProfile !in AppSettings.BYPASS_PROFILES) {
            AppSettings.setLastStatus(this, "Сначала нажмите Подобрать профиль")
            updateStatus()
            return
        }
        AppSettings.setBypassProfile(this, bestProfile)
        val summary = AppSettings.getBestTestSummary(this).ifBlank { "Лучшая стратегия применена" }
        AppLog.append(this, "UI apply best ${AppSettings.getBypassProfileName(this)} summary=$summary")
        AppSettings.setLastStatus(this, "Выбран профиль: ${AppSettings.getBypassProfileName(this)}\n$summary")
        if (AzapretVpnService.isRunning) {
            AzapretVpnService.stop(this)
            AppSettings.setVpnEnabled(this, true)
            applyBestButton.postDelayed({ AzapretVpnService.start(this) }, 1500)
            refreshStatusSoon()
        }
        updateStatus()
    }

    private fun toggleAutoStart() {
        if (AppSettings.isAutoStartEnabled(this)) {
            AppSettings.setAutoStartEnabled(this, false)
            AppSettings.setVpnEnabled(this, false)
            AppLog.append(this, "UI autostart disabled")
            if (AzapretVpnService.isRunning) AzapretVpnService.stop(this)
            AppSettings.setLastStatus(this, getString(R.string.autostart_stopped_status))
            updateStatus()
            refreshStatusSoon()
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, AUTO_START_REQUEST_CODE)
        } else {
            enableAutoStart()
        }
    }

    private fun enableAutoStart() {
        AppSettings.setAutoStartEnabled(this, true)
        AppSettings.setVpnEnabled(this, true)
        AppLog.append(this, "UI autostart enabled profile=${AppSettings.getBypassProfileName(this)}")
        AppSettings.setLastStatus(this, getString(R.string.autostart_enabled_status))
        if (!AzapretVpnService.isRunning) AzapretVpnService.start(this)
        updateStatus()
        refreshStatusSoon()
    }

    private fun openYouTube() {
        val packages = listOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube.googletv",
            "com.yandex.tv.ytplayer",
            "com.yandex.tv.videoplayer",
            "com.yandex.browser.tv"
        )
        val launchIntent = packages.asSequence()
            .mapNotNull { packageManager.getLaunchIntentForPackage(it) }
            .firstOrNull()

        if (launchIntent == null) {
            AppSettings.setLastStatus(this, getString(R.string.youtube_not_found_status))
            AppLog.append(this, "UI open YouTube failed: package not found")
            updateStatus()
            return
        }

        AppLog.append(this, "UI open YouTube")
        startActivity(launchIntent)
    }

    private fun runHardNetworkTest() {
        AppLog.append(this, "TEST hard step start step=${AppSettings.getHardStep(this) + 1}")
        debugText.text = getString(R.string.hard_network_test_running)
        thread(name = "AzapretHardNetworkTest") {
            val step = AppSettings.getHardStep(this)
            val header = "STEP ${step + 1}/8 VPN=${if (AzapretVpnService.isRunning) "ON" else "OFF"} profile=${AppSettings.getBypassProfileName(this)}"
            val line = try {
                when (step) {
                    0 -> "$header\nports 1080=${testLocalPort(1080)}"
                    1 -> "$header\nwww.youtube.com DNS ${shortResult(testDns("www.youtube.com"))}"
                    2 -> "$header\nwww.youtube.com DIRECT ${shortResult(testDirectTls("www.youtube.com", "/generate_204"))}"
                    3 -> "$header\nwww.youtube.com SOCKS1080 ${shortResult(testTargetThroughProxy("www.youtube.com", "/generate_204", 1080))}"
                    4 -> "$header\nyoutubei.googleapis.com DNS ${shortResult(testDns("youtubei.googleapis.com"))}"
                    5 -> "$header\nyoutubei.googleapis.com SOCKS1080 ${shortResult(testTargetThroughProxy("youtubei.googleapis.com", "/generate_204", 1080))}"
                    6 -> "$header\nredirector.googlevideo.com DNS ${shortResult(testDns("redirector.googlevideo.com"))}"
                    else -> "$header\nredirector.googlevideo.com SOCKS1080 ${shortResult(testTargetThroughProxy("redirector.googlevideo.com", "/generate_204", 1080))}"
                }
            } catch (t: Throwable) {
                "$header\nHARD CRASH ${t.javaClass.simpleName}: ${t.message ?: "без сообщения"}"
            }
            AppSettings.setHardStep(this, if (step >= 7) 0 else step + 1)
            runOnUiThread {
                AppLog.append(this, "TEST hard result ${line.replace("\n", " | ")}")
                AppSettings.setLastStatus(this, line)
                debugText.text = line
            }
        }
    }

    private fun runFullYoutubeDiagnostics() {
        AppLog.append(this, "YOUTUBE TEST start")
        debugText.text = getString(R.string.youtube_test_running)
        youtubeTestButton.isEnabled = false
        thread(name = "AzapretFullYoutubeDiagnostics") {
            val report = buildString {
                appendLine("YOUTUBE TEST")
                appendLine("VPN=${if (AzapretVpnService.isRunning) "ON" else "OFF"} profile=${AppSettings.getBypassProfileName(this@MainActivity)}")
                appendLine("ABI=${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")
                appendLine("IPv6=${hasIpv6Address()} IPv4=${hasIpv4Address()}")
                appendLine("packages=${youtubePackagesStatus()}")
                appendLine("youtubeTv=${packageVersion("com.google.android.youtube.tv")}")
                appendLine("yandexYt=${packageVersion("com.yandex.tv.ytplayer")}")
                appendLine("discovered=${discoverVideoPackages()}")
                appendLine("launchers=${discoverLauncherPackages()}")
                appendLine("local 1080=${testLocalPort(1080)}")

                val targets = listOf(
                    "www.youtube.com" to "/generate_204",
                    "youtubei.googleapis.com" to "/generate_204",
                    "redirector.googlevideo.com" to "/generate_204",
                    "i.ytimg.com" to "/generate_204",
                    "yt3.ggpht.com" to "/generate_204",
                    "android.clients.google.com" to "/generate_204",
                    "www.googleapis.com" to "/generate_204"
                )

                for ((host, path) in targets) {
                    appendLine("DNS $host ${testDns(host)}")
                    appendLine("TCP $host ${testTcp(host, 443)}")
                    appendLine("TLS $host ${testDirectTls(host, path)}")
                    appendLine("SOCKS $host ${testTargetThroughProxy(host, path, 1080)}")
                }

                appendLine("UDP443 www.youtube.com ${testUdpSend("www.youtube.com", 443)}")
                appendLine("UDP443 redirector.googlevideo.com ${testUdpSend("redirector.googlevideo.com", 443)}")
            }
            runOnUiThread {
                AppLog.append(this, report.replace("\n", " | "))
                AppSettings.setLastStatus(this, report)
                debugText.text = report.lines().take(10).joinToString("\n")
                youtubeTestButton.isEnabled = true
                updateStatus()
            }
        }
    }

    private fun showLogDialog() {
        val textView = TextView(this).apply {
            text = AppLog.readTail(this@MainActivity)
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val scrollView = ScrollView(this).apply {
            background = bgDrawable()
            addView(textView)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.open_log))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.close_log), null)
            .setNegativeButton(getString(R.string.clear_log)) { _, _ ->
                AppLog.clear(this)
                AppSettings.setLastStatus(this, "Журнал очищен")
                updateStatus()
            }
            .show()
    }

    private fun testLocalPort(port: Int): String = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1200) }
        "OPEN"
    } catch (e: Exception) {
        "CLOSED ${e.javaClass.simpleName}"
    }

    private fun testTcp(host: String, port: Int): String = try {
        val started = System.currentTimeMillis()
        Socket().use {
            it.soTimeout = 3000
            it.connect(InetSocketAddress(host, port), 3000)
        }
        "OK ${System.currentTimeMillis() - started}ms"
    } catch (e: Exception) {
        "FAIL ${e.javaClass.simpleName}: ${e.message ?: "без сообщения"}"
    }

    private fun testUdpSend(host: String, port: Int): String = try {
        val started = System.currentTimeMillis()
        val address = InetAddress.getByName(host)
        DatagramSocket().use { socket ->
            socket.soTimeout = 1200
            socket.connect(address, port)
            val data = byteArrayOf(0)
            socket.send(DatagramPacket(data, data.size, address, port))
        }
        "SEND_OK ${System.currentTimeMillis() - started}ms ${address.hostAddress}"
    } catch (e: Exception) {
        "FAIL ${e.javaClass.simpleName}: ${e.message ?: "без сообщения"}"
    }

    private fun testDns(host: String): String = try {
        val addresses = InetAddress.getAllByName(host).take(2).joinToString(",") { it.hostAddress ?: "no-ip" }
        if (addresses.isBlank()) "FAIL no-ip" else "OK $addresses"
    } catch (e: Exception) {
        "FAIL ${e.javaClass.simpleName}: ${e.message ?: "без сообщения"}"
    }

    private fun testDirectTls(host: String, path: String): String = testTls(host, path, null)

    private fun testTargetThroughProxy(host: String, path: String, proxyPort: Int): String =
        testTls(host, path, Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort)))

    private fun testTls(host: String, path: String, proxy: Proxy?): String = try {
        val started = System.currentTimeMillis()
        val rawSocket = if (proxy == null) Socket() else Socket(proxy)
        rawSocket.soTimeout = 3000
        val address = if (proxy == null) InetSocketAddress(host, 443) else InetSocketAddress.createUnresolved(host, 443)
        rawSocket.connect(address, 3000)
        val tlsSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(rawSocket, host, 443, true)
        tlsSocket.soTimeout = 3000
        tlsSocket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: AzapretTV/0.1\r\nConnection: close\r\n\r\n".toByteArray())
        tlsSocket.getOutputStream().flush()
        val firstLine = tlsSocket.getInputStream().bufferedReader().readLine() ?: "NO_RESPONSE"
        tlsSocket.close()
        "OK ${System.currentTimeMillis() - started}ms $firstLine"
    } catch (e: Exception) {
        "FAIL ${e.javaClass.simpleName}: ${e.message ?: "без сообщения"}"
    }

    private fun shortResult(value: String): String =
        value.replace('\n', ' ').let { if (it.length > 72) it.take(72) + "..." else it }

    private fun youtubePackagesStatus(): String {
        val packages = listOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube",
            "com.google.android.youtube.googletv",
            "com.google.android.youtube.oem",
            "com.google.android.apps.youtube.kids",
            "com.google.android.apps.youtube.music",
            "com.google.android.youtube.tvmusic",
            "com.yandex.tv.ytplayer",
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
            "ru.yandex.searchplugin",
            "com.google.android.gms",
            "com.android.vending",
            "com.yandex.browser",
            "com.yandex.browser.tv",
            "com.android.chrome",
            "org.mozilla.firefox"
        )
        return packages.joinToString(",") { pkg -> "$pkg=${if (isPackageInstalled(pkg)) "1" else "0"}" }
    }

    private fun discoverVideoPackages(): String = try {
        val needles = listOf("youtube", "yt", "google", "video", "player", "browser", "webview", "yandex", "vk", "rutube")
        val matches = packageManager.getInstalledPackages(0)
            .asSequence()
            .map { it.packageName }
            .filter { pkg -> needles.any { needle -> pkg.contains(needle, ignoreCase = true) } }
            .take(40)
            .toList()
        if (matches.isEmpty()) "none" else matches.joinToString(",")
    } catch (e: Exception) {
        "FAIL ${e.javaClass.simpleName}: ${e.message ?: "без сообщения"}"
    }

    private fun discoverLauncherPackages(): String = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(intent, 0)
            .asSequence()
            .map { info ->
                val label = info.loadLabel(packageManager)?.toString()?.replace(',', ' ') ?: "no-label"
                "${info.activityInfo.packageName}[$label]"
            }
            .filter { entry ->
                listOf("youtube", "yt", "ютуб", "google", "video", "видео", "player", "browser", "браузер", "webview", "yandex", "яндекс").any { needle ->
                    entry.contains(needle, ignoreCase = true)
                }
            }
            .take(50)
            .toList()
        if (matches.isEmpty()) "none" else matches.joinToString(",")
    } catch (e: Exception) {
        "FAIL ${e.javaClass.simpleName}: ${e.message ?: "без сообщения"}"
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun packageVersion(packageName: String): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        val versionName = info.versionName ?: "unknown"
        "$packageName=$versionName/${info.versionCode}"
    } catch (_: Exception) {
        "$packageName=not-installed"
    }

    private fun hasIpv4Address(): Boolean = hasAddress { it.hostAddress?.contains('.') == true }

    private fun hasIpv6Address(): Boolean = hasAddress { address ->
        val text = address.hostAddress ?: return@hasAddress false
        text.contains(':') && !address.isLoopbackAddress && !address.isLinkLocalAddress
    }

    private fun hasAddress(predicate: (InetAddress) -> Boolean): Boolean = try {
        NetworkInterface.getNetworkInterfaces().toList().any { iface ->
            iface.isUp && !iface.isLoopback && iface.inetAddresses.toList().any(predicate)
        }
    } catch (_: Exception) {
        false
    }

    private fun updateStatus() {
        val running = AzapretVpnService.isRunning
        statusText.text = if (running) getString(R.string.status_on) else getString(R.string.status_off)
        toggleButton.text = if (running) getString(R.string.turn_off_round) else getString(R.string.turn_on_round)
        hintText.text = if (running) getString(R.string.connected_hint) else getString(R.string.tap_hint)
        debugText.text = AppSettings.getLastStatus(this).ifBlank { getString(R.string.debug_waiting) }
        profileButton.text = "Профиль: ${AppSettings.getBypassProfileName(this)}"
        autoStartButton.text = if (AppSettings.isAutoStartEnabled(this)) getString(R.string.autostart_on) else getString(R.string.autostart_off)
        val bestProfile = AppSettings.getBestTestProfile(this)
        applyBestButton.isEnabled = bestProfile in AppSettings.BYPASS_PROFILES
        applyBestButton.alpha = if (applyBestButton.isEnabled) 1f else 0.55f
        updateButtonBackgrounds()
    }

    private fun updateButtonBackgrounds() {
        if (::toggleButton.isInitialized) toggleButton.background = circleButton(AzapretVpnService.isRunning, toggleButton.hasFocus())
        if (::profileButton.isInitialized) profileButton.background = roundedButton(profileButton.hasFocus())
        if (::autoStartButton.isInitialized) autoStartButton.background = roundedButton(autoStartButton.hasFocus())
        if (::openYoutubeButton.isInitialized) openYoutubeButton.background = roundedButton(openYoutubeButton.hasFocus())
        if (::youtubeTestButton.isInitialized) {
            youtubeTestButton.background = if (strategyTestHintActive) {
                attentionButton(youtubeTestButton.hasFocus(), strategyTestHintPulse)
            } else {
                roundedButton(youtubeTestButton.hasFocus())
            }
        }
        if (::applyBestButton.isInitialized) applyBestButton.background = roundedButton(applyBestButton.hasFocus())
        if (::logButton.isInitialized) logButton.background = roundedButton(logButton.hasFocus())
    }

    private fun bgDrawable() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFF210606.toInt(), 0xFF0F0505.toInt(), 0xFF030101.toInt())
    )

    private fun circleButton(active: Boolean, focused: Boolean = false): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        colors = if (active) {
            intArrayOf(0xFF27D986.toInt(), 0xFF0B9D67.toInt())
        } else {
            intArrayOf(if (focused) 0xFFFF3B3B.toInt() else 0xFFFF0000.toInt(), 0xFFB00000.toInt())
        }
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(dp(if (focused) 5 else 3), if (focused) Color.WHITE else 0xFFFFD2D2.toInt())
    }

    private fun roundedButton(focused: Boolean = false): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(28).toFloat()
        setColor(if (focused) 0xFF333333.toInt() else 0xFF242424.toInt())
        setStroke(dp(2), if (focused) Color.WHITE else 0xFF4A4A4A.toInt())
    }

    private fun attentionButton(focused: Boolean, pulse: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(28).toFloat()
        colors = if (pulse) {
            intArrayOf(0xFF4A1E0B.toInt(), 0xFF24120A.toInt())
        } else {
            intArrayOf(0xFF242424.toInt(), 0xFF1A1111.toInt())
        }
        orientation = GradientDrawable.Orientation.LEFT_RIGHT
        setStroke(dp(if (focused) 3 else 2), if (focused) Color.WHITE else 0xFFFFB347.toInt())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val VPN_REQUEST_CODE = 42
        private const val AUTO_START_REQUEST_CODE = 43
        private const val STRATEGY_HINT_DELAY_MS = 20_000L
        private const val STRATEGY_HINT_PULSE_MS = 2_500L
        private val MUTED = 0xFFFFD2D2.toInt()
        private val BLUE_SOFT = 0xFFFFD2D2.toInt()
    }
}
