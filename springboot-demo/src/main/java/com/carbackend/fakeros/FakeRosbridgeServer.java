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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * 如果消息是发往 /ai/input 的，则延迟 1 秒模拟 AI 回复，推送到 /ai/reply
     */
    @Override
    public void onMessage(WebSocket conn, String message) {
        System.out.println("【收到指令】" + message);
        if (message.contains("/ai/input")) {
            replyAiMessage(conn, message);
        }
    }

    /**
     * 模拟 AI 回复：延迟 1 秒后向客户端推送 /ai/reply 消息
     * 消息格式：{"op":"publish","topic":"/ai/reply","msg":{"text":"收到：<用户文字>","action":""}}
     */
    private void replyAiMessage(WebSocket conn, String message) {
        try {
            // 从客户端消息中取出用户发来的文字
            String userText = extractUserText(message);
            String reply = "{\"op\":\"publish\",\"topic\":\"/ai/reply\",\"msg\":{\"text\":\"收到："
                    + escapeJson(userText) + "\",\"action\":\"\"}}";
            // 延迟 1 秒后用 Timer 单独执行，避免阻塞 WebSocket 的接收线程
            new Timer(true).schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        conn.send(reply);
                        System.out.println("【AI回复】" + reply);
                    } catch (Exception e) {
                        System.out.println("【AI回复发送失败】" + e.getMessage());
                    }
                }
            }, 1000);
        } catch (Exception e) {
            System.out.println("【AI回复处理失败】" + e.getMessage());
        }
    }

    /**
     * 从客户端发来的 /ai/input 消息里取出 text 字段的内容
     * 取不到时返回原消息，保证回复内容不为空
     */
    private String extractUserText(String message) {
        Matcher matcher = Pattern.compile("\"text\"\\s*:\\s*\"(.*?)\"").matcher(message);
        return matcher.find() ? matcher.group(1) : message;
    }

    /**
     * 转义 JSON 字符串中的特殊字符，避免用户文字里的引号破坏 JSON 结构
     */
    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
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
