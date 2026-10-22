package com.carbackend.core;

import com.carbackend.controller.ChatWebSocketController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * rosbridge 的 WebSocket 客户端
 * 负责连接 rosbridge、持续发布底盘速度、发送机械臂指令、调用服务，并支持断线自动重连
 */
@Component
public class RosbridgeClient {

    // 底盘速度的持续发布频率（Hz）
    // 驱动侧 l150pro_driver_node 超过 0.5 秒收不到 /cmd_vel 就按零速处理（CMD_STALE_S），
    // 只发一帧的话车只会走 0.5 秒，所以这里必须持续发布
    private static final int CMD_PUBLISH_HZ = 20;

    // App 指令的有效期（毫秒）：App 按住时需以 >= 2Hz 重复调用同一指令刷新有效期，
    // 漏发或断网后自动回零，避免车一直跑
    private static final long CMD_HOLD_MS = 800;

    // AI 回复触发的指令没法由 App 续期，给一个更长但有限的有效期
    private static final long AI_ACTION_HOLD_MS = 2000;

    // 调用服务等待回复的最长时间（毫秒），机械臂归位本身要 5~15 秒
    private static final long SERVICE_TIMEOUT_MS = 30000;

    // rosbridge 未连接时，发送失败日志的最小间隔（毫秒），避免 20Hz 刷屏
    private static final long FAIL_LOG_INTERVAL = 5000;

    // rosbridge 地址，例如 ws://localhost:9090
    @Value("${rosbridge.url}")
    private String rosbridgeUrl;

    // 控制话题，例如 /cmd_vel
    @Value("${rosbridge.topic.cmd}")
    private String cmdTopic;

    // 机械臂归位服务，例如 /arm/rehome（std_srvs/srv/Trigger）
    @Value("${rosbridge.service.arm_rehome}")
    private String armRehomeService;

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
    private volatile WebSocketClient client;

    // 解析 rosbridge 推送的 JSON，用于按话题分发消息
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 收到 AI 回复后交给它推送给 App
    @Autowired
    private ChatWebSocketController chatWebSocketController;

    // 底盘指令的持续发布线程：定时器走 20Hz，一次发一条 /cmd_vel
    private ScheduledExecutorService cmdPublisher;

    // 当前速度指令（m/s, rad/s），由 sendCommand 写入，发布线程只读
    private volatile double cmdLinearX = 0;
    private volatile double cmdAngularZ = 0;

    // 指令的有效期截止时刻（System.currentTimeMillis），到点自动回零
    private volatile long cmdExpireAt = 0;

    // 上一拍是否为运动指令，用于只在"运动→停止"的边沿打日志，避免 20Hz 刷屏
    private volatile boolean cmdMoving = false;

    // 未能发送的速度指令帧数，用于节流打印"rosbridge未连接"
    private final AtomicLong droppedCmdFrames = new AtomicLong();

    // 上次打印发送失败日志的时刻
    private volatile long lastFailLogAt = 0;

    // 服务调用：调用 id → 等待结果的 future，收到 op=service_response 时按 id 唤醒
    private final Map<String, CompletableFuture<JsonNode>> pendingServices = new ConcurrentHashMap<>();

    // 服务调用 id 自增计数
    private final AtomicLong serviceCallSeq = new AtomicLong();

    /**
     * 项目启动时自动连接 rosbridge，并开启底盘速度的持续发布线程
     */
    @PostConstruct
    public void init() {
        connect();
        startCmdPublisher();
    }

