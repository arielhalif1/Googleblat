package com.googleblat.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One allowed app from catalog.json. */
data class AppEntry(
    val name: String,
    val packageName: String,
    val description: String,
    /** Allowed signing-certificate SHA-256 digests, lowercase hex without colons. */
    val certs: Set<String>,
    val source: Source,
)

sealed class Source {
    /** Installed by Google Play (opened briefly by Googleblat). The default. */
    object Play : Source()
    data class Url(val url: String) : Source()
    data class GitHub(val repo: String, val assetPattern: Regex) : Source()
    object FDroid : Source()
}

data class Catalog(val apps: List<AppEntry>, val block: Set<String>)

/** A concrete download for an [AppEntry]: where to get it and a label for the version. */
data class Resolved(val url: String, val version: String?)

object CatalogRepo {
    private const val PREFS = "catalog"

    /** Downloads the catalog and caches it; the cache is what the allowlist uses offline. */
    fun fetch(ctx: Context): Catalog {
        val text = Http.getText(BuildConfig.CATALOG_URL)
        val catalog = parse(JSONObject(text))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", text).apply()
        return catalog
    }

    /** The last catalog that downloaded successfully, else the copy bundled in the APK. */
    fun cached(ctx: Context): Catalog? {
        val text = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", null)
            ?: runCatching { ctx.assets.open("catalog.json").bufferedReader().use { it.readText() } }.getOrNull()
        return text?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
    }

    fun parse(json: JSONObject): Catalog {
        val apps = json.getJSONArray("apps").objects().map { a ->
            val src = a.optJSONObject("source") ?: JSONObject().put("type", "play")
            AppEntry(
                name = a.getString("name"),
                packageName = a.getString("packageName"),
                description = a.optString("description"),
                certs = (a.optJSONArray("certSha256") ?: JSONArray()).strings().map(::normalizeCert).toSet(),
                source = when (val type = src.getString("type")) {
                    "play" -> Source.Play
                    "url" -> Source.Url(src.getString("url"))
                    "github" -> Source.GitHub(
                        src.getString("repo"),
                        Regex(src.optString("asset", ".*\\.apk$")),
                    )
                    "fdroid" -> Source.FDroid
                    else -> error("Unknown source type '$type' for ${a.getString("packageName")}")
                },
            )
        }
        val block = (json.optJSONArray("block") ?: JSONArray()).strings().toSet()
        return Catalog(apps, block)
    }

    fun resolve(app: AppEntry): Resolved = when (val s = app.source) {
        Source.Play -> error("${app.packageName} installs through Google Play")
        is Source.Url -> Resolved(s.url, null)
        is Source.GitHub -> {
            val release = JSONObject(Http.getText("https://api.github.com/repos/${s.repo}/releases/latest"))
            val asset = release.getJSONArray("assets").objects()
                .firstOrNull { s.assetPattern.containsMatchIn(it.getString("name")) }
                ?: error("No asset matching ${s.assetPattern} in ${s.repo} ${release.optString("tag_name")}")
            Resolved(asset.getString("browser_download_url"), release.optString("tag_name"))
        }
        Source.FDroid -> {
            val info = JSONObject(Http.getText("https://f-droid.org/api/v1/packages/${app.packageName}"))
            val code = info.getLong("suggestedVersionCode")
            val name = info.getJSONArray("packages").objects()
                .firstOrNull { it.getLong("versionCode") == code }?.optString("versionName")
            Resolved("https://f-droid.org/repo/${app.packageName}_$code.apk", name)
        }
    }

    fun normalizeCert(s: String) = s.replace(":", "").replace(" ", "").lowercase()

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}

object Http {
    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Googleblat")
    }

    fun getText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun download(url: String, dest: File) {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} for $url")
            c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
    }
}
