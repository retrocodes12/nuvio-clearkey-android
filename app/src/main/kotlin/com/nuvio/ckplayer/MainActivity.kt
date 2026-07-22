package com.nuvio.ckplayer

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
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
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.scale
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
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
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
        // only honor the launch intent on a fresh start — a recreated activity
        // (process restore, config change) must not jump back into the player
        pendingPlay.value = if (savedInstanceState == null) parsePlayIntent(intent) else null
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

    // ---------- picture-in-picture ----------
    fun pipSupported(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipAspect(): Rational {
        val vs = activePipPlayer.value?.videoSize
        var w = vs?.width ?: 0
        var h = vs?.height ?: 0
        if (w <= 0 || h <= 0) { w = 16; h = 9 }
        // Android rejects PiP aspect ratios outside roughly 1:2.39 – 2.39:1
        val ratio = w.toFloat() / h
        return when {
            ratio > 2.35f -> Rational(235, 100)
            ratio < 0.43f -> Rational(43, 100)
            else -> Rational(w, h)
        }
    }

    fun enterPip(): Boolean {
        if (!pipSupported() || activePipPlayer.value == null) return false
        return runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(pipAspect()).build()
            )
        }.getOrDefault(false)
    }

    /** Keep the OS-level PiP params current: real aspect ratio + (API 31+) auto-enter on Home while playing. */
    fun refreshPipParams() {
        if (!pipSupported()) return
        runCatching {
            val b = PictureInPictureParams.Builder().setAspectRatio(pipAspect())
            if (Build.VERSION.SDK_INT >= 31) {
                b.setAutoEnterEnabled(activePipPlayer.value?.isPlaying == true)
            }
            setPictureInPictureParams(b.build())
        }
    }

    // Home press while a video plays → keep it going in a floating window.
    // API 31+ auto-enters via setAutoEnterEnabled; this covers API 26–30.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && activePipPlayer.value?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipMode.value = isInPictureInPictureMode
        // Left PiP while the activity stayed stopped = the floating window was dismissed
        // (not expanded back to full screen) — pause instead of playing on invisibly.
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED) {
            activePipPlayer.value?.pause()
        }
    }
}

/** The ExoPlayer currently on screen (wired by PlayerScreen) so the activity can drive PiP. */
private val activePipPlayer = mutableStateOf<ExoPlayer?>(null)

/** True while the app is in picture-in-picture; PlayerScreen hides all chrome. */
private val inPipMode = mutableStateOf(false)

// ---------- Nebula palette (matches the web/webOS player: flat, restrained) ----------
private val Red = Color(0xFFE50914)
private val Bg = Color(0xFF0B0B0F)
private val SurfaceC = Color(0xFF16161C)
private val Surface2 = Color(0xFF1E1E25)
private val LineC = Color(0xFF26262E)
private val Line2 = Color(0xFF33333C)
private val MutedC = Color(0xFFA3A3AD)
private val TextC = Color(0xFFFFFFFF)

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
    data class Play(val url: String, val title: String, val subs: List<SubTrack> = emptyList()) : Screen
}

/** One catalog's worth of content, tagged with where it came from. */
private class CatRow(val addon: Addon, val catalog: CatalogRef, val items: List<MetaItem>)

/** Session cache of addon manifests (Home and Search both need them). */
private val manifestCache = mutableMapOf<String, ManifestInfo>()
private suspend fun manifestFor(url: String): ManifestInfo =
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
    // the See-all target already applied, so re-entering doesn't reset a user's catalog switch
    var appliedInitial: CatalogRef? = null
    val gridState = LazyGridState()
}

/** A play request arriving from a nebula://play deep link. */
data class PlayReq(val mpd: String, val title: String)

/**
 * Watch-party UI state, file-level (like manifestCache) so it survives the nav
 * stack: AppRoot owns the session + events, PlayerScreen drives sync through it.
 */
