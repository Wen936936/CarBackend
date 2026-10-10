package com.carbackend.controller;

import com.carbackend.core.BackwardCommand;
import com.carbackend.core.CarCommand;
import com.carbackend.core.CarCommandException;
import com.carbackend.core.ForwardCommand;
import com.carbackend.core.LeftCommand;
import com.carbackend.core.RightCommand;
import com.carbackend.core.RosbridgeClient;
import com.carbackend.core.StopCommand;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
        // 转发给 rosbridge，真正控制机械臂
        try {
            rosbridgeClient.sendArm(p1, p2, p3, p4, p5, base);
        } catch (Exception e) {
            System.out.println("【转发rosbridge失败】" + e.getMessage());
        }
        return "指令 arm 执行成功";
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
}
