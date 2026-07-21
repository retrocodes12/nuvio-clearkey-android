package com.nuvio.ckplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val pendingPlay = mutableStateOf<PlayReq?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPlay.value = parsePlayIntent(intent)
        setContent { AppRoot(pendingPlay.value) { pendingPlay.value = null } }
    }

    // singleTop: a deep link while the app is already open arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parsePlayIntent(intent)?.let { pendingPlay.value = it }
    }

    // nebula://play?mpd=<manifest url>&t=<title>
    private fun parsePlayIntent(intent: Intent?): PlayReq? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        if (!data.scheme.equals("nebula", ignoreCase = true)) return null
        val mpd = data.getQueryParameter("mpd")?.trim().orEmpty()
        if (mpd.isEmpty()) return null
        val title = (data.getQueryParameter("t") ?: data.getQueryParameter("title") ?: "Nebula Sports").trim()
        return PlayReq(mpd, title)
    }
}

// ---------- Nebula palette (matches the web/webOS player) ----------
private val Red = Color(0xFFE50914)
private val RedBright = Color(0xFFFF2633)
private val RedDark = Color(0xFFB40510)
private val RedDeep = Color(0xFF7A060D)
private val Bg = Color(0xFF07070C)
private val SurfaceC = Color(0xFF161619)
private val Surface2 = Color(0xFF1E1E23)
private val Glass = Color(0x14FFFFFF)   // glass card top
private val Glass2 = Color(0x08FFFFFF)  // glass card bottom
private val LineC = Color(0x17FFFFFF)   // hairline borders
private val Line2 = Color(0x2BFFFFFF)   // brighter borders (inputs, icon rings)
private val MutedC = Color(0xFF8F96A3)
private val TextC = Color(0xFFF4F4F6)

private val RedGrad = listOf(RedBright, RedDark)
private val GlassGrad = listOf(Glass, Glass2)

private val DarkColors = darkColorScheme(
    primary = Red,
    background = Bg,
    surface = SurfaceC,
    onPrimary = Color.White,
    onBackground = TextC,
    onSurface = TextC,
)

private sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Addons : Screen
    data class Catalog(val addon: Addon, val initial: CatalogRef? = null) : Screen
    data class Streams(val addon: Addon, val item: MetaItem) : Screen
    data class Play(val url: String, val title: String) : Screen
}

/** One catalog's worth of content, tagged with where it came from. */
private class CatRow(val addon: Addon, val catalog: CatalogRef, val items: List<MetaItem>)

/** Session cache of addon manifests (Home and Search both need them). */
private val manifestCache = mutableMapOf<String, Pair<Addon, List<CatalogRef>>>()
private suspend fun manifestFor(url: String): Pair<Addon, List<CatalogRef>> =
    manifestCache[url] ?: Stremio.loadManifest(url).also { manifestCache[url] = it }

/** Home tab state, hoisted to AppRoot so rows survive navigating into a stream. */
private class HomeUiState {
    var rows by mutableStateOf<List<CatRow>>(emptyList())
    var loading by mutableStateOf(false)
    var hasAddons by mutableStateOf(true)
    var refreshKey by mutableStateOf(0)
    var sig: String? = null
    var builtAt = 0L
    val listState = LazyListState()
    fun invalidate() { sig = null; refreshKey++ }
}

/** Search tab state, hoisted for the same reason. */
private class SearchUiState {
    var query by mutableStateOf("")
    var submitted by mutableStateOf("")
    var sections by mutableStateOf<List<CatRow>>(emptyList())
    var searching by mutableStateOf(false)
    var searchedFor: String? = null
    val listState = LazyListState()
}

/**
 * Catalog screen state, hoisted to AppRoot: only the top of the nav stack is
 * composed, so anything remembered inside CatalogScreen dies the moment a
 * stream screen is pushed — search results, picked genre, and scroll position
 * were all lost on Back. Held here they survive until the catalog is popped.
 */
private class CatalogUiState {
    var catalogs by mutableStateOf<List<CatalogRef>>(emptyList())
    var current by mutableStateOf<CatalogRef?>(null)
    var genre by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var submitted by mutableStateOf("")
    var items by mutableStateOf<List<MetaItem>>(emptyList())
    var loading by mutableStateOf(true)
    var status by mutableStateOf("Loading…")
    // what (catalog, genre, query) the current items were fetched for; null after a failure so re-entering retries
    var loadedFor: Triple<CatalogRef?, String?, String>? = null
    val gridState = LazyGridState()
}

