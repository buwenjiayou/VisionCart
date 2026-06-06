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
import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final JwtUtil jwtUtil;
    private final VisionCartProperties properties;
    private final com.visioncart.service.recognition.AsyncRecognitionTaskManager taskManager;
    private final com.visioncart.repository.RecognitionHistoryRepository historyRepository;

    public WebSocketConfig(JwtUtil jwtUtil, VisionCartProperties properties,
                           com.visioncart.service.recognition.AsyncRecognitionTaskManager taskManager,
                           com.visioncart.repository.RecognitionHistoryRepository historyRepository) {
        this.jwtUtil = jwtUtil;
        this.properties = properties;
        this.taskManager = taskManager;
        this.historyRepository = historyRepository;
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
                .addInterceptors(new org.springframework.web.socket.server.HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request,
                                                   org.springframework.http.server.ServerHttpResponse response,
                                                   org.springframework.web.socket.WebSocketHandler handler,
                                                   Map<String, Object> attributes) {
                        // Extract token from query parameter for SockJS fallback transports (Bug #28)
                        String query = request.getURI().getQuery();
                        if (properties.getSecurity().isWebsocketQueryTokenEnabled() && query != null) {
                            for (String param : query.split("&")) {
                                if (param.startsWith("token=")) {
                                    attributes.put("ws_token", java.net.URLDecoder.decode(param.substring(6), java.nio.charset.StandardCharsets.UTF_8));
                                    break;
                                }
                            }
                        }
                        return true;
                    }
                    @Override
                    public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                                               org.springframework.http.server.ServerHttpResponse response,
                                               org.springframework.web.socket.WebSocketHandler handler,
                                               Exception exception) {}
                })
                .withSockJS();
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(2 * 1024 * 1024);      // 2MB (base64 crop images)
        registry.setSendBufferSizeLimit(4 * 1024 * 1024);   // 4MB
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
                // /topic/recognition/{sessionId} — verify ownership (Bug #29)
                if (destination.startsWith("/topic/recognition/")) {
                    String sessionId = destination.substring("/topic/recognition/".length());
                    return taskManager.belongsToUser(sessionId, userId);
                }
                // /topic/search/{sessionId} — verify ownership via task or history
                if (destination.startsWith("/topic/search/")) {
                    String sessionId = destination.substring("/topic/search/".length());
                    return taskManager.belongsToUser(sessionId, userId)
                            || historyRepository.findBySessionIdAndUserId(sessionId, userId).isPresent();
                }
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
            // Try token native header
            List<String> tokenHeaders = accessor.getNativeHeader("token");
            if (tokenHeaders != null && !tokenHeaders.isEmpty()) {
                return tokenHeaders.get(0);
            }
            // Try session attributes (set by handshake interceptor for SockJS query param, Bug #28)
            Map<String, Object> sessionAttrs = accessor.getSessionAttributes();
            if (sessionAttrs != null) {
                Object wsToken = sessionAttrs.get("ws_token");
                if (wsToken instanceof String token && !token.isBlank()) {
                    return token;
                }
            }
            return null;
        }
    }
}
