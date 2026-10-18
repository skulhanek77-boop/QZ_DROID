package cz.cliqplace.printbridge

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Jednoduchý log v paměti, který se zobrazuje v aplikaci. */
object BridgeLog {
    private const val TAG = "EntradioTisk"
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var version = 0
        private set

    fun i(msg: String) = add("I", msg)
    fun w(msg: String) = add("W", msg)
    fun e(msg: String) = add("E", msg)

    @Synchronized
    private fun add(level: String, msg: String) {
        when (level) {
            "E" -> Log.e(TAG, msg)
            "W" -> Log.w(TAG, msg)
            else -> Log.i(TAG, msg)
        }
        lines.addFirst("${fmt.format(Date())} $level  $msg")
        while (lines.size > MAX) lines.removeLast()
        version++
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        version++
    }
}
