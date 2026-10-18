package cz.cliqplace.printbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Spustí tiskový most po zapnutí zařízení a po aktualizaci aplikace. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Application.onCreate už službu spouští; tady jen pro jistotu.
        App.startBridge(context)
    }
}
