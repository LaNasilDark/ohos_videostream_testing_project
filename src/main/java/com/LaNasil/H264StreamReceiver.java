package com.LaNasil;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Base64;

/**
 * H.264 流接收器 - 轻量版
 * 无UI版本，专注于H.264流接收、解码和WebSocket广播
 * 适用于服务器部署和嵌入式系统
 */
public class H264StreamReceiver {

    private static final int DEFAULT_WS_PORT = 8080;

    // 视频渲染组件
    private H264VideoRenderer videoRenderer;

    // 网络和线程
    private Socket clientSocket;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private Thread receiverThread;

    // WebSocket 相关
    private WebSocketServer webSocketServer;
    private final Set<WebSocket> webSocketClients = new CopyOnWriteArraySet<>();

    // 统计跟踪
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong frameCount = new AtomicLong(0);
    private long startTime;
    private long lastStatsUpdate;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    // H.264帧组装器
    private H264FrameAssembler frameAssembler;

    // 连接参数
    private String targetHost;
    private int targetPort;
    private int wsPort;

    /**
     * 构造函数
     * 
     * @param host   H.264流服务器地址
     * @param port   H.264流服务器端口
     * @param wsPort WebSocket服务器端口
     */
    public H264StreamReceiver(String host, int port, int wsPort) {
        this.targetHost = host;
        this.targetPort = port;
        this.wsPort = wsPort;

        initialize();
    }

    /**
     * 构造函数，使用默认WebSocket端口
     * 
     * @param host H.264流服务器地址
     * @param port H.264流服务器端口
     */
    public H264StreamReceiver(String host, int port) {
        this(host, port, DEFAULT_WS_PORT);
    }

