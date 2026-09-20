package com.fernando.ahora

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.fernando.ahora.reminders.AppLinks
import com.fernando.ahora.reminders.LinkDestination
import com.fernando.ahora.reminders.NotificationLinkHandler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var links: NotificationLinkHandler

    /** Where the last notification link resolved to. The navigation host (M3) consumes and clears it. */
    val pendingDestination = MutableStateFlow<LinkDestination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Text("AHORA") }
        // Cold start only: a recreated activity (rotation, process restore) must not replay the launch intent.
        if (savedInstanceState == null) handleLink(intent)
        // Warm start (singleTop): the notification tap arrives here while the activity is already on screen.
        addOnNewIntentListener(::handleLink)
    }

    private fun handleLink(intent: Intent) {
        val link = AppLinks.parse(intent.data) ?: return
        lifecycleScope.launch { pendingDestination.value = links.resolve(link) }
    }
}
