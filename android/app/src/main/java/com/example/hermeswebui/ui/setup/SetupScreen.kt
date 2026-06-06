package com.example.hermeswebui.ui.setup

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// Custom Palette matching Hermes Dark gold/charcoal skin
private val HermesBg = Color(0xFF101012)
private val HermesCardBg = Color(0xFF1A1A1E)
private val HermesGold = Color(0xFFE5C158)
private val HermesTextPrimary = Color(0xFFEAEAEA)
private val HermesTextSecondary = Color(0xFF8E8E93)
private val HermesBorder = Color(0xFF2C2C30)
private val HermesError = Color(0xFFFF453A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onConnectSuccess: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var urlInput by remember { mutableStateOf("http://") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Load previously typed/saved URL if it exists
    LaunchedEffect(Unit) {
        val sharedPrefs = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
        val savedUrl = sharedPrefs.getString("server_url", "")
        val lastInput = sharedPrefs.getString("last_input_url", "")
        if (!savedUrl.isNullOrEmpty()) {
            urlInput = savedUrl
        } else if (!lastInput.isNullOrEmpty()) {
            urlInput = lastInput
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HermesBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header / App Title
            Text(
                text = "HERMES",
                color = HermesGold,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 6.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = "WEB UI CLIENT",
                color = HermesTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 4.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Setup Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, HermesBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = HermesCardBg),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Connect to Hermes Server",
                        color = HermesTextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "Enter the network address where your Hermes WebUI server is hosted. For example, your local IP or a Tailscale URL.",
                        color = HermesTextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = {
                            urlInput = it
                            errorMessage = null
                        },
                        label = { Text("Server URL", color = HermesTextSecondary) },
                        placeholder = { Text("http://192.168.1.100:8787") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = HermesTextPrimary,
                            unfocusedTextColor = HermesTextPrimary,
                            focusedBorderColor = HermesGold,
                            unfocusedBorderColor = HermesBorder,
                            cursorColor = HermesGold
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            color = HermesError,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = {
                            if (urlInput.isBlank() || urlInput == "http://" || urlInput == "https://") {
                                errorMessage = "Please enter a valid URL"
                                return@Button
                            }
                            isLoading = true
                            errorMessage = null

                            scope.launch {
                                val normalizedUrl = normalizeUrl(urlInput)
                                val reachable = testServerReachability(normalizedUrl)
                                isLoading = false

                                if (reachable) {
                                    // Save both last_input and the verified server_url
                                    val sharedPrefs = context.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
                                    sharedPrefs.edit()
                                        .putString("server_url", normalizedUrl)
                                        .putString("last_input_url", urlInput)
                                        .apply()
                                    onConnectSuccess(normalizedUrl)
                                } else {
                                    errorMessage = "Could not connect to server. Check IP and verify WebUI is running."
                                }
                            }
                        },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HermesGold,
                            contentColor = Color.Black,
                            disabledContainerColor = HermesBorder
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color.Black,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Text(
                                text = "CONNECT",
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                        }
                    }
                }
            }

            // Quick Setup Guide Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, HermesBorder, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = HermesCardBg.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Quick Help",
                        tint = HermesGold,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "How to Host Hermes on your Network",
                            color = HermesTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Start your WebUI with the host set to 0.0.0.0 so it listens to connections from your phone:\n\nHERMES_WEBUI_HOST=0.0.0.0 ./start.sh",
                            color = HermesTextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

private fun normalizeUrl(input: String): String {
    var url = input.trim()
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        url = "http://$url"
    }
    // Remove trailing slash for consistency
    if (url.endsWith("/")) {
        url = url.substring(0, url.length - 1)
    }
    return url
}

private suspend fun testServerReachability(urlStr: String): Boolean = withContext(Dispatchers.IO) {
    try {
        // Try testing both the /health endpoint or the root URL
        val connection = URL("$urlStr/health").openConnection() as HttpURLConnection
        connection.connectTimeout = 4000
        connection.readTimeout = 4000
        connection.requestMethod = "GET"
        
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            return@withContext true
        }
    } catch (e: Exception) {
        // fallback to main URL check
    }

    try {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.connectTimeout = 4000
        connection.readTimeout = 4000
        connection.requestMethod = "GET"
        
        val responseCode = connection.responseCode
        // Any response code means the server is reachable and listening!
        return@withContext responseCode >= 200 && responseCode < 600
    } catch (e: Exception) {
        false
    }
}