    /**
     * 初始化
     */
    private void initialize() {
        System.out.println("=== H.264 视频流接收器 - 轻量版 ===");
        System.out.println("目标服务器: " + targetHost + ":" + targetPort);
        System.out.println("WebSocket端口: " + wsPort);
        System.out.println("按 Ctrl+C 退出程序");
        System.out.println("=====================================");

        // 初始化帧组装器
        initializeFrameAssembler();

        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭程序...");
            disconnectFromServer();
            stopWebSocketServer();
            disposeVideoResources();
            System.out.println("程序已关闭");
        }));
    }

    /**
     * 启动服务
     */
    public void start() {
        // 启动WebSocket服务器
        startWebSocketServer();

        // 连接到H.264流服务器
        connectToServer(targetHost, targetPort);
    }

    /**
     * 启动WebSocket服务器
     */
    private void startWebSocketServer() {
        try {
            webSocketServer = new WebSocketServer(new InetSocketAddress(wsPort)) {
                @Override
                public void onOpen(WebSocket conn, ClientHandshake handshake) {
                    webSocketClients.add(conn);
                    logMessage("WebSocket客户端连接: " + conn.getRemoteSocketAddress());
                }

                @Override
                public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                    webSocketClients.remove(conn);
                    logMessage("WebSocket客户端断开: " + conn.getRemoteSocketAddress() +
                            " (代码:" + code + ", 原因:" + reason + ")");
                }

                @Override
                public void onMessage(WebSocket conn, String message) {
                    logMessage("收到WebSocket消息 [" + conn.getRemoteSocketAddress() + "]: " + message);
                }

                @Override
                public void onError(WebSocket conn, Exception ex) {
                    logMessage("WebSocket错误 [" + (conn != null ? conn.getRemoteSocketAddress() : "未知") + "]: "
                            + ex.getMessage());
                }

                @Override
                public void onStart() {
                    logMessage("WebSocket服务器启动成功,监听端口: " + wsPort);
                }
            };

            webSocketServer.start();

        } catch (Exception ex) {
            System.err.println("启动WebSocket服务器失败: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    /**
     * 连接到H.264流服务器
     */
    private void connectToServer(String host, int port) {
        if (isConnected.get()) {
            return;
        }

        try {
            clientSocket = new Socket();
            clientSocket.connect(new InetSocketAddress(host, port), 5000);
            isConnected.set(true);

            resetStatistics();
            startTime = System.currentTimeMillis();
            lastStatsUpdate = startTime;

            logMessage("成功连接到服务器 " + host + ":" + port);

            // 创建视频渲染器用于解码和Base64广播
            videoRenderer = new H264VideoRenderer(this);
            videoRenderer.start();

            // 立即显示初始统计信息
            System.out.println(); // 为统计信息预留一行
            printStats();

            // 启动接收线程
            receiverThread = new Thread(this::receiveH264Stream, "H264-Receiver-Thread");
            receiverThread.start();

            // 启动统计显示线程
            Thread statsThread = new Thread(this::printStatsLoop, "Stats-Thread");
            statsThread.setDaemon(true);
            statsThread.start();

        } catch (IOException e) {
            System.err.println("无法连接到服务器 " + host + ":" + port + " - " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 接收H.264流数据
     */
    private void receiveH264Stream() {
        try (BufferedInputStream inputStream = new BufferedInputStream(clientSocket.getInputStream())) {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream streamBuffer = new ByteArrayOutputStream();

            // 状态机变量用于精确解析NALU边界
            int state = 0;
            int lastNaluStart = -1;

            while (isConnected.get() && !Thread.currentThread().isInterrupted()) {
                int bytesRead = inputStream.read(buffer);
                if (bytesRead == -1) {
                    logMessage("服务器连接已关闭");
                    break;
                }

                // 将读取的数据添加到流缓冲区
                streamBuffer.write(buffer, 0, bytesRead);
                byte[] currentData = streamBuffer.toByteArray();

                // 使用状态机解析NALU单元
                int processedBytes = parseNalusWithStateMachine(currentData, lastNaluStart, state);

                // 保留未处理的数据
                if (processedBytes > 0 && processedBytes < currentData.length) {
                    byte[] remainingData = Arrays.copyOfRange(currentData, processedBytes, currentData.length);
                    streamBuffer.reset();
                    streamBuffer.write(remainingData);
                }

                updateStatistics(bytesRead);

                // 实时更新统计显示
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastStatsUpdate >= 300) {
                    printStats();
                    lastStatsUpdate = currentTime;
                }
            }

            // 处理剩余的数据
            byte[] remainingData = streamBuffer.toByteArray();
            if (remainingData.length > 0) {
                processCompleteNalu(remainingData);
            }

            // 强制完成当前帧
            if (frameAssembler != null) {
                frameAssembler.forceCompleteFrame();
            }

        } catch (IOException e) {
            if (isConnected.get()) {
                logMessage("接收数据时发生错误: " + e.getMessage());
            }
        } finally {
            logMessage("H.264流接收已停止");
            disconnectFromServer();
        }
    }

    /**
     * 使用状态机精确解析NALU单元
     */
    private int parseNalusWithStateMachine(byte[] data, int lastStart, int initialState) {
        int state = initialState;
        int lastNaluStart = lastStart;
        int processedBytes = 0;

        for (int i = 0; i < data.length; i++) {
            switch (state) {
                case 0: // 寻找第一个0x00
                    if (data[i] == 0x00) {
                        state = 1;
                    }
                    break;

                case 1: // 寻找第二个0x00
                    if (data[i] == 0x00) {
                        state = 2;
                    } else {
                        state = 0;
                    }
                    break;

                case 2: // 可能是3字节起始码或4字节起始码
                    if (data[i] == 0x00) {
                        state = 3; // 可能是4字节起始码
                    } else if (data[i] == 0x01) {
                        // 找到3字节起始码 (00 00 01)
                        if (lastNaluStart != -1) {
                            // 处理前一个NALU
                            byte[] naluData = Arrays.copyOfRange(data, lastNaluStart, i - 2);
                            processCompleteNalu(naluData);
                            processedBytes = i - 2;
                        }
                        lastNaluStart = i - 2;
                        state = 0;
                    } else {
                        state = 0;
                    }
                    break;

                case 3: // 寻找4字节起始码的0x01
                    if (data[i] == 0x01) {
                        // 找到4字节起始码 (00 00 00 01)
                        if (lastNaluStart != -1) {
                            // 处理前一个NALU
                            byte[] naluData = Arrays.copyOfRange(data, lastNaluStart, i - 3);
                            processCompleteNalu(naluData);
                            processedBytes = i - 3;
                        }
                        lastNaluStart = i - 3;
                        state = 0;
                    } else if (data[i] != 0x00) {
                        state = 0;
                    }
                    // 如果是0x00，保持在state 3
                    break;
            }
        }

        return processedBytes;
    }

    /**
     * 处理完整的NALU单元
     */
    private void processCompleteNalu(byte[] naluData) {
        if (naluData == null || naluData.length < 4) {
            return;
        }

        // 验证起始码
        if (!isValidStartCode(naluData, 0)) {
            logMessage("警告: NALU数据不包含有效起始码");
            return;
        }

        // 获取NALU类型和描述
        int startCodeLen = getStartCodeLength(naluData, 0);
        if (startCodeLen > 0 && naluData.length > startCodeLen) {
            byte nalHeader = naluData[startCodeLen];
            int nalType = nalHeader & 0x1F;
            String naluDesc = H264FrameAssembler.getNaluTypeDescription(nalType);

            // 处理NALU单元
            if (frameAssembler != null) {
                frameAssembler.processNALU(naluData);
            }

            // 详细日志（可选）
            if (System.getProperty("verbose") != null) {
                logMessage(String.format("解析NALU: 类型=%d (%s), 大小=%d字节",
                        nalType, naluDesc, naluData.length));
            }
        }
    }

    /**
     * 验证起始码是否有效
     */
    private boolean isValidStartCode(byte[] data, int pos) {
        if (data.length < pos + 3) {
            return false;
        }

        // 检查3字节起始码 (00 00 01)
        if (pos + 2 < data.length &&
                data[pos] == 0x00 && data[pos + 1] == 0x00 && data[pos + 2] == 0x01) {
            return true;
        }

        // 检查4字节起始码 (00 00 00 01)
        if (pos + 3 < data.length &&
                data[pos] == 0x00 && data[pos + 1] == 0x00 &&
                data[pos + 2] == 0x00 && data[pos + 3] == 0x01) {
            return true;
        }

        return false;
    }

    /**
     * 获取起始码长度
     */
    private int getStartCodeLength(byte[] data, int pos) {
        if (pos + 3 < data.length && data[pos] == 0x00 && data[pos + 1] == 0x00 && data[pos + 2] == 0x00
                && data[pos + 3] == 0x01) {
            return 4;
        }
        if (pos + 2 < data.length && data[pos] == 0x00 && data[pos + 1] == 0x00 && data[pos + 2] == 0x01) {
            return 3;
        }
        return 0;
    }

    /**
     * 统计信息显示循环
     */
    private void printStatsLoop() {
        while (isConnected.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(200); // 每200毫秒检查一次，实现更流畅的更新
                // 只有在数据发生变化时才重新打印统计信息
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastStatsUpdate >= 500) {
                    printStats();
                    lastStatsUpdate = currentTime;
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * 打印统计信息到控制台
     */
    private void printStats() {
        long elapsedTime = System.currentTimeMillis() - startTime;
        if (elapsedTime > 0) {
            double fps = (frameCount.get() * 1000.0) / elapsedTime;
            double dataRateKBps = (totalBytesReceived.get() * 1000.0) / (elapsedTime * 1024.0);
            long totalMB = totalBytesReceived.get() / (1024 * 1024);

            // 使用 \r 实现原地更新，让统计信息实时刷新
            System.out.printf("\r[实时统计] 帧数: %d | 帧率: %.2f fps | 数据率: %.2f KB/s | 总量: %d MB | WebSocket: %d 客户端",
                    frameCount.get(), fps, dataRateKBps, totalMB, webSocketClients.size());
            System.out.flush();

            // 每10秒打印一次详细的NALU统计
            if (elapsedTime % 10000 < 500 && System.getProperty("verbose") != null) {
                System.out.println(); // 换行
                logNaluStatistics();
            }
        }
    }

    /**
     * 调试方法：打印NALU解析统计信息
     */
    private void logNaluStatistics() {
        if (frameAssembler != null) {
            logMessage("帧组装器状态: " + frameAssembler.getFrameStats());
        }
    }

    /**
     * 初始化帧组装器
     */
    private void initializeFrameAssembler() {
        frameAssembler = new H264FrameAssembler(new H264FrameAssembler.FrameCallback() {
            @Override
            public void onFrameComplete(byte[] frameData, boolean isKeyFrame, long frameNumber) {
                handleCompleteFrame(frameData, isKeyFrame, frameNumber);
            }

            @Override
            public void onParameterSetsReceived(List<byte[]> parameterSets) {
                logMessage("收到参数集回调: SPS/PPS，总共 " + parameterSets.size() + " 个NALU");
                for (byte[] nalu : parameterSets) {
                    if (nalu == null || nalu.length == 0)
                        continue;

                    // 获取NALU类型来区分SPS和PPS
                    int startCodeLen = getStartCodeLength(nalu, 0);
                    if (nalu.length > startCodeLen) {
                        int nalType = nalu[startCodeLen] & 0x1F;

                        if (nalType == 7) { // SPS NALU
                            logMessage(String.format("已捕获并存储 SPS: 大小=%d字节", nalu.length));
                        } else if (nalType == 8) { // PPS NALU
                            logMessage(String.format("已捕获并存储 PPS: 大小=%d字节", nalu.length));
                        }
                    }
                }
            }
        });
    }

    /**
     * 处理完整的组装帧
     */
    private void handleCompleteFrame(byte[] frameData, boolean isKeyFrame, long frameNumber) {
        // 验证帧数据的有效性
        if (!H264FrameAssembler.isValidH264Frame(frameData)) {
            logMessage("警告: 帧数据可能不完整或无效，帧号=" + frameNumber);
            return;
        }

        // 发送到视频渲染器进行解码显示
        if (videoRenderer != null) {
            videoRenderer.renderFrame(frameData);
        }

        // 更新统计
        frameCount.incrementAndGet();

        String frameType = isKeyFrame ? "关键帧" : "普通帧";
        logMessage(String.format("处理完整帧: 帧号=%d, 类型=%s, 大小=%d 字节, 有效性=✓, WS客户端=%d",
                frameNumber, frameType, frameData.length, webSocketClients.size()));
    }

    /**
     * 通用的WebSocket广播方法
     */
    public void broadcastToWebSocketClients(String jsonMessage) {
        if (!webSocketClients.isEmpty()) {
            webSocketClients.removeIf(client -> {
                try {
                    if (client.isOpen()) {
                        client.send(jsonMessage);
                        return false; // 保留连接
                    } else {
                        return true; // 移除断开的连接
                    }
                } catch (Exception e) {
                    logMessage("WebSocket发送失败: " + e.getMessage());
                    return true; // 移除出错的连接
                }
            });
        }
    }

    /**
     * 使用接收到的数据更新统计
     */
    private void updateStatistics(int bytesReceived) {
        totalBytesReceived.addAndGet(bytesReceived);
    }

    /**
     * 重置所有统计计数器
     */
    private void resetStatistics() {
        totalBytesReceived.set(0);
        frameCount.set(0);

        // 重置帧组装器
        if (frameAssembler != null) {
            frameAssembler.reset();
        }
    }

    /**
     * 断开服务器连接并清理资源
     */
    private void disconnectFromServer() {
        if (!isConnected.compareAndSet(true, false)) {
            return;
        }

        if (receiverThread != null) {
            receiverThread.interrupt();
        }
        if (videoRenderer != null) {
            videoRenderer.stop();
        }

        cleanupConnection();

        String message = "已断开服务器连接";
        logMessage(message);
    }

    /**
     * 清理连接资源
     */
    private void cleanupConnection() {
        try {
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException e) {
            String errorMessage = "清理连接时发生错误: " + e.getMessage();
            logMessage(errorMessage);
        } finally {
            clientSocket = null;
        }
    }

    /**
     * 停止 WebSocket 服务器
     */
    private void stopWebSocketServer() {
        if (webSocketServer != null) {
            try {
                webSocketServer.stop(1000);
                webSocketClients.clear();
                logMessage("WebSocket服务器已停止");
            } catch (Exception ex) {
                String errorMessage = "停止WebSocket服务器时出错: " + ex.getMessage();
                logMessage(errorMessage);
                ex.printStackTrace();
            }
        }
    }

    /**
     * 释放视频渲染资源
     */
    private void disposeVideoResources() {
        if (videoRenderer != null) {
            videoRenderer.dispose();
        }
    }

    /**
     * 日志记录
     */
    private void logMessage(String message) {
        String timestamp = dateFormat.format(new Date());
        String logEntry = "[" + timestamp + "] " + message;
        System.out.println(logEntry);
    }

    /**
     * 验证IP地址
     */
    private static boolean isValidIpAddress(String ip) {
        if (ip == null || ip.trim().isEmpty())
            return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4)
            return false;
        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255)
                    return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 验证端口号
     */
    private static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    /**
     * 打印使用说明
     */
    private static void printUsage() {
        System.out.println("H.264 视频流接收器 - 轻量版");
        System.out.println("=============================");
        System.out.println("使用方法:");
        System.out.println("  java -jar <jarfile> <ip> <port> [ws_port]");
        System.out.println("\n参数说明:");
        System.out.println("  ip       - 服务器IP地址 (例如: 192.168.1.100)");
        System.out.println("  port     - 服务器端口号 (1-65535)");
        System.out.println("  ws_port  - WebSocket端口号 (可选，默认8080)");
        System.out.println("\n功能特性:");
        System.out.println("  • 自动启动WebSocket服务器");
        System.out.println("  • 自动连接到H.264流服务器");
        System.out.println("  • 实时视频解码和Base64广播");
        System.out.println("  • 支持多客户端连接");
        System.out.println("  • 实时统计信息显示");
        System.out.println("  • 使用 -Dverbose=true 启用详细日志");
        System.out.println("  • 按 Ctrl+C 退出程序");
        System.out.println("\n示例:");
        System.out.println("  java -jar receiver-lite.jar 192.168.5.114 8000");
        System.out.println("  java -jar receiver-lite.jar 192.168.5.114 8000 9090");
        System.out.println("  java -Dverbose=true -jar receiver-lite.jar 192.168.5.114 8000");
    }

    /**
     * 主函数
     */
    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("错误: 参数数量不正确。");
            printUsage();
            return;
        }

        try {
            String host = args[0].trim();
            int port = Integer.parseInt(args[1].trim());
            int wsPort = args.length == 3 ? Integer.parseInt(args[2].trim()) : DEFAULT_WS_PORT;

            if (!isValidIpAddress(host) || !isValidPort(port) || !isValidPort(wsPort)) {
                System.err.println("错误: 无效的IP地址或端口号。");
                printUsage();
                return;
            }

            // 创建并启动接收器
            H264StreamReceiver receiver = new H264StreamReceiver(host, port, wsPort);
            receiver.start();

            // 保持程序运行
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                System.out.println("\n程序被中断");
            }

        } catch (NumberFormatException e) {
            System.err.println("错误: 端口号必须是数字。");
            printUsage();
        }
    }

    /**
     * H.264 视频渲染器 - 轻量版
     * 专注于解码和WebSocket广播，无UI显示
     */
    private static class H264VideoRenderer implements Runnable {

        private BufferedImage currentFrame;
        private long frameCounter = 0;
        private final Java2DFrameConverter converter = new Java2DFrameConverter();
        private final PipedOutputStream dataOutputStream;
        private final PipedInputStream dataInputStream;
        private volatile boolean running = false;
        private Thread decoderThread;
        private final H264StreamReceiver parentReceiver;

        public H264VideoRenderer(H264StreamReceiver parentReceiver) {
            this.parentReceiver = parentReceiver;
            try {
                this.dataOutputStream = new PipedOutputStream();
                this.dataInputStream = new PipedInputStream(dataOutputStream, 1024 * 1024); // 1MB buffer
            } catch (IOException e) {
                throw new RuntimeException("无法创建管道流", e);
            }
        }

        public void start() {
            if (!running) {
                running = true;
                decoderThread = new Thread(this, "H264-Decoder-Thread");
                decoderThread.start();
            }
        }

        public void stop() {
            running = false;
            try {
                dataInputStream.close(); // 中断解码器的阻塞读取
                dataOutputStream.close();
            } catch (IOException e) {
                // 忽略关闭时的异常
            }
            if (decoderThread != null) {
                decoderThread.interrupt();
                try {
                    decoderThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        public void renderFrame(byte[] frameData) {
            if (!running)
                return;
            try {
                dataOutputStream.write(frameData);
                dataOutputStream.flush();
            } catch (IOException e) {
                if (running) {
                    System.err.println("写入帧数据到管道时出错: " + e.getMessage());
                }
            }
        }

        @Override
        public void run() {
            FFmpegFrameGrabber grabber = null;
            try {
                grabber = new FFmpegFrameGrabber(dataInputStream, 0);
                grabber.setFormat("h264");
                grabber.start();

                while (running && !Thread.currentThread().isInterrupted()) {
                    org.bytedeco.javacv.Frame frame = grabber.grab();
                    if (frame == null)
                        break;
                    if (frame.image != null) {
                        frameCounter++;
                        currentFrame = converter.convert(frame);

                        // 将解码后的图像编码为Base64并转发
                        broadcastDecodedFrameAsBase64(currentFrame, frameCounter);

                        if (System.getProperty("verbose") != null) {
                            System.out.println("解码帧: " + frameCounter + ", 大小: " + currentFrame.getWidth() + "x"
                                    + currentFrame.getHeight());
                        }
                    }
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println("解码线程异常: " + e.getMessage());
                }
            } finally {
                if (grabber != null) {
                    try {
                        grabber.stop();
                        grabber.release();
                    } catch (Exception e) {
                        System.err.println("关闭grabber时出错: " + e.getMessage());
                    }
                }
                System.out.println("解码线程已停止。");
            }
        }

        /**
         * 将解码后的BufferedImage转换为Base64并广播到WebSocket客户端
         */
        private void broadcastDecodedFrameAsBase64(BufferedImage image, long frameNumber) {
            try {
                // 将BufferedImage转换为Base64字符串
                String base64Image = bufferedImageToBase64(image, "PNG");

                // 创建JSON消息
                String jsonMessage = String.format(
                        "{\"type\":\"decoded_frame\",\"data\":\"%s\",\"frameNumber\":%d,\"width\":%d,\"height\":%d,\"format\":\"PNG\",\"timestamp\":%d}",
                        base64Image, frameNumber, image.getWidth(), image.getHeight(), System.currentTimeMillis());

                // 广播到WebSocket客户端
                parentReceiver.broadcastToWebSocketClients(jsonMessage);

                if (System.getProperty("verbose") != null) {
                    System.out.println("已广播解码帧到WebSocket客户端: 帧号=" + frameNumber +
                            ", 尺寸=" + image.getWidth() + "x" + image.getHeight());
                }

            } catch (Exception e) {
                System.err.println("广播解码帧失败: " + e.getMessage());
                e.printStackTrace();
            }
        }

        /**
         * 将BufferedImage转换为Base64字符串
         */
        private String bufferedImageToBase64(BufferedImage image, String format) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, format, baos);
            byte[] imageBytes = baos.toByteArray();
            return Base64.getEncoder().encodeToString(imageBytes);
        }

        public synchronized void dispose() {
            stop();
        }
    }
}
