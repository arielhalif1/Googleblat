package com.googleblat.store

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputType
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout

    private var catalog: Catalog? = null
    private var catalogError: String? = null
    private var loading = false
    private val busy = mutableSetOf<String>()
    private val errors = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        Lockdown.enforce(this)
        InstallEvents.listener = { pkg, error ->
            runOnUiThread {
                busy.remove(pkg)
                if (error != null) errors[pkg] = error else errors.remove(pkg)
                render()
            }
        }
        if (catalog == null && Lockdown.isDeviceOwner(this)) loadCatalog() else render()
    }

    override fun onPause() {
        super.onPause()
        InstallEvents.listener = null
    }

    private fun render() {
        root.removeAllViews()
        when {
            !Lockdown.isDeviceOwner(this) -> renderSetup()
            !Passwords.isSet(this) -> renderCreatePassword()
            else -> renderStore()
        }
    }

    // ---- Screens ----

    private fun renderSetup() {
        title("Googleblat setup")
        text(
            "Googleblat isn't the device owner yet, so it can't block other installs.\n\n" +
                "1. Remove all accounts (Google, Xiaomi) and Second space / Dual apps.\n" +
                "2. Connect to a computer with USB debugging on and run:\n\n" +
                "adb shell dpm set-device-owner ${packageName}/.AdminReceiver\n\n" +
                "3. Reopen this app. You can add your accounts back afterwards.\n\n" +
                "Full steps are in the project's README."
        )
    }

    private fun renderCreatePassword() {
        title("Choose an unlock password")
        text(
            "You'll need this to install anything from outside Googleblat. Unlocking waits " +
                "${Lockdown.UNLOCK_DELAY_SECONDS} seconds, then allows other installs for " +
                "${Lockdown.UNLOCK_MINUTES} minutes.\n\n" +
                "If you forget it, the only way out is a factory reset. Consider giving it to " +
                "someone you trust instead of remembering it yourself."
        )
        val first = passwordField("Password")
        val second = passwordField("Repeat password")
        button("Set password and lock") {
            val pw = first.text.toString()
            when {
                pw.length < 6 -> toast("Use at least 6 characters")
                pw != second.text.toString() -> toast("Passwords don't match")
                else -> {
                    Passwords.set(this, pw)
                    Lockdown.lock(this)
                    render()
                }
            }
        }
    }

    private fun renderStore() {
        title("Googleblat")
        val until = Lockdown.unlockedUntil(this)
        if (until == 0L) {
            text("🔒 Locked - apps install only from this store")
            button("Unlock for ${Lockdown.UNLOCK_MINUTES} minutes…") { askPasswordThenUnlock() }
        } else {
            text("🔓 Unlocked until ${DateFormat.getTimeFormat(this).format(Date(until))}. " +
                "Other installs, ADB, and blocked stores are allowed.")
            button("Lock now") { Lockdown.lock(this); render() }
            button("Change password") { changePassword() }
            button("Remove Googleblat completely…") { confirmRemove() }
        }
        button(if (loading) "Loading catalog…" else "Refresh catalog", enabled = !loading) { loadCatalog() }

        catalogError?.let { text("Couldn't load catalog: $it") }
        catalog?.apps?.forEach { renderApp(it) }
    }

    private fun renderApp(app: AppEntry) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, 0)
        }
        root.addView(card)
        card.addView(TextView(this).apply {
            text = app.name
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (app.description.isNotEmpty()) card.addView(TextView(this).apply { text = app.description })

        val installed = installedVersion(app.packageName)
        card.addView(TextView(this).apply {
            text = if (installed != null) "Installed: $installed" else "Not installed"
            alpha = 0.7f
        })
        errors[app.packageName]?.let { err ->
            card.addView(TextView(this).apply { text = err; setTextIsSelectable(true) })
        }
        card.addView(Button(this).apply {
            val working = app.packageName in busy
            text = when {
                working -> "Working…"
                installed != null -> "Update / reinstall"
                else -> "Install"
            }
            isEnabled = !working
            setOnClickListener { install(app) }
        })
    }

    // ---- Actions ----

    private fun loadCatalog() {
        loading = true
        render()
        io.execute {
            val result = runCatching { CatalogRepo.fetch() }
            runOnUiThread {
                loading = false
                result.onSuccess {
                    catalog = it
                    catalogError = null
                    Lockdown.setExtraBlocked(this, it.block)
                }.onFailure { catalogError = it.message ?: it.toString() }
                render()
            }
        }
    }

    private fun install(app: AppEntry) {
        busy += app.packageName
        errors.remove(app.packageName)
        render()
        io.execute {
            val result = runCatching { Installer.install(this, app) }
            // On success the session result arrives later via InstallEvents.
            result.onFailure { e ->
                runOnUiThread {
                    busy.remove(app.packageName)
                    errors[app.packageName] = e.message ?: e.toString()
                    render()
                }
            }
        }
    }

    private fun askPasswordThenUnlock() {
        val field = passwordInput()
        AlertDialog.Builder(this)
            .setTitle("Unlock password")
            .setView(padded(field))
            .setPositiveButton("Unlock") { _, _ ->
                when (val r = Passwords.check(this, field.text.toString())) {
                    Passwords.Result.Ok -> countdownThenUnlock()
                    Passwords.Result.Wrong -> toast("Wrong password")
                    is Passwords.Result.TooSoon -> toast("Too many tries. Wait ${r.seconds}s")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A cooling-off wait before unlocking: do you really need this? */
    private fun countdownThenUnlock() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Unlocking…")
            .setMessage("")
            .setNegativeButton("Never mind", null)
            .setCancelable(false)
            .create()
        val timer = object : CountDownTimer(Lockdown.UNLOCK_DELAY_SECONDS * 1000L, 1000) {
            override fun onTick(left: Long) {
                dialog.setMessage("Unlocking in ${(left + 999) / 1000} seconds. Still need it?")
            }

            override fun onFinish() {
                dialog.dismiss()
                Lockdown.unlock(this@MainActivity)
                render()
            }
        }
        dialog.setOnDismissListener { timer.cancel() }
        dialog.show()
        timer.start()
    }

    private fun changePassword() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val first = passwordInput("New password").also(box::addView)
        val second = passwordInput("Repeat").also(box::addView)
        AlertDialog.Builder(this)
            .setTitle("Change password")
            .setView(padded(box))
            .setPositiveButton("Save") { _, _ ->
                val pw = first.text.toString()
                when {
                    pw.length < 6 -> toast("Use at least 6 characters")
                    pw != second.text.toString() -> toast("Passwords don't match")
                    else -> { Passwords.set(this, pw); toast("Password changed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemove() {
        AlertDialog.Builder(this)
            .setTitle("Remove Googleblat?")
            .setMessage(
                "This lifts every block, unhides Google Play, and removes Googleblat as device " +
                    "owner. You can then uninstall it normally. Setting it up again needs the " +
                    "adb steps (and removing your accounts) again."
            )
            .setPositiveButton("Remove") { _, _ ->
                Lockdown.releaseCompletely(this)
                toast("Done. You can uninstall Googleblat from Settings now.")
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Helpers ----

    private fun installedVersion(pkg: String): String? = try {
        packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun title(s: String) = root.addView(TextView(this).apply {
        text = s
        textSize = 24f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(12))
    })

    private fun text(s: String) = root.addView(TextView(this).apply {
        text = s
        textSize = 15f
        setTextIsSelectable(true)
        setPadding(0, 0, 0, dp(12))
    })

    private fun button(label: String, enabled: Boolean = true, onClick: () -> Unit) =
        root.addView(Button(this).apply {
            text = label
            isEnabled = enabled
            setOnClickListener { onClick() }
        })

    private fun passwordField(hint: String) = passwordInput(hint).also(root::addView)

    private fun passwordInput(hint: String = "Password") = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun padded(v: View) = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
