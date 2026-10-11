package com.carbackend.controller;

import com.carbackend.core.BackwardCommand;
import com.carbackend.core.CarCommand;
import com.carbackend.core.CarCommandException;
import com.carbackend.core.ForwardCommand;
import com.carbackend.core.LeftCommand;
import com.carbackend.core.RightCommand;
import com.carbackend.core.RosbridgeClient;
import com.carbackend.core.StopCommand;
import com.carbackend.controller.dto.AiChatRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 小车 Web 控制器
 * 提供控制指令和历史记录两个 REST 接口
 */
@RestController
@RequestMapping("/car")
public class CarWebController {

    // 读取配置文件里的 rosbridge 地址
    @Value("${rosbridge.url}")
    private String rosbridgeUrl;

    // 读取配置文件里的控制话题
    @Value("${rosbridge.topic.cmd}")
    private String cmdTopic;

    // rosbridge 客户端，用于把指令真正发送给小车
    @Autowired
    private RosbridgeClient rosbridgeClient;

    // 指令映射表：根据 action 字符串查找对应的指令对象
    private final Map<String, CarCommand> commandMap = new HashMap<>();

    // 已执行指令的历史记录（内存存储，不使用数据库）
    private final List<String> history = new ArrayList<>();

    /**
     * 构造方法：初始化指令映射表
     */
    public CarWebController() {
        commandMap.put("forward", new ForwardCommand());
        commandMap.put("stop", new StopCommand());
        commandMap.put("left", new LeftCommand());
        commandMap.put("right", new RightCommand());
        commandMap.put("backward", new BackwardCommand());
    }

    /**
     * 控制接口：GET /car/command?action=xxx
     * 接收 action 参数，从映射表中查找并执行对应指令
     *
     * @param action 指令名称
     * @return 执行结果提示
     */
    @GetMapping("/command")
    public String command(@RequestParam("action") String action) {
        // 从映射表中查找指令
        CarCommand command = commandMap.get(action);
        // 未找到指令时抛出自定义异常
        if (command == null) {
            throw new CarCommandException("未知指令：" + action);
        }
        // 执行指令
        command.execute();
        // 将指令转发给 rosbridge，真正控制小车
        try {
            rosbridgeClient.sendCommand(action);
        } catch (Exception e) {
            System.out.println("【转发rosbridge失败】" + e.getMessage());
        }
        // 将指令记录到历史列表中
        history.add(action);
        return "指令 " + action + " 执行成功";
    }

    /**
     * 历史接口：GET /car/history
     * 返回执行过的指令列表
     *
     * @return 已执行指令的 List
     */
    @GetMapping("/history")
    public List<String> history() {
        return history;
    }

    /**
     * 捕获未知指令异常，返回提示信息
     *
     * @param e 小车指令异常
     * @return 异常提示信息
     */
    @ExceptionHandler(CarCommandException.class)
    public String handleCarCommandException(CarCommandException e) {
        return e.getMessage();
    }

    /**
     * 捕获参数类型不匹配异常（例如 enabled 传了 true/false 以外的值），返回提示信息
     *
     * @param e 参数类型不匹配异常
     * @return 异常提示信息
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public String handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return "参数 " + e.getName() + " 非法：" + e.getValue();
    }

    @GetMapping("/config")
    public String getConfig() {
        return "rosbridge地址：" + rosbridgeUrl + "，控制话题：" + cmdTopic;
    }

    // LED 合法颜色集合
    private static final List<String> LED_COLORS = List.of("red", "green", "blue", "white", "off");

    // LED 合法模式集合
    private static final List<String> LED_MODES = List.of("flow", "static", "blink");

    // 喇叭合法动作集合
    private static final List<String> BUZZER_ACTIONS = List.of("on", "off");

    /**
     * LED 颜色控制接口：POST /car/led?color=xxx
     * 接收颜色名称（red/green/blue/white/off），转发给 rosbridge 控制 LED
     *
     * @param color 颜色名称
     * @return 执行结果提示
     */
    @PostMapping("/led")
    public String led(@RequestParam("color") String color) {
        // 未知颜色直接抛出自定义异常
        if (!LED_COLORS.contains(color)) {
            throw new CarCommandException("未知的LED颜色：" + color);
        }
        // 转发给 rosbridge，真正控制 LED
        try {
            rosbridgeClient.sendLedColor(color);
        } catch (Exception e) {
            System.out.println("【转发rosbridge失败】" + e.getMessage());
        }
        return "指令 led=" + color + " 执行成功";
    }

