# 项目：CarBackend 小车后端

## 项目背景
这是小车项目的后端服务，用 Spring Boot 3 开发。
目前处于起步阶段，目标是提供 REST 接口，供后续对接 rosbridge 和 App。

## 技术栈
- Java 17
- Spring Boot 3.x
- Maven

## 项目结构
com.carbackend 包下：
- controller 包：存放 Web 接口（@RestController）
- core 包：存放核心逻辑（CarCommand 接口、实现类、异常）
- HelloApplication.java：启动类

## 接口规范
- 控制接口：GET /car/command?action=xxx
- 历史接口：GET /car/history

## 编码规范
- 所有接口加中文注释
- 使用 HashMap 管理指令映射
- 未知指令用自定义异常 CarCommandException 处理

## 常用命令
- 启动：在 IDEA 里运行 HelloApplication
- 测试：浏览器访问 http://localhost:8080