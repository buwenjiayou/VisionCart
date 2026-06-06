package com.visioncart.app.data

data class SendCodeRequest(
    val email: String
)

data class EmailLoginRequest(
    val email: String,
    val code: String
)

data class LoginResponse(
    val token: String,
    val refresh_token: String?,
    val user_id: Long,
    val email: String
)

data class UserProfile(
    val id: Long,
    val email: String,
    val created_at: String?
)
