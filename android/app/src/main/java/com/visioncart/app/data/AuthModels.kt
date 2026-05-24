package com.visioncart.app.data

// ==================== Auth DTOs ====================

data class SendCodeRequest(
    val email: String
)

data class EmailLoginRequest(
    val email: String,
    val code: String
)

data class LoginResponse(
    val token: String,
    val user_id: Long,
    val email: String,
    val nickname: String
)

data class UserProfile(
    val id: Long,
    val email: String,
    val nickname: String?,
    val avatar_url: String?,
    val created_at: String?
)
