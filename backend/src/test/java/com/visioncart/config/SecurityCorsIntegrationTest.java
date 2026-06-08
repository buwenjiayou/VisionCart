package com.visioncart.config;

import com.visioncart.api.controller.AuthController;
import com.visioncart.api.dto.LoginResponse;
import com.visioncart.service.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, WebConfig.class, JwtAuthenticationFilter.class,
        MonitoringTokenFilter.class, AdminOnlyFilter.class, RateLimitFilter.class,
        RateLimitService.class})
class SecurityCorsIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    JwtUtil jwtUtil;
    @MockBean
    AuthService authService;
    @MockBean
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Test
    void preflightUsesCorsConfigWithoutJwt() throws Exception {
        mockMvc.perform(options("/api/v1/search/products")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Methods",
                        org.hamcrest.Matchers.containsString("POST")));
    }

    @Test
    void refreshCanReachControllerWithoutAccessToken() throws Exception {
        when(authService.refreshToken("refresh-1"))
                .thenReturn(new LoginResponse("access-2", "refresh-2", 7L, "u@example.com"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"refresh-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.token", is("access-2")));
    }

}