    /**
     * LED 模式控制接口：POST /car/led/mode?mode=xxx
     * 接收模式名称（flow/static/blink），转发给 rosbridge 控制 LED
     *
     * @param mode 模式名称
     * @return 执行结果提示
     */
    @PostMapping("/led/mode")
    public String ledMode(@RequestParam("mode") String mode) {
        // 未知模式直接抛出自定义异常
        if (!LED_MODES.contains(mode)) {
            throw new CarCommandException("未知的LED模式：" + mode);
        }
        // 转发给 rosbridge，真正控制 LED
        try {
            rosbridgeClient.sendLedMode(mode);
        } catch (Exception e) {
            System.out.println("【转发rosbridge失败】" + e.getMessage());
        }
        return "指令 led/mode=" + mode + " 执行成功";
    }

    /**
     * 喇叭控制接口：POST /car/buzzer?action=xxx
     * 接收动作名称（on/off），转发给 rosbridge 控制喇叭
     *
     * @param action 动作名称
     * @return 执行结果提示
     */
    @PostMapping("/buzzer")
    public String buzzer(@RequestParam("action") String action) {
        // 未知动作直接抛出自定义异常
        if (!BUZZER_ACTIONS.contains(action)) {
            throw new CarCommandException("未知的喇叭动作：" + action);
        }
        // 转发给 rosbridge，真正控制喇叭
        try {
            rosbridgeClient.sendBuzzer(action);
        } catch (Exception e) {
            System.out.println("【转发rosbridge失败】" + e.getMessage());
        }
        return "指令 buzzer=" + action + " 执行成功";
    }

    /**
     * 图像处理开关接口：POST /car/image/process?enabled=true
     * 接收开关状态（true/false），转发给 rosbridge 控制 YOLO/OpenCV 图像处理是否开启
     *
     * @param enabled 是否开启图像处理
     * @return 执行结果提示
     */
    @PostMapping("/image/process")
    public String imageProcess(@RequestParam("enabled") boolean enabled) {
        // 转发给 rosbridge，真正控制图像处理开关
        try {
            rosbridgeClient.sendImageProcess(enabled);
        } catch (Exception e) {
            System.out.println("【转发rosbridge失败】" + e.getMessage());
        }
        return "指令 image/process=" + enabled + " 执行成功";
    }

    // 云台方案已废除，改用机械臂方案，接口暂停使用
    // /**
    //  * 云台转向接口：POST /car/ptz
    //  * 接收画面坐标百分比（x、y 均为 0~100），转发给 rosbridge 控制云台
    //  *
    //  * @param x 画面横向百分比（0~100）
    //  * @param y 画面纵向百分比（0~100）
    //  * @return 执行结果提示
    //  */
    // @PostMapping("/ptz")
    // public String ptz(@RequestParam("x") double x, @RequestParam("y") double y) {
    //     // 参数范围校验，超出 0~100 视为非法指令
    //     if (x < 0 || x > 100 || y < 0 || y > 100) {
    //         throw new CarCommandException("非法的云台坐标：x=" + x + ", y=" + y);
    //     }
    //     // 将坐标转发给 rosbridge，真正控制云台
    //     try {
    //         rosbridgeClient.sendPtz(x, y);
    //     } catch (Exception e) {
    //         System.out.println("【转发rosbridge失败】" + e.getMessage());
    //     }
    //     return "指令 ptz 执行成功";
    // }

    /**
     * 机械臂控制接口：POST /car/arm
     * 接收幻尔 LeArm 机械臂 6 个自由度（p1~p5 关节 + base 底盘）的角度值，
     * 转发给 rosbridge 发布到 /arm/command 话题
     *
     * @param p1   1 号关节角度
     * @param p2   2 号关节角度
     * @param p3   3 号关节角度
     * @param p4   4 号关节角度
     * @param p5   5 号关节角度
     * @param base 底盘（电机模式）角度
     * @return 执行结果提示
     */
    @PostMapping("/arm")
    public String arm(@RequestParam("p1") int p1,
                      @RequestParam("p2") int p2,
                      @RequestParam("p3") int p3,
                      @RequestParam("p4") int p4,
                      @RequestParam("p5") int p5,
                      @RequestParam("base") int base) {
        // 打印收到的参数，方便调试
        System.out.println("【收到机械臂指令】p1=" + p1 + ", p2=" + p2 + ", p3=" + p3
                + ", p4=" + p4 + ", p5=" + p5 + ", base=" + base);
        // 转发给 rosbridge，真正控制机械臂
        try {
            rosbridgeClient.sendArm(p1, p2, p3, p4, p5, base);
        } catch (Exception e) {
            System.out.println("【转发rosbridge失败】" + e.getMessage());
        }
        return "指令 arm 执行成功";
    }

