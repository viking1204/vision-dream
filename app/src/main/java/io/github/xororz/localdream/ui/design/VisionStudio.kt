package io.github.xororz.localdream.ui.design

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
 * Stable product-level navigation shared by each top-level screen scaffold.
 */
@Composable
fun VisionStudioNavigationBar(navController: NavController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
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