private class PartyUi {
    var session: PartySession? = null
    var code by mutableStateOf<String?>(null)
    var isHost by mutableStateOf(false)
    var count by mutableStateOf(1)
    var status by mutableStateOf<String?>(null)
    @Volatile var lastState: PartyState? = null
    var lastSeekAt = 0L
    fun active() = code != null
    fun reset() {
        session?.leave(); session = null
        code = null; isHost = false; count = 1; lastState = null
    }
}
private val partyUi = PartyUi()

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
            val ctx = LocalContext.current
            val scope = rememberCoroutineScope()
            fun push(s: Screen) { stack = stack + s }
            fun pop() {
                if (stack.size > 1) {
                    // a viewer backing out of playback leaves the party (the host keeps it alive)
                    if (stack.last() is Screen.Play && partyUi.active() && !partyUi.isHost) {
                        partyUi.reset(); partyUi.status = "Left the party"
                    }
                    stack = stack.dropLast(1)
                }
            }
            fun setTab(s: Screen) { stack = listOf(s) }

            // ---- watch party wiring ----
            fun partyEvent(ev: PartyEvent) {
                when (ev) {
                    is PartyEvent.Created -> {
                        partyUi.code = ev.code; partyUi.isHost = true; partyUi.count = 1
                        partyUi.status = "Party started — code ${ev.code}. Friends: Add-ons tab, Join party."
                    }
                    is PartyEvent.Joined -> {
                        partyUi.code = ev.code; partyUi.isHost = false; partyUi.count = ev.count
                        partyUi.lastState = ev.state
                        partyUi.status = "Joined party ${ev.code}"
                        ev.stream?.let { st -> stack = listOf(Screen.Home, Screen.Play(st.url, st.title, st.subs)) }
                    }
                    is PartyEvent.State -> partyUi.lastState = ev.state
                    is PartyEvent.StreamSwitch -> {
                        if (!partyUi.isHost) {
                            partyUi.lastState = null
                            partyUi.status = "Host switched streams"
                            stack = listOf(Screen.Home, Screen.Play(ev.stream.url, ev.stream.title, ev.stream.subs))
                        }
                    }
                    is PartyEvent.Peers -> partyUi.count = ev.count
                    PartyEvent.Promoted -> { partyUi.isHost = true; partyUi.status = "You are now the party host" }
                    is PartyEvent.Ended -> { partyUi.reset(); partyUi.status = ev.reason }
                    is PartyEvent.Error -> partyUi.status = ev.message
                    PartyEvent.Disconnected -> { partyUi.reset(); partyUi.status = "Party connection lost" }
                }
            }
            fun partyStart(stream: PartyStreamDesc) {
                partyUi.reset()
                partyUi.status = "Starting party…"
                partyUi.session = PartySession(scope) { partyEvent(it) }.also { it.create(stream) }
            }
            fun partyJoin(codeRaw: String) {
                val code = codeRaw.trim().replace(" ", "").uppercase()
                if (code.length < 4) { partyUi.status = "Enter the party code first"; return }
                partyUi.reset()
                partyUi.status = "Joining party…"
                partyUi.session = PartySession(scope) { partyEvent(it) }.also { it.join(code) }
            }
            fun partyLeave() { partyUi.reset(); partyUi.status = "Left the party" }
            LaunchedEffect(partyUi.status) {
                partyUi.status?.let {
                    android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show()
                    partyUi.status = null
                }
            }
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

            Box(Modifier.fillMaxSize()) {
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
                                onAddonsChanged = { manifestCache.clear(); homeState.invalidate() },
                                onJoinParty = { partyJoin(it) },
                            )
                            is Screen.Catalog -> CatalogScreen(
                                s.addon, s.initial,
                                catalogStates.getOrPut(s.addon.manifestUrl) { CatalogUiState() },
                                onBack = { pop() },
                                onOpen = { push(Screen.Streams(s.addon, it)) },
                            )
                            is Screen.Streams -> StreamsScreen(s.addon, s.item, onBack = { pop() }, onPlay = { push(Screen.Play(it.url, it.name, it.subtitles)) })
                            is Screen.Play -> PlayerScreen(
                                s.url, s.title, s.subs,
                                onPartyStart = { partyStart(it) },
                                onPartyLeave = { partyLeave() },
                            )
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
                Modifier.size(42.dp).background(Surface2, CircleShape),
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
            .background(if (on) Color.White else Surface2)
            .border(2.dp, if (focused) (if (on) Bg else Color.White) else Color.Transparent, pill)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(text, color = if (on) Bg else Color(0xFFC9C9D1), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
                    .background(SurfaceC)
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
    val tint = if (on) Color.White else if (focused) Color.White else MutedC
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
            .background(Red, RoundedCornerShape(14.dp))
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
                val cats = manifestFor(a.manifestUrl).catalogs
                val seenCat = HashSet<String>()
                for (c in cats.take(3)) {
                    if (!seenCat.add(c.type + "/" + c.id)) continue
                    runCatching {
                        val items = Stremio.loadCatalog(a.base, c, null).take(15)
                        if (items.isNotEmpty()) { rows.add(CatRow(a, c, items)); st.rows = rows.toList() }
                    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        st.builtAt = System.currentTimeMillis()
        st.loading = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text("◆ ", color = Red, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("NEBULA", color = TextC, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
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
                    .background(SurfaceC, RoundedCornerShape(20.dp))
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
                    colors = ButtonDefaults.buttonColors(containerColor = Red),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("Add an add-on", fontWeight = FontWeight.SemiBold) }
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
                val cats = manifestFor(a.manifestUrl).catalogs
                val sc = cats.firstOrNull { it.search } ?: return@runCatching
                val items = Stremio.loadCatalog(a.base, sc, null, q)
                if (items.isNotEmpty()) { out.add(CatRow(a, sc, items)); st.sections = out.toList() }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
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
                focusedBorderColor = Color.White, unfocusedBorderColor = Line2, cursorColor = Red,
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
private fun AddonsScreen(onOpen: (Addon) -> Unit, onAddonsChanged: () -> Unit, onJoinParty: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var addons by remember { mutableStateOf(loadAddons(ctx)) }
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var statusErr by remember { mutableStateOf(false) }
    var partyCode by remember { mutableStateOf("") }

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
                    .background(SurfaceC, RoundedCornerShape(20.dp))
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
                        focusedBorderColor = Color.White,
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
                                runCatching { Stremio.loadManifest(u).addon }.onSuccess { a ->
                                    val list = (addons.filterNot { it.manifestUrl == a.manifestUrl } + a)
                                    saveAddons(ctx, list); addons = list; url = ""; onAddonsChanged()
                                    status = "Added ${a.name}"; statusErr = false
                                }.onFailure { status = "Could not load: ${it.message}"; statusErr = true }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Red),
                        shape = RoundedCornerShape(8.dp),
                    ) { Text("Add add-on", fontWeight = FontWeight.SemiBold) }
                }
                if (status.isNotEmpty()) {
                    Text(status, color = if (statusErr) Color(0xFFFF6B6B) else Color(0xFF7CFC7C), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(SurfaceC, RoundedCornerShape(20.dp))
                    .border(1.dp, LineC, RoundedCornerShape(20.dp))
                    .padding(18.dp)
            ) {
                Text("WATCH PARTY", color = MutedC, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = partyCode, onValueChange = { partyCode = it },
                        placeholder = { Text("Party code", color = MutedC) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Line2,
                            cursorColor = Red,
                            focusedTextColor = TextC,
                            unfocusedTextColor = TextC,
                        ),
                    )
                    Button(
                        onClick = { onJoinParty(partyCode) },
                        colors = ButtonDefaults.buttonColors(containerColor = Red),
                        shape = RoundedCornerShape(8.dp),
                    ) { Text("Join", fontWeight = FontWeight.SemiBold) }
                }
                Text(
                    "To start one: play a stream, then tap the party button in the player. Friends enter your code here and watch in sync.",
                    color = MutedC, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                )
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
                        Modifier.fillMaxWidth().background(SurfaceC, RoundedCornerShape(16.dp))
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
                                    .background(Red, RoundedCornerShape(10.dp)),
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
            // arriving via a "See all" that targets a different catalog of this addon —
            // apply it once, so coming back from a stream keeps the user's own switch
            if (initial != null && initial != st.appliedInitial) {
                st.appliedInitial = initial
                val want = catalogs.firstOrNull { it.type == initial.type && it.id == initial.id }
                if (want != null && current != want) { current = want; genre = null; query = ""; submitted = "" }
            }
            return@LaunchedEffect
        }
        runCatching { Stremio.loadManifest(addon.manifestUrl).catalogs }
            .onSuccess {
                catalogs = it
                st.appliedInitial = initial
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
                    focusedBorderColor = Color.White, unfocusedBorderColor = Line2, cursorColor = Red,
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
// Stremio semantics: catalog add-ons and stream add-ons are separate. Ask the
// add-on the item came from PLUS every other installed add-on whose manifest
// serves streams for this type/id, and show the answers grouped per add-on.
@Composable
private fun StreamsScreen(addon: Addon, item: MetaItem, onBack: () -> Unit, onPlay: (StreamItem) -> Unit) {
    var sections by remember { mutableStateOf<List<Pair<String, List<StreamItem>>>>(emptyList()) }
    var status by remember { mutableStateOf("Loading streams…") }
    val ctx = LocalContext.current
    LaunchedEffect(item) {
        val order = listOf(addon) + loadAddons(ctx).filterNot { it.manifestUrl == addon.manifestUrl }
        val out = mutableListOf<Pair<String, List<StreamItem>>>()
        var failures = 0
        for (a in order) {
            runCatching {
                // origin is always asked; others only if their manifest matches
                if (a.manifestUrl != addon.manifestUrl &&
                    !manifestFor(a.manifestUrl).canStream(item.type, item.id)) return@runCatching
                val streams = Stremio.loadStreams(a.base, item.type, item.id)
                if (streams.isNotEmpty()) {
                    out.add(a.name to streams)
                    sections = out.toList()
                    val n = out.sumOf { it.second.size }
                    status = "$n stream${if (n > 1) "s" else ""}" + (if (out.size > 1) " from ${out.size} add-ons" else "")
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; failures++ }
        }
        if (sections.isEmpty()) {
            status = if (failures == order.size) "Failed to load streams." else "No playable streams right now."
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        BackBar(item.name, status, onBack)
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            sections.forEachIndexed { sectionIndex, (addonName, streams) ->
                if (sections.size > 1) item(key = "head/$sectionIndex") {
                    Text(
                        addonName, color = TextC, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            items(streams) { s ->
                FocusCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), onClick = { onPlay(s) }) {
                    Row(
                        Modifier.fillMaxWidth().background(SurfaceC, RoundedCornerShape(16.dp))
                            .border(1.dp, LineC, RoundedCornerShape(16.dp)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier.size(42.dp).background(Red, CircleShape),
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
}

// ---------- player (unchanged behavior) ----------
@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(
    url: String,
    title: String = "Nebula",
    subs: List<SubTrack> = emptyList(),
    onPartyStart: (PartyStreamDesc) -> Unit = {},
    onPartyLeave: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var hostDirty by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var videoQualityCount by remember { mutableStateOf(0) }
    var audioTrackCount by remember { mutableStateOf(0) }
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
            if (subs.isNotEmpty()) {
                b.setSubtitleConfigurations(
                    subs.map { st ->
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(st.url))
                            .setLanguage(st.lang)
                            .setMimeType(
                                if (Regex("\\.srt(\\?|#|$)", RegexOption.IGNORE_CASE).containsMatchIn(st.url))
                                    MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT
                            )
                            .build()
                    }
                )
            }
            exo.setMediaItem(b.build())
            exo.prepare()
        }.onFailure { error = it.message }
    }

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = "Playback error ${e.errorCodeName} (${e.errorCode})" }
            override fun onTracksChanged(tracks: Tracks) {
                var v = 0
                var au = 0
                for (g in tracks.groups) {
                    when (g.type) {
                        C.TRACK_TYPE_VIDEO -> for (i in 0 until g.length) {
                            if (g.isTrackSupported(i) && g.getTrackFormat(i).height > 0) v++
                        }
                        C.TRACK_TYPE_AUDIO -> if (g.length > 0) au++
                    }
                }
                videoQualityCount = v
                audioTrackCount = au
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                (activity as? MainActivity)?.refreshPipParams()
                if (partyUi.active() && partyUi.isHost) hostDirty = true
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) { (activity as? MainActivity)?.refreshPipParams() }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && partyUi.active() && partyUi.isHost) hostDirty = true
            }
        }
        exo.addListener(l)
        activePipPlayer.value = exo
        (activity as? MainActivity)?.refreshPipParams()
        onDispose {
            exo.removeListener(l); exo.release()
            if (activePipPlayer.value === exo) activePipPlayer.value = null
            // Clears (API 31+) auto-enter so backing out of the player can't PiP the browse UI.
            (activity as? MainActivity)?.refreshPipParams()
            activity?.let {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                setImmersive(it, false)
            }
        }
    }

    // In picture-in-picture only the video shows — no controller, gestures, or overlay buttons.
    val pip = inPipMode.value
    LaunchedEffect(pip) {
        playerViewRef?.useController = !pip
        if (pip) playerViewRef?.hideController()
    }

    // A party host arriving on a new stream takes the whole room along (mirrors the web player).
    LaunchedEffect(Unit) {
        if (partyUi.active() && partyUi.isHost) {
            partyUi.session?.sendStream(PartyStreamDesc(url, title, subs))
        }
    }
    // Watch-party sync loop: hosts broadcast state, viewers glide to the host's position.
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            delay(1000)
            n++
            if (!partyUi.active()) continue
            if (partyUi.isHost) {
                if (hostDirty || n % 4 == 0) {
                    hostDirty = false
                    val live = exo.isCurrentMediaItemLive
                    val pos = if (live) exo.currentLiveOffset.coerceAtLeast(0L) / 1000.0
                              else exo.currentPosition.coerceAtLeast(0L) / 1000.0
                    partyUi.session?.sendState(exo.isPlaying, pos, live)
                }
                continue
            }
            val s = partyUi.lastState ?: continue
            if (!s.playing) {
                if (exo.isPlaying) exo.pause()
                exo.setPlaybackSpeed(1f)
                if (!s.live && abs(exo.currentPosition / 1000.0 - s.pos) > 1) exo.seekTo((s.pos * 1000).toLong())
                continue
            }
            if (!exo.isPlaying && exo.playbackState == Player.STATE_READY) exo.play()
            val err: Double = if (s.live) {
                if (!exo.isCurrentMediaItemLive) continue
                exo.currentLiveOffset.coerceAtLeast(0L) / 1000.0 - s.pos
            } else {
                s.pos + (System.currentTimeMillis() - s.atLocal) / 1000.0 - exo.currentPosition / 1000.0
            }
            when {
                abs(err) > 1.25 -> {
                    if (System.currentTimeMillis() - partyUi.lastSeekAt > 4000) {
                        partyUi.lastSeekAt = System.currentTimeMillis()
                        exo.seekTo((exo.currentPosition + err * 1000).toLong().coerceAtLeast(0L))
                    }
                    exo.setPlaybackSpeed(1f)
                }
                err > 0.4 -> exo.setPlaybackSpeed(1.06f)
                err < -0.4 -> exo.setPlaybackSpeed(0.94f)
                abs(err) < 0.15 -> exo.setPlaybackSpeed(1f)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exo
                    useController = true
                    setShowSubtitleButton(true)
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
        if (!controllerVisible && !pip) {
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
        // Party badge: room code + member count, shown with the rest of the chrome.
        if (!pip && controllerVisible && partyUi.active()) {
            Text(
                "${partyUi.code} · ${partyUi.count}",
                color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color.White, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
        // PiP + party + quality + audio pickers — these are chrome: they hide with the controller.
        if (!pip && controllerVisible) Column(
            Modifier.align(Alignment.TopEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(
                onClick = {
                    if (partyUi.active()) onPartyLeave()
                    else onPartyStart(PartyStreamDesc(url, title, subs))
                },
                modifier = Modifier.size(44.dp).background(if (partyUi.active()) Color.White else Color(0xB3000000), CircleShape)
            ) {
                Icon(
                    Icons.Filled.Groups, contentDescription = if (partyUi.active()) "Leave party" else "Start watch party",
                    tint = if (partyUi.active()) Color.Black else Color.White, modifier = Modifier.size(24.dp)
                )
            }
            val mainActivity = activity as? MainActivity
            if (mainActivity?.pipSupported() == true) {
                IconButton(
                    onClick = { mainActivity.enterPip() },
                    modifier = Modifier.size(44.dp).background(Color(0xB3000000), CircleShape)
                ) {
                    Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "Picture-in-picture", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
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
                    modifier = Modifier.size(44.dp).background(Color(0xB3000000), CircleShape)
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Quality", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            if (audioTrackCount >= 2) {
                IconButton(
                    onClick = {
                        runCatching {
                            TrackSelectionDialogBuilder(context, "Audio", exo, C.TRACK_TYPE_AUDIO)
                                .setShowDisableOption(false)
                                .build()
                                .show()
                        }
                    },
                    modifier = Modifier.size(44.dp).background(Color(0xB3000000), CircleShape)
                ) {
                    Icon(Icons.Filled.Audiotrack, contentDescription = "Audio", tint = Color.White, modifier = Modifier.size(22.dp))
                }
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
