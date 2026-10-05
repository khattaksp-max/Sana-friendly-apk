package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.screens.CompanionScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.VoicePreviewScreen
import com.example.ui.screens.VoiceSettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SanaPink
import com.example.viewmodel.SanaViewModel
import com.example.viewmodel.UiEvent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                SanaAppRoot()
            }
        }
    }
}

@Composable
fun SanaAppRoot(
    viewModel: SanaViewModel = viewModel()
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle back button when on sub-screens
    if (selectedTab != 0) {
        BackHandler {
            selectedTab = 0
        }
    }

    // Trigger initial voice greeting on startup as specified in prompt
    LaunchedEffect(Unit) {
        viewModel.triggerInitialGreeting()
    }

    // Listen to UI events like tab navigation and snackbar alerts
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is UiEvent.NavigateToTab -> {
                    selectedTab = event.tabIndex
                }
                is UiEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("main_navigation_bar"),
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 0) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Companion"
                        )
                    },
                    label = { Text("SANA") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SanaPink,
                        selectedTextColor = SanaPink,
                        indicatorColor = SanaPink.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.testTag("tab_companion")
                )

                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 1) Icons.Default.RecordVoiceOver else Icons.Outlined.RecordVoiceOver,
                            contentDescription = "Voices"
                        )
                    },
                    label = { Text("Voices") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SanaPink,
                        selectedTextColor = SanaPink,
                        indicatorColor = SanaPink.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.testTag("tab_voices")
                )

                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 2) Icons.Default.Settings else Icons.Outlined.Settings,
                            contentDescription = "Settings"
                        )
                    },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SanaPink,
                        selectedTextColor = SanaPink,
                        indicatorColor = SanaPink.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.testTag("tab_settings")
                )

                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 3) Icons.Default.Assessment else Icons.Outlined.Assessment,
                            contentDescription = "Diagnostics"
                        )
                    },
                    label = { Text("Diagnostics") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SanaPink,
                        selectedTextColor = SanaPink,
                        indicatorColor = SanaPink.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier.testTag("tab_diagnostics")
                )
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> CompanionScreen(
                viewModel = viewModel,
                onNavigateToVoices = { selectedTab = 1 },
                modifier = Modifier.padding(innerPadding)
            )
            1 -> VoicePreviewScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(innerPadding)
            )
            2 -> VoiceSettingsScreen(
                viewModel = viewModel,
                onNavigateToVoices = { selectedTab = 1 },
                onNavigateToDiagnostics = { selectedTab = 3 },
                modifier = Modifier.padding(innerPadding)
            )
            3 -> DiagnosticsScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
