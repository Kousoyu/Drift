package com.kousoyu.drift

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kousoyu.drift.data.AuthManager
import com.kousoyu.drift.data.AuthState
import com.kousoyu.drift.data.DriftUser
import com.kousoyu.drift.data.UsernameCheckState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─── Auth ViewModel ───────────────────────────────────────────────────────────

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    val authState: StateFlow<AuthState> = AuthManager.authState

    // ─── Login form state ──────────────────────────────────────────────────────

    private val _emailInput = MutableStateFlow("")
    val emailInput = _emailInput.asStateFlow()

    private val _passwordInput = MutableStateFlow("")
    val passwordInput = _passwordInput.asStateFlow()

    private val _nicknameInput = MutableStateFlow("")
    val nicknameInput = _nicknameInput.asStateFlow()

    private val _regUsernameInput = MutableStateFlow("")
    val regUsernameInput = _regUsernameInput.asStateFlow()

    private val _inviteCodeInput = MutableStateFlow("")
    val inviteCodeInput = _inviteCodeInput.asStateFlow()

    private val _isRegisterMode = MutableStateFlow(false)
    val isRegisterMode = _isRegisterMode.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError = _loginError.asStateFlow()

    // ─── Username check state（注册 / 编辑 分离，避免状态污染）─────────────────

    private val _regUsernameCheckState = MutableStateFlow<UsernameCheckState>(UsernameCheckState.Idle)
    val regUsernameCheckState = _regUsernameCheckState.asStateFlow()

    private val _editUsernameCheckState = MutableStateFlow<UsernameCheckState>(UsernameCheckState.Idle)
    val editUsernameCheckState = _editUsernameCheckState.asStateFlow()

    private val _editUsernameInput = MutableStateFlow("")
    val editUsernameInput = _editUsernameInput.asStateFlow()

    private val _editNicknameInput = MutableStateFlow("")
    val editNicknameInput = _editNicknameInput.asStateFlow()

    private val _editAvatarUrl = MutableStateFlow("")
    val editAvatarUrl = _editAvatarUrl.asStateFlow()

    private val _saveResult = MutableStateFlow<String?>(null)
    val saveResult = _saveResult.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving = _isSaving.asStateFlow()

    private var regUsernameCheckJob: Job? = null
    private var editUsernameCheckJob: Job? = null

    // ─── Form sync helpers ─────────────────────────────────────────────────────

    fun setEmail(v: String) { _emailInput.value = v; _loginError.value = null }
    fun setPassword(v: String) { _passwordInput.value = v; _loginError.value = null }
    fun setNickname(v: String) { _nicknameInput.value = v }
    fun setRegUsername(v: String) {
        val normalized = v.lowercase().trim()
        _regUsernameInput.value = normalized
        _loginError.value = null
        _regUsernameCheckState.value = UsernameCheckState.Checking
        regUsernameCheckJob?.cancel()
        regUsernameCheckJob = viewModelScope.launch {
            delay(500)
            _regUsernameCheckState.value = AuthManager.checkUsernameAvailability(normalized)
        }
    }
    fun setInviteCode(v: String) { _inviteCodeInput.value = v.uppercase(); _loginError.value = null }
    fun toggleRegisterMode() {
        _isRegisterMode.value = !_isRegisterMode.value
        _loginError.value = null
    }

    /** 登录弹窗关闭时清空全部表单，防止残留 */
    fun resetLoginForm() {
        _emailInput.value = ""
        _passwordInput.value = ""
        _nicknameInput.value = ""
        _regUsernameInput.value = ""
        _inviteCodeInput.value = ""
        _loginError.value = null
        _isRegisterMode.value = false
        _regUsernameCheckState.value = UsernameCheckState.Idle
    }

    // ─── Auth actions ──────────────────────────────────────────────────────────

    fun loginWithEmail() {
        viewModelScope.launch {
            _loginError.value = null
            val result = AuthManager.loginWithEmail(_emailInput.value, _passwordInput.value)
            result.onFailure { _loginError.value = friendlyError(it) }
        }
    }

    fun registerWithEmail() {
        viewModelScope.launch {
            _loginError.value = null

            // ── 邮箱格式校验 ──
            val email = _emailInput.value.trim()
            if (email.isBlank()) {
                _loginError.value = "请输入邮箱"
                return@launch
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                _loginError.value = "请输入有效的邮箱地址"
                return@launch
            }

            // ── 密码强度校验 ──
            val password = _passwordInput.value
            if (password.length < 6) {
                _loginError.value = "密码至少需要 6 位"
                return@launch
            }

            // ── 用户名验证 ──
            val username = _regUsernameInput.value
            if (username.isBlank()) {
                _loginError.value = "请输入用户名"
                return@launch
            }
            val check = _regUsernameCheckState.value
            if (check is UsernameCheckState.Checking || check is UsernameCheckState.Taken || check is UsernameCheckState.Invalid) {
                _loginError.value = "请先修正用户名"
                return@launch
            }

            // ── 昵称校验 ──
            if (_nicknameInput.value.trim().isBlank()) {
                _loginError.value = "请输入昵称"
                return@launch
            }

            // ── 邀请码验证 ──
            if (!AuthManager.isValidInviteCode(_inviteCodeInput.value)) {
                _loginError.value = "邀请码无效"
                return@launch
            }
            val result = AuthManager.registerWithEmail(
                email, password, _nicknameInput.value.trim(), username
            )
            result.onFailure { _loginError.value = friendlyError(it) }
        }
    }

    private fun friendlyError(e: Throwable): String {
        val msg = e.message ?: return "操作失败"
        return when {
            msg.contains("rate_limit", true)          -> "操作过于频繁，请稍后再试"
            msg.contains("Invalid login", true)       -> "邮箱或密码错误"
            msg.contains("invalid_credentials", true) -> "邮箱或密码错误"
            msg.contains("Email not confirmed", true) -> "请先去邮箱验证账号"
            msg.contains("already registered", true)  -> "该邮箱已被注册"
            msg.contains("weak password", true)       -> "密码太弱，请使用更复杂的密码"
            msg.contains("network", true)             -> "网络连接失败"
            msg.contains("timeout", true)             -> "连接超时，请重试"
            msg.contains("Unable to resolve", true)   -> "无法连接服务器"
            // 已经是中文的友好消息，直接透传
            msg.startsWith("找不到") || msg.startsWith("请输入") || msg.startsWith("邮箱") || msg.startsWith("密码") -> msg
            else                                      -> "操作失败，请重试"
        }
    }

    fun loginWithQQ() {
        viewModelScope.launch { AuthManager.loginWithQQ() }
    }

    fun loginWithWeChat() {
        viewModelScope.launch { AuthManager.loginWithWeChat() }
    }

    fun logout() {
        AuthManager.logout()
        _loginError.value = null
    }

    // ─── Profile edit actions ──────────────────────────────────────────────────

    fun initEditState(user: DriftUser) {
        _editUsernameInput.value = user.username
        _editNicknameInput.value = user.nickname
        _editAvatarUrl.value = user.avatarUrl
        _editUsernameCheckState.value = UsernameCheckState.Idle
        _saveResult.value = null
    }

    fun setEditUsername(v: String) {
        val normalized = v.lowercase().trim()
        _editUsernameInput.value = normalized
        _editUsernameCheckState.value = UsernameCheckState.Checking

        // Debounce — cancel previous check
        editUsernameCheckJob?.cancel()
        editUsernameCheckJob = viewModelScope.launch {
            delay(500)
            _editUsernameCheckState.value = AuthManager.checkUsernameAvailability(normalized)
        }
    }

    fun setEditNickname(v: String) { _editNicknameInput.value = v }

    fun setEditAvatarUrl(v: String) { _editAvatarUrl.value = v }

    fun saveProfile() {
        viewModelScope.launch {
            _saveResult.value = null
            val check = _editUsernameCheckState.value
            if (check is UsernameCheckState.Checking || check is UsernameCheckState.Taken || check is UsernameCheckState.Invalid) {
                _saveResult.value = "请先修正用户名"
                return@launch
            }
            _isSaving.value = true
            val result = AuthManager.updateProfile(
                nickname = _editNicknameInput.value.ifBlank { null },
                username = _editUsernameInput.value.ifBlank { null },
                avatarUrl = _editAvatarUrl.value.ifBlank { null },
                context = getApplication()
            )
            _isSaving.value = false
            result.fold(
                onSuccess = { _saveResult.value = "✓ 保存成功" },
                onFailure = { _saveResult.value = it.message ?: "保存失败" }
            )
        }
    }

    fun clearSaveResult() { _saveResult.value = null }
}
