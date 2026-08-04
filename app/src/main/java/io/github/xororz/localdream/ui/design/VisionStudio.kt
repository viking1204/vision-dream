package io.github.xororz.localdream.ui.design

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import io.github.xororz.localdream.R
import io.github.xororz.localdream.navigation.Screen
import io.github.xororz.localdream.navigation.navigateTopLevel

@Immutable
data class StudioDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
)

val StudioDestinations = listOf(
    StudioDestination(Screen.Workbench.route, R.string.studio_nav_workbench, Icons.Default.Dashboard),
    StudioDestination(Screen.ChatGeneration.route, R.string.studio_nav_create, Icons.Default.AutoAwesome),
    StudioDestination(Screen.History.route, R.string.studio_nav_assets, Icons.Default.PhotoLibrary),
    StudioDestination(Screen.ModelList.route, R.string.studio_nav_models, Icons.Default.Memory),
    StudioDestination(Screen.RemoteLink.route, R.string.studio_nav_services, Icons.Default.Dns),
    StudioDestination(Screen.Settings.route, R.string.studio_nav_settings, Icons.Default.Settings),
)

val StudioCyan = Color(0xFF5EE7D7)
val StudioCoral = Color(0xFFFF8A7A)
val StudioSuccess = Color(0xFF63D9A4)

/**
 * Shared collapse state for the bottom navigation bar. Hoisted to a process
 * level singleton so the collapsed state survives top-level screen switches
 * (each top-level screen renders its own Scaffold bottomBar).
 */
object NavigationBarState {
    val collapsed = mutableStateOf(false)
}

/**
 * Stable product-level navigation shared by each top-level screen scaffold.
 */
@Composable
fun VisionStudioNavigationBar(navController: NavController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val collapsed by NavigationBarState.collapsed

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(visible = !collapsed) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NavigationCollapseHandle(
                    onClick = { NavigationBarState.collapsed.value = true },
                    contentDescription = stringResource(R.string.collapse_nav),
                    slotHeight = 14.dp,
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                    // The parent already applies the navigation bar inset; the
                    // default here would add it a second time.
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    StudioDestinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateTopLevel(destination.route) },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null,
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(destination.labelRes),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        }
        AnimatedVisibility(visible = collapsed) {
            // Collapsed state is a bare grab handle. Anything larger (the old
            // 40dp icon in a pill) still read as a bar and defeated the point
            // of collapsing.
            NavigationCollapseHandle(
                onClick = { NavigationBarState.collapsed.value = false },
                contentDescription = stringResource(R.string.expand_nav),
                slotHeight = 22.dp,
            )
        }
    }
}

/**
 * Thin, tappable grab handle used to toggle the bottom navigation. The visible
 * bar is 4dp tall while the touch slot stays comfortably tappable, which keeps
 * the collapsed navigation footprint at roughly a quarter of the expanded one.
 */
@Composable
private fun NavigationCollapseHandle(
    onClick: () -> Unit,
    contentDescription: String,
    slotHeight: Dp,
) {
    val label = contentDescription
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(slotHeight)
            .clickable(onClick = onClick, onClickLabel = label)
            .semantics { this.contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp),
            )
        }
    }
}

@Composable
fun StudioStatusPill(
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = StudioSuccess,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(100),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(100),
                color = accent,
                modifier = Modifier.padding(1.dp),
            ) {
                androidx.compose.foundation.layout.Spacer(
                    modifier = Modifier.padding(3.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
