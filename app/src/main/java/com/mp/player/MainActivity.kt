package com.mp.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.common.util.concurrent.ListenableFuture

// Globaler Zugriff auf den MediaController, damit jeder Screen (Library,
// Player, Presets) dieselbe, echte Wiedergabe steuert statt eigener Fake-Logik.
// Den ANZEIGE-Zustand liefert ausschliesslich PlaybackState.
object PlayerBridge {
    var controller: MediaController? by mutableStateOf(null)
}

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val main = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var reconnectAttempts = 0

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ohne diese Berechtigung zeigt Android 13+ die Medien-Benachrichtigung nicht an.
        // (Ordnerzugriff laeuft ueber SAF und braucht keine Medien-Berechtigung.)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        connect()
        setContent { MpApp() }
    }

    /** Verbindet mit dem PlayerService; verbindet sich neu, falls der Service-Prozess neu startet. */
    private fun connect() {
        if (destroyed) return
        try {
            val token = SessionToken(this, android.content.ComponentName(this, PlayerService::class.java))
            val future = MediaController.Builder(this, token)
                .setListener(object : MediaController.Listener {
                    override fun onDisconnected(controller: MediaController) {
                        main.post {
                            PlayerBridge.controller = null
                            PlaybackState.detach()
                            if (!destroyed) main.postDelayed({ connect() }, 500)
                        }
                    }
                })
                .buildAsync()
            controllerFuture = future
            future.addListener({
                try {
                    val c = future.get()
                    if (destroyed) {
                        MediaController.releaseFuture(future)
                        return@addListener
                    }
                    reconnectAttempts = 0
                    PlayerBridge.controller = c
                    PlaybackState.attach(c)
                } catch (e: Exception) {
                    if (!destroyed && reconnectAttempts++ < 5) main.postDelayed({ connect() }, 1000)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            if (!destroyed && reconnectAttempts++ < 5) main.postDelayed({ connect() }, 1000)
        }
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        PlayerBridge.controller = null
        PlaybackState.detach()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@Composable
fun MpApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Bibliothek einmal beim Start im Hintergrund laden (nicht erst, wenn die Startseite geoeffnet wird)
    LaunchedEffect(Unit) { LibraryState.loadAsync(context) }
    // Playlists beim Start laden (liegen in playlists.json; erster Start uebernimmt die alten Playlists)
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PlaylistStore.ensureLoaded(context) } }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            bottomBar = {
                Column {
                    // Mini-Player ueber der Navigationsleiste, auf JEDEM Screen sichtbar, solange ein Titel aktiv ist
                    // (ausser auf dem Wiedergabebildschirm selbst, dort ist der volle Player schon offen).
                    if (currentRoute != Routes.PLAYER) {
                        MiniPlayerBar(onOpen = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } })
                    }
                    if (currentRoute != Routes.PLAYER) {
                        BottomNavBar(
                            onCategories = { nav.navigate(Routes.LIBRARY_HOME) { launchSingleTop = true } },
                            onVisualizer = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } },
                            onSearch = { nav.navigate(Routes.SEARCH) { launchSingleTop = true } },
                            onMenu = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } } // Punkt 8: ☰ -> Einstellungen
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                NavHost(navController = nav, startDestination = Routes.LIBRARY_HOME) {
                    mpNavGraph(nav)
                }
            }
        }
    }
}

object Routes {
    const val LIBRARY_HOME = "library_home"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val PRESETS = "presets"
    const val QUEUE = "queue"
}
