package com.example.ui.navigation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.RayApplication
import com.example.ui.addserver.AddServerScreen
import com.example.ui.benchmark.BenchmarkScreen
import com.example.ui.benchmark.BenchmarkViewModel
import com.example.ui.diagnostics.DiagnosticsScreen
import com.example.ui.home.HomeScreen
import com.example.ui.importing.ImportScreen
import com.example.ui.importing.ImportViewModel
import com.example.ui.servers.ServersScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.subscription.SubscriptionViewModel
import com.example.ui.subscription.SubscriptionsScreen
import com.example.ui.theme.AppTheme
import com.example.ui.top10.Top10Screen
import com.example.ui.top10.Top10ViewModel
import com.example.ui.viewmodel.DiagnosticsViewModel
import com.example.ui.viewmodel.ServerViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.VpnViewModel

sealed class Screen(val route: String, val title: String, val icon: ImageVector, val unselectedIcon: ImageVector) {
    data object Home : Screen("home", "Tunnel", Icons.Filled.Shield, Icons.Outlined.Shield)
    data object Top10 : Screen("top10", "Top 10", Icons.Filled.EmojiEvents, Icons.Outlined.EmojiEvents)
    data object Servers : Screen("servers", "Nodes", Icons.Filled.Dns, Icons.Outlined.Dns)
    data object Benchmark : Screen("benchmark", "Benchmark", Icons.Filled.Speed, Icons.Outlined.Speed)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)

    // Additional sub-destinations
    data object Import : Screen("import", "Import", Icons.Filled.FileDownload, Icons.Outlined.FileDownload)
    data object Subscriptions : Screen("subscriptions", "Subscriptions", Icons.Filled.RssFeed, Icons.Outlined.RssFeed)
    data object Diagnostics : Screen("diagnostics", "Logs", Icons.Filled.Analytics, Icons.Outlined.Analytics)
    data object AddServer : Screen("add_server", "Add Node", Icons.Filled.Dns, Icons.Outlined.Dns)
    data object SecretChat : Screen("secret_chat", "Secret Chat", Icons.Filled.Shield, Icons.Outlined.Shield)
    data object GodBrowser : Screen("god_browser", "GOD Browser", Icons.Filled.Shield, Icons.Outlined.Shield)
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Top10,
    Screen.Servers,
    Screen.Benchmark,
    Screen.Settings
)

@Composable
fun MainApp(
    vpnViewModel: VpnViewModel,
    serverViewModel: ServerViewModel,
    diagnosticsViewModel: DiagnosticsViewModel,
    settingsViewModel: SettingsViewModel,
    onRequestVpnPermission: () -> Unit,
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = bottomNavItems.any { it.route == currentRoute }

    val app = RayApplication.instance
    val top10ViewModel: Top10ViewModel = viewModel {
        Top10ViewModel(app.serverRepository)
    }
    val benchmarkViewModel: BenchmarkViewModel = viewModel {
        BenchmarkViewModel(app.serverRepository, app.benchmarkRepository, app.benchmarkEngine)
    }
    val importViewModel: ImportViewModel = viewModel {
        ImportViewModel(app.serverRepository)
    }
    val subscriptionViewModel: SubscriptionViewModel = viewModel {
        SubscriptionViewModel(app.subscriptionRepository, app.subscriptionManager)
    }

    Scaffold(
        containerColor = AppTheme.colors.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = AppTheme.colors.surfaceCard,
                    tonalElevation = 0.dp,
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = AppTheme.colors.borderSubtle
                    )
                ) {
                    bottomNavItems.forEach { screen ->
                        val selected = currentRoute == screen.route
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.icon else screen.unselectedIcon,
                                    contentDescription = screen.title
                                )
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    fontSize = 10.sp
                                )
                            },
                            selected = selected,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppTheme.colors.primary,
                                selectedTextColor = AppTheme.colors.primary,
                                indicatorColor = AppTheme.colors.surfaceElevated,
                                unselectedIconColor = AppTheme.colors.textSecondary,
                                unselectedTextColor = AppTheme.colors.textSecondary
                            ),
                            modifier = Modifier.testTag("nav_item_${screen.route}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    vpnViewModel = vpnViewModel,
                    settingsViewModel = settingsViewModel,
                    onNavigateToServers = { navController.navigate(Screen.Servers.route) },
                    onRequestVpnPermission = onRequestVpnPermission,
                    onNavigateToSecretChat = { navController.navigate(Screen.SecretChat.route) },
                    onNavigateToGodBrowser = { navController.navigate(Screen.GodBrowser.route) }
                )
            }
            composable(Screen.Top10.route) {
                Top10Screen(
                    viewModel = top10ViewModel,
                    onSelectAndConnect = { profile ->
                        vpnViewModel.selectServer(profile)
                        navController.navigate(Screen.Home.route)
                    }
                )
            }
            composable(Screen.Servers.route) {
                ServersScreen(
                    serverViewModel = serverViewModel,
                    vpnViewModel = vpnViewModel,
                    settingsViewModel = settingsViewModel,
                    onNavigateToAddServer = { navController.navigate(Screen.AddServer.route) },
                    onNavigateToImport = { navController.navigate(Screen.Import.route) },
                    onNavigateToSubscriptions = { navController.navigate(Screen.Subscriptions.route) },
                    onNavigateToBenchmark = { navController.navigate(Screen.Benchmark.route) }
                )
            }
            composable(Screen.Benchmark.route) {
                BenchmarkScreen(
                    viewModel = benchmarkViewModel
                )
            }
            composable(Screen.Import.route) {
                ImportScreen(
                    viewModel = importViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Subscriptions.route) {
                SubscriptionsScreen(
                    viewModel = subscriptionViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.AddServer.route) {
                AddServerScreen(
                    serverViewModel = serverViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Diagnostics.route) {
                DiagnosticsScreen(
                    viewModel = diagnosticsViewModel,
                    settingsViewModel = settingsViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateToDiagnostics = { navController.navigate(Screen.Diagnostics.route) }
                )
            }
            composable(Screen.SecretChat.route) {
                com.example.ui.chat.SecretChatScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.GodBrowser.route) {
                com.example.ui.browser.GodBrowserScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