/** A play request arriving from a nebula://play deep link. */
data class PlayReq(val mpd: String, val title: String)

// ---------- add-on persistence ----------
private const val PREFS = "ckplayer"
private fun loadAddons(ctx: Context): List<Addon> {
    val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("addons", "[]") ?: "[]"
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Addon(
                o.getString("url"),
                o.optString("name", "Add-on"),
                o.optString("base", Stremio.baseOf(o.getString("url"))),
                o.optString("logo").ifEmpty { null },
            )
        }
    }.getOrDefault(emptyList())
}
private fun saveAddons(ctx: Context, list: List<Addon>) {
    val arr = JSONArray()
    list.forEach {
        arr.put(
            JSONObject().put("url", it.manifestUrl).put("name", it.name)
                .put("base", it.base).put("logo", it.logo ?: "")
        )
    }
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("addons", arr.toString()).apply()
}

@Composable
fun AppRoot(playReq: PlayReq? = null, onConsumed: () -> Unit = {}) {
    MaterialTheme(colorScheme = DarkColors) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
            val catalogStates = remember { HashMap<String, CatalogUiState>() }
            val homeState = remember { HomeUiState() }
            val searchState = remember { SearchUiState() }
            fun push(s: Screen) { stack = stack + s }
            fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
            fun setTab(s: Screen) { stack = listOf(s) }
            // Back pops the stack; from a non-Home tab root it returns to Home.
            BackHandler(enabled = stack.size > 1 || stack.last() != Screen.Home) {
                if (stack.size > 1) pop() else setTab(Screen.Home)
            }

            // Drop catalog state once its screen is no longer anywhere in the stack.
            LaunchedEffect(stack) {
                val live = stack.filterIsInstance<Screen.Catalog>().map { it.addon.manifestUrl }.toSet()
                catalogStates.keys.retainAll(live)
            }

            // A deep-link play request jumps straight to the player; Back returns Home.
            LaunchedEffect(playReq) {
                if (playReq != null) {
                    stack = listOf(Screen.Home, Screen.Play(playReq.mpd, playReq.title))
                    onConsumed()
                }
            }

            // Nebula backdrop: crimson glow top-left, violet glow bottom-right.
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0x30E50914), Color.Transparent),
                            center = Offset(size.width * 0.05f, -size.height * 0.08f),
                            radius = size.width * 1.1f,
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0x2E5E36EB), Color.Transparent),
                            center = Offset(size.width * 1.02f, size.height * 1.08f),
                            radius = size.width * 1.0f,
                        )
                    )
                }
            ) {
                val current = stack.last()
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (val s = current) {
                            is Screen.Home -> HomeScreen(
                                homeState,
                                onOpen = { a, item -> push(Screen.Streams(a, item)) },
                                onSeeAll = { a, c -> push(Screen.Catalog(a, c)) },
                                onGoAddons = { setTab(Screen.Addons) },
                            )
                            is Screen.Search -> SearchScreen(
                                searchState,
                                onOpen = { a, item -> push(Screen.Streams(a, item)) },
                            )
                            is Screen.Addons -> AddonsScreen(
                                onOpen = { push(Screen.Catalog(it)) },
                                onAddonsChanged = { homeState.invalidate() },
                            )
                            is Screen.Catalog -> CatalogScreen(
                                s.addon, s.initial,
                                catalogStates.getOrPut(s.addon.manifestUrl) { CatalogUiState() },
                                onBack = { pop() },
                                onOpen = { push(Screen.Streams(s.addon, it)) },
                            )
                            is Screen.Streams -> StreamsScreen(s.addon, s.item, onBack = { pop() }, onPlay = { push(Screen.Play(it.url, it.name)) })
                            is Screen.Play -> PlayerScreen(s.url)
                        }
                    }
                    if (current == Screen.Home || current == Screen.Search || current == Screen.Addons) {
                        BottomBar(current, onTab = { setTab(it) })
                    }
                }
            }
        }
    }
}

// ---------- shared pieces ----------

