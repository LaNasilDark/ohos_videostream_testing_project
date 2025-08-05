# OHOS 视频流测试项目

这是一个用于测试 OpenHarmony (OHOS) 设备视频流传输的项目，支持远程桌面和屏幕共享功能。

## 项目结构

```
├── launch.bat              # 启动脚本
├── remote_desktop_demo     # OHOS服务端可执行文件
├── classes/                # Java类文件
├── client/                 # Python客户端
│   ├── main.py
│   ├── image_receiver.py
│   └── ...
└── server/                 # OHOS服务端源码
    ├── src/
    ├── include/
    └── BUILD.gn
```

## 前置要求

### 必需软件

1. **OpenHarmony SDK** - 用于与OHOS设备通信
2. **HDC工具** - 华为设备连接工具，需要在PATH中可用
3. **Java Runtime Environment (JRE) 8+** - 运行Java客户端
4. **Python 3.7+** - 运行Python客户端

### ⚠️ 重要：下载必需的JAR文件

**在运行项目之前，你需要从 [Releases页面](https://github.com/LaNasilDark/ohos_videostream_testing_project/releases/tag/v1.0.1) 下载以下文件：**

- `ohos_videostream_testing_project-1.0.0-jar-with-dependencies.jar`

将下载的jar文件放在项目根目录下（与 `launch.bat`同级）。

## 快速开始

### 1. 准备设备

- 确保OHOS设备已连接并通过 `hdc list targets`可以检测到
- 设备需要开启开发者模式和USB调试

### 2. 运行项目

#### 方法一：自动检测设备

```bash
launch.bat
```

#### 方法二：指定设备ID

```bash
launch.bat [设备ID]
```

### 3. 客户端选项

项目支持多种客户端：

1. **Java客户端（推荐）**

   ```bash
   java -cp classes com.example.H264StreamReceiver [IP地址] 8000
   ```
2. **Python客户端**

   ```bash
   python ./client/main.py
   ```
3. **JAR客户端（无UI模式）**

   ```bash
   ohos_videostream_testing_project-1.0.0-jar-with-dependencies --noui [IP地址] 8000
   ```

## 功能特性

- 🖥️ **远程桌面** - 实时屏幕投射
- 🎥 **视频流传输** - H.264编码的高效视频流
- 🔧 **多客户端支持** - Java、Python多种客户端实现
- 📱 **OHOS原生支持** - 专为OpenHarmony系统优化

## 网络配置

### 默认端口

- 服务端监听端口：`8000`
- 协议：TCP

### 网络模式

1. **本地转发模式**（默认）

   ```bash
   hdc -t [设备ID] fport tcp:8000 tcp:8000
   ```
2. **局域网直连模式**

   - 确保PC和OHOS设备在同一网络
   - 使用设备的实际IP地址连接

## 故障排除

### 常见问题

1. **设备未检测到**

   ```bash
   # 检查设备连接
   hdc list targets

   # 重启HDC服务
   hdc kill
   hdc start
   ```
2. **连接被拒绝**

   - 检查防火墙设置
   - 确认端口转发是否正确
   - 验证服务端是否在设备上正常运行
3. **视频数据异常**

   - 检查网络稳定性
   - 确认H.264解码器可用性
   - 查看客户端日志输出
4. **JAR文件未找到**

   - 确认已从Releases页面下载jar文件
   - 检查文件名是否正确
   - 确认文件位于项目根目录

### 日志调试

服务端日志：

```bash
hdc -t [设备ID] shell "logcat | grep -i screen"
```

客户端会在控制台输出详细的连接和数据接收信息。

## 开发说明

### 编译服务端

```bash
# 在OHOS源码环境中
./build.sh --product-name [产品名] --build-target //path/to/server:scrcpyoh_server
```

### 客户端开发

- Java客户端：基于Socket和H.264解码
- Python客户端：支持实时显示和数据保存

## 许可证

本项目遵循相应的开源许可证，具体请查看源码文件头部的版权声明。

## 贡献

欢迎提交Issue和Pull Request来改进项目。

---

**注意：使用本项目前请确保已从Releases页面下载必需的JAR依赖文件！**
