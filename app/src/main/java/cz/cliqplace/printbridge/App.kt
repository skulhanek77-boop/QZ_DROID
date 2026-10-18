package cz.cliqplace.printbridge

import android.app.Application
import android.content.Context
import android.content.Intent
import android.webkit.WebView

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Nutné pro vykreslení celé HTML stránky do obrázku (musí být před vytvořením první WebView).
        WebView.enableSlowWholeDocumentDraw()
        BridgeLog.i("Aplikace spuštěna")
        SunmiPrinter.bind(this)
        startBridge(this)
    }

    companion object {
        fun startBridge(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, BridgeService::class.java))
            } catch (e: Exception) {
                BridgeLog.e("Službu nelze spustit: ${e.message}")
            }
        }
    }
}