/** Card wrapper: scales up + white border when focused (TV D-pad) or pressed. */
@Composable
private fun FocusCard(
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val zoom by animateFloatAsState(if (focused) 1.05f else 1f, tween(140), label = "zoom")
    Box(
        modifier
            .scale(zoom)
            .then(if (focused) Modifier.shadow(16.dp, shape, ambientColor = Red, spotColor = Red) else Modifier)
            .clip(shape)
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
    ) { content() }
}

@Composable
private fun BackBar(title: String, sub: String?, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FocusCard(shape = RoundedCornerShape(50), onClick = onBack) {
            Box(
                Modifier.size(42.dp).background(Color(0x14FFFFFF), CircleShape).border(1.dp, Line2, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = TextC, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) Text(sub, color = MutedC, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pill = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .clip(pill)
            .background(if (on) Brush.linearGradient(RedGrad) else Brush.linearGradient(listOf(Color(0x12FFFFFF), Color(0x12FFFFFF))))
            .border(
                if (focused) 2.dp else 1.dp,
                when { focused -> Color.White; on -> Color.Transparent; else -> LineC },
                pill,
            )
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(text, color = if (on) Color.White else Color(0xFFCFD3DA), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** One poster/landscape card — used by the catalog grid, Home rows, and Search. */
@Composable
private fun MetaCard(m: MetaItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(shape = RoundedCornerShape(12.dp), modifier = modifier, onClick = onClick) {
        Column(Modifier.padding(2.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(thumbRatio(m.posterShape))
                    .clip(RoundedCornerShape(11.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF1C1C24), Color(0xFF0D0D12))))
                    .border(1.dp, Color(0x0FFFFFFF), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (m.poster != null) {
                    AsyncImage(
                        model = m.poster, contentDescription = m.name,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        m.name.filter { it.isLetterOrDigit() }.take(2).uppercase().ifEmpty { "••" },
                        color = Color(0xFF3A3A45), fontSize = 22.sp, fontWeight = FontWeight.Black,
                    )
                }
            }
            Text(
                m.name, color = Color(0xFFE8E8EA), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 7.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

/** Row header on Home/Search: "Addon · Catalog" with a See-all pill. */
@Composable
private fun RowHeader(title: String, sub: String?, seeAll: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextC, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (sub != null) Text(sub, color = MutedC, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
        else Spacer(Modifier.weight(1f))
        if (seeAll != null) Chip("See all ›", false, seeAll)
    }
}

/** Bottom tab bar (Stremio-style): Home / Search / Add-ons. */
@Composable
private fun BottomBar(current: Screen, onTab: (Screen) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xF20A0A0E))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineC))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            TabItem("Home", Icons.Filled.Home, current == Screen.Home, Modifier.weight(1f)) { onTab(Screen.Home) }
            TabItem("Search", Icons.Filled.Search, current == Screen.Search, Modifier.weight(1f)) { onTab(Screen.Search) }
            TabItem("Add-ons", Icons.Filled.Extension, current == Screen.Addons, Modifier.weight(1f)) { onTab(Screen.Addons) }
        }
    }
}

@Composable
private fun TabItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val tint = if (on) RedBright else if (focused) Color.White else MutedC
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
            .background(if (on) Color(0x24E50914) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun shimmerAlpha(): Float {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "a",
    )
    return a
}

private fun thumbRatio(shape: String): Float = when (shape) {
    "landscape" -> 16f / 9f
    "square" -> 1f
    else -> 2f / 3f
}

/**
 * "Update available" banner shown on Home. Auto-downloads the APK in the
 * background as soon as it appears (cached per version), then Install is one tap.
 */
@Composable
private fun UpdateCard(version: String, notes: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(0) }
    var apk by remember { mutableStateOf<File?>(null) }
    var phase by remember { mutableStateOf("idle") } // idle · downloading · ready · failed
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun download() {
        phase = "downloading"; progress = 0; message = null
        val f = Updates.downloadApk(ctx, version) { progress = it }
        if (f != null) { apk = f; phase = "ready" } else { phase = "failed"; message = "Download failed — tap Retry" }
    }
    // Kick the download off automatically; reuse a completed one if already cached.
    LaunchedEffect(version) {
        val cached = Updates.cachedApk(ctx, version)
        if (cached != null) { apk = cached; phase = "ready" } else download()
    }

    Row(
        Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(RedDeep, Red)), RoundedCornerShape(14.dp))
            .padding(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Update available · v$version", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                message ?: when (phase) {
                    "downloading" -> "Downloading… $progress%"
                    "ready" -> "Ready — tap Install."
                    else -> notes.ifEmpty { "A new version is available." }
                },
                color = Color(0xFFFFE0E0), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Button(
            onClick = {
                when (phase) {
                    "downloading" -> {}
                    "ready" -> {
                        val f = apk
                        if (f != null && !Updates.installApk(ctx, f)) message = "Allow installs, then tap Install"
                    }
                    else -> scope.launch { download() }
                }
            },
            enabled = phase != "downloading",
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Red),
            shape = RoundedCornerShape(9.dp),
        ) {
            Text(
                when (phase) { "ready" -> "Install"; "downloading" -> "···"; "failed" -> "Retry"; else -> "Update" },
                fontWeight = FontWeight.Bold,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Color(0xFFFFD9D9))
        }
    }
}

