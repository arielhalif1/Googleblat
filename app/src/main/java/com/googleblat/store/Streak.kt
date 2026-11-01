package com.googleblat.store

import android.content.Context

/** Counts clean days since the start or the last reset, toward the 30-day goal and beyond. */
object Streak {
    const val GOAL_DAYS = 30
    private val NEXT_GOALS = listOf(30, 60, 90, 180, 365, 730)
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private const val PREFS = "streak"
    private const val KEY_START = "start"
    private const val KEY_BEST = "best"
    private const val KEY_RESETS = "resets"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Starts counting the first time it's called. */
    fun start(ctx: Context): Long {
        val p = prefs(ctx)
        if (!p.contains(KEY_START)) p.edit().putLong(KEY_START, System.currentTimeMillis()).apply()
        return p.getLong(KEY_START, System.currentTimeMillis())
    }

    /** Whole days completed since the start. */
    fun days(ctx: Context) = ((System.currentTimeMillis() - start(ctx)) / DAY_MS).toInt().coerceAtLeast(0)

    fun best(ctx: Context) = maxOf(prefs(ctx).getInt(KEY_BEST, 0), days(ctx))

    fun resets(ctx: Context) = prefs(ctx).getInt(KEY_RESETS, 0)

    fun nextGoal(days: Int) = NEXT_GOALS.firstOrNull { it > days } ?: ((days / 365) + 1) * 365

    /** Starts over from now, remembering the best run. */
    fun reset(ctx: Context) {
        prefs(ctx).edit()
            .putInt(KEY_BEST, best(ctx))
            .putInt(KEY_RESETS, resets(ctx) + 1)
            .putLong(KEY_START, System.currentTimeMillis())
            .apply()
    }

    fun message(days: Int): String = when {
        days == 0 -> "היום הראשון הוא הכי חשוב. כל דרך מתחילה בצעד אחד."
        days < 3 -> "התחלה טובה! הימים הראשונים הם הכי קשים, ואתה עובר אותם."
        days < 7 -> "כמעט שבוע. המוח כבר מתחיל להתרגל."
        days < 14 -> "יותר משבוע! זה כבר לא מקרי, זו בחירה."
        days < 21 -> "שבועיים פלוס. ההרגל החדש נבנה."
        days < GOAL_DAYS -> "הישורת האחרונה לפני 30 יום. אל תעצור עכשיו!"
        days == GOAL_DAYS -> "30 יום! השלמת את היעד. זה הישג אמיתי."
        else -> "יותר מ-30 יום. אתה כבר אדם עם הרגל חדש."
    }

    private val QUOTES = listOf(
        "\"הבא לטהר מסייעין אותו\" (יומא לח:)",
        "\"אין שום יאוש בעולם כלל\" (רבי נחמן מברסלב)",
        "\"איזהו גיבור? הכובש את יצרו\" (אבות ד, א)",
        "\"לפום צערא אגרא\" - לפי הצער, השכר (אבות ה, כג)",
        "\"במקום שבעלי תשובה עומדים, צדיקים גמורים אינם עומדים\" (ברכות לד:)",
        "\"שבע יפול צדיק וקם\" (משלי כד, טז)",
    )

    /** A different quote each day. */
    fun quote(days: Int) = QUOTES[days % QUOTES.size]
}
