package com.kousoyu.drift

import androidx.lifecycle.ViewModel
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

class AuthViewModel : ViewModel() {

    val authState: StateFlow<AuthState> = AuthManager.authState

    // ─── Login form state ──────────────────────────────────────────────────────

    private val _emailInput = MutableStateFlow("")
    val emailInput = _emailInput.asStateFlow()

    private val _passwordInput = MutableStateFlow("")
    val passwordInput = _passwordInput.asStateFlow()

    private val _nicknameInput = MutableStateFlow("")
    val nicknameInput = _nicknameInput.asStateFlow()

    private val _isRegisterMode = MutableStateFlow(false)
    val isRegisterMode = _isRegisterMode.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError = _loginError.asStateFlow()

    // ─── Username check state ──────────────────────────────────────────────────

    private val _usernameCheckState = MutableStateFlow<UsernameCheckState>(UsernameCheckState.Idle)
    val usernameCheckState = _usernameCheckState.asStateFlow()

    private val _editUsernameInput = MutableStateFlow("")
    val editUsernameInput = _editUsernameInput.asStateFlow()

    private val _editNicknameInput = MutableStateFlow("")
    val editNicknameInput = _editNicknameInput.asStateFlow()

    private val _editAvatarUrl = MutableStateFlow("")
    val editAvatarUrl = _editAvatarUrl.asStateFlow()

    private val _saveResult = MutableStateFlow<String?>(null)
    val saveResult = _saveResult.asStateFlow()

    private var usernameCheckJob: Job? = null

    // ─── Form sync helpers ─────────────────────────────────────────────────────

    fun setEmail(v: String) { _emailInput.value = v; _loginError.value = null }
    fun setPassword(v: String) { _passwordInput.value = v; _loginError.value = null }
    fun setNickname(v: String) { _nicknameInput.value = v }
    fun toggleRegisterMode() {
        _isRegisterMode.value = !_isRegisterMode.value
        _loginError.value = null
    }

    // ─── Auth actions ──────────────────────────────────────────────────────────

    fun loginWithEmail() {
        viewModelScope.launch {
            _loginError.value = null
            val result = AuthManager.loginWithEmail(_emailInput.value, _passwordInput.value)
            result.onFailure { _loginError.value = it.message }
        }
    }

    fun registerWithEmail() {
        viewModelScope.launch {
            _loginError.value = null
            val result = AuthManager.registerWithEmail(
                _emailInput.value, _passwordInput.value, _nicknameInput.value
            )
            result.onFailure { _loginError.value = it.message }
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
        _usernameCheckState.value = UsernameCheckState.Idle
        _saveResult.value = null
    }

    fun setEditUsername(v: String) {
        _editUsernameInput.value = v
        _usernameCheckState.value = UsernameCheckState.Checking

        // Debounce — cancel previous check
        usernameCheckJob?.cancel()
        usernameCheckJob = viewModelScope.launch {
            delay(500)
            _usernameCheckState.value = AuthManager.checkUsernameAvailability(v)
        }
    }

    fun setEditNickname(v: String) { _editNicknameInput.value = v }

    fun setEditAvatarUrl(v: String) { _editAvatarUrl.value = v }

    fun saveProfile() {
        viewModelScope.launch {
            _saveResult.value = null
            // Don't save if username is still being checked or is taken
            val check = _usernameCheckState.value
            if (check is UsernameCheckState.Checking || check is UsernameCheckState.Taken || check is UsernameCheckState.Invalid) {
                _saveResult.value = "请先修正用户名"
                return@launch
            }
            val result = AuthManager.updateProfile(
                nickname = _editNicknameInput.value.ifBlank { null },
                username = _editUsernameInput.value.ifBlank { null },
                avatarUrl = _editAvatarUrl.value.ifBlank { null }
            )
            result.fold(
                onSuccess = { _saveResult.value = "✓ 保存成功" },
                onFailure = { _saveResult.value = it.message ?: "保存失败" }
            )
        }
    }

    fun clearSaveResult() { _saveResult.value = null }
}