    /**
     * 项目关闭时停止持续发布并断开连接，避免线程残留
     */
    @PreDestroy
    public void shutdown() {
        if (cmdPublisher != null) {
            cmdPublisher.shutdownNow();
        }
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception e) {
            System.out.println("【关闭rosbridge连接失败】" + e.getMessage());
        }
    }

    /**
     * 启动底盘速度的持续发布线程
     * 驱动侧 0.5 秒收不到 /cmd_vel 就按零速处理，因此必须恒频发布：
     * 有效期内发当前指令，失效后发零速（保持链路存活，也让车及时停下）
     */
    private void startCmdPublisher() {
        cmdPublisher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cmd-vel-publisher");
            t.setDaemon(true);
            return t;
        });
        long periodMs = 1000 / CMD_PUBLISH_HZ;
        cmdPublisher.scheduleAtFixedRate(this::publishCmdTick, 0, periodMs, TimeUnit.MILLISECONDS);
        System.out.println("【底盘速度持续发布已启动】" + CMD_PUBLISH_HZ + "Hz，话题：" + cmdTopic
                + "，指令有效期：" + CMD_HOLD_MS + " 毫秒");
    }

    /**
     * 发布线程的一拍：有效期内发当前指令，否则发零速
     */
    private void publishCmdTick() {
        try {
            boolean expired = System.currentTimeMillis() > cmdExpireAt;
            if (expired && (cmdLinearX != 0 || cmdAngularZ != 0)) {
                // 指令过期：清零并打一次日志，说明是 App 没有续期或断开了
                cmdLinearX = 0;
                cmdAngularZ = 0;
                System.out.println("【底盘指令过期】超过 " + CMD_HOLD_MS + " 毫秒未续期，已自动停车");
            }
            double vx = cmdLinearX;
            double wz = cmdAngularZ;
            boolean moving = (vx != 0 || wz != 0);
            if (moving != cmdMoving) {
                System.out.println(moving
                        ? "【底盘开始运动】linear.x=" + vx + "，angular.z=" + wz
                        : "【底盘停车】");
                cmdMoving = moving;
            }
            sendQuietly(buildCmdJson(vx, wz));
        } catch (Exception e) {
            // 发布线程里抛异常会让定时任务永久停止，必须兜住
            System.out.println("【底盘发布异常】" + e.getMessage());
        }
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
                    // AI 回复话题的消息转发给 App，服务调用结果交给等待方，
                    // 其余消息仅打印日志。图像不走这里，由 RtspStreamService 拉 RTSP
                    if (isAiReply(message)) {
                        chatWebSocketController.broadcastMessage(message);
                        // AI 回复里如果带了 action 字段，顺手执行对应的硬件指令
                        handleAiAction(message);
                    } else if (!handleServiceResponse(message)) {
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
     * 下发底盘运动指令
     * 只设置目标速度和有效期，真正的发布由 20Hz 线程完成。
     * 驱动侧 0.5 秒收不到 /cmd_vel 就按零速处理，所以这里不能只发一帧。
     *
     * App 需要按住期间以不低于 0.8 秒一拍的频率重复调用同一指令来续期；
     * 松手时调用 stop，或者干脆停止调用，过期后会自动停车。
     *
     * @param action 指令名称
     */
    public void sendCommand(String action) {
        sendCommand(action, "app");
    }

    /**
     * 下发底盘运动指令，并指定指令来源
     * 来源决定有效期：App 指令靠持续调用续期，AI 回复触发的指令没人续期，
     * 给一个较长的有限有效期，避免 AI 说一句"前进"就让车一直跑
     *
     * @param action 指令名称
     * @param source 来源，app 或 ai
     */
    public void sendCommand(String action, String source) {
        double vx;
        double wz;
        switch (action) {
            case "forward":
                vx = 0.5;
                wz = 0;
                break;
            case "stop":
                vx = 0;
                wz = 0;
                break;
            case "left":
                vx = 0;
                wz = 0.5;
                break;
            case "right":
                vx = 0;
                wz = -0.5;
                break;
            case "backward":
                vx = -0.5;
                wz = 0;
                break;
            default:
                // 未知指令，交给统一异常处理
                throw new CarCommandException("未知指令：" + action);
        }
        // 先写有效期再写速度：发布线程看到新速度时有效期已经生效，不会误判过期
        long hold = "ai".equals(source) ? AI_ACTION_HOLD_MS : CMD_HOLD_MS;
        cmdExpireAt = System.currentTimeMillis() + hold;
        cmdLinearX = vx;
        cmdAngularZ = wz;
        if (vx != 0 || wz != 0) {
            System.out.println("【底盘指令】" + action + "（来源：" + source + "，有效期 " + hold + " 毫秒）");
        }
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
     * 机械臂归位
     * 调用树莓派侧的 /arm/rehome 服务（std_srvs/srv/Trigger）。
     * 固件跑的是与上电同一套流程：五个位置关节回 ARM_RESET_POSE，底座重新锚定并回正。
     * 整个过程约 5~15 秒，期间机械臂会动，App 需要给出"归位中"的提示。
     *
     * @return 服务返回的 message，超时或失败时返回原因
     */
    public String sendArmRehome() {
        String callId = "rehome-" + serviceCallSeq.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingServices.put(callId, future);
        try {
            // rosbridge 的服务调用格式：op=call_service，必须带 type，否则服务端无法反序列化请求
            String msg = "{\"op\":\"call_service\",\"id\":\"" + callId
                    + "\",\"service\":\"" + armRehomeService
                    + "\",\"type\":\"std_srvs/srv/Trigger\",\"args\":{}}";
            if (!send(msg)) {
                return "发送失败：rosbridge 未连接";
            }
            JsonNode values = future.get(SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS).path("values");
            boolean success = values.path("success").asBoolean(false);
            String message = values.path("message").asText("");
            System.out.println("【机械臂归位】success=" + success + "，message=" + message);
            return message.isEmpty() ? (success ? "归位指令已下发" : "归位失败") : message;
        } catch (java.util.concurrent.TimeoutException e) {
            return "服务调用超时：" + armRehomeService + " 无响应";
        } catch (Exception e) {
            return "服务调用失败：" + e.getMessage();
        } finally {
            // 无论成功失败都要摘掉等待，避免超时后 map 里留下僵尸项
            pendingServices.remove(callId);
        }
    }

    /**
     * 处理 rosbridge 返回的服务调用结果
     * op=service_response 的消息由调用方按 id 匹配，不属于业务日志，命中后不再打印
     *
     * @param message rosbridge 推送的原始 JSON
     * @return 是服务调用结果返回 true，否则返回 false
     */
    private boolean handleServiceResponse(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            if (!"service_response".equals(node.path("op").asText())) {
                return false;
            }
            CompletableFuture<JsonNode> future = pendingServices.get(node.path("id").asText());
            if (future != null) {
                future.complete(node);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
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
                // AI 触发的指令没有 App 来续期，用较长的有限有效期兜底
                sendCommand(action, "ai");
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
     *
     * @param message 要发送的 JSON
     * @return 是否成功交给底层发送
     */
    private boolean send(String message) {
        try {
            if (client != null && client.isOpen()) {
                client.send(message);
                return true;
            }
            System.out.println("【发送失败】rosbridge未连接：" + message);
            return false;
        } catch (Exception e) {
            System.out.println("【发送异常】" + e.getMessage());
            return false;
        }
    }

    /**
     * 高频发送方法，专供 20Hz 速度发布使用
     * 断线时每帧都会失败，不能像 send 那样每条都打日志，否则一秒刷 20 行，
     * 这里改成累计丢帧数、每 FAIL_LOG_INTERVAL 最多提示一次
     *
     * @param message 要发送的 JSON
     */
    private void sendQuietly(String message) {
        try {
            if (client != null && client.isOpen()) {
                long dropped = droppedCmdFrames.getAndSet(0);
                if (dropped > 0) {
                    System.out.println("【rosbridge已恢复】期间丢弃 " + dropped + " 帧速度指令");
                }
                client.send(message);
            } else {
                droppedCmdFrames.incrementAndGet();
                long now = System.currentTimeMillis();
                if (now - lastFailLogAt > FAIL_LOG_INTERVAL) {
                    lastFailLogAt = now;
                    System.out.println("【速度指令发送失败】rosbridge未连接，已累计丢弃 "
                            + droppedCmdFrames.get() + " 帧");
                }
            }
        } catch (Exception e) {
            droppedCmdFrames.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - lastFailLogAt > FAIL_LOG_INTERVAL) {
                lastFailLogAt = now;
                System.out.println("【速度指令发送异常】" + e.getMessage());
            }
        }
    }
}