    /**
     * AI 对话接口：POST /car/ai/chat
     * 请求体为 JSON：{"text":"xxx", "image":"xxx"}，image 是图片的 Base64 字符串（可选）。
     * image 体积较大，必须用 @RequestBody 接收，不能再走 URL 参数，否则会触发
     * Tomcat 的 "Request header is too large"。转发给 rosbridge 发布到 AI 输入话题，
     * 由树莓派上的 voice-chatbot 节点处理后，回复经 /ws/chat 推送给 App
     *
     * @param request 包含 text（必填）和 image（可选）的请求体
     * @return 执行结果提示
     */
    @PostMapping("/ai/chat")
    public String aiChat(@RequestBody AiChatRequest request) {
        String text = request.getText();
        String image = request.getImage() == null ? "" : request.getImage();
        // 转发给 rosbridge，真正的 AI 处理在树莓派侧完成
        try {
            rosbridgeClient.sendAiText(text, image);
        } catch (Exception e) {
            System.out.println("【转发rosbridge失败】" + e.getMessage());
        }
        return "AI 请求已发送";
    }

    /**
     * AI 纯文字对话接口（兼容旧版）：POST /car/ai/chat/text?text=xxx
     * 只接收文字，不带图片，方便前端纯文字对话场景沿用旧的 @RequestParam 方式调用
     *
     * @param text 用户输入的文字
     * @return 执行结果提示
     */
    @PostMapping("/ai/chat/text")
    public String aiChatText(@RequestParam("text") String text) {
        // 转发给 rosbridge，image 固定传空字符串
        try {
            rosbridgeClient.sendAiText(text, "");
        } catch (Exception e) {
            System.out.println("【转发rosbridge失败】" + e.getMessage());
        }
        return "AI 请求已发送";
    }

    /**
     * 机械臂归位接口：POST /car/arm/reset
     * 占位实现：STM32 归位接口由负责人后续提供，当前仅返回模拟提示，不做真实逻辑
     *
     * @return 模拟执行结果提示
     */
    @PostMapping("/arm/reset")
    public String armReset() {
        // TODO 待负责人提供 STM32 归位接口后，在此补上真实归位逻辑
        return "机械臂归位指令已发送（模拟）";
    }

    // 假地图图片路径，纯模拟阶段用；图片不存在时自动退化为 8x8 数组
    private static final String FAKE_MAP_PATH = "src/main/resources/fake_map.png";

    // 假地图的边长（模拟 8x8 的 OccupancyGrid，0=空地，100=障碍物）
    private static final int FAKE_MAP_SIZE = 8;

    /**
     * 地图接口：GET /car/map
     * 纯模拟阶段不连真机，直接返回一张假地图：
     * 若 resources 下存在 fake_map.png，则把图片转成 Base64 返回；否则返回 8x8 二维数组模拟 OccupancyGrid，
     * 数组按行优先排列（下标 = y * 8 + x），障碍物画成"边框 + 十字"，方便 App 端看清效果
     *
     * @return 地图数据，格式 {"width":8, "height":8, "data":[0,0,...]} 或 {"width":..., "height":..., "image":"base64..."}
     */
    @GetMapping("/map")
    public Map<String, Object> map() {
        Map<String, Object> result = new HashMap<>();
        // 先尝试读取本地假地图图片
        java.io.File file = new java.io.File(FAKE_MAP_PATH);
        if (file.exists()) {
            try {
                // 图片转 Base64，方便 App 直接解码显示
                byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                result.put("width", FAKE_MAP_SIZE);
                result.put("height", FAKE_MAP_SIZE);
                result.put("image", base64);
                return result;
            } catch (Exception e) {
                // 读图失败时不报错，继续走下面的数组兜底
                System.out.println("【读取假地图图片失败】" + e.getMessage());
            }
        }
        // 没有图片时，生成 8x8 的假 OccupancyGrid：最外圈当墙，第 4 行和第 4 列当十字隔断，
        // 剩下 4 个矩形空房间（合计 25 格空地），App 端一眼就能看出障碍物轮廓
        List<Integer> data = new ArrayList<>();
        for (int y = 0; y < FAKE_MAP_SIZE; y++) {
            for (int x = 0; x < FAKE_MAP_SIZE; x++) {
                boolean isBorder = (x == 0 || x == FAKE_MAP_SIZE - 1
                        || y == 0 || y == FAKE_MAP_SIZE - 1);
                boolean isCross = (x == 4 || y == 4);
                data.add(isBorder || isCross ? 100 : 0);
            }
        }
        result.put("width", FAKE_MAP_SIZE);
        result.put("height", FAKE_MAP_SIZE);
        result.put("data", data);
        return result;
    }

    /**
     * 导航目标点接口：POST /car/nav/goal?x=1&y=2
     * 占位实现：nav2 由负责人部署后再补真实转发逻辑，当前仅返回提示
     *
     * @param x 目标点在地图上的横坐标
     * @param y 目标点在地图上的纵坐标
     * @return 占位提示
     */
    @PostMapping("/nav/goal")
    public String navGoal(@RequestParam("x") double x, @RequestParam("y") double y) {
        // TODO 待负责人部署 nav2 后，在此把目标点发布到 goalTopic（配置项 rosbridge.topic.goal）
        return "导航功能开发中，目标点：(" + x + ", " + y + ")";
    }
}
