package cz.cliqplace.printbridge

import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * WebSocket server, který mluví stejným protokolem jako QZ Tray (qz-tray.js 2.x).
 * Web (entradio) se k němu připojí přes qz.websocket.connect() stejně jako na počítači.
 *
 * Protokol: klient posílá JSON {call, params, uid, signature, timestamp, certificate},
 * server odpovídá {uid, result} nebo {uid, error}.
 */
class QzServer(
    port: Int, // ne "val": getPort() už definuje WebSocketServer (kolize JVM signatur)
    private val secure: Boolean,
    private val engine: PrintEngine,
    private val settings: BridgeSettings
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        /** Verze, kterou hlásíme webu. qz-tray.js podle ní volí kompatibilitu. */
        const val QZ_VERSION = "2.2.4"
    }

    private val label = if (secure) "wss://localhost:$port" else "ws://localhost:$port"

    init {
        isReuseAddr = true
        connectionLostTimeout = 0 // qz-tray.js si sám posílá "ping"
    }

    override fun onWebsocketHandshakeReceivedAsServer(
        conn: WebSocket,
        draft: Draft,
        request: ClientHandshake
    ): ServerHandshakeBuilder {
        val remote = conn.remoteSocketAddress?.address
        if (remote == null || !remote.isLoopbackAddress) {
            BridgeLog.w("Odmítnuto připojení z ${remote?.hostAddress} (povoleno jen z tohoto zařízení)")
            throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "Only local connections are allowed")
        }
        val origin = request.getFieldValue("Origin")
        if (!settings.isOriginAllowed(origin)) {
            BridgeLog.w("Odmítnuta stránka $origin (není v povolených doménách)")
            throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "Origin not allowed")
        }
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    }

    @Volatile
    var started = false
        private set

    override fun onStart() {
        started = true
        BridgeLog.i("Server běží na $label")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val origin = handshake.getFieldValue("Origin").ifEmpty { "?" }
        BridgeLog.i("Web připojen ($label): $origin")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        BridgeLog.i("Web odpojen ($label)")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        BridgeLog.e("Chyba serveru $label: ${ex.message ?: ex.javaClass.simpleName}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        if (message == "ping") return
        val json = try {
            JSONObject(message)
        } catch (e: Exception) {
            BridgeLog.w("Neplatná zpráva: ${message.take(80)}")
            return
        }
        val uid = if (json.isNull("uid")) null else json.optString("uid")
        val call = if (json.isNull("call")) null else json.optString("call")
        val params = json.optJSONObject("params")

        // První zpráva po připojení nese certifikát webu (bez "call")
        if (call == null) {
            val hasCert = !json.isNull("certificate")
            BridgeLog.i("Web se představil ${if (hasCert) "s certifikátem" else "bez certifikátu"}")
            reply(conn, uid, null)
            return
        }

        BridgeLog.i("Volání: $call ${describe(call, params)}")

        try {
            when (call) {
                "getVersion" -> reply(conn, uid, QZ_VERSION)

                "printers.getDefault" -> reply(conn, uid, SunmiPrinter.NAME)

                "printers.find" -> {
                    val query = if (params == null || params.isNull("query")) "" else params.optString("query")
                    when {
                        query.isEmpty() -> reply(conn, uid, JSONArray().put(SunmiPrinter.NAME))
                        settings.acceptAnyPrinterName -> reply(conn, uid, query)
                        SunmiPrinter.NAME.contains(query, ignoreCase = true) -> reply(conn, uid, SunmiPrinter.NAME)
                        else -> replyError(conn, uid, "Specified printer could not be found.")
                    }
                }

                "printers.detail" -> reply(
                    conn, uid, JSONArray().put(
                        JSONObject()
                            .put("name", SunmiPrinter.NAME)
                            .put("driver", "SUNMI")
                            .put("connection", "INTERNAL")
                            .put("default", true)
                    )
                )

                "print" -> {
                    if (params != null && !settings.acceptAnyPrinterName) {
                        val name = params.optJSONObject("printer")?.str("name") ?: params.str("printer")
                        if (name.isNotEmpty() && !SunmiPrinter.NAME.equals(name, ignoreCase = true)) {
                            BridgeLog.w("Web chce tiskárnu '$name', tisknu přesto na vestavěnou")
                        }
                    }
                    engine.submit(params) { err ->
                        if (err == null) reply(conn, uid, null) else replyError(conn, uid, err)
                    }
                }

                "websocket.getNetworkInfo", "networking.device" -> reply(
                    conn, uid, JSONObject()
                        .put("ipAddress", "127.0.0.1")
                        .put("ip", "127.0.0.1")
                        .put("macAddress", "000000000000")
                        .put("mac", "000000000000")
                        .put("hostname", "localhost")
                )

                "networking.hostname" -> reply(conn, uid, "localhost")

                "networking.devices" -> reply(conn, uid, JSONArray())

                // Volání, která nic nedělají, ale web je může používat
                "printers.startListening", "printers.stopListening",
                "printers.clearQueue", "printers.getStatus" -> reply(conn, uid, null)

                else -> {
                    BridgeLog.w("Nepodporované volání '$call' – vracím prázdnou odpověď")
                    reply(conn, uid, null)
                }
            }
        } catch (e: Exception) {
            replyError(conn, uid, e.message ?: e.toString())
        }
    }

    private fun reply(conn: WebSocket, uid: String?, result: Any?) {
        if (uid == null || !conn.isOpen) return
        conn.send(JSONObject().put("uid", uid).put("result", result ?: JSONObject.NULL).toString())
    }

    private fun replyError(conn: WebSocket, uid: String?, error: String) {
        if (uid == null || !conn.isOpen) return
        conn.send(JSONObject().put("uid", uid).put("error", error).toString())
    }

    /** Stručný popis parametrů do logu (bez samotných dat) – ukáže, jak web volá QZ. */
    private fun describe(call: String, params: JSONObject?): String {
        if (params == null) return ""
        if (call != "print") return params.toString().take(200)
        val sb = StringBuilder()
        val printer = params.optJSONObject("printer")
        sb.append("\n   tiskárna: ").append(printer?.toString() ?: params.opt("printer"))
        params.optJSONObject("options")?.let { o ->
            val nonNull = JSONObject()
            o.keys().forEach { k -> if (!o.isNull(k)) nonNull.put(k, o.get(k)) }
            sb.append("\n   volby: ").append(nonNull.toString().take(300))
        }
        val data = params.optJSONArray("data")
        if (data != null) for (i in 0 until data.length()) {
            val d = data.get(i)
            if (d is JSONObject) {
                val payload = d.str("data")
                val flavor = d.str("flavor")
                val shown = if (flavor == "file" || payload.startsWith("http")) payload.take(200)
                else "${payload.length} znaků"
                sb.append("\n   data[$i]: type=${d.str("type")} format=${d.str("format")} flavor=$flavor → $shown")
                d.optJSONObject("options")?.let { sb.append(" volby=").append(it.toString().take(150)) }
            } else {
                sb.append("\n   data[$i]: text ${d.toString().length} znaků")
            }
        }
        return sb.toString()
    }
}
