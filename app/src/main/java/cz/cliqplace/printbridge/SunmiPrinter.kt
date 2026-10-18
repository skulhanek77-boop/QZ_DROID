package cz.cliqplace.printbridge

import android.content.Context
import android.graphics.Bitmap
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.InnerResultCallback
import com.sunmi.peripheral.printer.SunmiPrinterService
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Jedna tisková položka připravená k odeslání na tiskárnu. */
sealed class PrintItem {
    /** Stránky jako černobílé bitmapy (každá stránka = jedna vstupenka / účtenka). */
    class Pages(val pages: List<Bitmap>) : PrintItem()

    /** Surová data (ESC/POS příkazy). */
    class Raw(val bytes: ByteArray) : PrintItem()
}

/** Obal nad tiskovou službou vestavěné tiskárny Sunmi. */
object SunmiPrinter {
    const val NAME = "SUNMI vestavěná tiskárna"

    @Volatile
    private var service: SunmiPrinterService? = null

    @Volatile
    var hasPrinter = false
        private set

    private var appContext: Context? = null

    private val callback = object : InnerPrinterCallback() {
        override fun onConnected(s: SunmiPrinterService?) {
            service = s
            hasPrinter = try {
                s != null && InnerPrinterManager.getInstance().hasPrinter(s)
            } catch (e: Exception) {
                false
            }
            BridgeLog.i("Tisková služba Sunmi připojena (tiskárna: ${if (hasPrinter) "ano" else "nenalezena"}, papír: ${paperWidthMm()} mm)")
        }

        override fun onDisconnected() {
            service = null
            BridgeLog.w("Tisková služba Sunmi odpojena, zkouším znovu…")
            appContext?.let { bind(it) }
        }
    }

    fun bind(ctx: Context) {
        appContext = ctx.applicationContext
        try {
            val ok = InnerPrinterManager.getInstance().bindService(ctx.applicationContext, callback)
            if (!ok) BridgeLog.e("Tisková služba Sunmi na zařízení není (není to zařízení Sunmi?)")
        } catch (e: Exception) {
            BridgeLog.e("Připojení k tiskové službě Sunmi selhalo: ${e.message}")
        }
    }

    val isConnected: Boolean get() = service != null

    /** 1 = 58 mm, 2 = 80 mm. */
    fun paperWidthMm(): Int = try {
        if (service?.getPrinterPaper() == 1) 58 else 80
    } catch (e: Exception) {
        80
    }

    /** Šířka tiskové hlavy v bodech (203 dpi). */
    fun paperWidthPx(): Int = if (paperWidthMm() == 58) 384 else 576

    fun statusText(): String {
        val s = service ?: return "tisková služba nepřipojena"
        if (!hasPrinter) return "tiskárna nenalezena"
        val state = try {
            s.updatePrinterState()
        } catch (e: Exception) {
            -1
        }
        return when (state) {
            1 -> "připravena"
            2 -> "připravuje se"
            3 -> "chyba komunikace"
            4 -> "došel papír"
            5 -> "přehřátá"
            6 -> "otevřený kryt"
            7 -> "chyba řezačky"
            8 -> "řezačka v pořádku"
            9 -> "černá značka nenalezena"
            505 -> "tiskárna nenalezena"
            507 -> "aktualizace firmware selhala"
            else -> "neznámý stav ($state)"
        }
    }

    /**
     * Vytiskne položky jako jednu transakci. Blokuje, dokud tiskárna neohlásí výsledek.
     */
    @Synchronized
    fun print(items: List<PrintItem>, settings: BridgeSettings) {
        val s = service ?: throw IOException("Tisková služba Sunmi není připojena")
        val latch = CountDownLatch(1)
        var error: String? = null

        s.enterPrinterBuffer(true)
        try {
            for (item in items) {
                when (item) {
                    is PrintItem.Raw -> s.sendRAWData(item.bytes, null)
                    is PrintItem.Pages -> for (page in item.pages) {
                        for (chunk in Renderers.split(page, 800)) {
                            s.printBitmap(chunk, null)
                        }
                        if (settings.feedLines > 0) s.lineWrap(settings.feedLines, null)
                        if (settings.cutAfterPage) {
                            try {
                                s.cutPaper(null)
                            } catch (e: Exception) {
                                BridgeLog.w("Řezání papíru selhalo (zařízení asi nemá řezačku): ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            try {
                s.exitPrinterBuffer(false)
            } catch (ignore: Exception) {
            }
            throw e
        }

        s.exitPrinterBufferWithCallback(true, object : InnerResultCallback() {
            override fun onRunResult(isSuccess: Boolean) {}
            override fun onReturnString(result: String?) {}
            override fun onRaiseException(code: Int, msg: String?) {
                error = "Chyba tiskárny $code: $msg"
                latch.countDown()
            }

            override fun onPrintResult(code: Int, msg: String?) {
                if (code != 0) error = "Tisk skončil s kódem $code: $msg"
                latch.countDown()
            }
        })

        if (!latch.await(30, TimeUnit.SECONDS)) {
            BridgeLog.w("Tiskárna nepotvrdila dokončení do 30 s, beru jako odesláno")
        }
        error?.let { throw IOException(it) }
    }
}
