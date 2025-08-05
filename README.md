# H.264 视频流接收器 (轻量化命令行版本)

一个轻量级的H.264视频流接收和处理工具，支持WebSocket实时传输。这是原程序的轻量化版本，去除了所有GUI组件，只保留命令行模式。

## 功能特性

- ✅ **H.264流接收**: 连接到TCP服务器接收H.264视频流
- ✅ **实时帧解析**: 自动识别和解析H.264 NALU单元
- ✅ **WebSocket传输**: 将视频帧通过WebSocket以Base64格式广播
- ✅ **多客户端支持**: 支持多个WebSocket客户端同时连接
- ✅ **实时统计**: 显示帧率、数据传输率、连接时长等统计信息
- ✅ **轻量化设计**: 纯命令行模式，无GUI依赖，资源占用低
- ✅ **详细日志**: 支持详细模式显示每帧处理信息

## 快速开始

### 系统要求

- Java 11 或更高版本
- Maven 3.6+ (用于构建)

### 构建项目

```bash
mvn clean package
```

### 运行程序

基本用法：
```bash
java -jar target/ohos_videostream_testing_project-1.0.0-jar-with-dependencies.jar <ip> <port>
```

指定WebSocket端口：
```bash
java -jar target/ohos_videostream_testing_project-1.0.0-jar-with-dependencies.jar <ip> <port> <ws_port>
```

启用详细日志：
```bash
java -Dverbose=true -jar target/ohos_videostream_testing_project-1.0.0-jar-with-dependencies.jar <ip> <port>
```

### 使用示例

1. **连接到本地H.264流服务器**：
   ```bash
   java -jar target/ohos_videostream_testing_project-1.0.0-jar-with-dependencies.jar 192.168.1.100 8000
   ```

2. **指定WebSocket端口**：
   ```bash
   java -jar target/ohos_videostream_testing_project-1.0.0-jar-with-dependencies.jar 192.168.5.114 8000 8081
   ```

3. **启用详细日志模式**：
   ```bash
   java -Dverbose=true -jar target/ohos_videostream_testing_project-1.0.0-jar-with-dependencies.jar 192.168.5.114 8000
   ```

## WebSocket接口

### 连接地址
```
ws://localhost:<ws_port>
```
默认端口: 8080

### 消息格式

客户端将收到JSON格式的帧数据：

```json
{
  "type": "frame",
  "data": "base64编码的帧数据",
  "nalType": 1,
  "nalDesc": "非IDR图像",
  "size": 1024,
  "timestamp": 1628123456789
}
```

### 字段说明

- `type`: 消息类型，固定为 "frame"
- `data`: Base64编码的H.264帧数据
- `nalType`: NALU类型编号
- `nalDesc`: NALU类型描述
- `size`: 原始帧数据大小（字节）
- `timestamp`: 时间戳（毫秒）

### NALU类型说明

| 类型编号 | 描述 |
|---------|------|
| 1 | 非IDR图像 |
| 5 | IDR图像 |
| 6 | SEI |
| 7 | SPS参数 |
| 8 | PPS参数 |
| 其他 | 其他类型 |

## 测试客户端

项目包含两个HTML测试客户端：

1. **简单客户端**: `client_test.html` - 基础功能测试
2. **增强客户端**: `enhanced_client.html` - 功能完整，界面美观

使用方法：
1. 启动H.264流接收器
2. 在浏览器中打开测试客户端
3. 输入WebSocket地址（默认: ws://localhost:8080）
4. 点击"连接"开始接收数据

## 程序输出示例

```
=== H.264 视频流接收器 (命令行版本) ===
目标服务器: 192.168.5.114:8000
WebSocket端口: 8080
按 Ctrl+C 退出程序
=====================================
[2025-08-05 17:30:15.123] WebSocket服务器启动成功,监听端口: 8080
[2025-08-05 17:30:15.456] 成功连接到服务器 192.168.5.114:8000

[实时统计] 帧数: 125 | 帧率: 25.00 fps | 数据率: 512.5 KB/s | 总量: 2 MB | WebSocket: 1 客户端
```

## 架构设计

```
┌─────────────────┐    TCP     ┌──────────────────┐    WebSocket    ┌─────────────────┐
│  H.264 流服务器  │ ──────────→ │ H.264流接收器     │ ──────────────→ │  Web 客户端      │
│                │            │                 │                │                │
│ (视频源)        │            │ • 帧解析         │                │ • 实时显示       │
│                │            │ • 统计分析       │                │ • 数据监控       │
└─────────────────┘            │ • WebSocket广播  │                │ • 帧信息查看     │
                              └──────────────────┘                └─────────────────┘
```

## 性能特点

- **内存占用低**: 纯命令行模式，无GUI组件
- **处理能力强**: 高效的帧解析算法
- **实时性好**: 低延迟的数据处理和传输
- **稳定性高**: 完善的错误处理和资源管理

## 项目结构

```
src/main/java/com/LaNasil/
└── H264StreamReceiver.java    # 主程序类

测试文件:
├── client_test.html           # 简单测试客户端
├── enhanced_client.html       # 增强测试客户端
└── README.md                 # 项目文档
```

## 依赖项

项目使用以下主要依赖：

- **Java-WebSocket**: WebSocket服务器实现
- **Maven Assembly Plugin**: 打包所有依赖到单个JAR文件

## 版本更新

### v2.0.0 (轻量化版本)
- ✅ 移除所有GUI组件（Swing/AWT）
- ✅ 移除视频渲染功能（JavaCV依赖）
- ✅ 简化程序架构，只保留核心功能
- ✅ 优化命令行参数处理
- ✅ 改进统计信息显示
- ✅ 增强测试客户端功能

### v1.0.0 (原版本)
- GUI模式支持
- 视频实时渲染
- 命令行和GUI双模式

## 许可证

MIT License

## 贡献

欢迎提交Issue和Pull Request！
