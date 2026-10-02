package com.carbackend.fakeros;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 假的 rosbridge 服务器，仅用于本地测试
 * 监听 9090 端口，模拟 rosbridge 的 WebSocket 行为：
 * 1. 收到客户端消息时打印到控制台
 * 2. 每 2 秒读取本地测试图片，编码成 Base64 后推送给客户端
 */
public class FakeRosbridgeServer extends WebSocketServer {

    public FakeRosbridgeServer(int port) {
        super(new InetSocketAddress(port));
    }

    /**
     * 客户端连接成功时触发
     */
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("【客户端连接】" + conn.getRemoteSocketAddress());
    }

    /**
     * 客户端断开连接时触发
     */
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("【客户端断开】" + conn.getRemoteSocketAddress());
    }

    /**
     * 收到客户端发来的指令时触发，打印到控制台
     */
    @Override
    public void onMessage(WebSocket conn, String message) {
        System.out.println("【收到指令】" + message);
    }

    /**
     * 连接出现异常时触发，try-catch 避免影响其他客户端
     */
    @Override
    public void onError(WebSocket conn, Exception ex) {
        try {
            System.out.println("【假rosbridge异常】" + ex.getMessage());
        } catch (Exception e) {
            System.out.println("【假rosbridge异常处理失败】" + e.getMessage());
        }
    }

    /**
     * 服务器启动成功时触发
     */
    @Override
    public void onStart() {
        System.out.println("【假rosbridge启动】监听端口：" + getPort());
    }

    /**
     * 从 classpath 读取本地测试图片（src/main/resources/test.jpg），编码成 Base64 字符串
     * 读取失败时返回 null，避免影响服务器其他功能
     */
    private String loadImageBase64() {
        try (InputStream in = FakeRosbridgeServer.class.getResourceAsStream("/test.jpg")) {
            if (in == null) {
                System.out.println("【读取测试图片失败】未找到 test.jpg，请放到 src/main/resources/test.jpg");
                return null;
            }
            byte[] imageBytes = in.readAllBytes();
            return Base64.getEncoder().encodeToString(imageBytes);
        } catch (IOException e) {
            System.out.println("【读取测试图片异常】" + e.getMessage());
            return null;
        }
    }

    /**
     * 启动定时任务：每 2 秒向所有已连接客户端推送一条包含真实图片 Base64 数据的消息
     */
    private void startFakeImagePush() {
        String imageBase64 = loadImageBase64();
        if (imageBase64 == null) {
            System.out.println("【假图像推送未启动】图片加载失败");
            return;
        }
        String imageMsg = "{\"op\":\"publish\",\"topic\":\"/camera/image_raw\",\"msg\":\"" + imageBase64 + "\"}";
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    broadcast(imageMsg);
                } catch (Exception e) {
                    System.out.println("【推送图像消息失败】" + e.getMessage());
                }
            }
        }, 2000, 2000);
    }

    /**
     * 本地测试入口：独立启动假 rosbridge 服务器
     */
    public static void main(String[] args) {
        FakeRosbridgeServer server = new FakeRosbridgeServer(9090);
        server.start();
        server.startFakeImagePush();
    }
}
