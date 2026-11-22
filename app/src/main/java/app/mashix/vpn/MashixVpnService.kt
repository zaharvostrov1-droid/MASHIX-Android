package app.mashix.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.v2ray.ang.service.TProxyService
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VPN-сервис: поднимает TUN-интерфейс, направляет его трафик в
 * hev-socks5-tunnel (JNI), который переводит его в SOCKS5 на локальный порт,
 * где слушает ядро Xray (libv2ray) и шлёт всё в выбранный VLESS-сервер.
 * Архитектура повторяет проверенную связку v2rayNG.
 */
class MashixVpnService : VpnService() {
    private var tunInterface: ParcelFileDescriptor? = null
    private var controller: CoreController? = null
    private val stopping = AtomicBoolean(false)

    private val coreHandler = object : CoreCallbackHandler {
        override fun onEmitStatus(p0: Long, p1: String?): Long = 0
        override fun startup(): Long = 0
        override fun shutdown(): Long {
            stopEverything("Ядро остановлено")
            return 0
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        // Повторное нажатие кнопки питания = выключить (или игнор во время подключения)
        if (running.get() || connecting.get()) {
            if (running.get()) stopEverything("VPN отключён")
            return START_NOT_STICKY
        }
        val config = intent?.getStringExtra("config") ?: ""
        if (config.isEmpty()) {
            stopEverything("Нет конфигурации подключения")
            return START_NOT_STICKY
        }
        notifyUi(false, "Подключение…")
        connecting.set(true)
        Thread {
            try {
                startCoreAndTunnel(config)
            } catch (e: Exception) {
                connecting.set(false)
                notifyUi(false, "Ошибка подключения: ${e.message ?: "неизвестная ошибка"}")
                cleanupResources()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_STICKY
    }

    private fun startCoreAndTunnel(config: String) {
        // 1) TUN-интерфейс: весь IPv4-трафик устройства
        val tun = Builder()
            .setSession("MASHIX VPN")
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDR, 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(DNS_SERVER)
            .establish()
        if (tun == null) throw IllegalStateException("Android не выдал TUN-интерфейс")
        tunInterface = tun

        // 2) hev-socks5-tunnel: TUN -> SOCKS5 на 127.0.0.1
        val yaml = """
            tunnel:
              mtu: $TUN_MTU
              ipv4: $TUN_ADDR
            socks5:
              port: $SOCKS_PORT
              address: 127.0.0.1
              udp: 'udp'
            misc:
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-level: warning
        """.trimIndent()
        val cfgFile = File(filesDir, "hev-tunnel.yaml").apply { writeText(yaml) }
        if (!TProxyService.TProxyStartService(cfgFile.absolutePath, tun.fd)) {
            throw IllegalStateException("hev-socks5-tunnel не запустился")
        }

        // 3) Ядро Xray: локальный SOCKS5 -> выбранный сервер
        Seq.setContext(applicationContext)
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        Libv2ray.initCoreEnv(cacheDir.absolutePath, deviceId)
        val ctl = CoreController(coreHandler)
        ctl.startLoop(config, 0)
        controller = ctl
        running.set(true)
        connecting.set(false)
        notifyUi(true, "Подключено — трафик идёт через VPN")
    }

    private fun stopEverything(reason: String) {
        if (!stopping.compareAndSet(false, true)) return
        try {
            cleanupResources()
        } finally {
            connecting.set(false)
            notifyUi(false, reason)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            stopping.set(false)
        }
    }

    private fun cleanupResources() {
        if (running.getAndSet(false)) {
            try { controller?.stopLoop() } catch (_: Exception) {}
        }
        controller = null
        try { TProxyService.TProxyStopService() } catch (_: Exception) {}
        try { tunInterface?.close() } catch (_: Exception) {}
        tunInterface = null
    }

    override fun onRevoke() {
        stopEverything("VPN остановлен системой")
        super.onRevoke()
    }

    override fun onDestroy() {
        if (!stopping.get()) stopEverythingQuietly()
        super.onDestroy()
    }

    private fun stopEverythingQuietly() {
        if (!stopping.compareAndSet(false, true)) return
        try { cleanupResources() } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopping.set(false)
        }
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN-соединение", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("MASHIX")
            .setContentText("VPN активен")
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_VPN)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun notifyUi(connected: Boolean, message: String) {
        statusListener?.invoke(connected, message)
    }

    companion object {
        private const val CHANNEL_ID = "mashix_vpn"
        private const val NOTIF_ID = 42
        private const val TUN_MTU = 8500
        private const val TUN_ADDR = "26.26.26.1"
        private const val SOCKS_PORT = 10808
        private const val DNS_SERVER = "1.1.1.1"

        /** true, пока VPN-туннель активен. */
        val running = AtomicBoolean(false)

        /** true, пока идёт процесс подключения. */
        val connecting = AtomicBoolean(false)

        /** Сюда MainActivity кладёт колбэк статуса для страницы. */
        var statusListener: ((Boolean, String) -> Unit)? = null
    }
}
