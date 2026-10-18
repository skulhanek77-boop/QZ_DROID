package cz.cliqplace.printbridge

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.Executors

/**
 * Zpracování volání qz.print(): převede data (PDF, obrázek, HTML, ESC/POS) na tiskové položky
 * a pošle je na vestavěnou tiskárnu. Úlohy běží postupně v jednom vlákně.
 */
class PrintEngine(private val ctx: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val settings = BridgeSettings(ctx)

    fun submit(params: JSONObject?, done: (String?) -> Unit) {
        executor.execute {
            val started = System.currentTimeMillis()
            try {
                run(params ?: JSONObject())
                BridgeLog.i("Vytištěno za ${System.currentTimeMillis() - started} ms")
                done(null)
            } catch (e: Throwable) {
                val msg = e.message ?: e.toString()
                BridgeLog.e("Tisk selhal: $msg")
                done(msg)
            }
        }
    }

    /** Přímý tisk připravených položek (testovací tisk z aplikace). */
    fun submitItems(items: List<PrintItem>) {
        executor.execute {
            try {
                SunmiPrinter.print(items, settings)
                BridgeLog.i("Testovací tisk odeslán")
            } catch (e: Throwable) {
                BridgeLog.e("Testovací tisk selhal: ${e.message}")
            }
        }
    }

    private fun run(params: JSONObject) {
        val options = params.optJSONObject("options") ?: JSONObject()
        val copies = options.optInt("copies", 1).coerceIn(1, 50)
        val data = params.optJSONArray("data") ?: JSONArray()
        val items = ArrayList<PrintItem>()
        for (i in 0 until data.length()) {
            items.add(prepare(data.get(i), options))
        }
        if (items.isEmpty()) throw IOException("Žádná data k tisku")
        repeat(copies) { SunmiPrinter.print(items, settings) }
    }

    private fun prepare(item: Any, jobOptions: JSONObject): PrintItem {
        if (item is String) return PrintItem.Raw(encode(item, jobOptions))
        if (item !is JSONObject) throw IOException("Neznámý formát dat: ${item.javaClass.simpleName}")

        var type = item.str("type").lowercase().ifEmpty { "raw" }
        var format = item.str("format").lowercase()
        // starší verze QZ posílaly type: "pdf" / "html" / "image"
        if (type in listOf("pdf", "html", "image")) {
            format = type
            type = "pixel"
        }
        if (format.isEmpty()) format = if (type == "pixel") "image" else "command"
        val flavor = item.str("flavor").lowercase().ifEmpty {
            when (format) {
                "html" -> "file"
                "command" -> "plain"
                else -> "file"
            }
        }
        val payload = item.str("data")
        val width = SunmiPrinter.paperWidthPx()

        return when (format) {
            "command" -> PrintItem.Raw(
                when (flavor) {
                    "base64" -> decodeBase64(payload)
                    "hex" -> decodeHex(payload)
                    "file" -> download(payload)
                    else -> encode(payload, jobOptions)
                }
            )

            "pdf" -> {
                val bytes = if (flavor == "base64") decodeBase64(payload) else download(payload)
                pages(Renderers.renderPdf(ctx, bytes, width))
            }

            "image" -> {
                val bytes = if (flavor == "base64") decodeBase64(payload) else download(payload)
                pages(listOf(Renderers.renderImage(bytes, width)))
            }

            "html" -> {
                val (html, base) = if (flavor == "plain") {
                    payload to null
                } else if (flavor == "base64") {
                    String(decodeBase64(payload), Charsets.UTF_8) to null
                } else {
                    String(download(payload), Charsets.UTF_8) to payload
                }
                pages(listOf(Renderers.renderHtml(ctx, html, base, width)))
            }

            else -> throw IOException("Formát '$format' není podporován")
        }
    }

    private fun pages(raw: List<Bitmap>): PrintItem.Pages {
        val out = raw.mapNotNull { bmp ->
            val mono = Renderers.toMonochrome(bmp, settings.threshold)
            if (settings.trimBottom) Renderers.trimBottom(mono) else mono
        }
        if (out.isEmpty()) throw IOException("Dokument je prázdný")
        return PrintItem.Pages(out)
    }

    private fun encode(text: String, opts: JSONObject): ByteArray {
        val enc = when (val e = opts.opt("encoding")) {
            is String -> e
            is JSONObject -> e.str("to")
            else -> ""
        }
        val cs = try {
            if (enc.isNotEmpty()) Charset.forName(enc) else Charsets.ISO_8859_1
        } catch (e: Exception) {
            Charsets.ISO_8859_1
        }
        return text.toByteArray(cs)
    }

    private fun decodeBase64(s: String): ByteArray {
        val clean = s.substringAfter("base64,", s).trim()
        return Base64.decode(clean, Base64.DEFAULT)
    }

    private fun decodeHex(s: String): ByteArray {
        val clean = s.replace(Regex("(?i)0x|x|[^0-9a-f]"), "")
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun download(url: String): ByteArray {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IOException("Nepodporovaná adresa souboru: ${url.take(80)}")
        }
        var u = URL(url)
        repeat(6) {
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IOException("Přesměrování bez adresy")
                u = URL(u, loc)
                conn.disconnect()
                return@repeat
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("Stažení souboru selhalo (HTTP $code): ${url.take(120)}")
            }
            return conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
        }
        throw IOException("Příliš mnoho přesměrování: ${url.take(120)}")
    }
}

/** Bezpečné čtení řetězce: chybějící klíč i JSON null vrací "". */
internal fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")
