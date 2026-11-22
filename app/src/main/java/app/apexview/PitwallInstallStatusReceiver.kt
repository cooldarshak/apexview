package app.apexview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Pure protocol shared by persistent PackageInstaller and the active update UI. */
internal object InstallStatusProtocol {
    const val PREF_FAILED_RESULT = "failedInstallResult"
    fun uiAction(packageName: String): String = packageName + ".INSTALL_STATUS_UI"
    fun failureMessage(message: String?): String =
        "The update didn't install: " +
            DiagnosticRedaction.redact(message?.take(200) ?: "Android refused it").take(200)
}

/**
 * Explicit, non-exported PendingIntent target: the Android package installer
 * can finish after MainActivity/Updater is destroyed or the app process exits.
 * Never carry installer extras across the LAN or into diagnostic logs.
 */
class PitwallInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            if (confirm != null) {
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { publish(context, PackageInstaller.STATUS_FAILURE, "Unable to open Android installer confirmation") }
            } else {
                publish(context, PackageInstaller.STATUS_FAILURE, "Android installer confirmation is unavailable")
            }
            return
        }
        publish(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    private fun publish(context: Context, status: Int, message: String?) {
        val notice = if (status == PackageInstaller.STATUS_SUCCESS) null
            else InstallStatusProtocol.failureMessage(message)
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (notice == null) remove(InstallStatusProtocol.PREF_FAILED_RESULT)
            else putString(InstallStatusProtocol.PREF_FAILED_RESULT, notice)
        }.apply()
        // Only the current activity listens; persistence above handles the
        // process-death case without holding on to an obsolete Activity.
        context.sendBroadcast(Intent(InstallStatusProtocol.uiAction(context.packageName))
            .setPackage(context.packageName)
            .putExtra(PackageInstaller.EXTRA_STATUS, status)
            .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message?.take(200)))
    }
}
