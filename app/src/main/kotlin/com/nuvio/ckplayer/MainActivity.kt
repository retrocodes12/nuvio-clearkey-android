package com.nuvio.ckplayer

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE50914),
    background = Color(0xFF0B0B0B),
    surface = Color(0xFF17171B),
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
)

private sealed interface Screen {
    data object Home : Screen
    data class Catalog(val addon: Addon) : Screen
    data class Streams(val addon: Addon, val item: MetaItem) : Screen
    data class Play(val url: String, val title: String) : Screen
}

// ---------- add-on persistence ----------
private const val PREFS = "ckplayer"
private fun loadAddons(ctx: Context): List<Addon> {
    val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("addons", "[]") ?: "[]"
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Addon(o.getString("url"), o.optString("name", "Add-on"), o.optString("base", Stremio.baseOf(o.getString("url"))))
        }
    }.getOrDefault(emptyList())
}
private fun saveAddons(ctx: Context, list: List<Addon>) {
    val arr = JSONArray()
    list.forEach { arr.put(JSONObject().put("url", it.manifestUrl).put("name", it.name).put("base", it.base)) }
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("addons", arr.toString()).apply()
}

@Composable
fun AppRoot() {
    MaterialTheme(colorScheme = DarkColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
            fun push(s: Screen) { stack = stack + s }
            fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
            BackHandler(enabled = stack.size > 1) { pop() }

            when (val s = stack.last()) {
                is Screen.Home -> HomeScreen(onOpen = { push(Screen.Catalog(it)) })
                is Screen.Catalog -> CatalogScreen(s.addon, onOpen = { push(Screen.Streams(s.addon, it)) })
                is Screen.Streams -> StreamsScreen(s.addon, s.item, onPlay = { push(Screen.Play(it.url, it.name)) })
                is Screen.Play -> PlayerScreen(s.url)
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(title, color = Color(0xFFE50914), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Color(0xFF9AA3AF), fontSize = 14.sp)
    }
}

@Composable
private fun ListButton(text: String, sub: String? = null, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(Color(0xFF17171B), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Text(text, color = Color.White, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!sub.isNullOrEmpty()) Text(sub, color = Color(0xFF9AA3AF), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HomeScreen(onOpen: (Addon) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var addons by remember { mutableStateOf(loadAddons(ctx)) }
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Header("ClearKey Player", "Add a Stremio add-on, browse it, and play. Handles HLS, DASH, and DASH+ClearKey.")
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Add-on manifest URL") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                val u = url.trim()
                if (!Regex("manifest\\.json").containsMatchIn(u)) { status = "Enter a manifest URL (…/manifest.json)"; return@Button }
                status = "Adding…"
                scope.launch {
                    runCatching { Stremio.loadManifest(u).first }.onSuccess { a ->
                        val list = (addons.filterNot { it.manifestUrl == a.manifestUrl } + a)
                        saveAddons(ctx, list); addons = list; url = ""; status = "Added ${a.name}"
                    }.onFailure { status = "Could not load: ${it.message}" }
                }
            }) { Text("Add add-on") }
        }
        if (status.isNotEmpty()) Text(status, color = Color(0xFFCBD5E1), modifier = Modifier.padding(top = 8.dp))
        Text("Your add-ons", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
        if (addons.isEmpty()) {
            Text("No add-ons yet.", color = Color(0xFF9AA3AF))
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(addons) { a ->
                    ListButton(a.name, a.manifestUrl) { onOpen(a) }
                }
            }
        }
    }
}

@Composable
private fun CatalogScreen(addon: Addon, onOpen: (MetaItem) -> Unit) {
    val scope = rememberCoroutineScope()
    var catalogs by remember { mutableStateOf<List<CatalogRef>>(emptyList()) }
    var current by remember { mutableStateOf<CatalogRef?>(null) }
    var genre by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<MetaItem>>(emptyList()) }
    var status by remember { mutableStateOf("Loading…") }

    LaunchedEffect(addon) {
        runCatching { Stremio.loadManifest(addon.manifestUrl).second }
            .onSuccess { catalogs = it; current = it.firstOrNull(); status = if (it.isEmpty()) "No catalogs." else "" }
            .onFailure { status = "Failed: ${it.message}" }
    }
    LaunchedEffect(current, genre) {
        val c = current ?: return@LaunchedEffect
        status = "Loading items…"
        runCatching { Stremio.loadCatalog(addon.base, c, genre) }
            .onSuccess { items = it; status = if (it.isEmpty()) "No items." else "${it.size} items" }
            .onFailure { status = "Failed: ${it.message}" }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Header(addon.name, status)
        if (catalogs.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                items(catalogs) { c ->
                    Chip(c.name, c == current) { current = c; genre = null }
                }
            }
        }
        current?.genres?.take(16)?.let { gs ->
            if (gs.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                items(gs) { g -> Chip(g, genre == g) { genre = if (genre == g) null else g } }
            }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(items) { m -> ListButton(m.name) { onOpen(m) } }
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (on) Color(0xFFE50914) else Color(0xFF26262B), RoundedCornerShape(999.dp))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, color = if (on) Color.White else Color(0xFFCFD3DA), fontSize = 14.sp) }
}

@Composable
private fun StreamsScreen(addon: Addon, item: MetaItem, onPlay: (StreamItem) -> Unit) {
    var streams by remember { mutableStateOf<List<StreamItem>>(emptyList()) }
    var status by remember { mutableStateOf("Loading streams…") }
    LaunchedEffect(item) {
        runCatching { Stremio.loadStreams(addon.base, item.type, item.id) }
            .onSuccess { streams = it; status = if (it.isEmpty()) "No streams available right now." else "${it.size} streams" }
            .onFailure { status = "Failed: ${it.message}" }
    }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Header(item.name, status)
        LazyColumn(Modifier.fillMaxWidth()) {
            items(streams) { s ->
                ListButton(if (s.name.isNotEmpty()) s.name else "Stream", s.title.replace("\n", " · ")) { onPlay(s) }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(url: String) {
    val context = LocalContext.current
    val activity = context as? Activity
    var error by remember { mutableStateOf<String?>(null) }
    val exo = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }

    LaunchedEffect(url) {
        runCatching {
            val b = MediaItem.Builder().setUri(url)
            when {
                Regex("\\.mpd(\\?|#|$)", RegexOption.IGNORE_CASE).containsMatchIn(url) -> {
                    b.setMimeType(MimeTypes.APPLICATION_MPD)
                    val laurl = Stremio.resolveClearKeyLicenseUri(url)
                    if (laurl != null) {
                        b.setDrmConfiguration(
                            MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).setLicenseUri(laurl).build()
                        )
                    }
                }
                Regex("\\.m3u8", RegexOption.IGNORE_CASE).containsMatchIn(url) -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            exo.setMediaItem(b.build())
            exo.prepare()
        }.onFailure { error = it.message }
    }

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = "Playback error ${e.errorCodeName} (${e.errorCode})" }
        }
        exo.addListener(l)
        onDispose {
            exo.removeListener(l); exo.release()
            activity?.let {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                setImmersive(it, false)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exo
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    setFullscreenButtonClickListener { isFull ->
                        activity?.let {
                            it.requestedOrientation =
                                if (isFull) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            setImmersive(it, isFull)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        error?.let {
            Text(it, color = Color(0xFFFF6B6B), modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }
}

private fun setImmersive(activity: Activity, on: Boolean) {
    val window = activity.window
    WindowCompat.setDecorFitsSystemWindows(window, !on)
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    if (on) {
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
