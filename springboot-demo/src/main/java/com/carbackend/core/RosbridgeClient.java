package com.carbackend.core;

import jakarta.annotation.PostConstruct;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Timer;
import java.util.TimerTask;

/**
 * rosbridge 的 WebSocket 客户端
 * 负责连接 rosbridge、发送控制指令、订阅摄像头话题，并支持断线自动重连
 */
@Component
public class RosbridgeClient {

    // rosbridge 地址，例如 ws://localhost:9090
    @Value("${rosbridge.url}")
    private String rosbridgeUrl;

    // 控制话题，例如 /cmd_vel
    @Value("${rosbridge.topic.cmd}")
    private String cmdTopic;

    // 摄像头话题，例如 /camera/image_raw
    @Value("${rosbridge.topic.camera}")
    private String cameraTopic;

    // 重连间隔时间（毫秒）
    private static final int RECONNECT_INTERVAL = 3000;

    // 真正干活的 WebSocket 客户端对象
    private WebSocketClient client;

    /**
     * 项目启动时自动连接 rosbridge
     */
    @PostConstruct
    public void init() {
        connect();
    }

    /**
     * 建立一次 WebSocket 连接
     */
    private void connect() {
        try {
            client = new WebSocketClient(new URI(rosbridgeUrl)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    System.out.println("【rosbridge连接成功】" + rosbridgeUrl);
                    // 连接成功后，订阅摄像头话题
                    subscribeCamera();
                }

                @Override
                public void onMessage(String message) {
                    // 收到摄像头等话题推送的消息，打印日志
                    System.out.println("【收到rosbridge消息】" + message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    System.out.println("【rosbridge连接断开，准备重连】原因：" + reason);
                    // 断线后自动重连
                    scheduleReconnect();
                }

                @Override
                public void onError(Exception ex) {
                    System.out.println("【rosbridge连接异常】" + ex.getMessage());
                }
            };
            client.connect();
        } catch (Exception e) {
            // 连接地址非法等异常，打印日志后尝试重连
            System.out.println("【rosbridge连接失败】" + e.getMessage());
            scheduleReconnect();
        }
    }

    /**
     * 延迟一段时间后重新连接
     */
    private void scheduleReconnect() {
        Timer timer = new Timer(true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                System.out.println("【rosbridge尝试重连】");
                connect();
            }
        }, RECONNECT_INTERVAL);
    }

    /**
     * 订阅摄像头话题
     */
    private void subscribeCamera() {
        String subscribeMsg = "{\"op\":\"subscribe\",\"topic\":\"" + cameraTopic + "\"}";
        send(subscribeMsg);
    }

    /**
     * 发送控制指令
     * 将 forward、stop、left、right 转成 JSON，发布到 cmd_vel 话题
     *
     * @param action 指令名称
     */
    public void sendCommand(String action) {
        String msg;
        switch (action) {
            case "forward":
                msg = buildCmdJson(0.5, 0);
                break;
            case "stop":
                msg = buildCmdJson(0, 0);
                break;
            case "left":
                msg = buildCmdJson(0, 0.5);
                break;
            case "right":
                msg = buildCmdJson(0, -0.5);
                break;
            default:
                // 未知指令，交给统一异常处理
                throw new CarCommandException("未知指令：" + action);
        }
        send(msg);
    }

    /**
     * 拼接 cmd_vel 话题的发布 JSON
     */
    private String buildCmdJson(double linearX, double angularZ) {
        return "{\"op\":\"publish\",\"topic\":\"" + cmdTopic + "\",\"msg\":{\"linear\":{\"x\":" + linearX
                + "},\"angular\":{\"z\":" + angularZ + "}}}";
    }

    /**
     * 统一发送方法，连接不可用时不抛异常，只打印日志
     */
    private void send(String message) {
        try {
            if (client != null && client.isOpen()) {
                client.send(message);
            } else {
                System.out.println("【发送失败】rosbridge未连接：" + message);
            }
        } catch (Exception e) {
            System.out.println("【发送异常】" + e.getMessage());
        }
    }
}
