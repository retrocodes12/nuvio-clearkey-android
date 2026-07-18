package com.nuvio.ckplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Addon(val manifestUrl: String, val name: String, val base: String, val logo: String? = null)
data class CatalogRef(val type: String, val id: String, val name: String, val genres: List<String>)
data class MetaItem(val id: String, val type: String, val name: String, val poster: String?, val posterShape: String = "poster")
data class StreamItem(val name: String, val title: String, val url: String)

object Stremio {

    suspend fun httpGetText(u: String): String = withContext(Dispatchers.IO) {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "*/*")
        // Identify the Nebula app so the addon serves direct ClearKey DASH cards
        // (and skips the "Open in Nebula Player" launcher meant for other clients).
        conn.setRequestProperty("X-Nebula-Client", "android")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun baseOf(manifestUrl: String): String = manifestUrl.replace(Regex("/manifest\\.json.*$"), "")

    suspend fun loadManifest(url: String): Pair<Addon, List<CatalogRef>> {
        val j = JSONObject(httpGetText(url))
        val logo = j.optString("logo").ifEmpty { j.optString("icon") }.ifEmpty { null }
        val addon = Addon(url, j.optString("name", "Add-on"), baseOf(url), logo)
        val cats = mutableListOf<CatalogRef>()
        val arr = j.optJSONArray("catalogs") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val genres = mutableListOf<String>()
            val extra = c.optJSONArray("extra")
            if (extra != null) for (k in 0 until extra.length()) {
                val e = extra.getJSONObject(k)
                if (e.optString("name") == "genre") {
                    val opts = e.optJSONArray("options")
                    if (opts != null) for (o in 0 until opts.length()) genres.add(opts.getString(o))
                }
            }
            cats.add(CatalogRef(c.optString("type"), c.optString("id"), c.optString("name", c.optString("id")), genres))
        }
        return addon to cats
    }

    suspend fun loadCatalog(base: String, c: CatalogRef, genre: String?): List<MetaItem> {
        var u = "$base/catalog/${enc(c.type)}/${enc(c.id)}"
        if (!genre.isNullOrEmpty()) u += "/genre=${enc(genre)}"
        u += ".json"
        val j = JSONObject(httpGetText(u))
        val metas = j.optJSONArray("metas") ?: return emptyList()
        val out = mutableListOf<MetaItem>()
        for (i in 0 until metas.length()) {
            val m = metas.getJSONObject(i)
            val poster = m.optString("poster").ifEmpty { null }
            val shape = m.optString("posterShape").ifEmpty { "poster" }
            out.add(MetaItem(m.optString("id"), m.optString("type", c.type), m.optString("name", m.optString("id")), poster, shape))
        }
        return out
    }

    suspend fun loadStreams(base: String, type: String, id: String): List<StreamItem> {
        val u = "$base/stream/${enc(type)}/${enc(id)}.json"
        val j = JSONObject(httpGetText(u))
        val arr = j.optJSONArray("streams") ?: return emptyList()
        val out = mutableListOf<StreamItem>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val url = s.optString("url")
            if (url.isNotEmpty()) out.add(StreamItem(s.optString("name"), s.optString("title"), url))
        }
        return out
    }

    /** Extract a ClearKey license URL (dashif:laurl / clearkey:Laurl) from a DASH manifest. */
    suspend fun resolveClearKeyLicenseUri(mpdUrl: String): String? {
        return try {
            val xml = httpGetText(mpdUrl)
            Regex("<(?:\\w+:)?laurl[^>]*>([^<]+)</(?:\\w+:)?laurl>", RegexOption.IGNORE_CASE)
                .find(xml)?.groupValues?.get(1)?.trim()
        } catch (e: Exception) {
            null
        }
    }
}