// ---------- home (content rows, Stremio-style) ----------
@Composable
private fun HomeScreen(
    st: HomeUiState,
    onOpen: (Addon, MetaItem) -> Unit,
    onSeeAll: (Addon, CatalogRef) -> Unit,
    onGoAddons: () -> Unit,
) {
    val ctx = LocalContext.current
    var update by remember { mutableStateOf<Updates.Release?>(null) }

    // Best-effort update check against GitHub Releases, once per Home entry.
    LaunchedEffect(Unit) {
        val current = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull().orEmpty()
        if (current.isEmpty()) return@LaunchedEffect
        val dismissed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("updateDismissed", "").orEmpty()
        val rel = Updates.latest() ?: return@LaunchedEffect
        if (Updates.isNewer(rel.version, current) && rel.version != dismissed) update = rel
    }

    // Build content rows: each addon's first catalogs, shown as they load.
    // Kept unless the addon list changed or the rows are older than 5 minutes.
    LaunchedEffect(st.refreshKey) {
        val addons = loadAddons(ctx)
        st.hasAddons = addons.isNotEmpty()
        val sig = addons.joinToString("|") { it.manifestUrl }
        if (st.sig == sig && st.rows.isNotEmpty() && System.currentTimeMillis() - st.builtAt < 300_000) return@LaunchedEffect
        st.sig = sig
        st.loading = true
        val rows = mutableListOf<CatRow>()
        st.rows = emptyList()
        for (a in addons) {
            runCatching {
                val cats = manifestFor(a.manifestUrl).second
                for (c in cats.take(3)) {
                    runCatching {
                        val items = Stremio.loadCatalog(a.base, c, null).take(15)
                        if (items.isNotEmpty()) { rows.add(CatRow(a, c, items)); st.rows = rows.toList() }
                    }
                }
            }
        }
        st.builtAt = System.currentTimeMillis()
        st.loading = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text("◆ ", color = Red, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text(
                "NEBULA", fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp,
                style = LocalTextStyle.current.copy(
                    brush = Brush.linearGradient(listOf(Color.White, Color(0xFFFFC2C6), RedBright))
                ),
            )
        }
        update?.let { rel ->
            UpdateCard(
                version = rel.version,
                notes = rel.notes,
                onDismiss = {
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString("updateDismissed", rel.version).apply()
                    update = null
                },
            )
        }
        when {
            !st.hasAddons -> Column(
                Modifier.fillMaxWidth().padding(top = 36.dp)
                    .background(Brush.linearGradient(GlassGrad), RoundedCornerShape(20.dp))
                    .border(1.dp, LineC, RoundedCornerShape(20.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Nothing here yet", color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Add an add-on and its catalogs fill this screen.",
                    color = MutedC, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                Button(
                    onClick = onGoAddons,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .shadow(10.dp, RoundedCornerShape(12.dp), ambientColor = Red, spotColor = Red)
                        .background(Brush.linearGradient(RedGrad), RoundedCornerShape(12.dp)),
                ) { Text("＋ Add an add-on", fontWeight = FontWeight.Bold) }
            }
            st.rows.isEmpty() && st.loading -> {
                val a = shimmerAlpha()
                Column {
                    repeat(2) {
                        Box(Modifier.padding(top = 18.dp, bottom = 10.dp).width(180.dp).height(16.dp)
                            .clip(RoundedCornerShape(8.dp)).background(Surface2.copy(alpha = a)))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            repeat(4) {
                                Box(Modifier.width(210.dp).aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(11.dp)).background(Surface2.copy(alpha = a)))
                            }
                        }
                    }
                }
            }
            st.rows.isEmpty() -> Column(Modifier.padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn’t reach your add-ons right now.", color = MutedC, fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                Chip("Retry", false) { st.invalidate() }
            }
            else -> LazyColumn(state = st.listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                items(st.rows, key = { it.addon.manifestUrl + "/" + it.catalog.type + "/" + it.catalog.id }) { r ->
                    val multi = st.rows.count { it.addon.manifestUrl == r.addon.manifestUrl } > 1
                    Column {
                        RowHeader(
                            r.addon.name,
                            if (multi) "${r.catalog.name} · ${r.catalog.type.replaceFirstChar { it.uppercase() }}" else null,
                        ) { onSeeAll(r.addon, r.catalog) }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(r.items) { m ->
                                MetaCard(m, Modifier.width(if (m.posterShape == "landscape") 210.dp else 124.dp)) { onOpen(r.addon, m) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- search (one query, every add-on) ----------
@Composable
private fun SearchScreen(st: SearchUiState, onOpen: (Addon, MetaItem) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(st.submitted) {
        val q = st.submitted.trim()
        if (q.isEmpty()) { st.sections = emptyList(); st.searchedFor = null; return@LaunchedEffect }
        if (q == st.searchedFor && st.sections.isNotEmpty()) return@LaunchedEffect
        st.searching = true
        val out = mutableListOf<CatRow>()
        st.sections = emptyList()
        for (a in loadAddons(ctx)) {
            runCatching {
                val cats = manifestFor(a.manifestUrl).second
                val sc = cats.firstOrNull { it.search } ?: return@runCatching
                val items = Stremio.loadCatalog(a.base, sc, null, q)
                if (items.isNotEmpty()) { out.add(CatRow(a, sc, items)); st.sections = out.toList() }
            }
        }
        st.searchedFor = q
        st.searching = false
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Text("Search", color = TextC, fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 10.dp))
        OutlinedTextField(
            value = st.query,
            onValueChange = { st.query = it },
            placeholder = { Text("Search across your add-ons", color = MutedC) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MutedC) },
            trailingIcon = {
                if (st.query.isNotEmpty() || st.submitted.isNotEmpty()) {
                    IconButton(onClick = { st.query = ""; st.submitted = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = MutedC)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { st.submitted = st.query.trim() }),
            shape = RoundedCornerShape(999.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = RedBright, unfocusedBorderColor = Line2, cursorColor = Red,
                focusedTextColor = TextC, unfocusedTextColor = TextC,
            ),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        when {
            st.searching && st.sections.isEmpty() ->
                Text("Searching…", color = MutedC, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            st.submitted.isNotBlank() && st.sections.isEmpty() ->
                Text("No matches for “${st.submitted.trim()}”.", color = MutedC, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            else -> LazyColumn(state = st.listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                items(st.sections, key = { it.addon.manifestUrl + "/" + it.catalog.id }) { r ->
                    Column {
                        RowHeader(r.addon.name, "${r.items.size} result" + (if (r.items.size > 1) "s" else ""), null)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(r.items) { m ->
                                MetaCard(m, Modifier.width(if (m.posterShape == "landscape") 210.dp else 124.dp)) { onOpen(r.addon, m) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- add-ons (manage sources) ----------
@Composable
private fun AddonsScreen(onOpen: (Addon) -> Unit, onAddonsChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var addons by remember { mutableStateOf(loadAddons(ctx)) }
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var statusErr by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("Add-ons", color = TextC, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text(
                    "Add a Stremio add-on and its catalogs show up on Home. HLS · DASH · DASH+ClearKey.",
                    color = MutedC, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.linearGradient(GlassGrad), RoundedCornerShape(20.dp))
                    .border(1.dp, LineC, RoundedCornerShape(20.dp))
                    .padding(18.dp)
            ) {
                Text("ADD-ON MANIFEST URL", color = MutedC, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    placeholder = { Text("https://your-addon/…/manifest.json", color = MutedC) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RedBright,
                        unfocusedBorderColor = Line2,
                        cursorColor = Red,
                        focusedTextColor = TextC,
                        unfocusedTextColor = TextC,
                    ),
                )
                Row(Modifier.padding(top = 12.dp)) {
                    Button(
                        onClick = {
                            val u = url.trim()
                            if (!Regex("manifest\\.json").containsMatchIn(u)) {
                                status = "Enter a manifest URL (…/manifest.json)"; statusErr = true; return@Button
                            }
                            status = "Adding…"; statusErr = false
                            scope.launch {
                                runCatching { Stremio.loadManifest(u).first }.onSuccess { a ->
                                    val list = (addons.filterNot { it.manifestUrl == a.manifestUrl } + a)
                                    saveAddons(ctx, list); addons = list; url = ""; onAddonsChanged()
                                    status = "Added ${a.name}"; statusErr = false
                                }.onFailure { status = "Could not load: ${it.message}"; statusErr = true }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .shadow(10.dp, RoundedCornerShape(12.dp), ambientColor = Red, spotColor = Red)
                            .background(Brush.linearGradient(RedGrad), RoundedCornerShape(12.dp)),
                    ) { Text("Add add-on", fontWeight = FontWeight.Bold) }
                }
                if (status.isNotEmpty()) {
                    Text(status, color = if (statusErr) Color(0xFFFF6B6B) else Color(0xFF7CFC7C), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item {
            Text("Your add-ons", color = TextC, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        }
        if (addons.isEmpty()) {
            item { Text("No add-ons yet — paste a manifest URL above.", color = MutedC, fontSize = 14.sp) }
        } else {
            items(addons, key = { it.manifestUrl }) { a ->
                FocusCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), onClick = { onOpen(a) }) {
                    Row(
                        Modifier.fillMaxWidth().background(Brush.linearGradient(GlassGrad), RoundedCornerShape(16.dp))
                            .border(1.dp, LineC, RoundedCornerShape(16.dp)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (a.logo != null) {
                            AsyncImage(
                                model = a.logo, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black),
                            )
                        } else {
                            Box(
                                Modifier.size(48.dp)
                                    .background(Brush.linearGradient(listOf(Red, RedDeep)), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(a.name.take(1).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(a.name, color = TextC, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                a.manifestUrl.removePrefix("https://").removePrefix("http://"),
                                color = MutedC, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            val list = addons.filterNot { it.manifestUrl == a.manifestUrl }
                            saveAddons(ctx, list); addons = list; onAddonsChanged()
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MutedC, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------- catalog ----------
@Composable
private fun CatalogScreen(addon: Addon, initial: CatalogRef?, st: CatalogUiState, onBack: () -> Unit, onOpen: (MetaItem) -> Unit) {
    var catalogs by st::catalogs
    var current by st::current
    var genre by st::genre
    var query by st::query
    var submitted by st::submitted
    var items by st::items
    var loading by st::loading
    var status by st::status

    LaunchedEffect(addon, initial) {
        if (catalogs.isNotEmpty()) {
            // arriving via a "See all" that targets a different catalog of this addon
            val want = initial?.let { i -> catalogs.firstOrNull { it.type == i.type && it.id == i.id } }
            if (want != null && current != want) { current = want; genre = null; query = ""; submitted = "" }
            return@LaunchedEffect
        }
        runCatching { Stremio.loadManifest(addon.manifestUrl).second }
            .onSuccess {
                catalogs = it
                current = initial?.let { i -> it.firstOrNull { c -> c.type == i.type && c.id == i.id } } ?: it.firstOrNull()
                if (it.isEmpty()) { status = "No catalogs."; loading = false }
            }
            .onFailure { status = "Failed: ${it.message}"; loading = false }
    }
    LaunchedEffect(current, genre, submitted) {
        val q = submitted.trim()
        // Re-entering the screen (back from streams) relaunches this effect; if the
        // items on hand already match the wanted state, keep them instead of refetching.
        val want = Triple(current, genre, q)
        if (st.loadedFor == want) return@LaunchedEffect
        if (q.isNotEmpty()) {
            val sc = if (current?.search == true) current else catalogs.firstOrNull { it.search }
            if (sc == null) { items = emptyList(); status = "Search isn’t available here."; loading = false; return@LaunchedEffect }
            loading = true; status = "Searching…"; items = emptyList()
            runCatching { Stremio.loadCatalog(addon.base, sc, null, q) }
                .onSuccess { items = it; status = if (it.isEmpty()) "No matches for “$q”." else "${it.size} result${if (it.size > 1) "s" else ""} for “$q”"; loading = false; st.loadedFor = want }
                .onFailure { status = "Failed: ${it.message}"; loading = false; st.loadedFor = null }
        } else {
            val c = current ?: return@LaunchedEffect
            loading = true; status = "Loading…"; items = emptyList()
            runCatching { Stremio.loadCatalog(addon.base, c, genre) }
                .onSuccess { items = it; status = if (it.isEmpty()) "No items." else "${it.size} items"; loading = false; st.loadedFor = want }
                .onFailure { status = "Failed: ${it.message}"; loading = false; st.loadedFor = null }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        BackBar(addon.name, status, onBack)
        if (catalogs.any { it.search }) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search ${addon.name}", color = MutedC) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MutedC) },
                trailingIcon = {
                    if (query.isNotEmpty() || submitted.isNotEmpty()) {
                        IconButton(onClick = { query = ""; submitted = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = MutedC)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
                shape = RoundedCornerShape(999.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RedBright, unfocusedBorderColor = Line2, cursorColor = Red,
                    focusedTextColor = TextC, unfocusedTextColor = TextC,
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
        }
        if (catalogs.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                items(catalogs) { c -> Chip(c.name, c == current && submitted.isEmpty()) { current = c; genre = null; query = ""; submitted = "" } }
            }
        }
        if (submitted.isEmpty()) current?.genres?.take(20)?.let { gs ->
            if (gs.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                items(gs) { g -> Chip(g, genre == g) { genre = if (genre == g) null else g } }
            }
        }
        if (loading && items.isEmpty()) {
            val a = shimmerAlpha()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                items(12) {
                    Column {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Surface2.copy(alpha = a)))
                        Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.7f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Surface2.copy(alpha = a)))
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                state = st.gridState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                items(items) { m -> MetaCard(m) { onOpen(m) } }
            }
        }
    }
}

// ---------- streams ----------
@Composable
private fun StreamsScreen(addon: Addon, item: MetaItem, onBack: () -> Unit, onPlay: (StreamItem) -> Unit) {
    var streams by remember { mutableStateOf<List<StreamItem>>(emptyList()) }
    var status by remember { mutableStateOf("Loading streams…") }
    LaunchedEffect(item) {
        runCatching { Stremio.loadStreams(addon.base, item.type, item.id) }
            .onSuccess { streams = it; status = if (it.isEmpty()) "No playable streams right now." else "${it.size} stream${if (it.size > 1) "s" else ""}" }
            .onFailure { status = "Failed: ${it.message}" }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        BackBar(item.name, status, onBack)
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(streams) { s ->
                FocusCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), onClick = { onPlay(s) }) {
                    Row(
                        Modifier.fillMaxWidth().background(Brush.linearGradient(GlassGrad), RoundedCornerShape(16.dp))
                            .border(1.dp, LineC, RoundedCornerShape(16.dp)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier.size(42.dp)
                                .shadow(8.dp, CircleShape, ambientColor = Red, spotColor = Red)
                                .background(Brush.linearGradient(RedGrad), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(s.name.ifEmpty { "Stream" }, color = TextC, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = s.title.replace("\n", " · ")
                            if (sub.isNotEmpty()) Text(sub, color = MutedC, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ---------- player (unchanged behavior) ----------
@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(url: String) {
    val context = LocalContext.current
    val activity = context as? Activity
    var error by remember { mutableStateOf<String?>(null) }
    var videoQualityCount by remember { mutableStateOf(0) }
    var controllerVisible by remember { mutableStateOf(true) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var skipFlash by remember { mutableStateOf<Triple<Int, Int, Long>?>(null) } // zone (-1/+1), total secs, stamp
    var dragSeek by remember { mutableStateOf<Pair<Long, Long>?>(null) }        // target ms, delta ms
    val exo = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }

    fun seekBy(deltaMs: Long) {
        var t = exo.currentPosition + deltaMs
        if (t < 0) t = 0
        val dur = exo.duration
        if (dur != C.TIME_UNSET && t > dur) t = dur
        exo.seekTo(t)
    }
    fun doSkip(zone: Int) {
        seekBy(zone * 10_000L)
        val now = System.currentTimeMillis()
        val prev = skipFlash
        // Rapid re-taps on the same side accumulate (10s, 20s, 30s…) like YouTube.
        val total = if (prev != null && prev.first == zone && now - prev.third < 1200) prev.second + 10 else 10
        skipFlash = Triple(zone, total, now)
    }
    LaunchedEffect(skipFlash) { if (skipFlash != null) { delay(800); skipFlash = null } }

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
            override fun onTracksChanged(tracks: Tracks) {
                var n = 0
                for (g in tracks.groups) {
                    if (g.type == C.TRACK_TYPE_VIDEO) {
                        for (i in 0 until g.length) {
                            if (g.isTrackSupported(i) && g.getTrackFormat(i).height > 0) n++
                        }
                    }
                }
                videoQualityCount = n
            }
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
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                        controllerVisible = (v == View.VISIBLE)
                    })
                    playerViewRef = this
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
        // Touch gestures (phones): double-tap left/right = ±10s, horizontal swipe = seek,
        // plain tap = show controls. Mounted only while the controller is hidden so the
        // controller's own buttons stay tappable; remote/D-pad (TV) is unaffected.
        if (!controllerVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { pos ->
                                val zone = tapZone(pos.x, size.width)
                                val f = skipFlash
                                if (zone != 0 && f != null && f.first == zone &&
                                    System.currentTimeMillis() - f.third < 900
                                ) doSkip(zone) // a quick 3rd/4th tap keeps skipping
                                else playerViewRef?.showController()
                            },
                            onDoubleTap = { pos ->
                                val zone = tapZone(pos.x, size.width)
                                if (zone != 0) doSkip(zone) else playerViewRef?.showController()
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        var base = 0L
                        var accum = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { base = exo.currentPosition; accum = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                accum += dragAmount
                                var t = base + (accum / size.width * 90_000f).toLong() // full-width swipe ≈ 90s
                                if (t < 0) t = 0
                                val dur = exo.duration
                                if (dur != C.TIME_UNSET && t > dur) t = dur
                                dragSeek = Pair(t, t - base)
                            },
                            onDragEnd = { dragSeek?.let { exo.seekTo(it.first) }; dragSeek = null },
                            onDragCancel = { dragSeek = null }
                        )
                    }
            )
        }
        // Quality picker — only when the stream actually has more than one video quality.
        if (videoQualityCount >= 2) {
            IconButton(
                onClick = {
                    runCatching {
                        TrackSelectionDialogBuilder(context, "Quality", exo, C.TRACK_TYPE_VIDEO)
                            .setAllowAdaptiveSelections(true)
                            .setShowDisableOption(false)
                            .build()
                            .show()
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(44.dp)
                    .background(Color(0xB3000000), CircleShape)
            ) {
                Text("⚙", color = Color.White, fontSize = 20.sp)
            }
        }
        // Transient ±10s indicator on the tapped side.
        skipFlash?.let { f ->
            Text(
                (if (f.first > 0) "⏩ " else "⏪ ") + "${f.second}s",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(if (f.first > 0) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 44.dp)
                    .background(Color(0x8C000000), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
        // Live seek preview while swiping: target position + signed delta.
        dragSeek?.let { d ->
            Text(
                fmtTime(d.first) + "  (" + (if (d.second >= 0) "+" else "−") + fmtTime(abs(d.second)) + ")",
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x8C000000), RoundedCornerShape(24.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }
        error?.let {
            Text(it, color = Color(0xFFFF6B6B), modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }
}

/** Which double-tap zone an x position falls in: -1 left, +1 right, 0 middle (dead zone). */
private fun tapZone(x: Float, width: Int): Int = when {
    x < width * 0.35f -> -1
    x > width * 0.65f -> 1
    else -> 0
}

private fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
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
