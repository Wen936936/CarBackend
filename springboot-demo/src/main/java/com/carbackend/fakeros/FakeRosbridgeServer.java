package com.carbackend.fakeros;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 假的 rosbridge 服务器，仅用于本地测试
 * 监听 9090 端口，模拟 rosbridge 的 WebSocket 行为：
 * 1. 收到客户端消息时打印到控制台
 * 2. 每 2 秒主动推送一条假的图像消息
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
     * 启动定时任务：每 2 秒向所有已连接客户端推送一条假的图像消息
     */
    private void startFakeImagePush() {
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                String fakeImageMsg = "{\"op\":\"publish\",\"topic\":\"/camera/image_raw\",\"msg\":\"fake_image\"}";
                try {
                    broadcast(fakeImageMsg);
                } catch (Exception e) {
                    System.out.println("【推送假图像消息失败】" + e.getMessage());
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
