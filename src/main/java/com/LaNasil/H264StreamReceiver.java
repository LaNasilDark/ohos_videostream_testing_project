package com.LaNasil;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
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
 * H.264 流接收器
 * 从指定主机接收并处理H.264视频流数据
 * 支持命令行参数自动连接和无UI模式
 * 包含实时视频渲染功能和WebSocket流传输
 */
public class H264StreamReceiver extends JFrame {

    private static final String DEFAULT_HOST = "192.168.5.114";
    private static final int DEFAULT_PORT = 8000;
    // private static final String OUTPUT_FILE = "recv.h264";
    private static final int DEFAULT_WS_PORT = 8080;

    // UI组件
    private JTextArea logArea;
    private JButton connectButton;
    private JButton disconnectButton;
    private JButton clearButton;
    private JButton videoButton;
    private JButton wsButton;
    private JLabel statusLabel;
    private JTextField hostField;
    private JTextField portField;
    private JTextField wsPortField;
    private JLabel fpsLabel;
    private JLabel dataRateLabel;
    private JLabel wsClientLabel;

    // 视频渲染组件
    private H264VideoRenderer videoRenderer;
    private JFrame videoWindow;
    private byte[] spsData;
    private byte[] ppsData;
    private final ByteArrayOutputStream h264Buffer = new ByteArrayOutputStream();

    // 网络和线程
    private Socket clientSocket;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private Thread receiverThread;
    // private FileOutputStream outputFileStream;

    // WebSocket 相关
    private WebSocketServer webSocketServer;
    private final Set<WebSocket> webSocketClients = new CopyOnWriteArraySet<>();
    private boolean wsServerRunning = false;

    // 统计跟踪
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong frameCount = new AtomicLong(0);
    private long startTime;
    private long lastStatsUpdate;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    // H.264帧组装器
    private H264FrameAssembler frameAssembler;

    // 自动连接参数
    private String autoConnectHost;
    private int autoConnectPort;
    private boolean shouldAutoConnect;
    private boolean noUiMode = false;

    /**
     * 构造函数 - 初始化GUI界面
     */
    public H264StreamReceiver() {
        initializeGui();
    }

    /**
     * 带自动连接参数的构造函数
     *
     * @param host 自动连接的主机
     * @param port 自动连接的端口
     */
    public H264StreamReceiver(String host, int port) {
        this.autoConnectHost = host;
        this.autoConnectPort = port;
        this.shouldAutoConnect = true;
        initializeGui();
    }

    /**
     * 带命令行模式参数的构造函数
     *
     * @param host 自动连接的主机
     * @param port 自动连接的端口
     * @param noUi 是否为无UI模式
     */
    public H264StreamReceiver(String host, int port, boolean noUi) {
        this.autoConnectHost = host;
        this.autoConnectPort = port;
        this.shouldAutoConnect = true;
        this.noUiMode = noUi;

        if (!noUi) {
            initializeGui();
        } else {
            initializeHeadless();
        }
    }

    /**
     * 初始化无头模式（命令行模式）
     */
    private void initializeHeadless() {
        System.out.println("=== H.264 视频流接收器 (命令行模式) ===");
        System.out.println("目标服务器: " + autoConnectHost + ":" + autoConnectPort);
        System.out.println("WebSocket端口: " + DEFAULT_WS_PORT);
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

        // 自动启动WebSocket服务器
        startWebSocketServerHeadless();

        // 自动连接到H.264流服务器
        connectToServerHeadless(autoConnectHost, autoConnectPort);
    }

