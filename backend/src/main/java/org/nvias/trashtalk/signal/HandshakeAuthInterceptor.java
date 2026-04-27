package org.nvias.trashtalk.signal;

import org.nvias.trashtalk.auth.JwtService;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class HandshakeAuthInterceptor implements HandshakeInterceptor {

    static final String ATTR_USER_ID = "userId";

    private final JwtService jwtService;

    public HandshakeAuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String auth = request.getHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            if (jwtService.isValid(token)) {
                attributes.put(ATTR_USER_ID, jwtService.extractUserId(token));
                return true;
            }
        }
        // Fallback: token v query parametru ?token=... (pro klienty kde nelze nastavit header)
        String query = request.getURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    String token = param.substring(6);
                    if (jwtService.isValid(token)) {
                        attributes.put(ATTR_USER_ID, jwtService.extractUserId(token));
                        return true;
                    }
                }
            }
        }
        return false; // neautorizovaný handshake odmítnut
    }

    @Override
    public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res,
                               WebSocketHandler h, Exception ex) {}
}
