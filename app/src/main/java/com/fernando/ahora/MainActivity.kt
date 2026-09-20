package com.fernando.ahora

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.fernando.ahora.domain.model.ThemeMode
import com.fernando.ahora.reminders.AppLinks
import com.fernando.ahora.reminders.LinkDestination
import com.fernando.ahora.reminders.NotificationLinkHandler
import com.fernando.ahora.ui.AhoraRoot
import com.fernando.ahora.ui.MainViewModel
import com.fernando.ahora.ui.theme.AhoraTheme
import com.fernando.ahora.ui.theme.DarkAhoraColors
import com.fernando.ahora.ui.theme.LightAhoraColors
import com.fernando.ahora.ui.theme.useDark
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var links: NotificationLinkHandler

    private val mainViewModel: MainViewModel by viewModels()

    /**
     * Validated notification destinations, consumed once by the root (cold AND warm start). A channel rather than a
     * state: two identical taps in a row are two events, and none is lost before the first frame.
     */
    private val destinations = Channel<LinkDestination>(Channel.UNLIMITED)
    private val destinationFlow: Flow<LinkDestination> = destinations.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hold the first frame until the theme preference has been read, so the wrong theme never flashes (NFR-08).
        splash.setKeepOnScreenCondition { mainViewModel.themeMode.value == null }
        enableEdgeToEdge()

        setContent {
            val theme by mainViewModel.themeMode.collectAsStateWithLifecycle()
            val mode = theme ?: ThemeMode.SYSTEM
            val dark = mode.useDark()
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                val ground = if (dark) DarkAhoraColors.bg else LightAhoraColors.bg
                window.setBackgroundDrawable(ColorDrawable(ground.toArgb()))
                onDispose { }
            }
            AhoraTheme(mode) { AhoraRoot(links = destinationFlow) }
        }

        // Cold start only: a recreated activity (rotation, process restore) must not replay the launch intent.
        if (savedInstanceState == null) handleLink(intent)
        // Warm start (singleTop): the notification tap arrives here while the activity is already on screen.
        addOnNewIntentListener(::handleLink)
    }

    private fun handleLink(intent: Intent) {
        val link = AppLinks.parse(intent.data) ?: return
        lifecycleScope.launch { links.resolve(link)?.let { destinations.send(it) } }
    }
}
