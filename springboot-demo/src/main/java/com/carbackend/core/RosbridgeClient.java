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
                }

                @Override
                public void onMessage(String message) {
                    // 收到 rosbridge 推送的消息，打印日志即可
                    // 图像不再从这里转发，改由 RtspStreamService 拉 RTSP 流
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
