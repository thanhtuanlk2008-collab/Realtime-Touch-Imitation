package com.borgorninja.androtask

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.borgorninja.androtask.ui.*
import com.borgorninja.androtask.viewmodel.MacroViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MacroViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.i("Main", "App started")
        createNotificationChannel()

        setContent {
            AndroTaskTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home")        { HomeScreen(nav, viewModel) }
                    composable("editor/{id}") { back ->
                        val id = back.arguments?.getString("id")?.toLongOrNull() ?: 0L
                        MacroEditorScreen(nav, id, viewModel)
                    }
                    composable("autoclicker") { AutoClickerScreen(nav, viewModel) }
                    composable("settings")    { SettingsScreen(nav) }
                    composable("onboarding")  { OnboardingScreen(nav) }
                    composable("logs")        { LogScreen(nav) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from Android's overlay-permission screen.
        if (Settings.canDrawOverlays(this)) {
            ContextCompat.startForegroundService(
                this, Intent(this, FloatingOverlayService::class.java)
            )
        }
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "AndroTask Overlay", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shown while floating bubble is active" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    companion object { const val CHANNEL_ID = "androtask_overlay" }
}
