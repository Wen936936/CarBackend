package com.carbackend.core;

import com.carbackend.controller.ChatWebSocketController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Autowired;
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

    // 摄像头图像已改由 RtspStreamService 直接拉取 RTSP 流，不再走 rosbridge 转发

    // 云台方案已废除，改用机械臂方案
    // // 云台控制话题，例如 /ptz/cmd
    // @Value("${rosbridge.topic.ptz}")
    // private String ptzTopic;

    // 机械臂控制话题，例如 /arm/command
    @Value("${rosbridge.topic.arm}")
    private String armTopic;

    // LED/流水灯控制话题，例如 /led/cmd
    @Value("${rosbridge.topic.led}")
    private String ledTopic;

    // 喇叭控制话题，例如 /buzzer/cmd
    @Value("${rosbridge.topic.buzzer}")
    private String buzzerTopic;

    // 图像处理开关话题，例如 /image/process
    @Value("${rosbridge.topic.image_process}")
    private String imageProcessTopic;

    // AI 对话输入话题（模拟阶段为占位符 /ai/input，实机测试时替换）
    @Value("${rosbridge.topic.ai.input}")
    private String aiInputTopic;

    // AI 对话回复话题（模拟阶段为占位符 /ai/reply，实机测试时替换）
    @Value("${rosbridge.topic.ai.reply}")
    private String aiReplyTopic;

    // 重连间隔时间（毫秒）
    private static final int RECONNECT_INTERVAL = 3000;

    // 真正干活的 WebSocket 客户端对象
    private WebSocketClient client;

    // 解析 rosbridge 推送的 JSON，用于按话题分发消息
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 收到 AI 回复后交给它推送给 App
    @Autowired
    private ChatWebSocketController chatWebSocketController;

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
                    // 每次连接成功（含断线重连）都重新订阅 AI 回复话题
                    subscribeAiReply();
                }

                @Override
                public void onMessage(String message) {
                    // AI 回复话题的消息转发给 App，其余消息仅打印日志
                    // 图像不再从这里转发，改由 RtspStreamService 拉 RTSP 流
                    if (isAiReply(message)) {
                        chatWebSocketController.broadcastMessage(message);
                        // AI 回复里如果带了 action 字段，顺手执行对应的硬件指令
                        handleAiAction(message);
                    } else {
                        System.out.println("【收到rosbridge消息】" + message);
                    }
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
            case "backward":
                msg = buildCmdJson(-0.5, 0);
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

    // 云台方案已废除，改用机械臂方案
    // /**
    //  * 发送云台转向指令
    //  * 将 App 传来的 0~100 画面百分比坐标，线性映射为 -90~+90 度，发布到 ptz 话题
    //  *
    //  * @param x 画面横向百分比（0~100）
    //  * @param y 画面纵向百分比（0~100）
    //  */
    // public void sendPtz(double x, double y) {
    //     double pan = (x - 50) * 1.8;
    //     double tilt = (y - 50) * 1.8;
    //     String msg = "{\"op\":\"publish\",\"topic\":\"" + ptzTopic + "\",\"msg\":{\"pan\":" + pan
    //             + ",\"tilt\":" + tilt + "}}";
    //     send(msg);
    // }

    /**
     * 发送机械臂控制指令
     * 将 6 个自由度（p1~p5 关节 + base 底盘）的角度值发布到 /arm/command 话题
     *
     * @param p1   1 号关节角度
     * @param p2   2 号关节角度
     * @param p3   3 号关节角度
     * @param p4   4 号关节角度
     * @param p5   5 号关节角度
     * @param base 底盘（电机模式）角度
     */
    public void sendArm(int p1, int p2, int p3, int p4, int p5, int base) {
        String msg = "{\"op\":\"publish\",\"topic\":\"" + armTopic + "\",\"msg\":{\"position\":["
                + p1 + "," + p2 + "," + p3 + "," + p4 + "," + p5 + "," + base + "]}}";
        send(msg);
    }

    /**
     * 发送 LED 颜色指令
     * 将颜色名（red/green/blue/white/off）发布到 LED 话题
     *
     * @param color 颜色名称
     */
    public void sendLedColor(String color) {
        String msg = "{\"op\":\"publish\",\"topic\":\"" + ledTopic + "\",\"msg\":{\"color\":\"" + color + "\"}}";
        send(msg);
    }

    /**
     * 发送 LED 模式指令
     * 将模式名（flow/static/blink）发布到 LED 话题
     *
     * @param mode 模式名称
     */
    public void sendLedMode(String mode) {
        String msg = "{\"op\":\"publish\",\"topic\":\"" + ledTopic + "\",\"msg\":{\"mode\":\"" + mode + "\"}}";
        send(msg);
    }

    /**
     * 发送喇叭指令
     * 将动作名（on/off）发布到喇叭话题
     *
     * @param action 动作名称
     */
    public void sendBuzzer(String action) {
        String msg = "{\"op\":\"publish\",\"topic\":\"" + buzzerTopic + "\",\"msg\":{\"action\":\"" + action + "\"}}";
        send(msg);
    }

    /**
     * 发送图像处理开关指令
     * 将开关状态（true/false）发布到图像处理话题，控制 YOLO/OpenCV 是否处理画面
     *
     * @param enabled 是否开启图像处理
     */
    public void sendImageProcess(boolean enabled) {
        String msg = "{\"op\":\"publish\",\"topic\":\"" + imageProcessTopic
                + "\",\"msg\":{\"enabled\":" + enabled + "}}";
        send(msg);
    }

    /**
     * 发送 AI 对话文本（可选带图片）
     * 将文字和图片的 Base64 发布到 AI 输入话题，由树莓派上的 voice-chatbot 节点处理
     *
     * @param text        用户输入的文字
     * @param base64Image 图片的 Base64 字符串，没有图片时传空字符串
     */
    public void sendAiText(String text, String base64Image) {
        // image 允许为空，为空时补空字符串，保证 JSON 字段始终存在
        String image = (base64Image == null) ? "" : base64Image;
        String msg = "{\"op\":\"publish\",\"topic\":\"" + aiInputTopic
                + "\",\"msg\":{\"text\":\"" + escapeJson(text)
                + "\",\"image\":\"" + escapeJson(image) + "\"}}";
        send(msg);
        System.out.println("【发送AI请求】topic=" + aiInputTopic + "，text=" + text
                + "，image长度=" + image.length());
    }

    /**
     * 订阅 AI 回复话题
     * 连接成功时调用，之后 rosbridge 收到 voice-chatbot 的回复会主动推送过来
     */
    public void subscribeAiReply() {
        String msg = "{\"op\":\"subscribe\",\"topic\":\"" + aiReplyTopic + "\"}";
        send(msg);
        System.out.println("【订阅AI回复话题】" + aiReplyTopic);
    }

    /**
     * 判断一条 rosbridge 消息是否来自 AI 回复话题
     * 只处理 publish 类型且 topic 匹配的消息，解析失败一律返回 false，不影响其他消息
     *
     * @param message rosbridge 推送的原始 JSON
     * @return 是 AI 回复返回 true，否则返回 false
     */
    private boolean isAiReply(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            return "publish".equals(node.path("op").asText())
                    && aiReplyTopic.equals(node.path("topic").asText());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查 AI 回复中的 action 字段，不为空时自动执行对应的硬件指令
     * action 为空只代表这条回复是纯聊天内容，不需要控制小车
     * 执行指令可能因未知指令抛出 CarCommandException，这里统一 try-catch，避免影响消息转发
     *
     * @param message rosbridge 推送的 AI 回复原始 JSON
     */
    private void handleAiAction(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String action = node.path("msg").path("action").asText("");
            if (!action.isEmpty()) {
                System.out.println("【AI回复触发指令】action=" + action);
                sendCommand(action);
            }
        } catch (Exception e) {
            // 指令未知或解析失败都不应该影响 AI 对话流程，只打印日志
            System.out.println("【AI回复指令执行失败】" + e.getMessage());
        }
    }

    /**
     * 转义 JSON 字符串里的特殊字符
     * text 来自 App 输入，含引号、反斜杠或换行时会拼出非法 JSON，必须转义
     *
     * @param value 原始字符串
     * @return 可安全嵌入 JSON 的字符串
     */
    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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
