package com.visioncart.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visioncart.app.data.ApiClient
import com.visioncart.app.data.EmailLoginRequest
import com.visioncart.app.data.SendCodeRequest
import com.visioncart.app.data.TokenManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.Context
import androidx.compose.ui.tooling.preview.Preview

data class AuthUiState(
    val email: String = "",
    val code: String = "",
    val isLoading: Boolean = false,
    val countdown: Int = 0, // seconds remaining
    val error: String? = null,
    val success: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    context: Context,
    onLoginSuccess: () -> Unit
) {
    var state by remember { mutableStateOf(AuthUiState()) }
    val scope = rememberCoroutineScope()

    // Countdown timer for resend
    LaunchedEffect(state.countdown) {
        if (state.countdown > 0) {
            delay(1000)
            state = state.copy(countdown = state.countdown - 1)
        }
    }

    // Auto-login check is done in MainActivity before showing this screen

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFF7F9F8)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo
            Text(
                "🛒",
                fontSize = 64.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                "VisionCart",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0A7C66)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "AI 拍照识物 · 智能比价购物助手",
                color = Color(0xFF53615E),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(40.dp))

            // Email input
            OutlinedTextField(
                value = state.email,
                onValueChange = { state = state.copy(email = it, error = null) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("邮箱") },
                placeholder = { Text("请输入邮箱地址") },
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(16.dp))

            // Code input with send button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.code,
                    onValueChange = { state = state.copy(code = it, error = null) },
                    modifier = Modifier.weight(1f),
                    label = { Text("验证码") },
                    placeholder = { Text("6位验证码") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Button(
                    onClick = {
                        if (state.email.isBlank()) {
                            state = state.copy(error = "请输入邮箱")
                            return@Button
                        }
                        state = state.copy(isLoading = true, error = null)
                        scope.launch {
                            try {
                                val response = ApiClient.api.sendCode(
                                    SendCodeRequest(state.email)
                                )
                                if (response.code == 200) {
                                    state = state.copy(
                                        isLoading = false,
                                        countdown = 60,
                                        error = null
                                    )
                                } else {
                                    state = state.copy(
                                        isLoading = false,
                                        error = response.message
                                    )
                                }
                            } catch (e: Exception) {
                                state = state.copy(
                                    isLoading = false,
                                    error = "发送失败: ${e.message}"
                                )
                            }
                        }
                    },
                    enabled = state.countdown == 0 && !state.isLoading,
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0A7C66)
                    )
                ) {
                    Text(
                        if (state.countdown > 0) "${state.countdown}s" else "发送",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Error message
            state.error?.let { error ->
                Text(
                    error,
                    color = Color(0xFFD32F2F),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(16.dp))

            // Login button
            Button(
                onClick = {
                    if (state.email.isBlank() || state.code.isBlank()) {
                        state = state.copy(error = "请输入邮箱和验证码")
                        return@Button
                    }
                    if (state.code.length != 6) {
                        state = state.copy(error = "验证码为6位数字")
                        return@Button
                    }
                    state = state.copy(isLoading = true, error = null)
                    scope.launch {
                        try {
                            val response = ApiClient.api.login(
                                EmailLoginRequest(state.email, state.code)
                            )
                            if (response.code == 200 && response.data != null) {
                                val loginData = response.data
                                // Save token persistently
                                ApiClient.authToken = loginData.token
                                ApiClient.refreshTokenValue = loginData.refresh_token
                                ApiClient.currentUserId = loginData.user_id
                                TokenManager.saveToken(
                                    context,
                                    loginData.token,
                                    loginData.refresh_token,
                                    loginData.user_id,
                                    loginData.email
                                )
                                state = state.copy(isLoading = false, success = true)
                                onLoginSuccess()
                            } else {
                                state = state.copy(
                                    isLoading = false,
                                    error = response.message
                                )
                            }
                        } catch (e: Exception) {
                            state = state.copy(
                                isLoading = false,
                                error = "登录失败: ${e.message}"
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !state.isLoading && state.email.isNotBlank() && state.code.length == 6,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0A7C66),
                    disabledContainerColor = Color(0xFFB0BEC5)
                )
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("登录 / 注册", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "登录即表示同意《用户协议》和《隐私政策》",
                color = Color(0xFF9E9E9E),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun LoginScreenPreview() {
    MaterialTheme {
        LoginScreen(
            context = androidx.compose.ui.platform.LocalContext.current,
            onLoginSuccess = {}
        )
    }
}
