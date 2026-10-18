package cz.cliqplace.printbridge

import android.content.Context
import android.net.Uri

/** Nastavení aplikace uložené v SharedPreferences. */
class BridgeSettings(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    /** Ustřihnout papír po každé stránce PDF (= po každé vstupence). */
    var cutAfterPage: Boolean
        get() = p.getBoolean("cut", true)
        set(v) = p.edit().putBoolean("cut", v).apply()

    /** Počet prázdných řádků posunu papíru za každou stránkou. */
    var feedLines: Int
        get() = p.getInt("feed", 3)
        set(v) = p.edit().putInt("feed", v.coerceIn(0, 10)).apply()

    /** Práh pro převod na černobílou (0–255). Světlejší pixely budou bílé. */
    var threshold: Int
        get() = p.getInt("threshold", 170)
        set(v) = p.edit().putInt("threshold", v.coerceIn(30, 250)).apply()

    /** Oříznout prázdné místo na konci stránky (šetří papír). */
    var trimBottom: Boolean
        get() = p.getBoolean("trim", true)
        set(v) = p.edit().putBoolean("trim", v).apply()

    /** Přijmout jakýkoli název tiskárny z webu a tisknout vždy na vestavěnou tiskárnu. */
    var acceptAnyPrinterName: Boolean
        get() = p.getBoolean("anyName", true)
        set(v) = p.edit().putBoolean("anyName", v).apply()

    /** Povolené domény oddělené čárkou (prázdné = všechny). */
    var allowedDomains: String
        get() = p.getString("domains", "") ?: ""
        set(v) = p.edit().putString("domains", v.trim()).apply()

    fun isOriginAllowed(origin: String?): Boolean {
        val domains = allowedDomains.split(',', ' ', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (domains.isEmpty()) return true
        val host = try {
            Uri.parse(origin ?: "").host?.lowercase()
        } catch (e: Exception) {
            null
        } ?: return false
        return domains.any { host == it || host.endsWith(".$it") }
    }
}