    /**
     * 命令行模式下启动WebSocket服务器
     */
    private void startWebSocketServerHeadless() {
        try {
            webSocketServer = new WebSocketServer(new InetSocketAddress(DEFAULT_WS_PORT)) {
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
                    logMessage("WebSocket服务器启动成功,监听端口: " + DEFAULT_WS_PORT);
                }
            };

            webSocketServer.start();
            wsServerRunning = true;

        } catch (Exception ex) {
            System.err.println("启动WebSocket服务器失败: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    /**
     * 命令行模式下连接到H.264流服务器
     */
    private void connectToServerHeadless(String host, int port) {
        if (isConnected.get()) {
            return;
        }

        try {
            // outputFileStream = new FileOutputStream(OUTPUT_FILE);
            clientSocket = new Socket();
            clientSocket.connect(new InetSocketAddress(host, port), 5000);
            isConnected.set(true);

            resetStatistics();
            startTime = System.currentTimeMillis();
            lastStatsUpdate = startTime;

            logMessage("成功连接到服务器 " + host + ":" + port);

            // 创建视频渲染器用于解码和Base64广播（无头模式）
            videoRenderer = new H264VideoRenderer(this);
            videoRenderer.start();

            // 立即显示初始统计信息
            System.out.println(); // 为统计信息预留一行
            printStats();

            // 启动接收线程（无需视频渲染）
            receiverThread = new Thread(this::receiveH264StreamHeadless, "H264-Receiver-Thread");
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
     * 命令行模式下接收H.264流数据
     */
    private void receiveH264StreamHeadless() {
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

            // 发送原始NALU到WebSocket（向后兼容）
            sendRawNaluToWebSocket(naluData);

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
     * 调试方法：打印NALU解析统计信息
     */
    private void logNaluStatistics() {
        if (frameAssembler != null) {
            logMessage("帧组装器状态: " + frameAssembler.getFrameStats());
        }
    }

    /**
     * 发送原始NALU单元到WebSocket（向后兼容）
     */
    private void sendRawNaluToWebSocket(byte[] naluData) {
        String base64Nalu = Base64.getEncoder().encodeToString(naluData);

        int startCodeLen = getStartCodeLength(naluData, 0);
        if (naluData.length > startCodeLen) {
            byte nalHeader = naluData[startCodeLen];
            int nalType = nalHeader & 0x1F;
            String naluDesc = H264FrameAssembler.getNaluTypeDescription(nalType);

            // 发送到WebSocket客户端（原始NALU格式）
            broadcastFrameToWebSocket(base64Nalu, nalType, naluDesc, naluData.length);
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

            // 每10秒打印一次详细的NALU统计
            if (elapsedTime % 10000 < 500 && System.getProperty("verbose") != null) {
                System.out.println(); // 换行
                logNaluStatistics();
            }
        }
    }

    /**
     * 初始化图形用户界面
     */
    private void initializeGui() {
        setTitle("H.264 视频流接收器 (支持WebSocket)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // 初始化帧组装器
        initializeFrameAssembler();

        // 创建控制面板
        JPanel controlPanel = createControlPanel();

        // 创建统计面板
        JPanel statsPanel = createStatsPanel();

        // 创建日志显示区域
        logArea = new JTextArea(20, 60);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        // 状态标签
        statusLabel = new JLabel("就绪");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        // 创建视频窗口
        createVideoWindow();

        // 布局
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(controlPanel, BorderLayout.NORTH);
        topPanel.add(statsPanel, BorderLayout.SOUTH);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);

        // 添加窗口关闭事件处理
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                disconnectFromServer();
                stopWebSocketServer();
                disposeVideoResources();
                System.exit(0);
            }
        });

        // 如果提供了参数则自动连接
        if (shouldAutoConnect) {
            SwingUtilities.invokeLater(() -> {
                hostField.setText(autoConnectHost);
                portField.setText(String.valueOf(autoConnectPort));
                logMessage("使用命令行参数自动连接到: " + autoConnectHost + ":" + autoConnectPort);
                connectToServer(autoConnectHost, autoConnectPort);
            });
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
                            spsData = nalu;
                            logMessage(String.format("已捕获并存储 SPS: 大小=%d字节", nalu.length));
                        } else if (nalType == 8) { // PPS NALU
                            ppsData = nalu;
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

        String base64Frame = Base64.getEncoder().encodeToString(frameData);

        // 发送到视频渲染器进行解码显示
        if (videoRenderer != null) {
            videoRenderer.renderFrame(frameData);
        }

        // 发送到WebSocket客户端
        String frameType = isKeyFrame ? "关键帧" : "普通帧";
        broadcastCompleteFrameToWebSocket(base64Frame, frameType, frameData.length, frameNumber);

        // 更新统计
        frameCount.incrementAndGet();

        logMessage(String.format("处理完整帧: 帧号=%d, 类型=%s, 大小=%d 字节, 有效性=✓, WS客户端=%d",
                frameNumber, frameType, frameData.length, webSocketClients.size()));
    }

    /**
     * 广播完整帧到WebSocket客户端
     */
    private void broadcastCompleteFrameToWebSocket(String base64Frame, String frameType, int frameSize,
            long frameNumber) {
        if (!webSocketClients.isEmpty()) {
            String jsonMessage = String.format(
                    "{\"type\":\"complete_frame\",\"data\":\"%s\",\"frameType\":\"%s\",\"size\":%d,\"frameNumber\":%d,\"timestamp\":%d}",
                    base64Frame, frameType, frameSize, frameNumber, System.currentTimeMillis());

            webSocketClients.removeIf(client -> {
                try {
                    if (client.isOpen()) {
                        client.send(jsonMessage);
                        return false;
                    } else {
                        return true;
                    }
                } catch (Exception e) {
                    logMessage("发送WebSocket消息失败 [" + client.getRemoteSocketAddress() + "]: " + e.getMessage());
                    return true;
                }
            });

            updateWebSocketClientCount();
        }
    }

    private void createVideoWindow() {
        videoWindow = new JFrame("H.264 视频播放");
        videoRenderer = new H264VideoRenderer(this);

        videoWindow.add(videoRenderer);
        videoWindow.setSize(800, 600);
        videoWindow.setLocationRelativeTo(this);
        videoWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
    }

    /**
     * 创建包含连接控件的控制面板
     *
     * @return 包含控制按钮和输入字段的面板
     */
    private JPanel createControlPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        // H.264 连接控制面板
        JPanel h264Panel = new JPanel(new FlowLayout());
        h264Panel.setBorder(BorderFactory.createTitledBorder("H.264流连接"));

        h264Panel.add(new JLabel("服务器地址:"));
        hostField = new JTextField(shouldAutoConnect ? autoConnectHost : DEFAULT_HOST, 15);
        h264Panel.add(hostField);

        h264Panel.add(new JLabel("端口:"));
        portField = new JTextField(String.valueOf(shouldAutoConnect ? autoConnectPort : DEFAULT_PORT), 8);
        h264Panel.add(portField);

        connectButton = new JButton("连接");
        connectButton.addActionListener(new ConnectAction());
        h264Panel.add(connectButton);

        disconnectButton = new JButton("断开连接");
        disconnectButton.setEnabled(false);
        disconnectButton.addActionListener(e -> disconnectFromServer());
        h264Panel.add(disconnectButton);

        videoButton = new JButton("显示视频");
        videoButton.addActionListener(e -> toggleVideoWindow());
        h264Panel.add(videoButton);

        clearButton = new JButton("清空日志");
        clearButton.addActionListener(e -> {
            logArea.setText("");
            resetStatistics();
        });
        h264Panel.add(clearButton);

        // WebSocket 控制面板
        JPanel wsPanel = new JPanel(new FlowLayout());
        wsPanel.setBorder(BorderFactory.createTitledBorder("WebSocket服务"));

        wsPanel.add(new JLabel("WebSocket端口:"));
        wsPortField = new JTextField(String.valueOf(DEFAULT_WS_PORT), 6);
        wsPanel.add(wsPortField);

        wsButton = new JButton("启动WS服务");
        wsButton.addActionListener(e -> toggleWebSocketServer());
        wsPanel.add(wsButton);

        wsClientLabel = new JLabel("客户端: 0");
        wsPanel.add(wsClientLabel);

        panel.add(h264Panel);
        panel.add(wsPanel);

        return panel;
    }

    /**
     * 切换视频窗口可见性
     */
    private void toggleVideoWindow() {
        if (videoWindow.isVisible()) {
            videoWindow.setVisible(false);
            videoButton.setText("显示视频");
        } else {
            videoWindow.setVisible(true);
            videoButton.setText("隐藏视频");
        }
    }

    /**
     * 切换 WebSocket 服务器状态
     */
    private void toggleWebSocketServer() {
        if (!wsServerRunning) {
            startWebSocketServer();
        } else {
            stopWebSocketServer();
        }
    }

    /**
     * 启动 WebSocket 服务器
     */
    private void startWebSocketServer() {
        try {
            int wsPort = Integer.parseInt(wsPortField.getText().trim());

            webSocketServer = new WebSocketServer(new InetSocketAddress(wsPort)) {
                @Override
                public void onOpen(WebSocket conn, ClientHandshake handshake) {
                    webSocketClients.add(conn);
                    logMessage("WebSocket客户端连接: " + conn.getRemoteSocketAddress());
                    updateWebSocketClientCount();
                }

                @Override
                public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                    webSocketClients.remove(conn);
                    logMessage("WebSocket客户端断开: " + conn.getRemoteSocketAddress() +
                            " (代码:" + code + ", 原因:" + reason + ")");
                    updateWebSocketClientCount();
                }

                @Override
                public void onMessage(WebSocket conn, String message) {
                    // 处理客户端消息（如果需要）
                    logMessage("收到WebSocket消息 [" + conn.getRemoteSocketAddress() + "]: " + message);
                }

                @Override
                public void onError(WebSocket conn, Exception ex) {
                    logMessage("WebSocket错误 [" + (conn != null ? conn.getRemoteSocketAddress() : "未知") + "]: "
                            + ex.getMessage());
                    ex.printStackTrace();
                }

                @Override
                public void onStart() {
                    logMessage("WebSocket服务器启动成功,监听端口: " + wsPort);
                    SwingUtilities.invokeLater(() -> {
                        wsButton.setText("停止WS服务");
                        wsPortField.setEnabled(false);
                    });
                }
            };

            webSocketServer.start();
            wsServerRunning = true;

        } catch (NumberFormatException ex) {
            showError("WebSocket端口号必须是有效数字");
        } catch (Exception ex) {
            showError("启动WebSocket服务器失败: " + ex.getMessage());
            ex.printStackTrace();
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
                wsServerRunning = false;

                if (!noUiMode) {
                    SwingUtilities.invokeLater(() -> {
                        wsButton.setText("启动WS服务");
                        wsPortField.setEnabled(true);
                        updateWebSocketClientCount();
                    });
                }
                logMessage("WebSocket服务器已停止");
            } catch (Exception ex) {
                String errorMessage = "停止WebSocket服务器时出错: " + ex.getMessage();
                logMessage(errorMessage);
                ex.printStackTrace();
            }
        }
    }

