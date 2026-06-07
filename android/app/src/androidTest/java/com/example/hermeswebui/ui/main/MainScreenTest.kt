package com.example.hermeswebui.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.hermeswebui.ui.setup.SetupScreen
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Smoke tests for the Android client setup screen. */
class MainScreenTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Before
  fun setup() {
    composeTestRule.setContent { SetupScreen(onConnectSuccess = {}) }
  }

  @Test
  fun setupScreen_showsConnectionForm() {
    composeTestRule.onNodeWithText("HERMES").assertExists()
    composeTestRule.onNodeWithText("Connect to Hermes Server").assertExists()
    composeTestRule.onNodeWithText("CONNECT").assertExists()
  }
}
