package com.kousoyu.drift

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import com.kousoyu.drift.ui.theme.DriftTheme
import com.kousoyu.drift.ui.theme.ThemeMode
import androidx.lifecycle.viewmodel.compose.viewModel

// ─── Profile Screen ───────────────────────────────────────────────────────────

@Composable
fun ProfileScreen(
    currentTheme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onNavigateToEdit: () -> Unit
) {
    val vm: AuthViewModel = viewModel()
    val authState by vm.authState.collectAsState()

    var showLogin by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
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
            StatBlock(value = "12", label = "漫画", modifier = Modifier.weight(1f))
            StatDivider()
            StatBlock(value = "8",  label = "小说", modifier = Modifier.weight(1f))
            StatDivider()
            StatBlock(value = "1.2 G", label = "缓存", modifier = Modifier.weight(1f))
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
                    onClick = vm::logout,
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
            Text(text = "v1.0.0-alpha", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "开源 · 无广告 · 极简主义",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(60.dp))
        Text(
            text = "Drift · 游离\n© 2025 Drift Project",
            fontSize = 10.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }

    // ─── Login Bottom Sheet ────────────────────────────────────────────────────
    if (showLogin) {
        LoginSheet(
            vm = vm,
            onDismiss = { showLogin = false }
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
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(ThemeMode.SYSTEM, ThemeMode.DARK, ThemeMode.LIGHT).forEach { mode ->
            val isSelected = mode == currentTheme
            Surface(
                onClick = { onThemeChange(mode) },
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