    /**
     * 更新WebSocket客户端数量显示
     */
    private void updateWebSocketClientCount() {
        if (!noUiMode) {
            SwingUtilities.invokeLater(() -> {
                wsClientLabel.setText("客户端: " + webSocketClients.size());
            });
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
                        // logMessage(jsonMessage);
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

            // 如果客户端数量发生变化,更新显示
            updateWebSocketClientCount();
        }
    }

    /**
     * 通用的WebSocket广播方法
     */
    private void broadcastToWebSocketClients(String jsonMessage) {
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
            // 如果客户端数量发生变化,更新显示
            updateWebSocketClientCount();
        }
    }

    /**
     * 创建统计显示面板
     *
     * @return 包含统计标签的面板
     */
    private JPanel createStatsPanel() {
        JPanel panel = new JPanel(new FlowLayout());

        fpsLabel = new JLabel("帧率: 0.0 fps");
        dataRateLabel = new JLabel("数据率: 0.0 KB/s");

        panel.add(fpsLabel);
        panel.add(Box.createHorizontalStrut(20));
        panel.add(dataRateLabel);

        return panel;
    }

    /**
     * 连接按钮的动作处理
     */
    private class ConnectAction implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            try {
                String host = hostField.getText().trim();
                int port = Integer.parseInt(portField.getText().trim());
                connectToServer(host, port);
            } catch (NumberFormatException ex) {
                showError("端口号必须是有效的数字");
            } catch (Exception ex) {
                showError("连接失败: " + ex.getMessage());
            }
        }
    }

    /**
     * 连接到H.264流服务器
     *
     * @param host 服务器主机地址
     * @param port 服务器端口号
     */
    private void connectToServer(String host, int port) {
        if (isConnected.get()) {
            return;
        }

        try {
            // outputFileStream = new FileOutputStream(OUTPUT_FILE);
            clientSocket = new Socket();
            clientSocket.connect(new InetSocketAddress(host, port), 5000); // 5秒超时
            isConnected.set(true);

            SwingUtilities.invokeLater(() -> {
                connectButton.setEnabled(false);
                disconnectButton.setEnabled(true);
                hostField.setEnabled(false);
                portField.setEnabled(false);
            });

            resetStatistics();
            startTime = System.currentTimeMillis();
            lastStatsUpdate = startTime;

            logMessage("成功连接到服务器 " + host + ":" + port);
            updateStatus("已连接 - 接收数据中");

            // 启动接收线程和解码器线程
            receiverThread = new Thread(this::receiveH264Stream, "H264-Receiver-Thread");
            receiverThread.start();
            videoRenderer.start();

        } catch (IOException e) {
            showError("无法连接到服务器 " + host + ":" + port + " - " + e.getMessage());
            cleanupConnection();
        }
    }

    /**
     * 接收H.264流数据的主方法（GUI模式）
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

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastStatsUpdate >= 1000) {
                    updateStatsDisplay();
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
            SwingUtilities.invokeLater(() -> {
                disconnectFromServer();
                logMessage("H.264流接收已停止");
            });
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
     * 在UI中更新统计显示
     */
    private void updateStatsDisplay() {
        SwingUtilities.invokeLater(() -> {
            long elapsedTime = System.currentTimeMillis() - startTime;
            if (elapsedTime > 0) {
                double fps = (frameCount.get() * 1000.0) / elapsedTime;
                double dataRateKBps = (totalBytesReceived.get() * 1000.0) / (elapsedTime * 1024.0);
                fpsLabel.setText(String.format("帧率: %.2f fps", fps));
                dataRateLabel.setText(String.format("数据率: %.2f KB/s", dataRateKBps));
            }
        });
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

        if (!noUiMode) {
            SwingUtilities.invokeLater(() -> {
                fpsLabel.setText("帧率: 0.0 fps");
                dataRateLabel.setText("数据率: 0.0 KB/s");
            });
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
        if (!noUiMode) {
            updateStatus("就绪");
        }
    }

    /**
     * 清理连接资源
     */
    private void cleanupConnection() {
        try {
            // if (outputFileStream != null) {
            // outputFileStream.close();
            // }
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException e) {
            String errorMessage = "清理连接时发生错误: " + e.getMessage();
            logMessage(errorMessage);
        } finally {
            // outputFileStream = null;
            clientSocket = null;
        }

        if (!noUiMode) {
            SwingUtilities.invokeLater(() -> {
                connectButton.setEnabled(true);
                disconnectButton.setEnabled(false);
                hostField.setEnabled(true);
                portField.setEnabled(true);
            });
        }
    }

    /**
     * 释放视频渲染资源
     */
    private void disposeVideoResources() {
        if (videoRenderer != null) {
            videoRenderer.dispose();
        }
        if (videoWindow != null) {
            videoWindow.dispose();
        }
    }

    @Override
    public void dispose() {
        disconnectFromServer();
        stopWebSocketServer();
        disposeVideoResources();
        super.dispose();
    }

    private void logMessage(String message) {
        String timestamp = dateFormat.format(new Date());
        String logEntry = "[" + timestamp + "] " + message;

        if (noUiMode) {
            // 在无UI模式下使用控制台输出
            System.out.println(logEntry);
            return;
        }

        // 确保logArea已初始化
        if (logArea == null) {
            System.err.println("Warning: logArea is null, message: " + message);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            logArea.append(logEntry + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void updateStatus(String status) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(status));
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, message, "错误", JOptionPane.ERROR_MESSAGE);
            logMessage("错误: " + message);
        });
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
        System.out.println("  java -jar <jarfile>                           - 正常启动GUI界面");
        System.out.println("  java -jar <jarfile> <ip> <port>               - 使用指定IP和端口启动GUI并自动连接");
        System.out.println("  java -jar <jarfile> --noui <ip> <port>        - 命令行模式,无GUI界面");
        System.out.println("\n参数说明:");
        System.out.println("  ip     - 服务器IP地址 (例如: 192.168.1.100)");
        System.out.println("  port   - 服务器端口号 (1-65535)");
        System.out.println("  --noui - 启用命令行模式,不显示GUI界面");
        System.out.println("\n命令行模式说明:");
        System.out.println("  • 自动启动WebSocket服务器(端口8080)");
        System.out.println("  • 自动连接到指定的H.264流服务器");
        System.out.println("  • 实时显示统计信息");
        System.out.println("  • 使用 -Dverbose=true 启用详细日志");
        System.out.println("  • 按 Ctrl+C 退出程序");
        System.out.println("\nWebSocket功能:");
        System.out.println("  • 客户端可连接 ws://localhost:8080 接收Base64编码的视频帧");
        System.out.println("  • 支持多个客户端同时连接");
        System.out.println("\n示例:");
        System.out.println("  java -jar receiver.jar --noui 192.168.1.100 8000");
        System.out.println("  java -Dverbose=true -jar receiver.jar --noui 192.168.5.114 8000");
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 检查是否包含 --noui 参数
        boolean noUi = false;
        String[] filteredArgs = args;
        for (int i = 0; i < args.length; i++) {
            if ("--noui".equals(args[i])) {
                noUi = true;
                // 移除 --noui 参数,创建新的参数数组
                filteredArgs = new String[args.length - 1];
                int j = 0;
                for (int k = 0; k < args.length; k++) {
                    if (k != i) {
                        filteredArgs[j++] = args[k];
                    }
                }
                break;
            }
        }

        if (filteredArgs.length == 0) {
            if (noUi) {
                System.err.println("错误: --noui 参数需要同时指定服务器地址和端口");
                printUsage();
                return;
            }
            SwingUtilities.invokeLater(() -> new H264StreamReceiver().setVisible(true));
        } else if (filteredArgs.length == 2) {
            String host = filteredArgs[0].trim();
            try {
                int port = Integer.parseInt(filteredArgs[1].trim());
                if (!isValidIpAddress(host) || !isValidPort(port)) {
                    System.err.println("错误: 无效的IP地址或端口号。");
                    printUsage();
                    return;
                }

                if (noUi) {
                    // 命令行模式
                    @SuppressWarnings("unused")
                    H264StreamReceiver receiver = new H264StreamReceiver(host, port, true);
                    // 保持程序运行
                    try {
                        Thread.currentThread().join();
                    } catch (InterruptedException e) {
                        System.out.println("\n程序被中断");
                    }
                } else {
                    // GUI模式
                    SwingUtilities.invokeLater(() -> new H264StreamReceiver(host, port).setVisible(true));
                }
            } catch (NumberFormatException e) {
                System.err.println("错误: 端口号必须是数字。");
                printUsage();
            }
        } else {
            System.err.println("错误: 参数数量不正确。");
            printUsage();
        }
    }

    /**
     * H.264 视频渲染器 (使用JavaCV实现) - 优化版
     * 该版本使用单个解码器实例和专用解码线程,以提高性能。
     */
    private static class H264VideoRenderer extends JPanel implements Runnable {

        private BufferedImage currentFrame;
        private long frameCounter = 0;
        private final Java2DFrameConverter converter = new Java2DFrameConverter();
        private final PipedOutputStream dataOutputStream;
        private final PipedInputStream dataInputStream;
        private volatile boolean running = false;
        private Thread decoderThread;
        private final H264StreamReceiver parentReceiver; // 添加对外部类的引用

        public H264VideoRenderer(H264StreamReceiver parentReceiver) {
            this.parentReceiver = parentReceiver;
            setPreferredSize(new Dimension(800, 600));
            setBackground(Color.BLACK);
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
                        System.out.println("开始解码，目前时间: " + System.currentTimeMillis());
                        frameCounter++;
                        currentFrame = converter.convert(frame);

                        // 将解码后的图像编码为Base64并转发
                        broadcastDecodedFrameAsBase64(currentFrame, frameCounter);

                        System.out.println(currentFrame.toString());
                        System.out.println("解码帧: " + frameCounter + ", 大小: " + currentFrame.getWidth() + "x"
                                + currentFrame.getHeight());

                        SwingUtilities.invokeLater(this::repaint);
                        System.out.println("解码线程已更新帧，现在时间" + System.currentTimeMillis());
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

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (currentFrame != null) {
                int panelWidth = getWidth();
                int panelHeight = getHeight();
                int imgWidth = currentFrame.getWidth();
                int imgHeight = currentFrame.getHeight();
                double scale = Math.min((double) panelWidth / imgWidth, (double) panelHeight / imgHeight);
                int scaledWidth = (int) (imgWidth * scale);
                int scaledHeight = (int) (imgHeight * scale);
                int x = (panelWidth - scaledWidth) / 2;
                int y = (panelHeight - scaledHeight) / 2;
                g.drawImage(currentFrame, x, y, scaledWidth, scaledHeight, this);

                g.setColor(Color.YELLOW);
                g.setFont(new Font("SansSerif", Font.BOLD, 16));
                g.drawString("已解码: " + frameCounter, 10, 20);
            } else {
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, 20));
                String msg = "等待视频流...";
                FontMetrics fm = g.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(msg)) / 2;
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g.drawString(msg, x, y);
            }
        }

        /**
         * 将解码后的BufferedImage转换为Base64并广播到WebSocket客户端
         * 
         * @param image       解码后的BufferedImage
         * @param frameNumber 帧号
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

                System.out.println("已广播解码帧到WebSocket客户端: 帧号=" + frameNumber +
                        ", 尺寸=" + image.getWidth() + "x" + image.getHeight());

            } catch (Exception e) {
                System.err.println("广播解码帧失败: " + e.getMessage());
                e.printStackTrace();
            }
        }

        /**
         * 将BufferedImage转换为Base64字符串
         * 
         * @param image  BufferedImage对象
         * @param format 图像格式 (PNG, JPEG等)
         * @return Base64编码的字符串
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