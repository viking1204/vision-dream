package io.github.xororz.localdream.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

// Ignores pops while a pop transition is already running (current entry not RESUMED),
// so rapid back-button taps cannot pop the start destination and blank the NavHost.
fun NavController.popBackStackIfResumed(): Boolean {
    if (currentBackStackEntry?.lifecycle?.currentState != Lifecycle.State.RESUMED) {
        return false
    }
    return popBackStack()
}

/**
 * Switches between the five product-level destinations without building an
 * ever-growing tab history. Each destination restores its own navigation state.
 */
fun NavController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

sealed class Screen(val route: String) {
    object Workbench : Screen("workbench")

    object ModelList : Screen("model_list")
    object ModelRun : Screen("model_run/{modelId}?remote={remote}") {
        fun createRoute(modelId: String, remote: Boolean = false) = "model_run/$modelId?remote=$remote"
    }

    object Upscale : Screen("upscale")

    object History : Screen("history")

    object PromptManager : Screen("prompt_manager")

    object PerformancePresets : Screen("performance_presets")

    object ChatGeneration : Screen("chat_generation")

    object RemoteLink : Screen("remote_link")
}

val StudioTopLevelRoutes = listOf(
    Screen.Workbench.route,
    Screen.ChatGeneration.route,
    Screen.History.route,
    Screen.ModelList.route,
    Screen.RemoteLink.route,
)
