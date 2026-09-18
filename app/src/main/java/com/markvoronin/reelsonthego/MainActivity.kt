package com.markvoronin.reelsonthego

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.markvoronin.reelsonthego.data.AppThemeMode
import com.markvoronin.reelsonthego.data.PreferencesRepository
import com.markvoronin.reelsonthego.data.PrevAction
import com.markvoronin.reelsonthego.service.MediaButtonService
import com.markvoronin.reelsonthego.ui.theme.ReelsWhileDrivingTheme
import com.markvoronin.reelsonthego.ui.theme.StatusAmber
import com.markvoronin.reelsonthego.ui.theme.StatusGreen
import com.markvoronin.reelsonthego.util.ShizukuManager

class MainActivity : ComponentActivity() {

    private lateinit var prefsRepository: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefsRepository = PreferencesRepository(this)
        ShizukuManager.init()

        setContent {
            var currentThemeMode by remember { mutableStateOf(prefsRepository.themeMode) }

            ReelsWhileDrivingTheme(themeMode = currentThemeMode) {
                MainScreen(
                    prefsRepository = prefsRepository,
                    currentThemeMode = currentThemeMode,
                    onThemeModeChange = { newMode ->
                        currentThemeMode = newMode
                        prefsRepository.themeMode = newMode
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    prefsRepository: PreferencesRepository,
    currentThemeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit
) {
    val context = LocalContext.current

    // Preferences & State
    var isMasterEnabled by remember { mutableStateOf(prefsRepository.isServiceEnabled) }
    var isPrevDoubleTap by remember { mutableStateOf(prefsRepository.isPrevButtonDoubleTap) }
    var selectedSwipeDuration by remember { mutableLongStateOf(prefsRepository.swipeDurationMs) }
    var isGlobalSwipeEnabled by remember { mutableStateOf(prefsRepository.isGlobalSwipeEnabled) }
    var enabledPackages by remember { mutableStateOf(prefsRepository.enabledPackages) }

    // Service & Permission States
    var isMediaServiceRunning by remember { mutableStateOf(MediaButtonService.isRunning) }
    val shizukuAvailable by ShizukuManager.isAvailableFlow.collectAsState()
    val shizukuGranted by ShizukuManager.isGrantedFlow.collectAsState()
    var isBatteryOptIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    // UI state
    var showHelpSheet by remember { mutableStateOf(false) }

    // App Version Info
    val versionName = remember(context) {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.5"
        } catch (_: Exception) {
            "1.5"
        }
    }

    // Permission launcher for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            MediaButtonService.startService(context)
            isMediaServiceRunning = true
        } else {
            Toast.makeText(context, "Notification permission required for background service", Toast.LENGTH_SHORT).show()
        }
    }

    // Lifecycle observer to refresh states on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isMediaServiceRunning = MediaButtonService.isRunning
                ShizukuManager.refreshCapabilitiesAsync()
                isBatteryOptIgnored = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val handleShizukuClick = {
        if (shizukuAvailable && !shizukuGranted) {
            ShizukuManager.requestPermission()
        } else if (shizukuGranted) {
            ShizukuManager.grantMediaKeyPermissions(context)
            Toast.makeText(context, "Granted MediaKey permissions via Shizuku", Toast.LENGTH_SHORT).show()
        } else {
            val granted = ShizukuManager.refreshCapabilities()
            if (granted) {
                Toast.makeText(context, "Shizuku connected & granted!", Toast.LENGTH_SHORT).show()
            } else if (ShizukuManager.isAvailable) {
                ShizukuManager.requestPermission()
            } else {
                Toast.makeText(context, "Shizuku app is not running", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.DirectionsCar,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "WheelReels",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // Version Badge Chip
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "v$versionName",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val nextMode = currentThemeMode.next()
                            onThemeModeChange(nextMode)
                            val toastLabel = when (nextMode) {
                                AppThemeMode.DARK -> "Dark Mode 🌙"
                                AppThemeMode.LIGHT -> "Light Mode ☀️"
                                AppThemeMode.SYSTEM -> "System Theme 📱"
                            }
                            Toast.makeText(context, toastLabel, Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = when (currentThemeMode) {
                                AppThemeMode.DARK -> Icons.Rounded.DarkMode
                                AppThemeMode.LIGHT -> Icons.Rounded.LightMode
                                AppThemeMode.SYSTEM -> Icons.Rounded.SettingsBrightness
                            },
                            contentDescription = "Theme Mode",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = { showHelpSheet = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                            contentDescription = "Help Guide",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // Hero Master Power Card
            HeroMasterCard(
                isEnabled = isMasterEnabled,
                onEnabledChange = { enabled ->
                    isMasterEnabled = enabled
                    prefsRepository.isServiceEnabled = enabled
                },
                isMediaServiceRunning = isMediaServiceRunning,
                shizukuAvailable = shizukuAvailable,
                shizukuGranted = shizukuGranted,
                onToggleMediaService = {
                    if (!isMediaServiceRunning) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                MediaButtonService.startService(context)
                                isMediaServiceRunning = true
                            } else {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        } else {
                            MediaButtonService.startService(context)
                            isMediaServiceRunning = true
                        }
                    } else {
                        MediaButtonService.stopService(context)
                        isMediaServiceRunning = false
                    }
                },
                onShizukuClick = handleShizukuClick
            )

            // Swipe Speed / Duration Selector Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Scroll Speed",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PreferencesRepository.SWIPE_SPEED_OPTIONS.forEach { option ->
                            val isSelected = selectedSwipeDuration == option.durationMs
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedSwipeDuration = option.durationMs
                                    prefsRepository.swipeDurationMs = option.durationMs
                                },
                                label = {
                                    Text(
                                        text = option.label.split(" ").first(), // Short label
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Steering Wheel Action & Behavior Controls Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Behavior",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Remap Prev Button Row
                    SwitchSettingRow(
                        icon = Icons.Rounded.Favorite,
                        title = "Double-Tap Like on Prev",
                        subtitle = "Default action for Prev button (customizable per app below)",
                        checked = isPrevDoubleTap,
                        onCheckedChange = { checked ->
                            isPrevDoubleTap = checked
                            prefsRepository.isPrevButtonDoubleTap = checked
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Global Swiping Toggle Row
                    SwitchSettingRow(
                        icon = Icons.Rounded.Public,
                        title = "Global Swiping",
                        subtitle = "Enable in all applications",
                        checked = isGlobalSwipeEnabled,
                        onCheckedChange = { checked ->
                            isGlobalSwipeEnabled = checked
                            prefsRepository.isGlobalSwipeEnabled = checked
                        }
                    )
                }
            }

            // Target Apps Selector Card (Shown when Global Swipe is disabled)
            AnimatedVisibility(
                visible = !isGlobalSwipeEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Apps,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Target Apps",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        PreferencesRepository.SUPPORTED_APPS.forEachIndexed { index, app ->
                            val isAppEnabled = enabledPackages.contains(app.packageName)
                            val currentPrevAction = prefsRepository.getPrevActionForPackage(app.packageName)
                            var appPrevAction by remember(app.packageName, isPrevDoubleTap) { mutableStateOf(currentPrevAction) }

                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = app.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Switch(
                                        checked = isAppEnabled,
                                        onCheckedChange = { checked ->
                                            prefsRepository.togglePackage(app.packageName, checked)
                                            enabledPackages = prefsRepository.enabledPackages
                                        }
                                    )
                                }

                                AnimatedVisibility(visible = isAppEnabled) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Prev button:",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        PrevAction.entries.forEach { action ->
                                            val isSelected = appPrevAction == action
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    appPrevAction = action
                                                    prefsRepository.setPrevActionForPackage(app.packageName, action)
                                                },
                                                label = {
                                                    Text(
                                                        text = action.label,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                                ),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            if (index < PreferencesRepository.SUPPORTED_APPS.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }

            // Permissions & Advanced Integration Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Services & Access",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Bluetooth Listener Service Status
                    PermissionTileRow(
                        title = "Bluetooth Media Listener",
                        statusText = if (isMediaServiceRunning) "Running" else "Stopped",
                        isOk = isMediaServiceRunning,
                        onClick = {
                            if (!isMediaServiceRunning) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                        MediaButtonService.startService(context)
                                        isMediaServiceRunning = true
                                    } else {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                } else {
                                    MediaButtonService.startService(context)
                                    isMediaServiceRunning = true
                                }
                            } else {
                                MediaButtonService.stopService(context)
                                isMediaServiceRunning = false
                            }
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Shizuku Status
                    PermissionTileRow(
                        title = "Shizuku / ADB Touch Injection",
                        statusText = if (shizukuGranted) "Granted" else if (shizukuAvailable) "Available" else "Optional",
                        isOk = shizukuGranted,
                        onClick = handleShizukuClick
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Battery Optimization Status
                    PermissionTileRow(
                        title = "Unrestricted Battery Usage",
                        statusText = if (isBatteryOptIgnored) "Unrestricted" else "Optimize (May Kill Service)",
                        isOk = isBatteryOptIgnored,
                        onClick = {
                            launchBatteryOptimizationSettings(context)
                        }
                    )
                }
            }

            // Quick Gesture Tester Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.TouchApp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Test Gestures",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                if (ShizukuManager.isGranted) {
                                    val dm = context.resources.displayMetrics
                                    ShizukuManager.swipeUp(dm.widthPixels, dm.heightPixels, selectedSwipeDuration)
                                } else {
                                    Toast.makeText(context, "Shizuku permission required!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Next ⬆️", fontSize = 13.sp)
                        }

                        FilledTonalButton(
                            onClick = {
                                if (ShizukuManager.isGranted) {
                                    val dm = context.resources.displayMetrics
                                    ShizukuManager.swipeDown(dm.widthPixels, dm.heightPixels, selectedSwipeDuration)
                                } else {
                                    Toast.makeText(context, "Shizuku permission required!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Prev ⬇️", fontSize = 13.sp)
                        }

                        FilledTonalButton(
                            onClick = {
                                if (ShizukuManager.isGranted) {
                                    val dm = context.resources.displayMetrics
                                    ShizukuManager.doubleTap(dm.widthPixels, dm.heightPixels)
                                } else {
                                    Toast.makeText(context, "Shizuku permission required!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Like ❤️", fontSize = 13.sp)
                        }
                    }
                }
            }



            // Footer Version Info
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "WheelReels • v$versionName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }

    // Modal Bottom Sheet for Instructions
    if (showHelpSheet) {
        ModalBottomSheet(
            onDismissRequest = { showHelpSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Quick Setup Guide",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                HelpStepItem(number = "1", title = "Grant Accessibility", description = "Allows the app to scroll videos when steering wheel buttons are pressed.")
                HelpStepItem(number = "2", title = "Start Bluetooth Listener", description = "Enables background detection of media keys from your car head unit.")
                HelpStepItem(number = "3", title = "Connect Bluetooth", description = "Pair your phone with your car's media unit.")
                HelpStepItem(number = "4", title = "Enjoy Hands-free Scrolling", description = "Open Instagram, TikTok, or Shorts and press Next/Prev on your wheel!")

                Button(
                    onClick = { showHelpSheet = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Got it")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun HeroMasterCard(
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isMediaServiceRunning: Boolean,
    shizukuAvailable: Boolean,
    shizukuGranted: Boolean,
    onToggleMediaService: () -> Unit,
    onShizukuClick: () -> Unit
) {
    val containerColor = if (isEnabled) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    }

    val contentColor = if (isEnabled) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val cardBorder = BorderStroke(
        1.dp,
        if (isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = cardBorder,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = if (isEnabled) StatusGreen else Color.Gray,
                            modifier = Modifier.size(8.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isEnabled) "SERVICE ACTIVE" else "SERVICE PAUSED",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = if (isEnabled) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Steering Wheel Controls",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.surface
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Quick Status Chips Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusBadgeChip(
                    label = if (isMediaServiceRunning) "Listener Active" else "Start Listener",
                    isOk = isMediaServiceRunning,
                    onClick = onToggleMediaService,
                    modifier = Modifier.weight(1f)
                )

                StatusBadgeChip(
                    label = if (shizukuGranted) "Shizuku OK" else if (shizukuAvailable) "Shizuku Ready" else "Shizuku",
                    isOk = shizukuGranted,
                    onClick = onShizukuClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatusBadgeChip(
    label: String,
    isOk: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val bg = if (isOk) {
        if (isDark) Color(0xFF064E3B) else Color(0xFFD1FAE5)
    } else {
        if (isDark) Color(0xFF7F1D1D) else Color(0xFFFEE2E2)
    }

    val fg = if (isOk) {
        if (isDark) Color(0xFF6EE7B7) else Color(0xFF065F46)
    } else {
        if (isDark) Color(0xFFFCA5A5) else Color(0xFF991B1B)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isOk) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = fg,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SwitchSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PermissionTileRow(
    title: String,
    statusText: String,
    isOk: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isOk) StatusGreen.copy(alpha = 0.15f) else StatusAmber.copy(alpha = 0.15f)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isOk) StatusGreen else StatusAmber,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun HelpStepItem(
    number: String,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
}

private fun launchBatteryOptimizationSettings(context: Context) {
    val pkg = context.packageName
    val intents = listOf(
        // 1. Direct prompt to request ignore battery optimizations
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),

        // 2. App info settings page (Contains "Battery" -> "Unrestricted" on Android 8-15)
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),

        // 3. System-wide ignore battery optimization list
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),

        // 4. Xiaomi / MIUI / HyperOS Powerkeeper
        Intent().apply {
            setClassName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            putExtra("package_name", pkg)
            putExtra("package_label", "WheelReels")
        },

        // 5. Samsung Device Care
        Intent().apply {
            setClassName("com.samsung.android.looper", "com.samsung.android.looper.autoinitial.AutoInitialActivity")
        }
    )

    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Toast.makeText(context, "Set Battery Usage to 'Unrestricted'", Toast.LENGTH_LONG).show()
            return
        } catch (_: Exception) {
            // Try next intent fallback
        }
    }

    Toast.makeText(context, "Set Battery Usage to 'Unrestricted' in App Settings", Toast.LENGTH_LONG).show()
}
