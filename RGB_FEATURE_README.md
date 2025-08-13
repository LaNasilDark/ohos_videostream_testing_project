# RGB视频流功能说明

## 功能修改概述

本次修改将原本广播H.264 NALU数据的功能改为广播base64编码的RGB图像数据，使网页端能够直接显示视频图像。

## 主要修改内容

### 1. 后端修改 (H264StreamReceiver.java)

#### 新增字段
- `FFmpegFrameGrabber frameGrabber`: H.264解码器
- `Java2DFrameConverter frameConverter`: 帧格式转换器
- `ByteArrayOutputStream h264Buffer`: H.264数据缓冲区
- `List<byte[]> naluBuffer`: NALU单元缓冲区
- `byte[] spsData, ppsData`: SPS和PPS参数集

#### 新增方法
1. **`decodeAndSendFrame()`**: 解码NALU缓冲区并发送RGB帧
2. **`mergeNaluUnits()`**: 合并NALU单元成完整的H.264帧
3. **`decodeH264Frame()`**: 解码H.264帧为YUV格式
4. **`convertToRGB()`**: 将YUV帧转换为RGB图像
5. **`sendRGBImageAsBase64()`**: 将RGB图像转换为base64并通过WebSocket发送
6. **`broadcastToWebSocket()`**: 广播消息到WebSocket客户端
7. **`disposeRGBResources()`**: 释放RGB解码资源

#### 修改的方法
- **`processCompleteNalu()`**: 增加了NALU类型识别和缓冲逻辑
  - SPS/PPS参数集会被保存
  - IDR帧和非IDR帧会被缓冲并触发解码
  - 当收到IDR帧或缓冲区达到一定大小时触发解码和发送

### 2. WebSocket消息格式

**新的RGB帧消息格式**:
```json
{
  "type": "rgb_frame",
  "format": "png",
  "data": "base64编码的PNG图像数据",
  "width": 图像宽度,
  "height": 图像高度,
  "timestamp": 时间戳
}
```

### 3. 新增网页客户端 (rgb_client.html)

创建了专门用于接收和显示RGB视频流的HTML客户端，具有以下功能：
- **实时视频显示**: 使用Canvas显示从WebSocket接收的RGB图像
- **统计信息**: 显示帧率、分辨率、连接时长等
- **图像保存**: 支持保存当前显示的帧为PNG文件
- **自适应缩放**: 自动调整图像尺寸以适应Canvas大小
- **连接状态管理**: 显示连接状态和日志信息

## 技术流程

### RGB转换处理流程
1. **接收H.264流**: 从网络接收H.264编码的视频流
2. **NALU解析**: 识别并缓冲不同类型的NALU单元
3. **参数集保存**: 保存SPS和PPS参数集
4. **帧触发**: 当收到IDR帧或缓冲区满时触发解码
5. **帧合并**: 将SPS、PPS和图像NALU合并成完整的H.264帧
6. **H.264解码**: 使用FFmpeg解码H.264帧为YUV格式
7. **格式转换**: 将YUV帧转换为RGB BufferedImage
8. **图像编码**: 将RGB图像编码为PNG格式
9. **Base64编码**: 将PNG数据转换为Base64字符串
10. **WebSocket传输**: 通过WebSocket发送RGB数据给客户端
11. **网页显示**: 浏览器解码Base64数据并在Canvas上显示

### 关键特性
- **异步处理**: 使用CompletableFuture异步处理图像编码和发送
- **资源管理**: 自动释放FFmpeg解码器资源
- **错误处理**: 完善的异常处理和日志记录
- **兼容性**: 保留原有的帧组装器逻辑以保持兼容性

## 使用方法

### 1. 启动H.264流接收器
```bash
# GUI模式
java -jar udp-h264-1.0.0-jar-with-dependencies.jar

# 命令行模式（自动连接）
java -jar udp-h264-1.0.0-jar-with-dependencies.jar --noui 192.168.5.114 8000
```

### 2. 打开RGB视频流客户端
在浏览器中打开 `rgb_client.html`：
```
file:///path/to/rgb_client.html
```

### 3. 连接WebSocket
在网页中输入WebSocket地址（默认: `ws://localhost:8080`），点击"连接"按钮。

### 4. 查看RGB视频流
连接成功后，网页会自动显示解码后的RGB图像。

## 性能考虑

### 优点
- **直接显示**: 网页端可以直接显示图像，无需额外的解码
- **兼容性好**: PNG格式在所有浏览器中都有良好支持
- **功能丰富**: 支持图像保存、统计信息等功能

### 缺点
- **带宽消耗**: RGB数据比H.264数据大很多
- **CPU负载**: 增加了解码和编码的CPU消耗
- **内存使用**: 需要更多内存来处理图像数据
- **延迟**: 增加了解码和编码步骤，可能影响实时性

### 优化建议
1. **降低分辨率**: 可以在解码后缩放图像以减少数据传输量
2. **调整质量**: 可以调整PNG压缩质量以平衡文件大小和图像质量
3. **增加内存**: 建议增加JVM堆内存：`-Xmx2g`
4. **使用JPEG**: 对于不需要透明度的场景，可以改用JPEG格式以减少文件大小

## 文件清单

- **H264StreamReceiver.java**: 修改后的主程序，支持RGB转换和发送
- **rgb_client.html**: 新的RGB视频流网页客户端
- **enhanced_client.html**: 原有的H.264 NALU信息客户端（保持兼容）
- **client_test.html**: 简单的测试客户端（保持兼容）

## 故障排除

### 常见问题
1. **图像不显示**: 检查WebSocket连接状态和控制台错误信息
2. **帧率低**: 这是正常现象，RGB转换需要额外的处理时间
3. **内存不足**: 增加JVM内存分配
4. **解码失败**: 确保H.264流格式正确且包含必要的参数集

### 调试模式
启用详细日志：
```bash
java -Dverbose=true -jar udp-h264-1.0.0-jar-with-dependencies.jar --noui 192.168.5.114 8000
```

## 向后兼容性

- 原有的H.264 NALU广播功能被保留但默认禁用
- 可以通过修改代码重新启用原始NALU发送功能
- 所有原有的GUI功能和命令行参数保持不变
