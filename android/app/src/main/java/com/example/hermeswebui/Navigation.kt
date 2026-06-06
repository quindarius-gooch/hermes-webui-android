package com.example.hermeswebui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.hermeswebui.ui.setup.SetupScreen
import com.example.hermeswebui.ui.web.WebScreen

@Composable
fun MainNavigation() {
  val context = LocalContext.current
  val sharedPrefs = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
  val savedUrl = sharedPrefs.getString("server_url", null)

  val startDestination = if (savedUrl.isNullOrEmpty()) Setup else Web(savedUrl)
  val backStack = rememberNavBackStack(startDestination)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Setup> {
          SetupScreen(
            onConnectSuccess = { url ->
              backStack.clear()
              backStack.add(Web(url))
            },
            modifier = Modifier.fillMaxSize()
          )
        }
        entry<Web> { key ->
          WebScreen(
            url = key.url,
            onExit = {
              sharedPrefs.edit().remove("server_url").apply()
              backStack.clear()
              backStack.add(Setup)
            },
            modifier = Modifier.fillMaxSize()
          )
        }
      },
  )
}
