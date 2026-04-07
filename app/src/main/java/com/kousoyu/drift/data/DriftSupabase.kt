package com.kousoyu.drift.data

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.SupabaseClient

// ─── Supabase Client ──────────────────────────────────────────────────────────
//
// 使用方式：
// 1. 前往 https://supabase.com 免费注册
// 2. 创建一个新 Project (名字随意，比如 "drift-cloud")
// 3. 进入 Project Settings → API，复制你的 Project URL 和 anon/public key
// 4. 粘贴到下面的 SUPABASE_URL 和 SUPABASE_ANON_KEY 中
//
// ───────────────────────────────────────────────────────────────────────────────

object DriftSupabase {

    // ⚠️ 请替换为你自己的 Supabase 项目凭据
    private const val SUPABASE_URL = "https://lzixuzbsskepfzjrqmev.supabase.co"
    private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imx6aXh1emJzc2tlcGZ6anJxbWV2Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzU0ODUzNDksImV4cCI6MjA5MTA2MTM0OX0.XwAgJArv0RinMQXWYaHu5zIYdY3tillI2psIyYY6AnI"

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
            install(Storage)
        }
    }

    /** 快速判断是否配置完成（用户已填入真实凭据） */
    val isConfigured: Boolean
        get() = !SUPABASE_URL.contains("YOUR_PROJECT_ID")
}
