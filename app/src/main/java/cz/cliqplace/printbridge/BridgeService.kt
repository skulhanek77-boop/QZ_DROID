package cz.cliqplace.printbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.java_websocket.server.DefaultSSLWebSocketServerFactory

/** Služba na popředí, která drží spuštěné WebSocket servery (ws 8182 a wss 8181). */
class BridgeService : Service() {

    companion object {
        const val SECURE_PORT = 8181
        const val INSECURE_PORT = 8182

        @Volatile
        var running = false
            private set

        @Volatile
        private var secureServer: QzServer? = null

        @Volatile
        private var insecureServer: QzServer? = null

        val secureRunning: Boolean get() = running && secureServer?.started == true
        val insecureRunning: Boolean get() = running && insecureServer?.started == true

        @Volatile
        var engine: PrintEngine? = null
            private set
    }

    private val servers = java.util.concurrent.CopyOnWriteArrayList<QzServer>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            goForeground()
        } catch (e: Exception) {
            // Android 12+ může odmítnout start na popředí (např. při obnově START_STICKY z pozadí)
            BridgeLog.e("Službu nelze spustit na popředí: ${e.message}")
            stopSelf()
            return
        }
        running = true
        Thread({ startServers() }, "qz-start").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        secureServer = null
        insecureServer = null
        servers.forEach {
            try {
                it.stop(1000)
            } catch (ignore: Exception) {
            }
        }
        servers.clear()
        BridgeLog.w("Služba zastavena")
        super.onDestroy()
    }

    private fun startServers() {
        val settings = BridgeSettings(this)
        val eng = PrintEngine(applicationContext)
        engine = eng

        try {
            val ws = QzServer(INSECURE_PORT, false, eng, settings)
            ws.start()
            servers.add(ws)
            insecureServer = ws
        } catch (e: Exception) {
            BridgeLog.e("Server ws://localhost:$INSECURE_PORT nelze spustit: ${e.message}")
        }

        try {
            val ssl = CertManager.sslContext(applicationContext)
            val wss = QzServer(SECURE_PORT, true, eng, settings)
            wss.setWebSocketFactory(DefaultSSLWebSocketServerFactory(ssl))
            wss.start()
            servers.add(wss)
            secureServer = wss
        } catch (e: Exception) {
            BridgeLog.e("Server wss://localhost:$SECURE_PORT nelze spustit: ${e.message}")
        }
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("bridge", "Tiskový most", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, "bridge")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Entradio Tisk běží")
            .setContentText("Přijímá tisk z prohlížeče (kompatibilní s QZ Tray)")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }
}
