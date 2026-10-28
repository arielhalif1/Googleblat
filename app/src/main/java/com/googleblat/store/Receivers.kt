package com.googleblat.store

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

class AdminReceiver : DeviceAdminReceiver()

class RelockReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RELOCK = "com.googleblat.store.RELOCK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Lockdown.enforce(context)
    }
}

/** Receives the result of a PackageInstaller session started by [Installer]. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(Installer.EXTRA_PACKAGE) ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Shouldn't happen for a device owner, but fall back to the system prompt.
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> InstallEvents.finished(context, pkg, null)
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                InstallEvents.finished(context, pkg, "Install failed ($status): $msg")
            }
        }
    }
}

/** Tells the UI (if open) that an install finished. */
object InstallEvents {
    @Volatile var listener: ((pkg: String, error: String?) -> Unit)? = null

    fun finished(ctx: Context, pkg: String, error: String?) {
        val l = listener
        if (l != null) l(pkg, error)
        else Toast.makeText(ctx, error ?: "Installed $pkg", Toast.LENGTH_LONG).show()
    }
}
