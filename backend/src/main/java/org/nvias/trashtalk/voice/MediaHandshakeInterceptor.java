package org.nvias.trashtalk.voice;

import org.nvias.trashtalk.auth.JwtService;
import org.nvias.trashtalk.signal.HandshakeAuthInterceptor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Interceptor pro /ws/media/{sessionId}.
 * Ověří JWT stejně jako HandshakeAuthInterceptor a navíc uloží mediaSessionId z URL path.
 */
@Component
public class MediaHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtService jwtService;

    public MediaHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // extrahuj mediaSessionId z path /ws/media/{sessionId}
        String path = request.getURI().getPath();
        String[] parts = path.split("/");
        if (parts.length < 4) return false;
        String mediaSessionId = parts[parts.length - 1];
        attributes.put("mediaSessionId", mediaSessionId);

        // JWT auth
        String auth = request.getHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            if (jwtService.isValid(token)) {
                attributes.put(HandshakeAuthInterceptor.ATTR_USER_ID, jwtService.extractUserId(token));
                return true;
            }
        }
        String query = request.getURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    String token = param.substring(6);
                    if (jwtService.isValid(token)) {
                        attributes.put(HandshakeAuthInterceptor.ATTR_USER_ID, jwtService.extractUserId(token));
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res,
                               WebSocketHandler h, Exception ex) {}
}
