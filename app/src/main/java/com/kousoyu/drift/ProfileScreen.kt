package com.kousoyu.drift

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kousoyu.drift.data.AuthState
import com.kousoyu.drift.data.DriftUser
import com.kousoyu.drift.data.UpdateManager
import com.kousoyu.drift.ui.theme.DriftTheme
import com.kousoyu.drift.ui.theme.ThemeMode
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

// ─── Profile Screen ───────────────────────────────────────────────────────────

@Composable
fun ProfileScreen(
    currentTheme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onNavigateToEdit: () -> Unit,
    updateManager: UpdateManager? = null
) {
    val vm: AuthViewModel = viewModel()
    val profileVm: ProfileViewModel = viewModel()
    val authState by vm.authState.collectAsState()
    val updateState = updateManager?.updateState?.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // ── Live data from ProfileViewModel ─────────────────────────────────
    val favoriteCount by profileVm.favoriteCount.collectAsState()
    val novelFavoriteCount by profileVm.novelFavoriteCount.collectAsState()
    val cacheSize by profileVm.cacheSize.collectAsState()

    // 每次页面可见时刷新缓存大小
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                profileVm.refreshCacheSize()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var showLogin by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { scaffoldPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding)
            .verticalScroll(scrollState)
            .padding(horizontal = 28.dp)
            .padding(top = 56.dp, bottom = 40.dp)
    ) {

        // ─── Identity Block ──────────────────────────────────────────────────
        Crossfade(
            targetState = authState,
            animationSpec = tween(300),
            label = "identity"
        ) { state ->
            when (state) {
                is AuthState.LoggedIn -> LoggedInIdentity(
                    user = state.user,
                    onEditClick = onNavigateToEdit
                )
                is AuthState.Loading -> LoadingIdentity()
                else -> GuestIdentity(onLoginClick = { showLogin = true })
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(32.dp))

        // ─── Stats Block ─────────────────────────────────────────────────────
        Text(
            text = "我的收藏",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            StatBlock(value = "$favoriteCount", label = "漫画", modifier = Modifier.weight(1f))
            StatDivider()
            StatBlock(value = "$novelFavoriteCount", label = "小说", modifier = Modifier.weight(1f))
        }

        // ─── Cache Row ───────────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(20.dp))
        Surface(
            onClick = {
                profileVm.clearCache()
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("缓存已清理 ✓", duration = SnackbarDuration.Short)
                }
            },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "图片缓存",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = cacheSize,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(32.dp))

        // ─── Theme Picker ─────────────────────────────────────────────────────
        Text(
            text = "主题外观",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        ThemeSelectorRow(currentTheme = currentTheme, onThemeChange = onThemeChange)

        Spacer(modifier = Modifier.height(40.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(32.dp))

        // ─── Logout (only when logged in) ─────────────────────────────────────
        AnimatedVisibility(
            visible = authState is AuthState.LoggedIn,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                TextButton(
                    onClick = { showLogoutConfirm = true },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = "退出登录",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }
                Spacer(Modifier.height(40.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                Spacer(Modifier.height(32.dp))
            }
        }

        // ─── About ────────────────────────────────────────────────────────────
        Text(
            text = "关于",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Drift 游离", fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(
                text = "v${updateManager?.getCurrentVersionName() ?: "1.0"}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "开源 · 无广告 · 极简主义",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )

        // ─── Check Update Button ─────────────────────────────────────────────
        Spacer(modifier = Modifier.height(16.dp))
        val currentUpdateState = updateState?.value
        OutlinedButton(
            onClick = {
                coroutineScope.launch {
                    try { updateManager?.checkUpdate() } catch (_: Exception) { }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = currentUpdateState !is UpdateManager.UpdateState.Checking,
            shape = MaterialTheme.shapes.small
        ) {
            when (currentUpdateState) {
                is UpdateManager.UpdateState.Checking -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("检查中...", fontSize = 13.sp)
                }
                is UpdateManager.UpdateState.UpToDate -> {
                    Icon(
                        Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("已是最新版本 ✓", fontSize = 13.sp)
                }
                is UpdateManager.UpdateState.Error -> {
                    Text("检查更新（网络异常，点击重试）", fontSize = 13.sp)
                }
                else -> {
                    Icon(
                        Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("检查更新", fontSize = 13.sp)
                }
            }
        }

        // ─── Show update dialog (available / downloading / error) ────────────
        val showDialog = currentUpdateState is UpdateManager.UpdateState.Available ||
                currentUpdateState is UpdateManager.UpdateState.Downloading ||
                currentUpdateState is UpdateManager.UpdateState.Error

        if (showDialog) {
            val info = when (currentUpdateState) {
                is UpdateManager.UpdateState.Available -> currentUpdateState.info
                else -> updateManager?.lastUpdateInfo
            }
            if (info != null && currentUpdateState != null) {
                UpdateDialog(
                    info = info,
                    currentVersion = updateManager?.getCurrentVersionName() ?: "1.0",
                    updateState = currentUpdateState,
                    onUpdate = { updateManager?.downloadAndInstall(info) },
                    onDismiss = { updateManager?.dismiss() }
                )
            }
        }

        Spacer(modifier = Modifier.height(60.dp))
        Text(
            text = "Drift · 游离\n© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} Drift Project",
            fontSize = 10.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
    } // end Scaffold

    // ─── Login Bottom Sheet ────────────────────────────────────────────────────
    if (showLogin) {
        LoginSheet(
            vm = vm,
            onDismiss = { showLogin = false; vm.resetLoginForm() }
        )
    }

    // ─── 退出登录确认弹窗 ─────────────────────────────────────────────────
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出登录", fontWeight = FontWeight.Bold) },
            text = { Text("确定要退出当前账号吗？", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { showLogoutConfirm = false; vm.logout() }) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

// ─── Identity Composables ─────────────────────────────────────────────────────

@Composable
private fun LoggedInIdentity(user: DriftUser, onEditClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        AvatarDisplay(
            avatarUrl = user.avatarUrl,
            nickname = user.nickname,
            size = 64,
            fontSize = 24
        )
        Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.nickname,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground,
                letterSpacing = (-0.5).sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "@${user.username}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        IconButton(
            onClick = onEditClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "编辑资料",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun GuestIdentity(onLoginClick: () -> Unit) {
    Column {
        Text(
            text = "游客",
            fontSize = 52.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onBackground,
            letterSpacing = (-1).sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "登录后体验多端同步与云书架",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onLoginClick,
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(
                text = "立即登录 →",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun LoadingIdentity() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "连接中...",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

// ─── Stat Block ───────────────────────────────────────────────────────────────

@Composable
fun StatBlock(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onBackground,
            letterSpacing = (-0.5).sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatDivider() {
    Box(modifier = Modifier.height(40.dp).width(0.5.dp).padding(vertical = 4.dp)) {
        HorizontalDivider(
            modifier = Modifier.fillMaxHeight().width(0.5.dp),
            color = MaterialTheme.colorScheme.outline
        )
    }
}

// ─── Theme Selector ───────────────────────────────────────────────────────────

@Composable
fun ThemeSelectorRow(currentTheme: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT).forEach { mode ->
            val isSelected = mode == currentTheme
            Surface(
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onThemeChange(mode)
                },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.small,
                color = if (isSelected) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isSelected) MaterialTheme.colorScheme.background
                               else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text(
                    text = mode.label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                )
            }
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF000000, showSystemUi = true)
@Composable
fun ProfileDarkPreview() {
    DriftTheme(darkTheme = true) {
        ProfileScreen(currentTheme = ThemeMode.SYSTEM, onThemeChange = {}, onNavigateToEdit = {})
    }
}
