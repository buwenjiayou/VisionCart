package com.visioncart.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.security.Principal;
import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final JwtUtil jwtUtil;
    private final VisionCartProperties properties;

    public WebSocketConfig(JwtUtil jwtUtil, VisionCartProperties properties) {
        this.jwtUtil = jwtUtil;
        this.properties = properties;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/recognition")
                .setAllowedOrigins(properties.getSecurity().allowedOriginList().toArray(String[]::new))
                .withSockJS();
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(64 * 1024);      // 64KB
        registry.setSendBufferSizeLimit(128 * 1024);   // 128KB
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new JwtStompChannelInterceptor());
    }

    /**
     * STOMP interceptor that validates JWT from CONNECT frames.
     * Rejects unauthenticated CONNECT and SUBSCRIBE attempts.
     */
    private class JwtStompChannelInterceptor implements ChannelInterceptor {

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

            if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                String token = extractToken(accessor);
                if (token != null && jwtUtil.validateToken(token)) {
                    Long userId = jwtUtil.getUserId(token);
                    String email = jwtUtil.getEmail(token);
                    accessor.setUser(new StompPrincipal(userId, email));
                    log.info("STOMP CONNECT authenticated: userId={}", userId);
                } else {
                    log.warn("STOMP CONNECT rejected: invalid or missing token");
                    return null;
                }
            }

            if (accessor != null && StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                Principal user = accessor.getUser();
                if (user == null) {
                    String token = extractToken(accessor);
                    if (token != null && jwtUtil.validateToken(token)) {
                        Long userId = jwtUtil.getUserId(token);
                        accessor.setUser(new StompPrincipal(userId, jwtUtil.getEmail(token)));
                    } else {
                        log.warn("STOMP SUBSCRIBE rejected: unauthenticated");
                        return null;
                    }
                }
                // Validate topic ownership
                String destination = accessor.getDestination();
                if (destination != null && !isTopicAllowed(accessor.getUser(), destination)) {
                    log.warn("STOMP SUBSCRIBE rejected: user={} not authorized for topic={}",
                            accessor.getUser().getName(), destination);
                    return null;
                }
            }

            return message;
        }

        private boolean isTopicAllowed(Principal user, String destination) {
            if (user instanceof StompPrincipal principal) {
                Long userId = principal.userId();
                // /topic/price-alert/{userId} — must match own userId
                if (destination.startsWith("/topic/price-alert/")) {
                    String topicUserId = destination.substring("/topic/price-alert/".length());
                    return String.valueOf(userId).equals(topicUserId);
                }
                // /topic/recognition/{sessionId} — allowed for any authenticated user
                // (sessionId is a UUID, ownership is checked at the REST API level)
                return destination.startsWith("/topic/recognition/");
            }
            return false;
        }

        private String extractToken(StompHeaderAccessor accessor) {
            // Try Authorization header
            List<String> authHeaders = accessor.getNativeHeader("Authorization");
            if (authHeaders != null && !authHeaders.isEmpty()) {
                String header = authHeaders.get(0);
                if (header.startsWith("Bearer ")) {
                    return header.substring(7);
                }
            }
            // Try token query parameter (for SockJS)
            List<String> tokenHeaders = accessor.getNativeHeader("token");
            if (tokenHeaders != null && !tokenHeaders.isEmpty()) {
                return tokenHeaders.get(0);
            }
            return null;
        }
    }
}
