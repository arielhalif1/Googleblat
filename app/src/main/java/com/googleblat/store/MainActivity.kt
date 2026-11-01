package com.googleblat.store

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private var showStreak = false

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
        showStreak = intent.getBooleanExtra(Reminders.EXTRA_OPEN_STREAK, false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Reminders.EXTRA_OPEN_STREAK, false)) showStreak = true
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
        if (catalog == null) catalog = CatalogRepo.cached(this)
        if (!loading && Lockdown.isDeviceOwner(this)) loadCatalog() else render()
    }

    override fun onPause() {
        super.onPause()
        InstallEvents.listener = null
    }

    private fun render() {
        root.removeAllViews()
        root.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        when {
            !Lockdown.isDeviceOwner(this) -> renderSetup()
            !Passwords.isSet(this) -> renderCreatePassword()
            !Lockdown.isSetupDone(this) -> renderReview()
            else -> {
                renderTabs()
                if (showStreak) renderStreak() else renderStore()
            }
        }
    }

    private fun renderTabs() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, streak) in listOf("Store" to false, "🔥 ספירת ימים" to true)) {
            row.addView(Button(this).apply {
                text = label
                isEnabled = showStreak != streak
                setOnClickListener { showStreak = streak; render() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)
    }

    private fun renderStreak() {
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val days = Streak.days(this)
        val goal = Streak.nextGoal(days)
        title("ספירת ימים")
        root.addView(TextView(this).apply {
            text = days.toString()
            textSize = 72f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = if (days == 1) "יום נקי" else "ימים נקיים"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        })
        root.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = goal
            progress = days
            scaleY = 3f
            setPadding(0, dp(8), 0, dp(8))
        })
        text(
            if (days < Streak.GOAL_DAYS) "עוד ${Streak.GOAL_DAYS - days} ימים ליעד של ${Streak.GOAL_DAYS} יום"
            else "היעד הבא: $goal יום (עוד ${goal - days})"
        )
        text(Streak.message(days))
        text(Streak.quote(days))
        text("השיא שלך: ${Streak.best(this)} ימים · התחלת הספירה: ${date(Streak.start(this))}")

        text("🌙 שעות מסוכנות: ${Lockdown.DANGER_START_HOUR}:00–${Lockdown.DANGER_END_HOUR}:00. בשעות האלה אי אפשר לפתוח את החסימה.\n🔔 תזכורות: 9:00, ${Lockdown.DANGER_START_HOUR}:00, 22:30.")
        renderPanic()

        button("נפלתי, מתחילים מחדש") {
            AlertDialog.Builder(this)
                .setTitle("להתחיל את הספירה מחדש?")
                .setMessage("זה בסדר. נפילה היא לא סוף הדרך, היא חלק ממנה. השיא שלך נשמר.")
                .setPositiveButton("מתחילים מחדש") { _, _ ->
                    Streak.reset(this)
                    toast("\"שבע יפול צדיק וקם\". יום 0, יוצאים לדרך.")
                    render()
                }
                .setNegativeButton("ביטול", null)
                .show()
        }
    }

    /** The panic button, or how long panic mode still runs. */
    private fun renderPanic() {
        val panicUntil = Lockdown.panicUntil(this)
        if (panicUntil > 0) {
            text("🚨 מצב פאניקה פעיל עד ${date(panicUntil)} ${time(panicUntil)}. אי אפשר לפתוח את החסימה, גם לא עם הסיסמה. אפשר רק להאריך.")
        }
        button(if (panicUntil > 0) "🚨 להאריך את מצב הפאניקה" else "🚨 כפתור פאניקה") { confirmPanic() }
    }

    private fun confirmPanic() {
        AlertDialog.Builder(this)
            .setTitle("כמה זמן לחסום לגמרי?")
            .setItems(Lockdown.PANIC_CHOICES.map { it.first }.toTypedArray()) { _, which ->
                val (label, minutes) = Lockdown.PANIC_CHOICES[which]
                AlertDialog.Builder(this)
                    .setTitle("מצב פאניקה ל-$label?")
                    .setMessage(
                        "הטלפון יינעל עכשיו, ובמשך $label לא תוכל לפתוח את החסימה, גם עם הסיסמה. " +
                            "אפליקציות מהרשימה ימשיכו לעבוד ולהתעדכן כרגיל.\n\n" +
                            "אין דרך לבטל את זה לפני הזמן. הדרך היחידה היא איפוס יצרן, שמוחק הכל."
                    )
                    .setPositiveButton("כן, לחסום") { _, _ ->
                        Lockdown.panic(this, minutes)
                        render()
                    }
                    .setNegativeButton("ביטול", null)
                    .show()
            }
            .setNegativeButton("ביטול", null)
            .show()
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
            "You'll need this to install anything from outside Googleblat. Unlocking allows " +
                "other installs for a time you choose, from 15 minutes to 24 hours.\n\n" +
                "If you forget it, the only way out is a factory reset. Consider giving it to " +
                "someone you trust instead of remembering it yourself."
        )
        val first = passwordField("Password")
        val second = passwordField("Repeat password")
        button("Set password") {
            val pw = first.text.toString()
            when {
                pw.length < 6 -> toast("Use at least 6 characters")
                pw != second.text.toString() -> toast("Passwords don't match")
                else -> {
                    Passwords.set(this, pw)
                    render()
                }
            }
        }
    }

    /** Before the first lock: choose which current apps outside the catalog to keep. */
    private fun renderReview() {
        title("Review your apps")
        if (catalog == null) {
            text(if (loading) "Loading catalog…" else "Couldn't load catalog: $catalogError")
            if (!loading) button("Retry") { loadCatalog() }
            return
        }
        val strays = Guard.strays(this).sortedBy { Guard.label(this, it.packageName).lowercase() }
        text(
            "When locked, apps that aren't in the catalog get hidden. Their data is kept, and you " +
                "can bring them back while unlocked. Tick the ones to keep."
        )
        if (strays.isEmpty()) text("Nothing to hide - every installed app is allowed.")
        val boxes = strays.map { app ->
            CheckBox(this).apply {
                text = "${Guard.label(this@MainActivity, app.packageName)}  (${app.packageName})"
            }.also(root::addView)
        }
        button("Lock now") {
            val keep = strays.zip(boxes).filter { it.second.isChecked }.map { it.first.packageName }
            Lockdown.finishSetup(this, keep.toSet())
            render()
        }
    }

    private fun renderStore() {
        title("Googleblat")
        val until = Lockdown.unlockedUntil(this)
        val filter = Lockdown.websiteFilter(this)
        text(if (filter != null) "🛡 Website filter on ($filter)" else "⚠ Website filter not active yet. It turns on once the phone is online.")
        if (until == 0L) {
            text("🔒 Locked - only apps from this list can be installed")
            when {
                Lockdown.panicUntil(this) > 0 -> {}
                Lockdown.inDangerHours() -> text(
                    "🌙 Danger hours (${Lockdown.DANGER_START_HOUR}:00–${Lockdown.DANGER_END_HOUR}:00): " +
                        "unlocking is only possible between ${Lockdown.DANGER_END_HOUR}:00 and ${Lockdown.DANGER_START_HOUR}:00."
                )
                else -> button("Unlock…") { askPasswordThenUnlock() }
            }
            renderPanic()
        } else {
            text("🔓 Unlocked until ${time(until)}. All apps are visible, and other installs, ADB, " +
                "Google Play and other stores are allowed. Apps not on the list (or kept) are " +
                "hidden again when it locks.")
            button("Lock now") { Lockdown.lock(this); render() }
            button("Change password") { changePassword() }
            button("Remove Googleblat completely…") { confirmRemove() }
        }
        val playUntil = Lockdown.playOpenUntil(this)
        if (until == 0L && playUntil > 0) {
            text("Google Play is open until ${time(playUntil)}. Apps not on the list are hidden as soon as they install.")
            button("Close Google Play now") { Lockdown.closePlay(this); render() }
        }
        if (Lockdown.playMode(this) == "hidden") {
            text("⚠ Android wouldn't let Google Play be paused, so it's hidden instead. Apps that " +
                "check Play integrity (banking) may fail while locked.")
        }
        button(if (loading) "Loading catalog…" else "Refresh catalog", enabled = !loading) { loadCatalog() }

        catalogError?.let { text("Couldn't refresh catalog, using the saved copy: $it") }
        catalog?.apps?.forEach { renderApp(it) }
        if (until != 0L) renderOtherApps()
    }

    /** While unlocked: apps outside the catalog, and whether each may stay. */
    private fun renderOtherApps() {
        val hiddenOrStray = (Guard.hidden(this) + Guard.strays(this).map { it.packageName })
            .sortedBy { Guard.label(this, it).lowercase() }
        val kept = Guard.kept(this).sortedBy { Guard.label(this, it).lowercase() }
        if (hiddenOrStray.isEmpty() && kept.isEmpty()) return
        title("Apps outside the catalog")
        for (pkg in hiddenOrStray) {
            val state = if (pkg in Guard.hidden(this)) "hidden" else "will be hidden on lock"
            text("${Guard.label(this, pkg)} - $state")
            button("Keep ${Guard.label(this, pkg)}") { Guard.allow(this, pkg); render() }
        }
        for (pkg in kept) {
            text("${Guard.label(this, pkg)} - kept")
            button("Stop keeping ${Guard.label(this, pkg)}") { Guard.disallow(this, pkg); render() }
        }
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
                app.source == Source.Play -> if (installed != null) "Update on Google Play" else "Install from Google Play"
                working -> "Working…"
                installed != null -> "Update / reinstall"
                else -> "Install"
            }
            isEnabled = !working
            setOnClickListener {
                if (app.source == Source.Play) Lockdown.openPlayFor(this@MainActivity, app.packageName)
                else install(app)
            }
        })
    }

    // ---- Actions ----

    private fun loadCatalog() {
        loading = true
        render()
        io.execute {
            val result = runCatching { CatalogRepo.fetch(this) }
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
        if (!Lockdown.canUnlock(this)) return
        val field = passwordInput()
        AlertDialog.Builder(this)
            .setTitle("Unlock password")
            .setView(padded(field))
            .setPositiveButton("Unlock") { _, _ ->
                when (val r = Passwords.check(this, field.text.toString())) {
                    Passwords.Result.Ok -> chooseUnlockDuration()
                    Passwords.Result.Wrong -> toast("Wrong password")
                    is Passwords.Result.TooSoon -> toast("Too many tries. Wait ${r.seconds}s")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseUnlockDuration() {
        AlertDialog.Builder(this)
            .setTitle("Unlock for…")
            .setItems(Lockdown.UNLOCK_CHOICES.map { it.first }.toTypedArray()) { _, which ->
                Lockdown.unlock(this, Lockdown.UNLOCK_CHOICES[which].second)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun date(t: Long): String = DateFormat.getDateFormat(this).format(Date(t))

    private fun time(t: Long): String = DateFormat.getTimeFormat(this).format(Date(t))

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
