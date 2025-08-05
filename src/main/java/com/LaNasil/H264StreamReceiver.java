package com.LaNasil;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Base64;

/**
 * H.264 流接收器 (命令行版本)
 * 从指定主机接收并处理H.264视频流数据
 * 支持WebSocket流传输
 */
public class H264StreamReceiver {

    private static final int DEFAULT_WS_PORT = 8080;

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

    // 连接参数
    private int wsPort;

    /**
     * 构造函数
     */
    public H264StreamReceiver(String host, int port) {
        this(host, port, DEFAULT_WS_PORT);
    }

    /**
     * 带WebSocket端口的构造函数
     */
    public H264StreamReceiver(String host, int port, int wsPort) {
        this.wsPort = wsPort;

        System.out.println("=== H.264 视频流接收器 (命令行版本) ===");
        System.out.println("目标服务器: " + host + ":" + port);
        System.out.println("WebSocket端口: " + wsPort);
        System.out.println("按 Ctrl+C 退出程序");
        System.out.println("=====================================");

        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭程序...");
            disconnectFromServer();
            stopWebSocketServer();
            System.out.println("程序已关闭");
        }));

        // 自动启动WebSocket服务器
        startWebSocketServer();

        // 自动连接到H.264流服务器
        connectToServer(host, port);
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
            ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream();
            boolean inFrame = false;

            while (isConnected.get() && !Thread.currentThread().isInterrupted()) {
                int bytesRead = inputStream.read(buffer);
                if (bytesRead == -1) {
                    logMessage("服务器连接已关闭");
                    break;
                }

                for (int i = 0; i < bytesRead - 3; i++) {
                    if (buffer[i] == 0x00 && buffer[i + 1] == 0x00 && buffer[i + 2] == 0x00 && buffer[i + 3] == 0x01) {
                        if (inFrame) {
                            byte[] frameData = frameBuffer.toByteArray();
                            if (frameData.length > 0) {
                                processFrame(frameData);
                                frameCount.incrementAndGet();
                            }
                            frameBuffer.reset();
                        }
                        inFrame = true;
                    }
                }

                if (inFrame) {
                    frameBuffer.write(buffer, 0, bytesRead);
                }

                updateStatistics(bytesRead);

                // 实时更新统计显示
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastStatsUpdate >= 300) { // 每300毫秒更新一次
                    printStats();
                    lastStatsUpdate = currentTime;
                }
            }

            // 处理最后一帧
            if (inFrame && frameBuffer.size() > 0) {
                processFrame(frameBuffer.toByteArray());
                frameCount.incrementAndGet();
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
     * 处理帧数据
     */
    private void processFrame(byte[] frameData) {
        String base64Frame = Base64.getEncoder().encodeToString(frameData);

        int startCodeLen = getStartCodeLength(frameData, 0);
        if (frameData.length > startCodeLen) {
            byte nalHeader = frameData[startCodeLen];
            int nalType = nalHeader & 0x1F;
            String naluDesc = getNaluTypeDescription(nalType);

            // 发送到WebSocket客户端
            broadcastFrameToWebSocket(base64Frame, nalType, naluDesc, frameData.length);

            // 实时更新统计信息（每处理一帧就更新一次）
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastStatsUpdate >= 500) { // 每500毫秒更新一次统计显示
                printStats();
                lastStatsUpdate = currentTime;
            }

            // 只在详细模式下显示每帧信息
            if (System.getProperty("verbose") != null) {
                logMessage(String.format("处理帧: 类型=%d (%s), 大小=%d 字节, WS客户端=%d",
                        nalType, naluDesc, frameData.length, webSocketClients.size()));
            }
        }
    }

    /**
     * 命令行模式下的统计信息显示循环
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

                String message = "WebSocket服务器已停止";
                logMessage(message);
            } catch (Exception ex) {
                String errorMessage = "停止WebSocket服务器时出错: " + ex.getMessage();
                logMessage(errorMessage);
                ex.printStackTrace();
            }
        }
    }

    /**
     * 广播Base64帧数据到所有WebSocket客户端
     */
    private void broadcastFrameToWebSocket(String base64Frame, int nalType, String naluDesc, int frameSize) {
        if (!webSocketClients.isEmpty()) {
            // 创建JSON格式的消息
            String jsonMessage = String.format(
                    "{\"type\":\"frame\",\"data\":\"%s\",\"nalType\":%d,\"nalDesc\":\"%s\",\"size\":%d,\"timestamp\":%d}",
                    base64Frame, nalType, naluDesc, frameSize, System.currentTimeMillis());

            // 广播到所有连接的客户端
            webSocketClients.removeIf(client -> {
                try {
                    if (client.isOpen()) {
                        client.send(jsonMessage);
                        return false; // 保留连接
                    } else {
                        return true; // 移除断开的连接
                    }
                } catch (Exception e) {
                    String errorMessage = "发送WebSocket消息失败 [" + client.getRemoteSocketAddress() + "]: "
                            + e.getMessage();
                    logMessage(errorMessage);
                    return true; // 移除出错的连接
                }
            });
        }
    }

    /**
     * 获取NALU类型描述
     *
     * @param type NALU类型值
     * @return 描述字符串
     */
    private String getNaluTypeDescription(int type) {
        switch (type) {
            case 1:
                return "非IDR图像";
            case 5:
                return "IDR图像";
            case 6:
                return "SEI";
            case 7:
                return "SPS参数";
            case 8:
                return "PPS参数";
            default:
                return "其他";
        }
    }

    /**
     * 获取起始码长度
     *
     * @param data 数据缓冲区
     * @param pos  位置
     * @return 起始码长度 (3 或 4)
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
     * 使用接收到的数据更新统计
     *
     * @param bytesReceived 本次更新接收的字节数
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

    private void logMessage(String message) {
        String timestamp = dateFormat.format(new Date());
        String logEntry = "[" + timestamp + "] " + message;
        System.out.println(logEntry);
    }

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

    private static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    private static void printUsage() {
        System.out.println("使用方法:");
        System.out.println("  java -jar <jarfile> <ip> <port>               - 命令行模式");
        System.out.println("  java -jar <jarfile> <ip> <port> <ws_port>     - 指定WebSocket端口");
        System.out.println("\n参数说明:");
        System.out.println("  ip       - 服务器IP地址 (例如: 192.168.1.100)");
        System.out.println("  port     - 服务器端口号 (1-65535)");
        System.out.println("  ws_port  - WebSocket端口号 (可选，默认8080)");
        System.out.println("\n功能说明:");
        System.out.println("  • 自动启动WebSocket服务器");
        System.out.println("  • 自动连接到指定的H.264流服务器");
        System.out.println("  • 实时显示统计信息");
        System.out.println("  • 使用 -Dverbose=true 启用详细日志");
        System.out.println("  • 按 Ctrl+C 退出程序");
        System.out.println("\nWebSocket功能:");
        System.out.println("  • 客户端可连接 ws://localhost:<ws_port> 接收Base64编码的视频帧");
        System.out.println("  • 支持多个客户端同时连接");
        System.out.println("\n示例:");
        System.out.println("  java -jar receiver.jar 192.168.1.100 8000");
        System.out.println("  java -jar receiver.jar 192.168.5.114 8000 8081");
        System.out.println("  java -Dverbose=true -jar receiver.jar 192.168.5.114 8000");
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("错误: 参数数量不正确。");
            printUsage();
            return;
        }

        String host = args[0].trim();
        int port;
        int wsPort = DEFAULT_WS_PORT;

        try {
            port = Integer.parseInt(args[1].trim());
            if (args.length == 3) {
                wsPort = Integer.parseInt(args[2].trim());
            }
        } catch (NumberFormatException e) {
            System.err.println("错误: 端口号必须是数字。");
            printUsage();
            return;
        }

        if (!isValidIpAddress(host) || !isValidPort(port) || !isValidPort(wsPort)) {
            System.err.println("错误: 无效的IP地址或端口号。");
            printUsage();
            return;
        }

        // 创建并启动H264流接收器
        @SuppressWarnings("unused")
        H264StreamReceiver receiver = new H264StreamReceiver(host, port, wsPort);

        // 保持程序运行
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println("\n程序被中断");
        }
    }
}