# H264 视频流接收器

这是一个基于 Java Swing 和 [JavaCV](https://github.com/bytedeco/javacv) 库构建的H.264视频流接收和播放应用程序。

主要功能是从网络接收H.264视频流数据，进行实时解码和显示，同时通过WebSocket将解码后的图像以Base64格式实时广播给多个客户端。适用于视频监控、流媒体接收、远程视频观看和视频流分析等场景。

## 核心功能

- **网络流接收**: 支持TCP连接接收H.264视频流数据
- **实时视频播放**: 使用FFmpeg进行H.264解码和实时渲染显示
- **WebSocket流传输**: 将解码后的图像以Base64 PNG格式实时广播
- **多客户端支持**: 支持多个WebSocket客户端同时连接观看
- **双模式运行**: 支持GUI模式和无头命令行模式
- **帧数据广播**: 同时广播原始NALU数据和完整H.264帧
- **性能监控**: 实时显示帧率、数据传输率等统计信息
- **NALU分析**: 识别并显示H.264 NALU单元类型（SPS、PPS、IDR帧等）
- **命令行支持**: 支持命令行参数自动连接到指定服务器

## 项目结构

```text
ohos_videostream_testing_project/
├── pom.xml                               # Maven 配置文件
├── README.md                             # 项目说明文档
├── video_client.html                     # WebSocket客户端 - 完整功能版
├── simple_video_client.html              # WebSocket客户端 - 简化版
├── doablerelease/                        # 发布版本
│   ├── udp-h264-1.0.0-jar-with-dependencies.jar
│   └── vlc-module/                       # VLC运行时库（可选）
├── nalu_output/                          # NALU单元输出文件夹
└── src/
    └── main/
        └── java/
            └── com/
                └── LaNasil/
                    ├── H264StreamReceiver.java    # 主程序 - H.264流接收器
                    ├── H264FrameAssembler.java     # H.264帧组装器
                    └── H264NaluSplitter.java      # H.264 NALU分割器
```

## 核心组件

### H264StreamReceiver (主程序)

- **功能**: H.264视频流的网络接收、解码、显示和WebSocket广播
- **特性**:
  - 图形化用户界面，支持连接控制
  - 实时视频渲染窗口
  - WebSocket服务器，支持多客户端连接
  - 统计信息显示（帧率、数据率、客户端数量）
  - 详细的日志记录
  - 支持命令行参数自动连接
  - 无头模式运行支持

### H264VideoRenderer (内嵌类)

- **功能**: 使用JavaCV进行H.264解码和视频渲染，同时进行Base64广播
- **特性**:
  - 独立解码线程，提高性能
  - 自适应画面缩放
  - 帧计数显示
  - 解码图像Base64编码并WebSocket广播
  - 支持PNG格式输出

### H264FrameAssembler

- **功能**: H.264流的帧组装和分析
- **特性**:
  - 完整帧组装和验证
  - 关键帧识别
  - 参数集管理（SPS/PPS）

### WebSocket广播系统

- **功能**: 实时视频流数据广播
- **支持的数据类型**:
  - `decoded_frame`: 解码后的PNG图像（Base64编码）
  - `complete_frame`: 完整的H.264帧数据
  - `frame`: 原始NALU单元数据

## 技术要求

- **Java Development Kit (JDK)**: 11 或更高版本
- **Maven**: 3.6 或更高版本用于构建项目
- **系统架构**: 支持64位Windows/Linux系统
- **网络环境**: 能够访问H.264视频流源

## 运行方式

### 1. IDE 中运行

1. 使用 IDE (VS Code, IntelliJ IDEA, Eclipse) 打开项目
2. IDE 会自动识别为 Maven 项目并下载依赖
3. 找到 `src/main/java/com/LaNasil/H264StreamReceiver.java`
4. 直接运行 `main` 方法启动程序

### 2. 命令行运行

#### 编译和打包

```bash
# 在项目根目录下执行
mvn clean compile assembly:single
```

这会在 `target` 目录下生成包含所有依赖的可执行JAR文件：
`udp-h264-1.0.0-jar-with-dependencies.jar`

#### 启动程序

```bash
# 方式1: 直接启动GUI界面
java -jar target/udp-h264-1.0.0-jar-with-dependencies.jar

# 方式2: 使用命令行参数自动连接（GUI模式）
java -jar target/udp-h264-1.0.0-jar-with-dependencies.jar <服务器IP> <端口号>

# 方式3: 无头模式运行（推荐用于服务器部署）
java -jar target/udp-h264-1.0.0-jar-with-dependencies.jar --noui <服务器IP> <端口号>

# 方式4: 无头模式 + 详细日志
java -Dverbose=true -jar target/udp-h264-1.0.0-jar-with-dependencies.jar --noui <服务器IP> <端口号>

# 示例
java -jar target/udp-h264-1.0.0-jar-with-dependencies.jar 192.168.5.114 8000
java -jar target/udp-h264-1.0.0-jar-with-dependencies.jar --noui 192.168.5.114 8000
```

### 3. 使用Maven插件运行

```bash
# 直接通过Maven运行
mvn exec:java

# 带参数运行
mvn exec:java -Dexec.args="192.168.5.114 8000"
mvn exec:java -Dexec.args="--noui 192.168.5.114 8000"
```

## WebSocket客户端使用

程序启动后会自动在端口8080启动WebSocket服务器。可以使用提供的HTML客户端或自定义客户端连接：

### 使用内置HTML客户端

1. **完整功能版本** (`video_client.html`):
   - 打开浏览器访问 `video_client.html`
   - 输入WebSocket地址: `ws://localhost:8080`
   - 点击连接按钮
   - 查看实时视频流和详细统计信息

2. **简化版本** (`simple_video_client.html`):
   - 打开浏览器访问 `simple_video_client.html`
   - 专注于视频显示，界面更简洁

### WebSocket消息格式

客户端会接收到以下格式的JSON消息：

```json
// 解码后的图像（主要显示内容）
{
  "type": "decoded_frame",
  "data": "base64编码的PNG图像",
  "frameNumber": 123,
  "width": 1920,
  "height": 1080,
  "format": "PNG",
  "timestamp": 1691234567890
}

// 完整的H.264帧
{
  "type": "complete_frame",
  "data": "base64编码的H.264帧数据",
  "frameType": "关键帧/普通帧",
  "size": 12345,
  "frameNumber": 123,
  "timestamp": 1691234567890
}

// 原始NALU单元
{
  "type": "frame",
  "data": "base64编码的NALU数据",
  "nalType": 5,
  "nalDesc": "IDR帧",
  "size": 8192,
  "timestamp": 1691234567890
}
```

## 使用说明

### GUI操作界面

1. **H.264流连接设置**:
   - 服务器地址: 输入H.264流服务器的IP地址
   - 端口: 输入服务器端口号（默认8000）

2. **WebSocket服务设置**:
   - WebSocket端口: 设置WebSocket服务器端口（默认8080）
   - 客户端数量显示: 实时显示连接的WebSocket客户端数量

3. **控制按钮**:
   - `连接`: 连接到指定的流服务器
   - `断开连接`: 断开当前连接
   - `显示视频`: 打开/关闭视频播放窗口
   - `启动WS服务`: 启动/停止WebSocket服务器
   - `清空日志`: 清除日志内容并重置统计信息

4. **统计信息**:
   - 实时显示接收帧率 (fps)
   - 实时显示数据传输率 (KB/s)
   - WebSocket客户端连接数量

5. **日志区域**:
   - 显示连接状态、接收到的NALU类型
   - 显示WebSocket客户端连接/断开信息
   - 显示错误信息和调试信息

### 无头模式操作

无头模式下程序会：

- 自动启动WebSocket服务器（端口8080）
- 自动连接到指定的H.264流服务器
- 在控制台实时显示统计信息
- 支持Ctrl+C优雅退出

控制台输出示例：

```text
=== H.264 视频流接收器 (命令行模式) ===
目标服务器: 192.168.5.114:8000
WebSocket端口: 8080
按 Ctrl+C 退出程序
=====================================

[实时统计] 帧数: 150 | 帧率: 25.3 fps | 数据率: 1024.5 KB/s | 总量: 15 MB | WebSocket: 2 客户端
```

### 视频窗口功能

- 实时显示解码后的H.264视频
- 自动适配窗口大小，保持视频比例
- 显示已解码帧数统计
- 支持独立窗口操作

### WebSocket客户端功能

1. **完整版客户端** (`video_client.html`):
   - 实时视频显示
   - 详细的统计信息（帧率、数据量、连接状态）
   - 实时日志记录
   - 帧信息显示
   - 支持多种数据类型显示

2. **简化版客户端** (`simple_video_client.html`):
   - 专注于视频显示
   - 基本统计信息
   - 轻量级，适合直接观看

## 技术特点

### 高性能架构

- **多线程设计**: 网络接收线程与视频解码线程分离，确保流畅的数据处理
- **管道流处理**: 使用管道流在接收线程和解码线程间传输数据，减少内存拷贝
- **缓冲优化**: 1MB管道缓冲区，适应网络抖动和解码速度差异
- **异步WebSocket**: 非阻塞的WebSocket广播，不影响视频解码性能

### 智能NALU识别和帧组装

- 自动识别H.264起始码（0x00000001 / 0x000001）
- 解析NALU类型：
  - **SPS (7)**: 序列参数集
  - **PPS (8)**: 图像参数集
  - **IDR帧 (5)**: 关键帧
  - **非IDR帧 (1)**: 普通帧
  - **SEI (6)**: 补充增强信息
- 完整帧组装和验证
- 关键帧识别和标记

### 实时统计监控

- 动态计算并显示帧率 (FPS)
- 实时数据传输速率监控 (KB/s)
- WebSocket客户端连接数监控
- 总接收字节数和帧数统计
- 详细的时间戳日志记录

### WebSocket实时广播

- 支持多客户端同时连接
- 三种数据类型广播：
  - 解码后的PNG图像（Base64编码）
  - 完整的H.264帧数据
  - 原始NALU单元数据
- JSON格式消息，易于解析
- 自动客户端连接管理

## 故障排除

### 常见问题

1. **连接失败**

   ```text
   错误: 无法连接到服务器 192.168.x.x:8000
   ```

   - 检查服务器IP地址和端口是否正确
   - 确认服务器正在运行并监听指定端口
   - 检查防火墙设置是否阻止了连接

2. **视频无法显示**

   ```text
   解码线程异常: xxxxx
   ```

   - 确认接收到的数据是有效的H.264流
   - 检查流是否包含必要的SPS/PPS参数集
   - 验证JavaCV依赖是否正确安装

3. **WebSocket连接问题**

   ```text
   WebSocket连接失败 / 客户端无法接收数据
   ```

   - 确认WebSocket服务器已启动（检查端口8080）
   - 检查防火墙是否阻止WebSocket端口
   - 确认浏览器支持WebSocket
   - 检查网络连接是否稳定

4. **性能问题**
   - 如果出现丢帧现象，可能是网络带宽不足
   - 检查CPU使用率，解码H.264需要一定的计算资源
   - 考虑调整缓冲区大小以适应网络条件
   - WebSocket客户端过多可能影响性能

### 调试建议

- 启用详细日志记录：使用 `-Dverbose=true` 参数
- 查看NALU类型和帧信息
- 使用网络抓包工具验证数据传输
- 检查WebSocket消息格式是否正确
- 监控内存使用情况，特别是在长时间运行时

### 端口使用说明

- **8000**: H.264流服务器端口（可配置）
- **8080**: WebSocket服务器端口（可配置）
- 确保这些端口没有被其他程序占用

## 开发扩展

### 自定义功能

可以通过修改源代码来扩展功能：

1. **支持其他编解码格式**: 修改JavaCV配置支持H.265等
2. **添加录制功能**: 扩展保存格式，支持MP4等容器
3. **网络协议扩展**: 支持UDP、RTSP等其他协议
4. **图像处理**: 在解码后添加滤镜、缩放等处理
5. **WebSocket协议扩展**: 添加控制命令支持
6. **多路流支持**: 同时接收多个视频流

### 自定义WebSocket客户端

可以创建自定义的WebSocket客户端来接收视频数据：

```javascript
const ws = new WebSocket('ws://localhost:8080');

ws.onmessage = function(event) {
    const message = JSON.parse(event.data);
    
    if (message.type === 'decoded_frame') {
        // 显示解码后的PNG图像
        const img = document.createElement('img');
        img.src = `data:image/png;base64,${message.data}`;
        document.body.appendChild(img);
    }
};
```

### 依赖说明

- **JavaCV**: 提供FFmpeg Java绑定，用于视频解码
- **Java-WebSocket**: WebSocket服务器实现
- **ImageIO**: 图像格式转换（PNG编码）
- **Base64**: 数据编码传输
- **Swing**: GUI界面框架（可选，无头模式不需要）

### 部署建议

1. **服务器部署**: 推荐使用无头模式运行
2. **容器化**: 可以打包为Docker镜像部署
3. **负载均衡**: 支持多实例部署，通过负载均衡分发客户端连接
4. **监控**: 建议添加健康检查和性能监控

## 性能优化建议

- **内存优化**: 定期清理缓冲区，避免内存泄漏
- **网络优化**: 调整TCP缓冲区大小
- **解码优化**: 根据硬件能力调整解码线程数
- **WebSocket优化**: 限制客户端连接数，避免过载

## 许可证

本项目仅供学习和研究使用。请遵守相关的开源许可证要求。

## 更新日志

### v1.1.0

- ✅ 新增WebSocket实时视频流广播功能
- ✅ 新增Base64编码的PNG图像传输
- ✅ 新增无头模式支持
- ✅ 新增多客户端连接支持
- ✅ 新增HTML客户端示例
- ✅ 优化帧组装和验证逻辑
- ✅ 改进错误处理和日志记录

### v1.0.0

- ✅ 基础H.264流接收和解码功能
- ✅ 实时视频显示
- ✅ NALU单元分析
- ✅ 统计信息显示
