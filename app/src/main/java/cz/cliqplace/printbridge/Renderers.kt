package cz.cliqplace.printbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Převod PDF / obrázků / HTML na černobílé bitmapy v šířce tiskové hlavy. */
object Renderers {

    // ---------- PDF ----------

    fun renderPdf(ctx: Context, bytes: ByteArray, widthPx: Int): List<Bitmap> {
        val file = File.createTempFile("job", ".pdf", ctx.cacheDir)
        try {
            file.writeBytes(bytes)
            val pages = ArrayList<Bitmap>()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            val scale = widthPx.toFloat() / page.width
                            val h = max(1, ceil(page.height * scale).toInt())
                            val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            val m = Matrix().apply { setScale(scale, scale) }
                            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            pages.add(bmp)
                        }
                    }
                }
            }
            return pages
        } finally {
            file.delete()
        }
    }

    // ---------- Obrázek ----------

    fun renderImage(bytes: ByteArray, widthPx: Int): Bitmap {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("Obrázek nelze dekódovat")
        val w = min(widthPx, src.width)
        val h = max(1, (src.height * (w.toFloat() / src.width)).roundToInt())
        val scaled = if (w == src.width) src else Bitmap.createScaledBitmap(src, w, h, true)
        // Vycentrovat na šířku papíru, průhlednost nahradit bílou.
        val out = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        Canvas(out).drawBitmap(scaled, ((widthPx - w) / 2).toFloat(), 0f, null)
        return out
    }

    // ---------- HTML ----------

    /**
     * Vykreslí HTML mimo obrazovku do bitmapy. Musí se volat mimo hlavní vlákno.
     * Šířka obsahu se zjistí z <body> (např. šablona s width: 80mm) a výsledek se škáluje na šířku hlavy.
     */
    fun renderHtml(ctx: Context, html: String, baseUrl: String?, widthPx: Int): Bitmap {
        val future = CompletableFuture<Bitmap>()
        val main = Handler(Looper.getMainLooper())
        main.post {
            try {
                HtmlJob(ctx, main, widthPx, future).start(html, baseUrl)
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return try {
            future.get(40, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw IOException("Vykreslení HTML trvalo příliš dlouho")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw IOException("Vykreslení HTML selhalo: ${e.cause?.message}")
        }
    }

    private class HtmlJob(
        val ctx: Context,
        val main: Handler,
        val widthPx: Int,
        val future: CompletableFuture<Bitmap>
    ) {
        private val density = ctx.resources.displayMetrics.density
        private val wv = WebView(ctx)
        private var loaded = false

        fun start(html: String, baseUrl: String?) {
            wv.settings.javaScriptEnabled = true
            wv.settings.loadsImagesAutomatically = true
            wv.setBackgroundColor(Color.WHITE)
            wv.isVerticalScrollBarEnabled = false
            wv.isHorizontalScrollBarEnabled = false
            layout(widthPx, 400)
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (loaded) return
                    loaded = true
                    // počkat na fonty / obrázky (QR kódy)
                    main.postDelayed({ measure() }, 1200)
                }
            }
            wv.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        }

        private fun layout(w: Int, h: Int) {
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, w, h)
        }

        private fun measure() {
            val js = "(function(){var b=document.body,d=document.documentElement;" +
                    "var r=b.getBoundingClientRect();" +
                    "return {w:Math.ceil(Math.max(r.right,1)),h:Math.ceil(Math.max(d.scrollHeight,b.scrollHeight,r.bottom))};})()"
            wv.evaluateJavascript(js) { result ->
                try {
                    val o = JSONObject(result)
                    val cssW = o.getDouble("w")
                    val cssH = o.getDouble("h")
                    val pxW = max(1, ceil(cssW * density).toInt())
                    val pxH = min(30000, max(1, ceil(cssH * density).toInt()))
                    layout(pxW, pxH)
                    main.postDelayed({ draw(pxW, pxH) }, 400)
                } catch (e: Throwable) {
                    finish(e)
                }
            }
        }

        private fun draw(pxW: Int, pxH: Int) {
            try {
                val bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                wv.draw(Canvas(bmp))
                val outH = max(1, (pxH * (widthPx.toFloat() / pxW)).roundToInt())
                val out = if (pxW == widthPx) bmp else Bitmap.createScaledBitmap(bmp, widthPx, outH, true)
                future.complete(out)
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            } finally {
                wv.destroy()
            }
        }

        private fun finish(e: Throwable) {
            future.completeExceptionally(e)
            wv.destroy()
        }
    }

    // ---------- Úpravy bitmap ----------

    /** Převod na čistě černobílou bitmapu (termotiskárna neumí odstíny). */
    fun toMonochrome(src: Bitmap, threshold: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = (c ushr 24) and 0xff
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            // průhledné pixely míchat s bílou
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val mixed = (lum * a + 255 * (255 - a)) / 255
            px[i] = if (mixed < threshold) Color.BLACK else Color.WHITE
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Ořízne bílé místo na konci. Vrací null, pokud je stránka úplně prázdná. */
    fun trimBottom(src: Bitmap, margin: Int = 8): Bitmap? {
        val w = src.width
        val row = IntArray(w)
        var last = -1
        for (y in src.height - 1 downTo 0) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            if (row.any { it == Color.BLACK }) {
                last = y
                break
            }
        }
        if (last < 0) return null
        val h = min(src.height, last + 1 + margin)
        return if (h == src.height) src else Bitmap.createBitmap(src, 0, 0, w, h)
    }

    /** Rozdělí dlouhou bitmapu na kusy, aby je tisková služba zvládla. */
    fun split(src: Bitmap, maxH: Int): List<Bitmap> {
        if (src.height <= maxH) return listOf(src)
        val parts = ArrayList<Bitmap>()
        var y = 0
        while (y < src.height) {
            val h = min(maxH, src.height - y)
            parts.add(Bitmap.createBitmap(src, 0, y, src.width, h))
            y += h
        }
        return parts
    }

    /** Testovací účtenka nakreslená přímo (bez PDF/HTML). */
    fun testTicket(widthPx: Int): Bitmap {
        val h = 420
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val c = Canvas(bmp)
        val p = android.graphics.Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
            textSize = 34f
            isFakeBoldText = true
        }
        c.drawText("ENTRADIO TISK", 20f, 50f, p)
        p.textSize = 26f
        p.isFakeBoldText = false
        c.drawText("Testovací tisk z aplikace", 20f, 100f, p)
        c.drawText("Šířka hlavy: $widthPx bodů", 20f, 140f, p)
        c.drawText(java.text.SimpleDateFormat("d. M. yyyy HH:mm:ss", java.util.Locale("cs")).format(java.util.Date()), 20f, 180f, p)
        p.strokeWidth = 3f
        c.drawLine(20f, 210f, (widthPx - 20).toFloat(), 210f, p)
        // jednoduchý šachovnicový vzor pro kontrolu kvality
        val s = 20
        for (yy in 0 until 6) for (xx in 0 until (widthPx - 40) / s) {
            if ((xx + yy) % 2 == 0) c.drawRect(
                (20 + xx * s).toFloat(), (240 + yy * s).toFloat(),
                (20 + xx * s + s).toFloat(), (240 + yy * s + s).toFloat(), p
            )
        }
        c.drawText("Pokud čtete tento text, tisk funguje.", 20f, 400f, p)
        return bmp
    }
}
