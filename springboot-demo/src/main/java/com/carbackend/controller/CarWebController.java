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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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

    @GetMapping("/config")
    public String getConfig() {
        return "rosbridge地址：" + rosbridgeUrl + "，控制话题：" + cmdTopic;
    }
}
