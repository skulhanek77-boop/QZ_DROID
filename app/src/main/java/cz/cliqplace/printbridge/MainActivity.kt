package cz.cliqplace.printbridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Jednoduchá obrazovka: stav, test tisku, certifikát, nastavení a log. */
class MainActivity : Activity() {

    private lateinit var settings: BridgeSettings
    private lateinit var status: TextView
    private lateinit var log: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var shownLogVersion = -1

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = BridgeSettings(this)
        App.startBridge(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        }

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Entradio Tisk"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Tiskový most kompatibilní s QZ Tray pro vestavěnou tiskárnu Sunmi. " +
                    "Stačí, aby aplikace běžela; pokladna v Chromu tiskne stejně jako s QZ Tray na počítači."
            setPadding(0, dp(4), 0, dp(12))
        })

        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFF1F3F4.toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(section("Akce"))
        root.addView(row(
            button("Testovací tisk") { testPrint() },
            button("Uložit certifikát") { exportCert() }
        ))
        root.addView(row(
            button("Otevřít nastavení zabezpečení") { openSecurity() },
            button("Výjimka z úspory baterie") { openBattery() }
        ))

        root.addView(section("Nastavení tisku"))
        root.addView(check("Ustřihnout papír za každou vstupenkou", settings.cutAfterPage) { settings.cutAfterPage = it })
        root.addView(check("Oříznout prázdné místo na konci stránky", settings.trimBottom) { settings.trimBottom = it })
        root.addView(check("Tisknout na vestavěnou tiskárnu bez ohledu na název tiskárny z webu", settings.acceptAnyPrinterName) {
            settings.acceptAnyPrinterName = it
        })

        val feedLabel = TextView(this)
        fun feedText() { feedLabel.text = "Posun papíru za vstupenkou: ${settings.feedLines} řádků" }
        feedText()
        root.addView(feedLabel)
        root.addView(SeekBar(this).apply {
            max = 10
            progress = settings.feedLines
            setOnSeekBarChangeListener(simpleSeek { settings.feedLines = it; feedText() })
        })

        val thrLabel = TextView(this)
        fun thrText() { thrLabel.text = "Práh černé: ${settings.threshold} (vyšší = tmavší tisk)" }
        thrText()
        root.addView(thrLabel)
        root.addView(SeekBar(this).apply {
            max = 220
            progress = settings.threshold - 30
            setOnSeekBarChangeListener(simpleSeek { settings.threshold = it + 30; thrText() })
        })

        root.addView(TextView(this).apply {
            text = "Povolené domény (oddělené čárkou, prázdné = všechny), např. entradio.cz"
            setPadding(0, dp(12), 0, 0)
        })
        val domains = EditText(this).apply {
            setText(settings.allowedDomains)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        root.addView(domains, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(button("Uložit domény") {
            settings.allowedDomains = domains.text.toString()
            Toast.makeText(this, "Uloženo", Toast.LENGTH_SHORT).show()
        })

        root.addView(section("Log"))
        root.addView(button("Vymazat log") { BridgeLog.clear() })
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(log)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refresh() {
        val ok = { b: Boolean -> if (b) "✔" else "✘" }
        status.text = buildString {
            append("${ok(BridgeService.running)} Služba: ${if (BridgeService.running) "běží" else "neběží"}\n")
            append("${ok(BridgeService.insecureRunning)} ws://localhost:${BridgeService.INSECURE_PORT}\n")
            append("${ok(BridgeService.secureRunning)} wss://localhost:${BridgeService.SECURE_PORT}\n")
            append("${ok(SunmiPrinter.isConnected && SunmiPrinter.hasPrinter)} Tiskárna: ${SunmiPrinter.statusText()}")
            if (SunmiPrinter.isConnected) append(", papír ${SunmiPrinter.paperWidthMm()} mm")
        }
        if (BridgeLog.version != shownLogVersion) {
            shownLogVersion = BridgeLog.version
            log.text = BridgeLog.text()
        }
    }

    private fun testPrint() {
        val eng = BridgeService.engine
        if (eng == null) {
            Toast.makeText(this, "Služba ještě nenaběhla", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val w = SunmiPrinter.paperWidthPx()
                val bmp = Renderers.toMonochrome(Renderers.testTicket(w), settings.threshold)
                eng.submitItems(listOf(PrintItem.Pages(listOf(bmp))))
            } catch (e: Exception) {
                BridgeLog.e("Test selhal: ${e.message}")
            }
        }.start()
    }

    private fun exportCert() {
        Thread {
            try {
                val where = CertManager.exportCa(applicationContext)
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Certifikát uložen")
                        .setMessage(
                            "Soubor: $where\n\n" +
                                    "Nainstalujte ho jako certifikát CA:\n" +
                                    "Nastavení → Zabezpečení → Šifrování a pověření → Instalovat certifikát → Certifikát CA → vyberte soubor entradio-tisk-ca.crt.\n\n" +
                                    "Certifikát platí jen pro localhost (tento tablet), na jiné weby nemá vliv. " +
                                    "Je potřeba jen tehdy, když pokladna používá zabezpečené připojení (wss)."
                        )
                        .setPositiveButton("Otevřít nastavení") { _, _ -> openSecurity() }
                        .setNegativeButton("Zavřít", null)
                        .show()
                }
            } catch (e: Exception) {
                BridgeLog.e("Uložení certifikátu selhalo: ${e.message}")
                runOnUiThread { Toast.makeText(this, "Chyba: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun openSecurity() {
        try {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openBattery() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Na tomto zařízení není k dispozici", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- pomocné prvky UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }

    private fun check(label: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        isChecked = value
        setOnCheckedChangeListener { _, b -> onChange(b) }
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
