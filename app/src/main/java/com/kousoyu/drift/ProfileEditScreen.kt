package com.kousoyu.drift

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kousoyu.drift.data.AuthState
import com.kousoyu.drift.data.DriftUser
import com.kousoyu.drift.data.UsernameCheckState

// ─── Profile Edit Screen ──────────────────────────────────────────────────────

@Composable
fun ProfileEditScreen(
    vm: AuthViewModel,
    onBack: () -> Unit
) {
    val authState by vm.authState.collectAsState()
    val user = (authState as? AuthState.LoggedIn)?.user

    // Initialize form state when user is available
    LaunchedEffect(user) {
        if (user != null) vm.initEditState(user)
    }

    if (user == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val editUsername by vm.editUsernameInput.collectAsState()
    val editNickname by vm.editNicknameInput.collectAsState()
    val editAvatarUrl by vm.editAvatarUrl.collectAsState()
    val usernameCheck by vm.usernameCheckState.collectAsState()
    val saveResult by vm.saveResult.collectAsState()

    // Photo picker for custom avatars
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let { vm.setEditAvatarUrl(it.toString()) }
        }
    )

    // Show save result snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(saveResult) {
        saveResult?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            vm.clearSaveResult()
            if (it.startsWith("✓")) onBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .padding(top = 20.dp, bottom = 48.dp)
        ) {

            // ─── Top bar ────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Filled.ArrowBackIosNew,
                        contentDescription = "返回",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = vm::saveProfile,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        "保存",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // ─── Avatar (Clickable to pick from gallery) ──────────────────────────────────────────────────────
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.clickable {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    AvatarDisplay(
                        avatarUrl = editAvatarUrl,
                        nickname = editNickname.ifBlank { user.nickname },
                        size = 88,
                        fontSize = 32
                    )
                    // Camera Icon Overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = "选择头像",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ─── Avatar URL input ─────────────────────────────────────────────
            Text(
                text = "头像 URL",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(10.dp))
            MinimalTextField(
                value = editAvatarUrl,
                onValueChange = vm::setEditAvatarUrl,
                placeholder = "https://... 或留空使用文字头像",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                )
            )

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
            Spacer(Modifier.height(32.dp))

            // ─── Nickname ────────────────────────────────────────────────────
            Text(
                text = "昵称",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "昵称可以重复，随意起",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(12.dp))
            MinimalTextField(
                value = editNickname,
                onValueChange = vm::setEditNickname,
                placeholder = "你的显示名称",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            Spacer(Modifier.height(32.dp))

            // ─── Username ─────────────────────────────────────────────────────
            Text(
                text = "用户名",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "全局唯一的 ID，小写字母、数字、点、下划线，3-20 位",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(12.dp))
            MinimalTextField(
                value = editUsername,
                onValueChange = vm::setEditUsername,
                placeholder = "@username",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                trailingIcon = {
                    UsernameCheckIcon(state = usernameCheck)
                }
            )

            // ─── Username check feedback ──────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            AnimatedContent(
                targetState = usernameCheck,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "usernameCheck"
            ) { check ->
                when (check) {
                    is UsernameCheckState.Checking -> Text(
                        "检查中...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    is UsernameCheckState.Available -> Text(
                        "@$editUsername 可以使用",
                        fontSize = 12.sp,
                        color = Color(0xFF34C759) // green
                    )
                    is UsernameCheckState.Taken -> Text(
                        check.message,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    is UsernameCheckState.Invalid -> Text(
                        check.message,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    else -> Spacer(Modifier.height(0.dp))
                }
            }

            Spacer(Modifier.height(48.dp))

            // ─── Auth provider info ───────────────────────────────────────────
            Text(
                text = "登录方式  •  ${
                    when(user.authProvider) {
                        com.kousoyu.drift.data.AuthProvider.EMAIL -> "邮箱  ${user.email}"
                        com.kousoyu.drift.data.AuthProvider.QQ -> "QQ 授权"
                        com.kousoyu.drift.data.AuthProvider.WECHAT -> "微信授权"
                    }
                }",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

// ─── Username check trailing icon ─────────────────────────────────────────────

@Composable
fun UsernameCheckIcon(state: UsernameCheckState) {
    Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        when (state) {
            is UsernameCheckState.Checking -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            is UsernameCheckState.Available -> Icon(
                Icons.Filled.Check, contentDescription = null,
                tint = Color(0xFF34C759), modifier = Modifier.size(18.dp)
            )
            is UsernameCheckState.Taken, is UsernameCheckState.Invalid -> Icon(
                Icons.Filled.Close, contentDescription = null,
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)
            )
            else -> Unit
        }
    }
}

// ─── Avatar Display Component ─────────────────────────────────────────────────

@Composable
fun AvatarDisplay(
    avatarUrl: String,
    nickname: String,
    size: Int = 52,
    fontSize: Int = 20
) {
    val colors = listOf(
        Color(0xFF6C5CE7),
        Color(0xFF00B894),
        Color(0xFFE17055),
        Color(0xFF0984E3),
        Color(0xFFD63031)
    )
    val colorIndex = (nickname.firstOrNull()?.code ?: 0) % colors.size
    val bgColor = colors[colorIndex]

    if (avatarUrl.isNotEmpty()) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = nickname,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = (nickname.firstOrNull() ?: "?").toString().uppercase(),
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
