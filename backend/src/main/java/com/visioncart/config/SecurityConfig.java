package com.visioncart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final AdminOnlyFilter adminOnlyFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter,
                          AdminOnlyFilter adminOnlyFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.adminOnlyFilter = adminOnlyFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/api/v1/health").permitAll()

                // Metrics: require auth (ADMIN in production)
                .requestMatchers("/api/v1/metrics/log").authenticated()
                .requestMatchers("/api/v1/metrics/**").authenticated()

                // Actuator: health is public for load balancers/monitoring
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                // Actuator metrics/prometheus: require auth (monitoring agents should use tokens)
                .requestMatchers("/actuator/metrics/**").authenticated()
                .requestMatchers("/actuator/prometheus").authenticated()
                .requestMatchers("/actuator/**").authenticated()

                // Swagger: require auth (disable in prod via spring.profiles.active=prod)
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html").authenticated()
                .requestMatchers("/v3/api-docs/**").authenticated()

                // WebSocket: allow connection, auth handled at message level
                .requestMatchers("/ws/**").permitAll()

                // All other API endpoints require authentication
                .requestMatchers("/api/v1/**").authenticated()

                // Default: deny unknown endpoints
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(adminOnlyFilter, JwtAuthenticationFilter.class)
            .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
