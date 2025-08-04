package com.example;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.CanvasFrame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H.264 流接收器
 * 从指定主机接收并处理H.264视频流数据
 * 支持命令行参数自动连接
 * 包含实时视频渲染功能
 */
public class H264StreamReceiver extends JFrame {

    private static final String DEFAULT_HOST = "192.168.5.114";
    private static final int DEFAULT_PORT = 8000;
    private static final String OUTPUT_FILE = "recv.h264";

    // UI组件
    private JTextArea logArea;
    private JButton connectButton;
    private JButton disconnectButton;
    private JButton clearButton;
    private JButton videoButton;
    private JLabel statusLabel;
    private JTextField hostField;
    private JTextField portField;
    private JLabel fpsLabel;
    private JLabel dataRateLabel;

    // 视频渲染组件
    private H264VideoRenderer videoRenderer;
    private JFrame videoWindow;

    // 网络和线程
    private Socket clientSocket;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private Thread receiverThread;
    private FileOutputStream outputFileStream;

    // 统计跟踪
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong frameCount = new AtomicLong(0);
    private long startTime;
    private long lastStatsUpdate;

    // 自动连接参数
    private String autoConnectHost;
    private int autoConnectPort;
    private boolean shouldAutoConnect;

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
     * 初始化图形用户界面
     */
    private void initializeGui() {
        setTitle("H.264 视频流接收器");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

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
     * 创建视频显示窗口
     */
    private void createVideoWindow() {
        videoWindow = new JFrame("H.264 视频播放");
        videoRenderer = new H264VideoRenderer();

        videoWindow.add(videoRenderer);
        videoWindow.setSize(800, 600);
        videoWindow.setLocationRelativeTo(null);
        videoWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
    }

    /**
     * 创建包含连接控件的控制面板
     * 
     * @return 包含控制按钮和输入字段的面板
     */
    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new FlowLayout());

        // 主机输入
        panel.add(new JLabel("服务器地址:"));
        hostField = new JTextField(shouldAutoConnect ? autoConnectHost : DEFAULT_HOST, 15);
        panel.add(hostField);

        // 端口输入
        panel.add(new JLabel("端口:"));
        portField = new JTextField(String.valueOf(shouldAutoConnect ? autoConnectPort : DEFAULT_PORT), 8);
        panel.add(portField);

        // 连接按钮
        connectButton = new JButton("连接");
        connectButton.addActionListener(new ConnectAction());
        panel.add(connectButton);

        // 断开连接按钮
        disconnectButton = new JButton("断开连接");
        disconnectButton.setEnabled(false);
        disconnectButton.addActionListener(e -> disconnectFromServer());
        panel.add(disconnectButton);

        // 视频按钮
        videoButton = new JButton("显示视频");
        videoButton.addActionListener(e -> toggleVideoWindow());
        panel.add(videoButton);

        // 清除日志按钮
        clearButton = new JButton("清空日志");
        clearButton.addActionListener(e -> {
            logArea.setText("");
            resetStatistics();
        });
        panel.add(clearButton);

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
            // 创建输出文件流
            outputFileStream = new FileOutputStream(OUTPUT_FILE);

            // 连接到服务器
            clientSocket = new Socket();
            clientSocket.connect(new InetSocketAddress(host, port), 5000); // 5秒超时

            isConnected.set(true);

            // 更新UI状态
            connectButton.setEnabled(false);
            disconnectButton.setEnabled(true);
            hostField.setEnabled(false);
            portField.setEnabled(false);

            // 初始化统计
            resetStatistics();
            startTime = System.currentTimeMillis();
            lastStatsUpdate = startTime;

            logMessage("成功连接到服务器 " + host + ":" + port);
            logMessage("开始接收H.264视频流数据，保存到文件: " + OUTPUT_FILE);
            updateStatus("已连接 - 接收数据中");

