package com.kousoyu.drift.data

import android.content.Context
import android.net.Uri
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Profile Data Model (maps to Supabase "profiles" table) ──────────────────

@Serializable
data class ProfileRow(
    val id: String,
    val username: String? = null,
    val nickname: String? = null,
    val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

// ─── Profile Repository (Supabase ← → App) ──────────────────────────────────
//
// 职责：
// 1. 注册后创建 profile 行
// 2. 登录后拉取 profile 数据
// 3. 编辑页保存 profile 修改
// 4. 用户名查重（真实的全局唯一性检查）
// 5. 用户名 → 邮箱 反查（支持用户名登录）
//
// ─────────────────────────────────────────────────────────────────────────────

object ProfileRepository {

    private const val TABLE = "profiles"

    // ─── 创建 profile（注册后调用）────────────────────────────────────────────

    suspend fun createProfile(
        userId: String,
        username: String,
        nickname: String,
        email: String = "",
        avatarUrl: String = ""
    ): Result<Unit> = runCatching {
        if (!DriftSupabase.isConfigured) return@runCatching

        DriftSupabase.client.postgrest[TABLE].insert(
            ProfileRow(
                id = userId,
                username = username,
                nickname = nickname,
                email = email,
                avatarUrl = avatarUrl
            )
        )
    }

    // ─── 拉取 profile（登录后调用）────────────────────────────────────────────

    suspend fun fetchProfile(userId: String): ProfileRow? {
        if (!DriftSupabase.isConfigured) return null

        return runCatching {
            DriftSupabase.client.postgrest[TABLE]
                .select { filter { eq("id", userId) } }
                .decodeSingleOrNull<ProfileRow>()
        }.getOrNull()
    }

    // ─── 更新 profile（编辑页保存时调用）──────────────────────────────────────

    suspend fun updateProfile(
        userId: String,
        username: String? = null,
        nickname: String? = null,
        avatarUrl: String? = null
    ): Result<Unit> = runCatching {
        if (!DriftSupabase.isConfigured) return@runCatching

        val updates = mutableMapOf<String, String>()
        username?.let { updates["username"] = it }
        nickname?.let { updates["nickname"] = it }
        avatarUrl?.let { updates["avatar_url"] = it }
        if (updates.isEmpty()) return@runCatching

        DriftSupabase.client.postgrest[TABLE].update(updates) {
            filter { eq("id", userId) }
        }
    }

    // ─── 用户名查重（编辑页实时检查）─────────────────────────────────────────

    suspend fun isUsernameTaken(username: String, excludeUserId: String? = null): Boolean {
        if (!DriftSupabase.isConfigured) return false

        return runCatching {
            val rows = DriftSupabase.client.postgrest[TABLE]
                .select {
                    filter { eq("username", username) }
                }
                .decodeList<ProfileRow>()

            // 如果查到的用户是自己，则不算"已占用"
            rows.any { it.id != excludeUserId }
        }.getOrDefault(false)
    }

    // ─── 用户名 → 邮箱 反查（用户名登录用）──────────────────────────────────

    suspend fun findEmailByUsername(username: String): String? {
        if (!DriftSupabase.isConfigured) return null

        return runCatching {
            DriftSupabase.client.postgrest[TABLE]
                .select {
                    filter { eq("username", username.lowercase().trim()) }
                }
                .decodeSingleOrNull<ProfileRow>()
                ?.email
        }.getOrNull()
    }

    // ─── 头像上传到 Supabase Storage ──────────────────────────────────

    private const val AVATAR_BUCKET = "avatars"

    /**
     * 上传本地图片到 Supabase Storage，返回公网 URL
     *
     * @param context   Android Context（用于读取 content:// URI）
     * @param userId    用户 ID（作为文件路径前缀）
     * @param localUri  本地图片 URI（content:// 或 file://）
     */
    suspend fun uploadAvatar(
        context: Context,
        userId: String,
        localUri: String
    ): Result<String> = runCatching {
        if (!DriftSupabase.isConfigured) error("未配置 Supabase")

        val uri = Uri.parse(localUri)
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: error("无法读取图片")

        // ── 端侧压缩：缩放到 256x256，JPEG 80% 质量（~30-50KB）──
        val original = android.graphics.BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        val size = 256
        val scaled = android.graphics.Bitmap.createScaledBitmap(original, size, size, true)
        if (scaled !== original) original.recycle()
        val output = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, output)
        scaled.recycle()
        val bytes = output.toByteArray()

        // ── 保存到本地文件缓存（下次冷启动同步加载，不闪烁）──
        getLocalAvatarFile(context).writeBytes(bytes)

        // 文件路径：avatars/{userId}/avatar.jpg（覆盖式更新）
        val path = "$userId/avatar.jpg"
        val bucket = DriftSupabase.client.storage.from(AVATAR_BUCKET)

        bucket.upload(path, bytes) {
            upsert = true
        }

        // 返回公开访问 URL（加时间戳破缓存）
        "${bucket.publicUrl(path)}?t=${System.currentTimeMillis()}"
    }

    /** 本地头像缓存文件路径 */
    fun getLocalAvatarFile(context: Context): java.io.File =
        java.io.File(context.filesDir, "avatar_cache.jpg")
}
