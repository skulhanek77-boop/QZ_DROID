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
     * Vykreslí HTML mimo obrazovku. Musí se volat mimo hlavní vlákno.
     *
     * - [pageWidthCss] > 0: stránka se rozvrhne na tuto šířku (QZ volba pageWidth, např. 80 mm),
     *   jinak se šířka zjistí z <body> (šablona má width: 80mm).
     * - Výsledek se rozdělí na stránky podle CSS page-break-after / break-after
     *   (i z @media print), takže každá vstupenka se vytiskne a ustřihne zvlášť.
     */
    fun renderHtml(ctx: Context, html: String, baseUrl: String?, widthPx: Int, pageWidthCss: Double = 0.0): List<Bitmap> {
        val future = CompletableFuture<List<Bitmap>>()
        val main = Handler(Looper.getMainLooper())
        var job: HtmlJob? = null
        main.post {
            try {
                job = HtmlJob(ctx, main, widthPx, pageWidthCss, future).also { it.start(html, baseUrl) }
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return try {
            future.get(45, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            main.post { job?.abort() }
            throw IOException("Vykreslení HTML trvalo příliš dlouho")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw IOException("Vykreslení HTML selhalo: ${e.cause?.message}")
        }
    }

    private const val MEASURE_JS = """(function(){
var b=document.body,d=document.documentElement,r=b.getBoundingClientRect(),sy=window.scrollY||0,cuts=[],sel=[];
function add(el){var q=el.getBoundingClientRect();if(q.height>0)cuts.push(q.bottom+sy);}
function isBreak(st){var a=(st.pageBreakAfter||'')+' '+(st.breakAfter||'');return /always|page/.test(a);}
function walk(rs){for(var j=0;j<rs.length;j++){var ru=rs[j];
 if(ru.cssRules&&!ru.selectorText){try{walk(ru.cssRules);}catch(e){}}
 else if(ru.style&&ru.selectorText&&isBreak(ru.style))sel.push(ru.selectorText);}}
for(var i=0;i<document.styleSheets.length;i++){try{walk(document.styleSheets[i].cssRules);}catch(e){}}
sel.forEach(function(s){try{document.querySelectorAll(s).forEach(add);}catch(e){}});
var all=b.getElementsByTagName('*');for(var k=0;k<all.length;k++){try{if(isBreak(getComputedStyle(all[k])))add(all[k]);}catch(e){}}
return {w:Math.max(r.right,1),h:Math.max(d.scrollHeight,b.scrollHeight,r.bottom+sy),cuts:cuts};
})()"""

    private class HtmlJob(
        val ctx: Context,
        val main: Handler,
        val widthPx: Int,
        val pageWidthCss: Double,
        val future: CompletableFuture<List<Bitmap>>
    ) {
        private val density = ctx.resources.displayMetrics.density
        private val wv = WebView(ctx)
        private var loaded = false
        private var destroyed = false

        fun start(html: String, baseUrl: String?) {
            wv.settings.javaScriptEnabled = true
            wv.settings.loadsImagesAutomatically = true
            wv.settings.blockNetworkImage = false
            wv.setBackgroundColor(Color.WHITE)
            wv.isVerticalScrollBarEnabled = false
            wv.isHorizontalScrollBarEnabled = false
            val initialW = if (pageWidthCss > 0) ceil(pageWidthCss * density).toInt() else widthPx
            layout(max(1, initialW), 400)
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (loaded) return
                    loaded = true
                    // počkat na webové fonty a obrázky (QR kódy)
                    main.postDelayed({ measure() }, 1500)
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
            if (destroyed) return
            wv.evaluateJavascript(MEASURE_JS) { result ->
                try {
                    val o = JSONObject(result)
                    val cssW = if (pageWidthCss > 0) pageWidthCss else o.getDouble("w")
                    val cssH = o.getDouble("h")
                    val pxW = max(1, ceil(cssW * density).toInt())
                    // strop ~20 Mpx kvůli paměti
                    val maxH = max(1, 20_000_000 / pxW)
                    val pxH = min(maxH, max(1, ceil(cssH * density).toInt()))
                    val arr = o.optJSONArray("cuts")
                    val cuts = ArrayList<Int>()
                    if (arr != null) for (i in 0 until arr.length()) {
                        cuts.add((arr.optDouble(i) * density).roundToInt())
                    }
                    layout(pxW, pxH)
                    main.postDelayed({ draw(pxW, pxH, cuts) }, 500)
                } catch (e: Throwable) {
                    fail(e)
                }
            }
        }

        private fun draw(pxW: Int, pxH: Int, cutsPx: List<Int>) {
            if (destroyed) return
            try {
                val bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                wv.draw(Canvas(bmp))
                val k = widthPx.toFloat() / pxW
                val outH = max(1, (pxH * k).roundToInt())
                val out = if (pxW == widthPx) bmp else Bitmap.createScaledBitmap(bmp, widthPx, outH, true)

                // rozdělit na stránky podle zlomů
                val bounds = cutsPx.map { (it * k).roundToInt() }
                    .filter { it in 20 until outH - 20 }
                    .distinct().sorted()
                val pages = ArrayList<Bitmap>()
                var y0 = 0
                for (y in bounds + outH) {
                    if (y - y0 >= 20) pages.add(Bitmap.createBitmap(out, 0, y0, widthPx, y - y0))
                    y0 = y
                }
                if (pages.isEmpty()) pages.add(out)
                BridgeLog.i("HTML vykresleno: ${pages.size} str., ${widthPx}×$outH bodů")
                future.complete(pages)
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            } finally {
                abort()
            }
        }

        private fun fail(e: Throwable) {
            future.completeExceptionally(e)
            abort()
        }

        fun abort() {
            if (destroyed) return
            destroyed = true
            try {
                wv.destroy()
            } catch (ignore: Throwable) {
            }
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
