package com.carbackend.controller;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 摄像头 WebSocket 接口
 * 暴露 /ws/camera 端点，App 端连接后，RosbridgeClient 收到摄像头图像时会通过本类转发给所有已连接的 App
 */
@Configuration
@EnableWebSocket
public class CameraWebSocketController extends TextWebSocketHandler implements WebSocketConfigurer {

    // 已连接的 App 端会话集合
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    /**
     * 注册 WebSocket 路由：/ws/camera
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(this, "/ws/camera").setAllowedOrigins("*");
    }

    /**
     * App 端连接成功时，记录会话
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        System.out.println("【摄像头WebSocket连接】" + session.getId());
    }

    /**
     * App 端断开时，移除会话
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        System.out.println("【摄像头WebSocket断开】" + session.getId());
    }

    /**
     * 将图像的 Base64 字符串广播给所有已连接的 App 端
     *
     * @param base64Image 图像的 Base64 字符串
     */
    public void broadcastImage(String base64Image) {
        String payload = "{\"topic\":\"/camera/image_raw\",\"image\":\"" + base64Image + "\"}";
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (Exception e) {
                System.out.println("【转发图像给App失败】" + e.getMessage());
            }
        }
    }
}
