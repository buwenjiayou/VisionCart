package com.visioncart.api.controller;

import com.visioncart.api.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;

@RestController
public class HealthController {

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;

    @Autowired
    public HealthController(DataSource dataSource, StringRedisTemplate redisTemplate) {
        this.dataSource = dataSource;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/api/v1/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "status", "up",
                "time", Instant.now().toString()
        ));
    }

    @GetMapping("/api/v1/health/deep")
    public ApiResponse<Map<String, Object>> deepHealth() {
        Map<String, Object> details = new LinkedHashMap<>();
        String overallStatus = "up";

        // Check MySQL
        try (Connection conn = dataSource.getConnection()) {
            conn.isValid(3);
            details.put("mysql", "up");
        } catch (Exception e) {
            details.put("mysql", "down");
            overallStatus = "degraded";
        }

        // Check Redis
        try {
            redisTemplate.opsForValue().get("visioncart:health:ping");
            details.put("redis", "up");
        } catch (Exception e) {
            details.put("redis", "down");
            overallStatus = "degraded";
        }

        details.put("status", overallStatus);
        details.put("time", Instant.now().toString());

        return ApiResponse.ok(details);
    }
}
