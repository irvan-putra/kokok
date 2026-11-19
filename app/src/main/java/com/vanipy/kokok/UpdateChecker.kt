package com.vanipy.kokok

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the project's GitHub releases for a newer signed APK and hands the
 * download to the system DownloadManager; the system package installer shows
 * the confirmation. Nothing installs silently.
 */
object UpdateChecker {
    private const val RELEASES_API = "https://api.github.com/repos/irvan-putra/kokok/releases/latest"

    data class Update(val version: String, val apkUrl: String)

    fun check(currentVersion: String): Update? {
        val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        if (conn.responseCode != 200) return null
        val body = conn.inputStream.readBytes().toString(Charsets.UTF_8)
        val json = JSONObject(body)
        val tag = json.getString("tag_name").removePrefix("v")
        if (tag == currentVersion) return null
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            if (name.endsWith(".apk")) {
                return Update(tag, a.getString("browser_download_url"))
            }
        }
        return null
    }

    fun downloadAndPromptInstall(context: Context, update: Update) {
        val name = "kokok-v${update.version}.apk"
        val req = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("kokok ${update.version}")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
            .setMimeType("application/vnd.android.package-archive")
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    }
}
