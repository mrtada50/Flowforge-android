package com.flowforge.android.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.flowforge.android.R
import com.flowforge.android.features.common.ComingSoonScreen
import com.flowforge.android.features.dashboard.DashboardScreen
import com.flowforge.android.features.settings.SettingsScreen
import com.flowforge.android.features.workflowstudio.WorkflowStudioScreen
import com.flowforge.android.features.editor.EditorScreen
import com.flowforge.android.features.requestlab.RequestLabScreen
import com.flowforge.android.features.parserstudio.ParserStudioScreen
import com.flowforge.android.features.runner.RunnerScreen
import com.flowforge.android.features.results.ResultsScreen
import kotlinx.coroutines.launch

enum class Destination(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    Dashboard("dashboard", R.string.nav_dashboard, Icons.Default.Home),
    WorkflowStudio("workflow_studio", R.string.nav_workflow_studio, Icons.Default.Edit),
    RequestLab("request_lab", R.string.nav_request_lab, Icons.AutoMirrored.Filled.Send),
    ParserStudio("parser_studio", R.string.nav_parser_studio, Icons.Default.Search),
    DataManager("data_manager", R.string.nav_data_manager, Icons.AutoMirrored.Filled.List),
    ProxyManager("proxy_manager", R.string.nav_proxy_manager, Icons.Default.Lock),
    Runner("runner", R.string.nav_runner, Icons.Default.PlayArrow),
    Results("results", R.string.nav_results, Icons.Default.Check),
    Settings("settings", R.string.nav_settings, Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val current = Destination.entries.firstOrNull { it.route == route }
        ?: if (route?.startsWith("editor") == true) Destination.WorkflowStudio else Destination.Dashboard

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.app_name), Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                    Destination.entries.forEach { d ->
                        NavigationDrawerItem(
                            label = { Text(stringResource(d.label)) },
                            icon = { Icon(d.icon, contentDescription = null) },
                            selected = d == current,
                            onClick = {
                                scope.launch { drawerState.close() }
                                nav.navigate(d.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(current.label)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu))
                        }
                    },
                )
            },
        ) { padding ->
            NavHost(nav, startDestination = Destination.Dashboard.route, modifier = Modifier.padding(padding)) {
                composable(Destination.Dashboard.route) { DashboardScreen() }
                composable(Destination.WorkflowStudio.route) {
                    WorkflowStudioScreen(onOpen = { id -> nav.navigate("editor/$id") })
                }
                composable(
                    "editor/{workflowId}",
                    arguments = listOf(navArgument("workflowId") { type = NavType.StringType }),
                ) { entry -> EditorScreen(entry.arguments?.getString("workflowId").orEmpty()) }
                composable(Destination.RequestLab.route) { RequestLabScreen() }
                composable(Destination.ParserStudio.route) { ParserStudioScreen() }
                composable(Destination.DataManager.route) { ComingSoonScreen(5) }
                composable(Destination.ProxyManager.route) { ComingSoonScreen(5) }
                composable(Destination.Runner.route) { RunnerScreen() }
                composable(Destination.Results.route) { ResultsScreen() }
                composable(Destination.Settings.route) { SettingsScreen() }
            }
        }
    }
}
