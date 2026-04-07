package com.kousoyu.drift

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kousoyu.drift.data.AuthState
import com.kousoyu.drift.data.UsernameCheckState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff

// ─── Login Bottom Sheet ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginSheet(
    vm: AuthViewModel,
    onDismiss: () -> Unit
) {
    val authState by vm.authState.collectAsState()
    val isRegisterMode by vm.isRegisterMode.collectAsState()
    val email by vm.emailInput.collectAsState()
    val password by vm.passwordInput.collectAsState()
    val nickname by vm.nicknameInput.collectAsState()
    val inviteCode by vm.inviteCodeInput.collectAsState()
    val loginError by vm.loginError.collectAsState()
    val focusManager = LocalFocusManager.current

    var passwordVisible by remember { mutableStateOf(false) }

    // Auto-dismiss on successful login
    LaunchedEffect(authState) {
        if (authState is AuthState.LoggedIn) onDismiss()
    }

    val isLoading = authState is AuthState.Loading

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 14.dp, bottom = 6.dp)
                    .width(36.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(top = 12.dp, bottom = 48.dp)
        ) {

            // ─── Title ──────────────────────────────────────────────────────
            Crossfade(targetState = isRegisterMode, label = "title") { register ->
                Text(
                    text = if (register) "创建账号" else "欢迎回来",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground,
                    letterSpacing = (-0.5).sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (isRegisterMode) "注册后拥有云书架与多端同步" else "登录 Drift，继续你的漂流之旅",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(36.dp))

            // ─── Register-only fields ────────────────────────────────────
            AnimatedVisibility(
                visible = isRegisterMode,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val regUsername by vm.regUsernameInput.collectAsState()
                val usernameCheck by vm.regUsernameCheckState.collectAsState()

                Column {
                    MinimalTextField(
                        value = nickname,
                        onValueChange = vm::setNickname,
                        placeholder = "昵称",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                    )
                    Spacer(Modifier.height(20.dp))
                    MinimalTextField(
                        value = regUsername,
                        onValueChange = vm::setRegUsername,
                        placeholder = "用户名（唯一 ID）",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        trailingIcon = {
                            UsernameCheckIcon(state = usernameCheck)
                        }
                    )
                    // Username check hint
                    if (regUsername.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        AnimatedContent(
                            targetState = usernameCheck,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "regUsernameCheck"
                        ) { check ->
                            when (check) {
                                is UsernameCheckState.Checking -> Text(
                                    "检查中...", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                is UsernameCheckState.Available -> Text(
                                    "@$regUsername 可以使用", fontSize = 11.sp,
                                    color = Color(0xFF34C759)
                                )
                                is UsernameCheckState.Taken -> Text(
                                    check.message, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                                is UsernameCheckState.Invalid -> Text(
                                    check.message, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                                else -> Spacer(Modifier.height(0.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    MinimalTextField(
                        value = inviteCode,
                        onValueChange = vm::setInviteCode,
                        placeholder = "邀请码",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }

            // ─── Email / Username ─────────────────────────────────────────
            MinimalTextField(
                value = email,
                onValueChange = vm::setEmail,
                placeholder = if (isRegisterMode) "邮箱" else "邮箱 / 用户名",
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isRegisterMode) KeyboardType.Email else KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
            )

            Spacer(Modifier.height(20.dp))

            // ─── Password ────────────────────────────────────────────────────
            MinimalTextField(
                value = password,
                onValueChange = vm::setPassword,
                placeholder = "密码",
                visualTransformation = if (passwordVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (isRegisterMode) ImeAction.Done else ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    if (isRegisterMode) vm.registerWithEmail() else vm.loginWithEmail()
                }),
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )

            // ─── Error Message ──────────────────────────────────────────────
            AnimatedVisibility(
                visible = loginError != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = loginError ?: "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            Spacer(Modifier.height(32.dp))

            // ─── Primary Action Button ───────────────────────────────────────
            Button(
                onClick = {
                    focusManager.clearFocus()
                    if (isRegisterMode) vm.registerWithEmail() else vm.loginWithEmail()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !isLoading,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                    disabledContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.background,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = if (isRegisterMode) "创建账号" else "登录",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ─── Toggle register/login ────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isRegisterMode) "已有账号？" else "还没有账号？",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = vm::toggleRegisterMode,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(
                        text = if (isRegisterMode) "直接登录" else "注册",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // ─── Divider ─────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                Text(
                    text = "  或者  ",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
            }

            Spacer(Modifier.height(24.dp))

            // ─── Third-party Buttons ──────────────────────────────────────────
            var showComingSoon by remember { mutableStateOf<String?>(null) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OAuthButton(
                    label = "QQ",
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading,
                    onClick = {
                        focusManager.clearFocus()
                        showComingSoon = "QQ 登录即将开放，敬请期待 ✨"
                    }
                )
                OAuthButton(
                    label = "微信",
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading,
                    onClick = {
                        focusManager.clearFocus()
                        showComingSoon = "微信登录即将开放，敬请期待 ✨"
                    }
                )
            }

            // ─── Coming Soon Hint ────────────────────────────────────────────
            AnimatedVisibility(
                visible = showComingSoon != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = showComingSoon ?: "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

// ─── Minimal Underline Text Field ─────────────────────────────────────────────

@Composable
fun MinimalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val textColor = MaterialTheme.colorScheme.onBackground
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val lineColor = MaterialTheme.colorScheme.outline

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    color = textColor
                ),
                cursorBrush = SolidColor(textColor),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = visualTransformation,
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(placeholder, color = placeholderColor, fontSize = 15.sp)
                        }
                        inner()
                    }
                }
            )
            if (trailingIcon != null) trailingIcon()
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = lineColor, thickness = 0.5.dp)
    }
}

// ─── OAuth Outlined Button ────────────────────────────────────────────────────

@Composable
fun OAuthButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outline
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onBackground
        )
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
