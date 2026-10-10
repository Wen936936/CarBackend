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
 * AI 对话 WebSocket 接口
 * 暴露 /ws/chat 端点，App 端连接后，RosbridgeClient 收到的 AI 回复会通过本类推送给所有已连接的 App
 */
@Configuration
@EnableWebSocket
public class ChatWebSocketController extends TextWebSocketHandler implements WebSocketConfigurer {

    // 已连接的 App 端会话集合
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    /**
     * 注册 WebSocket 路由：/ws/chat
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(this, "/ws/chat").setAllowedOrigins("*");
    }

    /**
     * App 端连接成功时，记录会话
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        System.out.println("【AI对话WebSocket连接】" + session.getId());
    }

    /**
     * App 端断开时，移除会话
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        System.out.println("【AI对话WebSocket断开】" + session.getId());
    }

    /**
     * 将 AI 回复的 JSON 广播给所有已连接的 App 端
     * 直接转发 rosbridge 的原始消息，避免模拟阶段臆造回复字段
     *
     * @param json rosbridge 推送的 AI 回复 JSON
     */
    public void broadcastMessage(String json) {
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (Exception e) {
                System.out.println("【转发AI回复给App失败】" + e.getMessage());
            }
        }
    }
}
