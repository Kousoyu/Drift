package com.kousoyu.drift.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ─── Domain Models ────────────────────────────────────────────────────────────

enum class AuthProvider { QQ, WECHAT, EMAIL }

data class DriftUser(
    val uid: String,
    val username: String,          // unique @handle, e.g. "kousoyu"
    val nickname: String,          // display name, can repeat
    val avatarUrl: String = "",    // empty = use generated initials avatar
    val email: String = "",
    val authProvider: AuthProvider = AuthProvider.EMAIL
)

sealed class AuthState {
    object Guest : AuthState()
    object Loading : AuthState()
    data class LoggedIn(val user: DriftUser) : AuthState()
    data class Error(val message: String) : AuthState()
}

sealed class UsernameCheckState {
    object Idle : UsernameCheckState()
    object Checking : UsernameCheckState()
    object Available : UsernameCheckState()
    data class Taken(val message: String) : UsernameCheckState()
    data class Invalid(val message: String) : UsernameCheckState()
}

// ─── Simulated User Database ─────────────────────────────────────────────────

private val TAKEN_USERNAMES = setOf(
    "admin", "drift", "kousoyu", "system", "root", "anonymous",
    "user", "test", "manga", "anime", "reader"
)

private val USERNAME_REGEX = Regex("^[a-z0-9_.]{3,20}$")

// ─── Auth Manager (Singleton) ────────────────────────────────────────────────

object AuthManager {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val currentUser: DriftUser?
        get() = (_authState.value as? AuthState.LoggedIn)?.user

    // ─── Email Login / Register ───────────────────────────────────────────────

    suspend fun loginWithEmail(email: String, password: String): Result<DriftUser> {
        _authState.value = AuthState.Loading
        delay(1200) // simulate network
        return runCatching {
            // Mock: any valid email/password combo succeeds
            if (email.isBlank() || !email.contains("@")) error("邮箱格式不正确")
            if (password.length < 6) error("密码至少需要 6 位")
            val user = DriftUser(
                uid = "uid_${email.hashCode().toString().takeLast(6)}",
                username = email.substringBefore("@").take(20)
                    .replace(Regex("[^a-z0-9_.]"), "_").lowercase(),
                nickname = email.substringBefore("@"),
                email = email,
                authProvider = AuthProvider.EMAIL
            )
            _authState.value = AuthState.LoggedIn(user)
            user
        }.onFailure { _authState.value = AuthState.Error(it.message ?: "登录失败") }
    }

    suspend fun registerWithEmail(email: String, password: String, nickname: String): Result<DriftUser> {
        _authState.value = AuthState.Loading
        delay(1400)
        return runCatching {
            if (email.isBlank() || !email.contains("@")) error("邮箱格式不正确")
            if (password.length < 6) error("密码至少需要 6 位")
            if (nickname.isBlank()) error("昵称不能为空")
            val user = DriftUser(
                uid = "uid_${System.currentTimeMillis().toString().takeLast(8)}",
                username = email.substringBefore("@").take(20)
                    .replace(Regex("[^a-z0-9_.]"), "_").lowercase(),
                nickname = nickname.trim(),
                email = email,
                authProvider = AuthProvider.EMAIL
            )
            _authState.value = AuthState.LoggedIn(user)
            user
        }.onFailure { _authState.value = AuthState.Error(it.message ?: "注册失败") }
    }

    // ─── Third-party OAuth (Simulated) ───────────────────────────────────────

    suspend fun loginWithQQ(): Result<DriftUser> {
        _authState.value = AuthState.Loading
        delay(1500)
        return runCatching {
            val user = DriftUser(
                uid = "qq_${System.currentTimeMillis().toString().takeLast(8)}",
                username = "qq_user_${(1000..9999).random()}",
                nickname = "QQ用户${(1000..9999).random()}",
                avatarUrl = "",
                authProvider = AuthProvider.QQ
            )
            _authState.value = AuthState.LoggedIn(user)
            user
        }.onFailure { _authState.value = AuthState.Error("QQ 登录暂时不可用") }
    }

    suspend fun loginWithWeChat(): Result<DriftUser> {
        _authState.value = AuthState.Loading
        delay(1500)
        return runCatching {
            val user = DriftUser(
                uid = "wx_${System.currentTimeMillis().toString().takeLast(8)}",
                username = "wx_user_${(1000..9999).random()}",
                nickname = "微信用户${(1000..9999).random()}",
                avatarUrl = "",
                authProvider = AuthProvider.WECHAT
            )
            _authState.value = AuthState.LoggedIn(user)
            user
        }.onFailure { _authState.value = AuthState.Error("微信 登录暂时不可用") }
    }

    // ─── Profile Updates ──────────────────────────────────────────────────────

    suspend fun updateProfile(
        nickname: String? = null,
        username: String? = null,
        avatarUrl: String? = null
    ): Result<DriftUser> {
        val current = currentUser ?: return Result.failure(Exception("请先登录"))
        delay(600)
        return runCatching {
            val updated = current.copy(
                nickname = nickname?.trim() ?: current.nickname,
                username = username?.trim() ?: current.username,
                avatarUrl = avatarUrl ?: current.avatarUrl
            )
            _authState.value = AuthState.LoggedIn(updated)
            updated
        }
    }

    // ─── Username Availability Check ──────────────────────────────────────────

    suspend fun checkUsernameAvailability(username: String): UsernameCheckState {
        if (username.isBlank()) return UsernameCheckState.Idle
        if (username.length < 3) return UsernameCheckState.Invalid("至少 3 个字符")
        if (username.length > 20) return UsernameCheckState.Invalid("最多 20 个字符")
        if (!USERNAME_REGEX.matches(username)) {
            return UsernameCheckState.Invalid("只能包含小写字母、数字、点和下划线")
        }
        delay(600) // simulate network check
        return if (TAKEN_USERNAMES.contains(username.lowercase())) {
            UsernameCheckState.Taken("@$username 已被使用")
        } else {
            UsernameCheckState.Available
        }
    }

    // ─── Logout ───────────────────────────────────────────────────────────────

    fun logout() {
        _authState.value = AuthState.Guest
    }
}
