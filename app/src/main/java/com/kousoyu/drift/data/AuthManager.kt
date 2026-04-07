package com.kousoyu.drift.data

import android.content.Context
import android.content.SharedPreferences
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─── Domain Models ────────────────────────────────────────────────────────────

enum class AuthProvider { QQ, WECHAT, EMAIL }

data class DriftUser(
    val uid: String,
    val username: String,          // unique @handle
    val nickname: String,          // display name
    val avatarUrl: String = "",
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

private val USERNAME_REGEX = Regex("^[a-z0-9_.]{3,20}$")

// ─── Auth Manager (Singleton) ────────────────────────────────────────────────
//
// 设计理念: 优雅降级 (Graceful Degradation)
// - 如果 Supabase 已配置 → 走真实云端鉴权
// - 如果 Supabase 未配置 → 走本地离线模式（不影响开发体验）
//
// ──────────────────────────────────────────────────────────────────────────────

object AuthManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─── 邀请码系统 ──────────────────────────────────────────────────────────────
    private val VALID_INVITE_CODES = setOf(
        "DRIFT2026",     // 公测邀请码
        "KOUSOYU",       // 开发者专属
        "XUYVELIO",      // 创始人专属
    )

    fun isValidInviteCode(code: String): Boolean =
        VALID_INVITE_CODES.contains(code.trim().uppercase())

    private val _authState = MutableStateFlow<AuthState>(AuthState.Guest)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val currentUser: DriftUser?
        get() = (_authState.value as? AuthState.LoggedIn)?.user

    // ─── 本地缓存（免等待网络即时显示用户信息）─────────────────────────────────
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("drift_auth", Context.MODE_PRIVATE)
        restoreFromCache()
        // 确保头像文件存在（首次升级或跨设备登录时自动下载）
        ensureAvatarCached()
    }

    private fun ensureAvatarCached() {
        val ctx = appContext ?: return
        val url = prefs?.getString("avatarUrl", null)
        if (url.isNullOrEmpty()) return
        val localFile = ProfileRepository.getLocalAvatarFile(ctx)
        if (localFile.exists()) {
            _avatarReady.value = true
            return
        }
        // 后台下载头像文件到本地（~50KB，非常快）
        scope.launch {
            try {
                val bytes = java.net.URL(url).readBytes()
                localFile.writeBytes(bytes)
                _avatarReady.value = true
            } catch (_: Exception) { _avatarReady.value = true }
        }
    }

    // AvatarDisplay 观察此信号来重新检查本地文件
    private val _avatarReady = MutableStateFlow(false)
    val avatarReady: StateFlow<Boolean> = _avatarReady.asStateFlow()

    private fun restoreFromCache() {
        val p = prefs ?: return
        val uid = p.getString("uid", null) ?: return
        _authState.value = AuthState.LoggedIn(
            DriftUser(
                uid = uid,
                username = p.getString("username", "") ?: "",
                nickname = p.getString("nickname", "") ?: "",
                avatarUrl = p.getString("avatarUrl", "") ?: "",
                email = p.getString("email", "") ?: "",
                authProvider = AuthProvider.EMAIL
            )
        )
    }

    private fun cacheUser(user: DriftUser) {
        prefs?.edit()
            ?.putString("uid", user.uid)
            ?.putString("username", user.username)
            ?.putString("nickname", user.nickname)
            ?.putString("avatarUrl", user.avatarUrl)
            ?.putString("email", user.email)
            ?.apply()
    }

    private fun clearCache() {
        prefs?.edit()?.clear()?.apply()
    }

    init {
        if (DriftSupabase.isConfigured) {
            scope.launch {
                DriftSupabase.client.auth.sessionStatus.collect { status ->
                    when (status) {
                        is SessionStatus.Authenticated -> {
                            val session = status.session
                            val uid = session.user?.id ?: ""
                            val email = session.user?.email ?: ""

                            // 快速路径：同一用户的 token 刷新，跳过重复网络请求
                            val existing = currentUser
                            if (existing != null && existing.uid == uid) return@collect

                            // 后台拉取最新 profile
                            var profile = ProfileRepository.fetchProfile(uid)
                            if (profile == null && uid.isNotBlank()) {
                                val fallbackUsername = email.substringBefore("@")
                                    .replace(Regex("[^a-z0-9_.]"), "_").lowercase()
                                val fallbackNickname = session.user?.userMetadata
                                    ?.get("nickname")?.toString()
                                    ?.removeSurrounding("\"")
                                    ?: email.substringBefore("@")
                                ProfileRepository.createProfile(
                                    userId = uid,
                                    username = fallbackUsername,
                                    nickname = fallbackNickname,
                                    email = email
                                )
                                profile = ProfileRepository.fetchProfile(uid)
                            }
                            val user = DriftUser(
                                uid = uid,
                                username = profile?.username
                                    ?: email.substringBefore("@")
                                        .replace(Regex("[^a-z0-9_.]"), "_").lowercase(),
                                nickname = profile?.nickname
                                    ?: session.user?.userMetadata?.get("nickname")
                                        ?.toString()?.removeSurrounding("\"")
                                    ?: email.substringBefore("@"),
                                avatarUrl = profile?.avatarUrl ?: "",
                                email = email,
                                authProvider = AuthProvider.EMAIL
                            )
                            cacheUser(user)
                            _authState.value = AuthState.LoggedIn(user)
                        }
                        is SessionStatus.NotAuthenticated -> {
                            // 有缓存用户时不降级（SDK 初始化期间会瞬间触发此事件）
                            // 真正退出走 logout()，会显式清缓存 + 设 Guest
                            if (currentUser == null) {
                                _authState.value = AuthState.Guest
                            }
                        }
                        is SessionStatus.Initializing -> {
                            // 不设 Loading — 缓存已立即恢复了用户态
                        }
                        else -> { /* NetworkError 等 — 保持现有状态 */ }
                    }
                }
            }
        }
    }

    // ─── Email / Username Login ──────────────────────────────────────────────────

    suspend fun loginWithEmail(emailOrUsername: String, password: String): Result<DriftUser> {
        _authState.value = AuthState.Loading
        return runCatching {
            if (emailOrUsername.isBlank()) error("请输入邮箱或用户名")
            if (password.length < 6) error("密码至少需要 6 位")

            // 智能判断：有 @ → 邮箱登录，没有 → 用户名登录
            val email = if (emailOrUsername.contains("@")) {
                emailOrUsername
            } else {
                // 从 profiles 表查找用户名对应的邮箱
                ProfileRepository.findEmailByUsername(emailOrUsername)
                    ?: error("找不到用户名 @${emailOrUsername}")
            }

            if (DriftSupabase.isConfigured) {
                // ────── 真实云端登录 ──────
                DriftSupabase.client.auth.signInWith(Email) {
                    this.email = email
                    this.password = password
                }
                val session = DriftSupabase.client.auth.currentSessionOrNull()
                val uid = session?.user?.id ?: ""
                // 从 profiles 表拉取真实的用户资料
                val profile = ProfileRepository.fetchProfile(uid)
                val user = DriftUser(
                    uid = uid,
                    username = profile?.username
                        ?: email.substringBefore("@")
                            .replace(Regex("[^a-z0-9_.]"), "_").lowercase(),
                    nickname = profile?.nickname
                        ?: email.substringBefore("@"),
                    avatarUrl = profile?.avatarUrl ?: "",
                    email = email,
                    authProvider = AuthProvider.EMAIL
                )
                cacheUser(user)
                _authState.value = AuthState.LoggedIn(user)
                user
            } else {
                // ────── 离线降级模式 ──────
                val user = DriftUser(
                    uid = "local_${email.hashCode().toString().takeLast(6)}",
                    username = email.substringBefore("@")
                        .replace(Regex("[^a-z0-9_.]"), "_").lowercase(),
                    nickname = email.substringBefore("@"),
                    email = email,
                    authProvider = AuthProvider.EMAIL
                )
                cacheUser(user)
                _authState.value = AuthState.LoggedIn(user)
                user
            }
        }.onFailure {
            _authState.value = AuthState.Guest   // 恢复而非卡死在 Error
        }
    }

    // ─── Email Register ──────────────────────────────────────────────────────────

    suspend fun registerWithEmail(
        email: String, password: String, nickname: String, username: String = ""
    ): Result<DriftUser> {
        _authState.value = AuthState.Loading
        return runCatching {
            if (email.isBlank() || !email.contains("@")) error("邮箱格式不正确")
            if (password.length < 6) error("密码至少需要 6 位")
            if (nickname.isBlank()) error("昵称不能为空")

            // 用户选择的用户名，或从邮箱自动生成
            val finalUsername = username.ifBlank {
                email.substringBefore("@")
                    .replace(Regex("[^a-z0-9_.]"), "_").lowercase()
            }

            if (DriftSupabase.isConfigured) {
                // ────── 真实云端注册 ──────
                DriftSupabase.client.auth.signUpWith(Email) {
                    this.email = email
                    this.password = password
                    this.data = kotlinx.serialization.json.buildJsonObject {
                        put("nickname", kotlinx.serialization.json.JsonPrimitive(nickname.trim()))
                    }
                }
                val session = DriftSupabase.client.auth.currentSessionOrNull()
                val uid = session?.user?.id ?: "pending"
                // 在 profiles 表中创建用户资料行（含 email）
                if (uid != "pending") {
                    ProfileRepository.createProfile(
                        userId = uid,
                        username = finalUsername,
                        nickname = nickname.trim(),
                        email = email
                    )
                }
                val user = DriftUser(
                    uid = uid,
                    username = finalUsername,
                    nickname = nickname.trim(),
                    email = email,
                    authProvider = AuthProvider.EMAIL
                )
                if (session?.user != null) {
                    _authState.value = AuthState.LoggedIn(user)
                } else {
                    _authState.value = AuthState.Guest
                }
                user
            } else {
                // ────── 离线降级模式 ──────
                val user = DriftUser(
                    uid = "local_${System.currentTimeMillis().toString().takeLast(8)}",
                    username = finalUsername,
                    nickname = nickname.trim(),
                    email = email,
                    authProvider = AuthProvider.EMAIL
                )
                _authState.value = AuthState.LoggedIn(user)
                user
            }
        }.onFailure {
            _authState.value = AuthState.Guest
        }
    }

    // ─── Third-party OAuth (预留接口) ────────────────────────────────────────────

    suspend fun loginWithQQ(): Result<DriftUser> {
        return Result.failure(Exception("QQ 登录即将开放，敬请期待"))
    }

    suspend fun loginWithWeChat(): Result<DriftUser> {
        return Result.failure(Exception("微信登录即将开放，敬请期待"))
    }

    // ─── Profile Updates ─────────────────────────────────────────────────────────

    suspend fun updateProfile(
        nickname: String? = null,
        username: String? = null,
        avatarUrl: String? = null,
        context: android.content.Context? = null
    ): Result<DriftUser> {
        val current = currentUser ?: return Result.failure(Exception("请先登录"))
        return runCatching {
            // 如果头像是本地 URI，先上传到 Storage 拿公网 URL
            var finalAvatarUrl = avatarUrl ?: current.avatarUrl
            if (context != null && avatarUrl != null &&
                (avatarUrl.startsWith("content://") || avatarUrl.startsWith("file://"))
            ) {
                finalAvatarUrl = ProfileRepository.uploadAvatar(
                    context = context,
                    userId = current.uid,
                    localUri = avatarUrl
                ).getOrThrow()
                _avatarReady.value = true  // 本地文件已写入
            }

            val updated = current.copy(
                nickname = nickname?.trim() ?: current.nickname,
                username = username?.trim() ?: current.username,
                avatarUrl = finalAvatarUrl
            )

            // 同步到 Supabase profiles 表
            ProfileRepository.updateProfile(
                userId = current.uid,
                username = updated.username,
                nickname = updated.nickname,
                avatarUrl = updated.avatarUrl
            )

            cacheUser(updated)
            _authState.value = AuthState.LoggedIn(updated)
            updated
        }
    }

    // ─── Username Availability Check ─────────────────────────────────────────────

    suspend fun checkUsernameAvailability(username: String): UsernameCheckState {
        if (username.isBlank()) return UsernameCheckState.Idle
        if (username.length < 3) return UsernameCheckState.Invalid("至少 3 个字符")
        if (username.length > 20) return UsernameCheckState.Invalid("最多 20 个字符")
        if (!USERNAME_REGEX.matches(username)) {
            return UsernameCheckState.Invalid("只能包含小写字母、数字、点和下划线")
        }
        // 真实查询 profiles 表检查唯一性
        val taken = ProfileRepository.isUsernameTaken(username, excludeUserId = currentUser?.uid)
        return if (taken) UsernameCheckState.Taken("该用户名已被占用") else UsernameCheckState.Available
    }

    // ─── Logout ──────────────────────────────────────────────────────────────────

    fun logout() {
        if (DriftSupabase.isConfigured) {
            scope.launch {
                try { DriftSupabase.client.auth.signOut() } catch (_: Exception) { }
            }
        }
        clearCache()
        _avatarReady.value = false
        appContext?.let { ProfileRepository.getLocalAvatarFile(it).delete() }
        _authState.value = AuthState.Guest
    }
}
