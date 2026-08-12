package com.cloudchat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.cloudchat.ui.MainScreen
import com.cloudchat.ui.SettingsScreen
import com.cloudchat.ui.theme.CloudChatTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import com.cloudchat.repository.SettingsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    // State to hold shared content
    private val sharedContent = mutableStateOf<SharedData?>(null)
    private val quickAction = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        
        // Request MANAGE_EXTERNAL_STORAGE permission for Android 11+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivity(intent)
                }
            }
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = android.graphics.`Color`.TRANSPARENT
        window.navigationBarColor = android.graphics.`Color`.TRANSPARENT
        
        // Handle notch area for true full screen
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        
        // Set light status and navigation bars (dark icons)
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        setContent {
            CloudChatTheme {
                val navController = rememberNavController()
                val settingsRepository = remember { SettingsRepository(this@MainActivity) }
                val appModeState by settingsRepository.appMode.collectAsState(initial = null)
                val currentConfig by settingsRepository.currentConfig.collectAsState(initial = null)
                
                val scope = rememberCoroutineScope()
                var isTopBarVisible by remember { mutableStateOf(true) }
                var topBarActions by remember { mutableStateOf<@Composable RowScope.() -> Unit>({}) }
                var topBarTitle by remember { mutableStateOf("CloudChat") }
                var topBarTitleComposable by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
                var onTopBarBackClick by remember { mutableStateOf<(() -> Unit)?>(null) }
                
                // Wait for storage to load the appMode
                val appMode = appModeState ?: return@CloudChatTheme

                val startDestination = when (appMode) {
                    com.cloudchat.model.AppMode.NOT_SET -> "mode_selection"
                    else -> "main"
                }

                // Only force navigate if the mode is explicitly cleared (Switch Mode)
                LaunchedEffect(appMode) {
                    if (appMode == com.cloudchat.model.AppMode.NOT_SET) {
                        navController.navigate("mode_selection") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }

                Scaffold(
                    topBar = {
                        if (isTopBarVisible && appMode != com.cloudchat.model.AppMode.NOT_SET) {
                            TopAppBar(
                                title = {
                                    if (topBarTitleComposable != null) {
                                        topBarTitleComposable?.invoke()
                                    } else if (topBarTitle.isNotEmpty()) {
                                        Text(topBarTitle, style = MaterialTheme.typography.titleLarge)
                                    }
                                },
                                navigationIcon = {
                                    if (onTopBarBackClick != null) {
                                        IconButton(modifier = Modifier.size(40.dp), onClick = { onTopBarBackClick?.invoke() }) {
                                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                actions = {
                                    topBarActions(this)
                                    IconButton(
                                        modifier = Modifier.size(40.dp),
                                        onClick = { navController.navigate("settings") }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings, 
                                            contentDescription = "Settings"
                                        )
                                    }
                                }
                            )
                        }
                    }
                ) { padding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isTopBarVisible && appMode != com.cloudchat.model.AppMode.NOT_SET) padding else PaddingValues(0.dp)),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        NavHost(navController = navController, startDestination = startDestination) {
                            composable("mode_selection") {
                                ModeSelectionScreen(
                                    onModeSelected = { mode ->
                                        scope.launch {
                                            settingsRepository.setAppMode(mode)
                                            if (mode == com.cloudchat.model.AppMode.FULL) {
                                                navController.navigate("settings") {
                                                    popUpTo("mode_selection") { inclusive = true }
                                                }
                                            } else {
                                                navController.navigate("main") {
                                                    popUpTo("mode_selection") { inclusive = true }
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                            composable("main") {
                                if (currentConfig == null) {
                                    LaunchedEffect(Unit) {
                                        navController.navigate("settings")
                                    }
                                } else {
                                    MainScreen(
                                        sharedData = sharedContent.value,
                                        quickAction = quickAction.value,
                                        onFullScreenToggle = { isTopBarVisible = !it },
                                        onSharedDataHandled = {
                                            sharedContent.value = null
                                        },
                                        onQuickActionHandled = {
                                            quickAction.value = null
                                        },
                                        setTopBarActions = { actions ->
                                            topBarActions = actions
                                        },
                                        setTopBarTitle = { topBarTitle = it },
                                        setTopBarTitleComposable = { topBarTitleComposable = it },
                                        setTopBarNavigationIcon = { onTopBarBackClick = it }
                                    )
                                }
                            }
                            composable("settings") {
                                SettingsScreen(onBack = { 
                                    if (navController.previousBackStackEntry != null) {
                                        navController.popBackStack()
                                    } else {
                                        navController.navigate("main")
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ModeSelectionScreen(onModeSelected: (com.cloudchat.model.AppMode) -> Unit) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Text("欢迎使用 CloudChat", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("请选择您的使用模式", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = { onModeSelected(com.cloudchat.model.AppMode.FULL) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("完整版 (极速通过)", style = MaterialTheme.typography.titleMedium)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedButton(
                onClick = { onModeSelected(com.cloudchat.model.AppMode.SELF_BUILT) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("自建版 (自定义服务器)", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val actionExtra = intent.getStringExtra("quick_action")
        if (!actionExtra.isNullOrEmpty()) {
            quickAction.value = actionExtra
            intent.removeExtra("quick_action")
            return
        }
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            sharedContent.value = SharedData(text, uri)
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
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
