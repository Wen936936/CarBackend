# 项目：CarBackend 小车后端

## 项目背景
Spring Boot 3 后端，对接树莓派 rosbridge，为 App 提供控制接口。

## 技术栈
- Java 17, Spring Boot 3.x, Maven
- WebSocket（用于连接 rosbridge）

## 项目结构
com.carbackend 包下：
- controller 包：Web 接口（CarWebController）
- core 包：核心逻辑（CarCommand 接口、实现类、RosbridgeClient）
- fakeros 包：本地测试用的假 rosbridge 服务器
- HelloApplication.java：启动类

## 配置文件（application.properties）
- rosbridge.url=ws://localhost:9090
- rosbridge.topic.cmd=/cmd_vel
- rosbridge.topic.camera=/camera/image_raw
- rosbridge.topic.ptz=/ptz/cmd

## 编码规范
- 所有接口加中文注释
- 使用 HashMap 管理指令映射
- 未知指令用 CarCommandException 处理
- 连接 rosbridge 的代码必须 try-catch