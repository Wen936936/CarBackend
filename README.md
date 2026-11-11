# CarBackend · 智能小车后端中间件

> 基于 Spring Boot 3 的智能小车后端，作为 **Android App** 与 **树莓派 ROS 2 小车** 之间的中间层：
> 向上为 App 提供 REST / WebSocket 接口，向下通过 WebSocket 连接 `rosbridge` 转发控制指令，并直接拉取 K230 摄像头的 RTSP 视频流。

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.0-brightgreen)
![WebSocket](https://img.shields.io/badge/WebSocket-rosbridge-blue)
![JavaCV](https://img.shields.io/badge/JavaCV-1.5.11-red)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

---

## 项目简介

本项目是智能小车的**中间件 / 后端服务**。它把手机 App 的操作请求翻译成 ROS 2 话题消息，交由树莓派上的 `rosbridge_server` 执行；同时把车端的视频流和 AI 回复回传给 App。

```
┌─────────────┐   HTTP / WebSocket    ┌──────────────────┐   WebSocket(JSON)   ┌──────────────────┐
│  Android App │ ───────────────────▶ │  CarBackend      │ ──────────────────▶ │  rosbridge_server │
│  (遥控 / 对话)│ ◀─────────────────── │  Spring Boot 3   │ ◀────────────────── │  (树莓派 ROS 2)    │
└─────────────┘   图像 / AI 回复        └──────────────────┘   话题 / 服务响应     └──────────────────┘
                                              │
                                              │ RTSP (JavaCV / FFmpeg)
                                              ▼
                                        ┌───────────┐
                                        │  K230 摄像头│
                                        └───────────┘
```

**角色分工：**

| 端 | 职责 |
|---|---|
| Android App | 用户交互：遥控、摄像头画面、AI 对话、机械臂、LED / 喇叭 |
| **CarBackend（本项目）** | 协议转换、指令保鲜、视频转码、AI 动作拦截 |
| 树莓派 ROS 2 | `rosbridge_server` + 驱动节点 + `voice-chatbot` 节点 |
| K230 | 板载摄像头，输出 RTSP 流 |

---

## 技术栈

- **Java 17** + **Spring Boot 3.3.0**
- **Spring WebSocket** — 向 App 暴露 `/ws/camera`、`/ws/chat` 端点
- **Java-WebSocket** — 作为客户端连接 `rosbridge`，并在本地模拟服务
- **JavaCV 1.5.11** — 通过 FFmpeg 拉取并解码 RTSP 视频流
- **Maven** — 多模块构建（父 POM 聚合 `springboot-demo`）

---

## 核心功能亮点

### 1. 20 Hz 心跳保活，防止驱动急停

ROS 2 底盘驱动（`l150pro_driver_node`）有一条安全策略：**超过 0.5 秒收不到 `/cmd_vel` 就按零速处理**。因此"发一帧就走"是行不通的。

后端用一条独立定时线程，以 **20 Hz 恒定发布** 底盘速度：

- 指令有效期内 → 持续发布当前速度
- 指令过期 → 自动发布零速，保持链路存活并及时停车
- App 按住期间需以 **≥ 2 Hz** 重复调用同一指令来续期（有效期 800 ms）
- AI 触发的动作没人续期，单独给 2 s 有限有效期兜底，避免"AI 说一句前进就一直跑"

> 断线时 20 Hz 会疯狂刷日志，所以发送层做了**丢帧计数 + 5 秒节流提示**，恢复后补报丢弃帧数。

### 2. JavaCV 低延迟视频推流

- 后端主动拉取 K230 的 RTSP 流，逐帧用 FFmpeg 解码 → JPEG 编码 → Base64
- 通过 `/ws/camera` 广播给所有已连接的 App（`CopyOnWriteArraySet` 管理多端会话）
- 针对低延迟设置了关键参数：

```java
grabber.setOption("rtsp_transport", "tcp");  // TCP 传输出错少、不花屏
grabber.setOption("fflags", "nobuffer");     // 不缓冲
grabber.setOption("flags", "low_delay");     // 低延迟模式
grabber.setOption("max_delay", "0");         // 不额外等待
```

- 流断开 / 地址不可达时，**每 3 秒自动重连**，采集器在 `finally` 中必定释放，避免句柄泄漏

### 3. rosbridge 服务调用（call_service）

普通话题只能"发完不管"，而机械臂归位是一个**需要等结果**的动作（约 5–15 秒）。后端实现了完整的服务调用链路：

- 发送 `op=call_service`（带 `type` 和服务名）并生成唯一调用 `id`
- 用 `ConcurrentHashMap<String, CompletableFuture>` 挂起等待
- 收到 `op=service_response` 时按 `id` 唤醒对应 Future
- 30 秒超时兜底，`finally` 中清理僵尸等待项

对应接口：`POST /car/arm/reset` → 树莓派 `/arm/rehome`（`std_srvs/srv/Trigger`）。

### 4. AI 动作拦截与解析

App 的 AI 对话走 `POST /car/ai/chat`，文字（及可选 Base64 图片）经 rosbridge 发布到 `/ai/input`，由树莓派上的 `voice-chatbot` 节点处理。

当 `/ai/reply` 回流时，后端会做两件事：

1. **转发**：把原始 JSON 通过 `/ws/chat` 推送给所有 App
2. **拦截**：解析 `msg.action` 字段，非空时自动下发对应硬件指令

```json
{
  "op": "publish",
  "topic": "/ai/reply",
  "msg": { "text": "好的，正在前进", "action": "forward" }
}
```

> `action` 为空表示纯聊天，不触发硬件；未知动作只打日志，不中断对话链路。

### 5. 其他能力

- 机械臂 6 自由度控制（`p1~p5` + `base`）发布到 `/arm/command`
- LED 颜色 / 模式、喇叭、图像处理（YOLO/OpenCV）开关转发
- 断线**自动重连**（每 3 秒），重连成功后自动重新订阅 AI 回复话题
- 内置 **假 rosbridge 服务器**（`FakeRosbridgeServer`），无真机也能本地联调

---

## 项目结构

```
SpringBoot_Text/
├── pom.xml                     # 父 POM（聚合模块）
├── README.md
└── springboot-demo/
    ├── pom.xml
    └── src/main/
        ├── java/com/carbackend/
        │   ├── HelloApplication.java          # 启动类
        │   ├── controller/
        │   │   ├── CarWebController.java      # REST 接口
        │   │   ├── CameraWebSocketController  # /ws/camera 视频广播
        │   │   ├── ChatWebSocketController    # /ws/chat AI 回复推送
        │   │   └── dto/AiChatRequest.java     # AI 请求体
        │   ├── core/
        │   │   ├── RosbridgeClient.java       # rosbridge 客户端（20Hz 发布 / 服务调用）
        │   │   ├── RtspStreamService.java     # RTSP 拉流与 JPEG 编码
        │   │   └── *Command.java              # 指令映射（策略模式）
        │   └── fakeros/
        │       └── FakeRosbridgeServer.java   # 本地假 rosbridge（9090）
        └── resources/
            ├── application.properties
            └── test.jpg
```

---

## 接口一览

### REST

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/car/command?action=forward` | 底盘控制（`forward` / `backward` / `left` / `right` / `stop`） |
| GET | `/car/history` | 指令历史 |
| GET | `/car/config` | 当前 rosbridge 配置 |
| POST | `/car/arm?p1=&p2=&p3=&p4=&p5=&base=` | 机械臂角度控制 |
| POST | `/car/arm/reset` | 机械臂归位（服务调用） |
| POST | `/car/led?color=red` | LED 颜色（`red` / `green` / `blue` / `white` / `off`） |
| POST | `/car/led/mode?mode=flow` | LED 模式（`flow` / `static` / `blink`） |
| POST | `/car/buzzer?action=on` | 喇叭（`on` / `off`） |
| POST | `/car/image/process?enabled=true` | 图像处理开关 |
| POST | `/car/ai/chat` | AI 对话（JSON Body：`{"text":"...","image":"<Base64>"}`） |
| GET | `/car/map` | 模拟地图（图片 Base64 或 8×8 OccupancyGrid） |
| POST | `/car/nav/goal?x=&y=` | 导航目标点（占位，待 nav2 接入） |

### WebSocket

| 端点 | 方向 | 说明 |
|---|---|---|
| `ws://<host>:8080/ws/camera` | 后端 → App | 实时视频帧（Base64 JPEG） |
| `ws://<host>:8080/ws/chat` | 后端 → App | AI 回复原始 JSON |

---

## 运行说明

### 前置条件

- JDK 17+
- Maven 3.8+
- 一个可访问的 `rosbridge_server`（默认 `ws://localhost:9090`）；没有真机时可用内置假服务
- （可选）K230 RTSP 流地址；没有摄像头时视频线程会每 3 秒重试，不影响其他功能

### 1. 修改配置

编辑 `springboot-demo/src/main/resources/application.properties`，按真机环境替换地址：

```properties
server.port=8080

# rosbridge 地址（真机改成树莓派 IP）
rosbridge.url=ws://192.168.1.100:9090

# ROS 2 话题
rosbridge.topic.cmd=/cmd_vel
rosbridge.topic.arm=/arm/command
rosbridge.topic.led=/led/cmd
rosbridge.topic.buzzer=/buzzer/cmd
rosbridge.topic.image_process=/image/process
rosbridge.topic.ai.input=/ai/input
rosbridge.topic.ai.reply=/ai/reply
rosbridge.topic.map=/map
rosbridge.topic.goal=/goal_pose

# 机械臂归位服务
rosbridge.service.arm_rehome=/arm/rehome

# K230 RTSP 流（真机改成 K230 IP）
k230.rtsp.url=rtsp://192.168.1.101:8554/k230
```

> ⚠️ 树莓派上的 `rosbridge_server` 若绑定在 `127.0.0.1`，局域网内无法连接，需改为 `0.0.0.0` 并放行 9090 端口。

### 2. 打包

在项目根目录（父 POM 所在处）：

```bash
mvn clean package
```

或只构建子模块：

```bash
mvn -pl springboot-demo -am clean package
```

产物：

```
springboot-demo/target/springboot-demo-1.0-SNAPSHOT.jar
```

> 首次构建会下载 JavaCV 全平台依赖（体积较大），耗时较长属正常现象。

### 3. 运行

```bash
java -jar springboot-demo/target/springboot-demo-1.0-SNAPSHOT.jar
```

也可以覆盖配置而不改代码：

```bash
java -jar springboot-demo/target/springboot-demo-1.0-SNAPSHOT.jar \
  --rosbridge.url=ws://192.168.1.100:9090 \
  --k230.rtsp.url=rtsp://192.168.1.101:8554/k230
```

开发阶段也可以直接在 IDE 中运行 `HelloApplication`。

### 4. 本地无真机联调

内置假 rosbridge 会监听 9090，并模拟 AI 回复：

```bash
java -cp springboot-demo/target/classes com.carbackend.fakeros.FakeRosbridgeServer
```

或直接在 IDE 中运行 `FakeRosbridgeServer#main`。随后启动后端即可走通完整链路（AI 回复会在 1 秒后返回）。

### 5. 验证

```bash
# 前进（App 需持续调用续期）
curl "http://localhost:8080/car/command?action=forward"

# 停车
curl "http://localhost:8080/car/command?action=stop"

# AI 对话
curl -X POST http://localhost:8080/car/ai/chat \
  -H "Content-Type: application/json" \
  -d '{"text":"向前走一点"}'
```

App 端连接 `ws://<host>:8080/ws/camera` 即可看到实时画面。

---

## 开发约定

- 所有类与公开方法保留**中文注释**
- 指令通过 `HashMap<String, CarCommand>` 映射（策略模式），未知指令统一抛 `CarCommandException`
- 所有 rosbridge 交互必须 `try-catch`，异常只记日志、不中断主流程
- 多线程共享集合统一使用并发容器（`CopyOnWriteArrayList` / `CopyOnWriteArraySet` / `ConcurrentHashMap`）

---

## Roadmap

- [ ] 接入 nav2 真实导航目标点下发
- [ ] 真实地图话题替换本地模拟地图
- [ ] 视频流 H.264 硬编以减少带宽
- [ ] 指令历史持久化（当前为内存存储）

---

## License

MIT