            // 启动接收线程
            receiverThread = new Thread(() -> receiveH264Stream());
            receiverThread.start();

        } catch (IOException e) {
            showError("无法连接到服务器 " + host + ":" + port + " - " + e.getMessage());
            cleanupConnection();
        }
    }

    /**
     * 接收H.264流数据的主方法
     */
    private void receiveH264Stream() {
        try (BufferedInputStream inputStream = new BufferedInputStream(clientSocket.getInputStream())) {

            byte[] buffer = new byte[4096]; // 使用较大缓冲区提升效率
            byte[] window = new byte[4]; // 滑动窗口用于帧头检测
            int windowPos = 0;

            // 用于临时存储当前帧数据
            ByteArrayOutputStream currentFrame = null;
            boolean inFrame = false;

            while (isConnected.get() && !Thread.currentThread().isInterrupted()) {
                int bytesRead = inputStream.read(buffer);
                if (bytesRead == -1) {
                    logMessage("服务器连接已关闭");
                    break;
                }
                if (bytesRead == 0) {
                    continue;
                }

                for (int i = 0; i < bytesRead; i++) {
                    window[windowPos % 4] = buffer[i];
                    windowPos++;

                    // 检查帧起始标识符 (0x00 0x00 0x00 0x01)
                    if (windowPos >= 4 &&
                            window[(windowPos - 4) % 4] == 0x00 &&
                            window[(windowPos - 3) % 4] == 0x00 &&
                            window[(windowPos - 2) % 4] == 0x00 &&
                            window[(windowPos - 1) % 4] == 0x01) {

                        logMessage("检测到帧起始标识符 (滑动窗口)");
                        frameCount.incrementAndGet();

                        // 如果已经在帧中，说明遇到新帧，尝试渲染上一帧
                        if (inFrame && currentFrame != null && currentFrame.size() > 0) {
                            byte[] frameData = currentFrame.toByteArray();
                            renderFrame(frameData);
                        }
                        // 开始新帧
                        currentFrame = new ByteArrayOutputStream();
                        inFrame = true;
                    }

                    // 记录当前字节到帧容器
                    if (inFrame && currentFrame != null) {
                        currentFrame.write(buffer[i]);
                    }
                }

                // 日志输出（仅显示前32字节，避免日志过大）
                StringBuilder hexString = new StringBuilder();
                int logLen = Math.min(bytesRead, 32);
                for (int i = 0; i < logLen; i++) {
                    hexString.append(String.format("0x%02X ", buffer[i] & 0xFF));
                }
                logMessage("接收数据: " + hexString.toString() + (bytesRead > 32 ? "...(共" + bytesRead + "字节)" : ""));

                // 写入文件
                outputFileStream.write(buffer, 0, bytesRead);
                outputFileStream.flush();

                // 更新统计
                updateStatistics(bytesRead);

                // 每秒刷新一次统计显示
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastStatsUpdate >= 1000) {
                    updateStatsDisplay();
                    lastStatsUpdate = currentTime;
                }
            }

            // 处理最后一帧
            if (inFrame && currentFrame != null && currentFrame.size() > 0) {
                byte[] frameData = currentFrame.toByteArray();
                renderFrame(frameData);
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
     * 渲染接收到的H.264帧
     * 
     * @param frameData 完整的帧数据
     */
    private void renderFrame(byte[] frameData) {
        if (videoRenderer != null) {
            videoRenderer.renderFrame(frameData);
        }

        // 解析并记录帧信息
        int startCodeLen = getStartCodeLength(frameData, 0);
        if (frameData.length > startCodeLen) {
            byte nalHeader = frameData[startCodeLen];
            int nalType = nalHeader & 0x1F;
            String naluDesc = getNaluTypeDescription(nalType);
            logMessage(String.format("渲染帧: 类型=%d (%s), 大小=%d 字节",
                    nalType, naluDesc, frameData.length));
            // 沟槽的ai怎么这么不好用 天天顾左右而言它 我看这openai也是纯串子 byd 代码补全给我气笑了 
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
        if (pos + 2 < data.length && data[pos] == 0x00 && data[pos + 1] == 0x00 && data[pos + 2] == 0x01) {
            return 3;
        }
        if (pos + 3 < data.length && data[pos] == 0x00 && data[pos + 1] == 0x00 && data[pos + 2] == 0x00
                && data[pos + 3] == 0x01) {
            return 4;
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
            long currentTime = System.currentTimeMillis();
            long elapsedTime = currentTime - startTime;

            if (elapsedTime > 0) {
                double fps = (frameCount.get() * 1000.0) / elapsedTime;
                double dataRateKBps = (totalBytesReceived.get() * 1000.0) / (elapsedTime * 1024.0);

                fpsLabel.setText(String.format("帧率: %.2f fps", fps));
                dataRateLabel.setText(String.format("数据率: %.2f KB/s", dataRateKBps));

                updateStatus(String.format("已连接 - 接收了 %d 帧，总计 %.2f MB",
                        frameCount.get(), totalBytesReceived.get() / (1024.0 * 1024.0)));
            }
        });
    }

    /**
     * 重置所有统计计数器
     */
    private void resetStatistics() {
        totalBytesReceived.set(0);
        frameCount.set(0);
        SwingUtilities.invokeLater(() -> {
            fpsLabel.setText("帧率: 0.0 fps");
            dataRateLabel.setText("数据率: 0.0 KB/s");
        });
    }

    /**
     * 断开服务器连接并清理资源
     */
    private void disconnectFromServer() {
        if (!isConnected.get()) {
            return;
        }

        isConnected.set(false);

        // 中断接收线程
        if (receiverThread != null) {
            receiverThread.interrupt();
        }

        cleanupConnection();

        logMessage("已断开服务器连接");
        updateStatus("就绪");
    }

    /**
     * 清理连接资源
     */
    private void cleanupConnection() {
        try {
            if (outputFileStream != null) {
                outputFileStream.close();
                outputFileStream = null;
            }
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException e) {
            logMessage("清理连接时发生错误: " + e.getMessage());
        }

        // 重置UI状态
        SwingUtilities.invokeLater(() -> {
            connectButton.setEnabled(true);
            disconnectButton.setEnabled(false);
            hostField.setEnabled(true);
            portField.setEnabled(true);
        });
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

    /**
     * 重写dispose以清理视频资源
     */
    @Override
    public void dispose() {
        disposeVideoResources();
        super.dispose();
    }

    /**
     * 带时间戳记录消息到日志区域
     * 
     * @param message 要记录的消息
     */
    private void logMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            String timestamp = dateFormat.format(new Date());
            logArea.append("[" + timestamp + "] " + message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /**
     * 更新状态标签
     * 
     * @param status 状态消息
     */
    private void updateStatus(String status) {
        statusLabel.setText(status);
    }

    /**
     * 显示错误消息对话框
     * 
     * @param message 错误消息
     */
    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, message, "错误", JOptionPane.ERROR_MESSAGE);
            logMessage("错误: " + message);
        });
    }

    /**
     * 验证IP地址格式
     * 
     * @param ip 要验证的IP地址字符串
     * @return 如果有效返回true，否则false
     */
    private static boolean isValidIpAddress(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }

        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }

        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 验证端口号
     * 
     * @param port 要验证的端口号
     * @return 如果有效返回true，否则false
     */
    private static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    /**
     * 打印使用信息
     */
    private static void printUsage() {
        System.out.println("使用方法:");
        System.out.println("  java H264StreamReceiver                    - 正常启动GUI界面");
        System.out.println("  java H264StreamReceiver <ip> <port>        - 使用指定IP和端口自动连接");
        System.out.println();
        System.out.println("参数说明:");
        System.out.println("  ip     - 服务器IP地址 (例如: 192.168.1.100)");
        System.out.println("  port   - 服务器端口号 (1-65535)");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java H264StreamReceiver 192.168.5.114 8000");
    }

    /**
     * 程序入口点
     * 
     * @param args 命令行参数: [host] [port]
     */
    public static void main(String[] args) {
        // 设置系统外观
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 解析命令行参数
        if (args.length == 2) {
            try {
                String host = args[0].trim();
                int port = Integer.parseInt(args[1].trim());

                // 验证参数
                if (!isValidIpAddress(host)) {
                    System.err.println("错误: 无效的IP地址格式: " + host);
                    printUsage();
                    return;
                }

                if (!isValidPort(port)) {
                    System.err.println("错误: 无效的端口号: " + port + " (有效范围: 1-65535)");
                    printUsage();
                    return;
                }

                // 创建带自动连接参数的接收器
                SwingUtilities.invokeLater(() -> {
                    H264StreamReceiver receiver = new H264StreamReceiver(host, port);
                    receiver.setVisible(true);
                });

                System.out.println("使用命令行参数启动: " + host + ":" + port);

            } catch (NumberFormatException e) {
                System.err.println("错误: 端口号必须是数字: " + args[1]);
                printUsage();
            }
        } else if (args.length == 0) {
            // 无参数正常启动
            SwingUtilities.invokeLater(() -> {
                H264StreamReceiver receiver = new H264StreamReceiver();
                receiver.setVisible(true);
            });
        } else {
            // 参数数量不正确
            System.err.println("错误: 参数数量不正确");
            printUsage();
        }
    }

    /**
     * H.264 视频渲染器 (使用JavaCV实现)
     */
    private static class H264VideoRenderer extends JPanel {

        private BufferedImage currentFrame;
        private long frameCounter = 0;
        private FFmpegFrameGrabber grabber;
        private boolean decoderInitialized = false;
        private CanvasFrame canvasFrame;
        private Java2DFrameConverter converter = new Java2DFrameConverter();

        public H264VideoRenderer() {
            setPreferredSize(new Dimension(640, 480));
            setBackground(Color.BLACK);
        }

        /**
         * 初始化解码器
         */
        private synchronized void initializeDecoder() {
            try {
                if (grabber == null) {
                    // 创建帧抓取器
                    grabber = new FFmpegFrameGrabber(new ByteArrayInputStream(new byte[0]));
                    grabber.setFormat("h264");

                    // 设置解码参数
                    grabber.setOption("analyzeduration", "100000"); // 100ms分析时长
                    grabber.setOption("probesize", "4096"); // 4KB探测大小

                    // 启动解码器
                    grabber.start();
                    decoderInitialized = true;

                    System.out.println("H.264解码器初始化成功");
                }
            } catch (Exception e) {
                System.err.println("解码器初始化失败: " + e.getMessage());
                grabber = null;
            }
        }

        /**
         * 渲染完整的H.264帧
         * 
         * @param frameData 包含起始码的完整帧数据
         */
        public synchronized void renderFrame(byte[] frameData) {
            try {
                // 为每帧创建新的FFmpegFrameGrabber实例
                FFmpegFrameGrabber frameGrabber = new FFmpegFrameGrabber(new ByteArrayInputStream(frameData));
                frameGrabber.setFormat("h264");
                frameGrabber.start();

                org.bytedeco.javacv.Frame frame = frameGrabber.grabImage();
                if (frame != null && frame.image != null) {
                    frameCounter++;

                    // 转换为BufferedImage
                    currentFrame = converter.convert(frame);

                    // 在Swing线程中更新显示
                    SwingUtilities.invokeLater(this::repaint);
                }
                frameGrabber.stop();
                frameGrabber.release();
            } catch (Exception e) {
                System.err.println("解码错误: " + e.getMessage());
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            if (currentFrame != null) {
                // 缩放图像以适应面板
                Image scaledImage = currentFrame.getScaledInstance(
                        getWidth(), getHeight(), Image.SCALE_SMOOTH);
                g.drawImage(scaledImage, 0, 0, this);

                // 绘制帧计数器叠加层
                g.setColor(Color.YELLOW);
                g.setFont(new Font("宋体", Font.BOLD, 16));
                g.drawString("已解码帧数: " + frameCounter, 20, 30);
            } else {
                // 绘制占位符
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, getWidth(), getHeight());

                g.setColor(Color.WHITE);
                g.setFont(new Font("宋体", Font.BOLD, 20));
                String msg = "等待视频数据...";
                int msgWidth = g.getFontMetrics().stringWidth(msg);
                g.drawString(msg, (getWidth() - msgWidth) / 2, getHeight() / 2);
            }
        }

        /**
         * 启用JavaCV原生窗口
         */
        public void enableNativeWindow() {
            if (canvasFrame == null) {
                canvasFrame = new CanvasFrame("H.264 原生渲染器");
                canvasFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
                canvasFrame.setCanvasSize(640, 480);
            }
            canvasFrame.setVisible(true);
        }

        /**
         * 清理资源
         */
        public synchronized void dispose() {
            if (grabber != null) {
                try {
                    grabber.stop();
                    grabber.release();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (canvasFrame != null) {
                canvasFrame.dispose();
            }
        }
    }
}