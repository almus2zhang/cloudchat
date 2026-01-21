package com.cloudchat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cloudchat.ui.MainScreen
import com.cloudchat.ui.SettingsScreen
import com.cloudchat.ui.theme.CloudChatTheme

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    // State to hold shared content
    private val sharedContent = mutableStateOf<SharedData?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIntent(intent)

        setContent {
            CloudChatTheme {
                val navController = rememberNavController()
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("CloudChat") },
                            actions = {
                                IconButton(onClick = { navController.navigate("settings") }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                                }
                            }
                        )
                    }
                ) { padding ->
                    Surface(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        NavHost(navController = navController, startDestination = "main") {
                            composable("main") {
                                MainScreen(sharedContent.value) {
                                    // Clear shared content after handling
                                    sharedContent.value = null
                                }
                            }
                            composable("settings") {
                                SettingsScreen(onBack = { navController.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            sharedContent.value = SharedData(text, uri)
        } else if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            sharedContent.value = SharedData(uris = uris)
        }
    }
}

data class SharedData(
    val text: String? = null,
    val uri: Uri? = null,
    val uris: ArrayList<Uri>? = null
)
