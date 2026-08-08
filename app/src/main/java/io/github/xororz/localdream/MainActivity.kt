package io.github.xororz.localdream

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.xororz.localdream.data.AppSecurityPreferences
import io.github.xororz.localdream.data.MigrationState
import io.github.xororz.localdream.data.ModelStorage
import io.github.xororz.localdream.navigation.Screen
import io.github.xororz.localdream.security.AppLockAuthenticationPolicy
import io.github.xororz.localdream.security.AppLockAuthenticationReadiness
import io.github.xororz.localdream.ui.design.VisionStudioNavigationBar
import io.github.xororz.localdream.ui.screens.ChatGenerationScreen
import io.github.xororz.localdream.ui.screens.HistoryScreen
import io.github.xororz.localdream.ui.screens.MigrationScreen
import io.github.xororz.localdream.ui.screens.ModelListScreen
import io.github.xororz.localdream.ui.screens.ModelRunScreen
import io.github.xororz.localdream.ui.screens.ModelStorageAccessScreen
import io.github.xororz.localdream.ui.screens.PerformancePresetScreen
import io.github.xororz.localdream.ui.screens.PromptManagerScreen
import io.github.xororz.localdream.ui.screens.RemoteScreen
import io.github.xororz.localdream.ui.screens.SettingsScreen
import io.github.xororz.localdream.ui.screens.StudioHomeScreen
import io.github.xororz.localdream.ui.screens.UpscaleScreen
import io.github.xororz.localdream.ui.screens.repository.ModelSearchScreen
import io.github.xororz.localdream.ui.screens.repository.RepositoryConfigScreen
import io.github.xororz.localdream.ui.theme.LocalDreamTheme
import io.github.xororz.localdream.ui.theme.LocalThemeController
import io.github.xororz.localdream.ui.theme.rememberThemeController
import io.github.xororz.localdream.ui.theme.sharedAxisXEnter
import io.github.xororz.localdream.ui.theme.sharedAxisXExit
import io.github.xororz.localdream.ui.theme.sharedAxisXPopEnter
import io.github.xororz.localdream.ui.theme.sharedAxisXPopExit
import io.github.xororz.localdream.ui.theme.sharedAxisXPredictivePopEnter
import io.github.xororz.localdream.ui.theme.sharedAxisXPredictivePopExit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    private sealed interface StorageState {
        data object Missing : StorageState
        data object Preparing : StorageState
        data object Ready : StorageState
        data class Failed(val message: String) : StorageState
    }

    private var storageState by mutableStateOf<StorageState>(StorageState.Missing)
    private var prepareStorageJob: Job? = null
    private lateinit var securityPreferences: AppSecurityPreferences
    private lateinit var biometricPrompt: BiometricPrompt
    private var authenticationInProgress = false
    private var pendingBiometricEnable = false
    private var activityStarted = false
    private var isActivityUnlocked by mutableStateOf(true)
    private var authenticationError by mutableStateOf<String?>(null)
    var biometricLockEnabled by mutableStateOf(false)
        private set

    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted: Boolean ->
        if (isGranted) {
            prepareModelStorage()
        } else {
            storageState = StorageState.Missing
            Toast.makeText(
                this,
                getString(R.string.permission_storage_required),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(
                this,
                getString(R.string.permission_notification_required),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // ok
                }

                shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) -> {
                    Toast.makeText(
                        this,
                        getString(R.string.permission_storage_required),
                        Toast.LENGTH_LONG,
                    ).show()
                    requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }

                else -> {
                    requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }

    private fun checkNotificationPermission() {
        // > Android 13
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // ok
                }

                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Toast.makeText(
                        this,
                        getString(R.string.permission_notification_required),
                        Toast.LENGTH_LONG,
                    ).show()
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                else -> {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        securityPreferences = AppSecurityPreferences(this)
        biometricLockEnabled = securityPreferences.isBiometricLockEnabled()
        isActivityUnlocked = !biometricLockEnabled
        configureBiometricPrompt()
        checkNotificationPermission()
        refreshModelStorageState()

        val app = application as LocalDreamApplication

        setContent {
            val themeController = rememberThemeController()
            CompositionLocalProvider(LocalThemeController provides themeController) {
                LocalDreamTheme(themeController.state) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        if (!isActivityUnlocked) {
                            BiometricLockScreen(
                                errorMessage = authenticationError,
                                onUnlock = ::requestBiometricUnlock,
                            )
                        } else {
                            when (val currentStorageState = storageState) {
                                StorageState.Ready -> {
                                    val migrationState by app.migrationState.collectAsState()
                                    when (migrationState) {
                                        is MigrationState.Done,
                                        is MigrationState.NotNeeded,
                                        -> AppContent()

                                        is MigrationState.Idle,
                                        is MigrationState.InProgress,
                                        is MigrationState.Failed,
                                        -> MigrationScreen(
                                            state = migrationState,
                                            onRetry = { app.retryMigration() },
                                            onSkip = { app.skipMigration() },
                                        )
                                    }
                                }

                                StorageState.Missing,
                                StorageState.Preparing,
                                is StorageState.Failed,
                                -> ModelStorageAccessScreen(
                                    publicPath = ModelStorage.publicModelsDir().absolutePath,
                                    isPreparing = currentStorageState is StorageState.Preparing,
                                    errorMessage = (currentStorageState as? StorageState.Failed)?.message,
                                    onGrantAccess = {
                                        if (ModelStorage.hasAccess(this)) {
                                            prepareModelStorage()
                                        } else {
                                            openModelStorageSettings()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (biometricLockEnabled) {
            window.decorView.post(::requestBiometricUnlock)
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
    }

    override fun onResume() {
        super.onResume()
        refreshModelStorageState()
        if (biometricLockEnabled && !isActivityUnlocked) {
            window.decorView.post(::requestBiometricUnlock)
        }
    }

    override fun onStop() {
        activityStarted = false
        if (!isChangingConfigurations) {
            if (authenticationInProgress) {
                biometricPrompt.cancelAuthentication()
                // Some platform credential flows do not dispatch a cancellation
                // callback. Reset the local guard so the next foreground entry
                // can always start a fresh authentication attempt.
                authenticationInProgress = false
            }
            if (pendingBiometricEnable) {
                cancelPendingBiometricEnable()
            } else if (biometricLockEnabled) {
                isActivityUnlocked = false
                authenticationError = null
            }
        }
        super.onStop()
    }

    fun setBiometricLockEnabled(enabled: Boolean): Boolean {
        if (!enabled) {
            pendingBiometricEnable = false
            securityPreferences.setBiometricLockEnabled(false)
            biometricLockEnabled = false
            authenticationError = null
            isActivityUnlocked = true
            return true
        }
        if (biometricLockEnabled || pendingBiometricEnable) return true
        if (authenticationReadiness() == AppLockAuthenticationReadiness.UNAVAILABLE) {
            Toast.makeText(
                this,
                getString(R.string.biometric_unavailable),
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        // Enabling is a two-phase operation: do not persist the lock until the
        // user proves that a strong biometric or device credential is usable.
        pendingBiometricEnable = true
        authenticationError = null
        isActivityUnlocked = false
        requestBiometricUnlock()
        return true
    }

    private fun configureBiometricPrompt() {
        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    super.onAuthenticationSucceeded(result)
                    authenticationInProgress = false
                    if (!activityStarted) {
                        if (pendingBiometricEnable) {
                            cancelPendingBiometricEnable()
                        }
                        isActivityUnlocked = !biometricLockEnabled
                        return
                    }
                    if (pendingBiometricEnable) {
                        pendingBiometricEnable = false
                        securityPreferences.setBiometricLockEnabled(true)
                        biometricLockEnabled = true
                    }
                    authenticationError = null
                    isActivityUnlocked = true
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    super.onAuthenticationError(errorCode, errString)
                    authenticationInProgress = false
                    if (pendingBiometricEnable) {
                        cancelPendingBiometricEnable()
                        return
                    }
                    if (!biometricLockEnabled) {
                        authenticationError = null
                        isActivityUnlocked = true
                        return
                    }
                    if (authenticationReadiness() ==
                        AppLockAuthenticationReadiness.UNAVAILABLE
                    ) {
                        recoverFromUnavailableAuthentication()
                        return
                    }
                    authenticationError = errString.toString()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    authenticationError = getString(R.string.biometric_not_recognized)
                }
            },
        )
    }

    private fun requestBiometricUnlock() {
        if ((!biometricLockEnabled && !pendingBiometricEnable) ||
            isActivityUnlocked ||
            authenticationInProgress ||
            !activityStarted
        ) {
            return
        }
        if (authenticationReadiness() == AppLockAuthenticationReadiness.UNAVAILABLE) {
            if (pendingBiometricEnable) {
                cancelPendingBiometricEnable()
                Toast.makeText(
                    this,
                    getString(R.string.biometric_unavailable),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                recoverFromUnavailableAuthentication()
            }
            return
        }
        authenticationInProgress = true
        authenticationError = null
        try {
            biometricPrompt.authenticate(createBiometricPromptInfo())
        } catch (_: IllegalArgumentException) {
            authenticationInProgress = false
            if (pendingBiometricEnable) {
                cancelPendingBiometricEnable()
                Toast.makeText(
                    this,
                    getString(R.string.biometric_unavailable),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                recoverFromUnavailableAuthentication()
            }
        }
    }

    private fun authenticationReadiness(): AppLockAuthenticationReadiness {
        val biometricManager = BiometricManager.from(this)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AppLockAuthenticationPolicy.fromCanAuthenticateResult(
                biometricManager.canAuthenticate(AUTHENTICATORS),
            )
        } else {
            val strongBiometricResult = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG,
            )
            val keyguardManager = getSystemService(KeyguardManager::class.java)
            AppLockAuthenticationPolicy.forLegacyDevice(
                strongBiometricResult = strongBiometricResult,
                deviceCredentialAvailable = keyguardManager?.isDeviceSecure == true,
            )
        }
    }

    private fun createBiometricPromptInfo(): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_unlock_title))
            .setSubtitle(getString(R.string.biometric_unlock_subtitle))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(AUTHENTICATORS)
        } else {
            // BIOMETRIC_STRONG | DEVICE_CREDENTIAL is unsupported on API 28-29.
            // The legacy flag keeps the screen-lock fallback available there.
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        // Device credentials replace the negative button; setting both is an
        // illegal PromptInfo combination.
        return builder.build()
    }

    private fun recoverFromUnavailableAuthentication() {
        authenticationInProgress = false
        pendingBiometricEnable = false
        securityPreferences.setBiometricLockEnabled(false)
        biometricLockEnabled = false
        authenticationError = null
        isActivityUnlocked = true
        Toast.makeText(
            this,
            getString(R.string.biometric_lock_recovered),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun cancelPendingBiometricEnable() {
        pendingBiometricEnable = false
        securityPreferences.setBiometricLockEnabled(false)
        biometricLockEnabled = false
        authenticationError = null
        isActivityUnlocked = true
    }

    private fun refreshModelStorageState() {
        if (ModelStorage.hasAccess(this)) {
            prepareModelStorage()
        } else {
            prepareStorageJob?.cancel()
            storageState = StorageState.Missing
            checkStoragePermission()
        }
    }

    private fun prepareModelStorage() {
        if (prepareStorageJob?.isActive == true) return
        storageState = StorageState.Preparing
        prepareStorageJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ModelStorage.requireModelsDir(this@MainActivity)
                    ModelStorage.migrateLegacyModels(this@MainActivity)
                }
            }
            result.fold(
                onSuccess = { report ->
                    storageState = StorageState.Ready
                    if (report.failed > 0) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.model_storage_migration_partial, report.failed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                onFailure = { error ->
                    storageState = StorageState.Failed(
                        error.message ?: getString(R.string.model_storage_prepare_failed),
                    )
                },
            )
        }
    }

    private fun openModelStorageSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        val appSettingsIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching {
            startActivity(appSettingsIntent)
        }.recoverCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }.onFailure {
            Toast.makeText(
                this,
                getString(R.string.model_storage_settings_unavailable),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private companion object {
        const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}

@Composable
private fun BiometricLockScreen(
    errorMessage: String?,
    onUnlock: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Fingerprint,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.biometric_locked_message),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(
            onClick = onUnlock,
            modifier = Modifier.padding(top = 20.dp),
        ) {
            Text(stringResource(R.string.biometric_unlock_action))
        }
    }
}

@Composable
private fun AppContent() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Screen.Workbench.route,
        enterTransition = { sharedAxisXEnter() },
        exitTransition = { sharedAxisXExit() },
        popEnterTransition = { sharedAxisXPopEnter() },
        popExitTransition = { sharedAxisXPopExit() },
        predictivePopEnterTransition = { _ -> sharedAxisXPredictivePopEnter() },
        predictivePopExitTransition = { _ -> sharedAxisXPredictivePopExit() },
    ) {
        composable(Screen.Workbench.route) {
            StudioHomeScreen(navController)
        }
        composable(Screen.ModelList.route) {
            ModelListScreen(
                navController = navController,
                isTopLevel = true,
                bottomBar = { VisionStudioNavigationBar(navController) },
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }
        composable(
            route = Screen.ModelRun.route,
            arguments = listOf(
                navArgument("modelId") {
                    type = NavType.StringType
                },
                navArgument("remote") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments?.getString("modelId") ?: ""
            val isRemote = backStackEntry.arguments?.getBoolean("remote") ?: false

            ModelRunScreen(
                modelId = modelId,
                isRemote = isRemote,
                navController = navController,
            )
        }
        composable(Screen.Upscale.route) {
            UpscaleScreen(navController)
        }
        composable(Screen.History.route) {
            HistoryScreen(
                navController = navController,
                isTopLevel = true,
                bottomBar = { VisionStudioNavigationBar(navController) },
            )
        }
        composable(Screen.PromptManager.route) {
            PromptManagerScreen(navController)
        }
        composable(Screen.PerformancePresets.route) {
            PerformancePresetScreen(navController)
        }
        composable(Screen.ChatGeneration.route) {
            ChatGenerationScreen(
                navController = navController,
                isTopLevel = true,
                bottomBar = { VisionStudioNavigationBar(navController) },
            )
        }
        composable(Screen.RemoteLink.route) {
            RemoteScreen(
                navController = navController,
                isTopLevel = true,
                bottomBar = { VisionStudioNavigationBar(navController) },
            )
        }
        composable(Screen.RepositoryConfig.route) {
            RepositoryConfigScreen(navController = navController)
        }
        composable(Screen.ModelSearch.route) {
            ModelSearchScreen(navController = navController)
        }
    }
}
