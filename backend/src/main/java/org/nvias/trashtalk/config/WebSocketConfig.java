package org.nvias.trashtalk.config;

import org.nvias.trashtalk.signal.HandshakeAuthInterceptor;
import org.nvias.trashtalk.signal.SignalingWebSocketHandler;
import org.nvias.trashtalk.voice.MediaBridgeHandler;
import org.nvias.trashtalk.voice.MediaHandshakeInterceptor;
import org.nvias.trashtalk.voice.VideoBridgeHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SignalingWebSocketHandler handler;
    private final HandshakeAuthInterceptor  authInterceptor;
    private final MediaBridgeHandler        mediaBridgeHandler;
    private final VideoBridgeHandler        videoBridgeHandler;
    private final MediaHandshakeInterceptor mediaInterceptor;

    public WebSocketConfig(SignalingWebSocketHandler handler,
                           HandshakeAuthInterceptor authInterceptor,
                           MediaBridgeHandler mediaBridgeHandler,
                           VideoBridgeHandler videoBridgeHandler,
                           MediaHandshakeInterceptor mediaInterceptor) {
        this.handler            = handler;
        this.authInterceptor    = authInterceptor;
        this.mediaBridgeHandler = mediaBridgeHandler;
        this.videoBridgeHandler = videoBridgeHandler;
        this.mediaInterceptor   = mediaInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/signal")
                .addInterceptors(authInterceptor)
                .setAllowedOrigins("*");

        registry.addHandler(mediaBridgeHandler, "/ws/media/*")
                .addInterceptors(mediaInterceptor)
                .setAllowedOrigins("*");

        // Video bridge reuses MediaHandshakeInterceptor — extracts last path segment as mediaSessionId
        registry.addHandler(videoBridgeHandler, "/ws/video/*")
                .addInterceptors(mediaInterceptor)
                .setAllowedOrigins("*");
    }
}
