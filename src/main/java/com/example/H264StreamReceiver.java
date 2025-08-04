package com.example;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
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
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

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
        videoWindow.setLocationRelativeTo(this);
        videoWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
    }

    /**
     * 创建包含连接控件的控制面板
     *
     * @return 包含控制按钮和输入字段的面板
     */
    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new FlowLayout());

        panel.add(new JLabel("服务器地址:"));
        hostField = new JTextField(shouldAutoConnect ? autoConnectHost : DEFAULT_HOST, 15);
        panel.add(hostField);

        panel.add(new JLabel("端口:"));
        portField = new JTextField(String.valueOf(shouldAutoConnect ? autoConnectPort : DEFAULT_PORT), 8);
        panel.add(portField);

        connectButton = new JButton("连接");
        connectButton.addActionListener(new ConnectAction());
        panel.add(connectButton);

        disconnectButton = new JButton("断开连接");
        disconnectButton.setEnabled(false);
        disconnectButton.addActionListener(e -> disconnectFromServer());
        panel.add(disconnectButton);

        videoButton = new JButton("显示视频");
        videoButton.addActionListener(e -> toggleVideoWindow());
        panel.add(videoButton);

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
            outputFileStream = new FileOutputStream(OUTPUT_FILE);
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
            logMessage("开始接收H.264视频流数据，保存到文件: " + OUTPUT_FILE);
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
     * 接收H.264流数据的主方法
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
                    // 查找起始码 0x00 0x00 0x00 0x01
                    if (buffer[i] == 0x00 && buffer[i + 1] == 0x00 && buffer[i + 2] == 0x00 && buffer[i + 3] == 0x01) {
                        if (inFrame) {
                            // 发现新帧的起始，处理已缓冲的旧帧
                            byte[] frameData = frameBuffer.toByteArray();
                            if (frameData.length > 0) {
                                renderFrame(frameData);
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

                outputFileStream.write(buffer, 0, bytesRead);
                updateStatistics(bytesRead);

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastStatsUpdate >= 1000) {
                    updateStatsDisplay();
                    lastStatsUpdate = currentTime;
                }
            }

            // 处理最后一帧
            if (inFrame && frameBuffer.size() > 0) {
                renderFrame(frameBuffer.toByteArray());
                frameCount.incrementAndGet();
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

        int startCodeLen = getStartCodeLength(frameData, 0);
        if (frameData.length > startCodeLen) {
            byte nalHeader = frameData[startCodeLen];
            int nalType = nalHeader & 0x1F;
            String naluDesc = getNaluTypeDescription(nalType);
            logMessage(String.format("渲染帧: 类型=%d (%s), 大小=%d 字节", nalType, naluDesc, frameData.length));
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
        SwingUtilities.invokeLater(() -> {
            fpsLabel.setText("帧率: 0.0 fps");
            dataRateLabel.setText("数据率: 0.0 KB/s");
        });
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
            }
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException e) {
            logMessage("清理连接时发生错误: " + e.getMessage());
        } finally {
            outputFileStream = null;
            clientSocket = null;
        }

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

    @Override
    public void dispose() {
        disconnectFromServer();
        disposeVideoResources();
        super.dispose();
    }

    private void logMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = dateFormat.format(new Date());
            logArea.append("[" + timestamp + "] " + message + "\n");
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
        System.out.println("  java -jar <jarfile>                    - 正常启动GUI界面");
        System.out.println("  java -jar <jarfile> <ip> <port>        - 使用指定IP和端口自动连接");
        System.out.println("\n参数说明:");
        System.out.println("  ip     - 服务器IP地址 (例如: 192.168.1.100)");
        System.out.println("  port   - 服务器端口号 (1-65535)");
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (args.length == 0) {
            SwingUtilities.invokeLater(() -> new H264StreamReceiver().setVisible(true));
        } else if (args.length == 2) {
            String host = args[0].trim();
            try {
                int port = Integer.parseInt(args[1].trim());
                if (!isValidIpAddress(host) || !isValidPort(port)) {
                    System.err.println("错误: 无效的IP地址或端口号。");
                    printUsage();
                    return;
                }
                SwingUtilities.invokeLater(() -> new H264StreamReceiver(host, port).setVisible(true));
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
     * 该版本使用单个解码器实例和专用解码线程，以提高性能。
     */
    private static class H264VideoRenderer extends JPanel implements Runnable {

        private BufferedImage currentFrame;
        private long frameCounter = 0;
        private final Java2DFrameConverter converter = new Java2DFrameConverter();
        private final PipedOutputStream dataOutputStream;
        private final PipedInputStream dataInputStream;
        private volatile boolean running = false;
        private Thread decoderThread;

        public H264VideoRenderer() {
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
                        frameCounter++;
                        currentFrame = converter.convert(frame);
                        SwingUtilities.invokeLater(this::repaint);
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

        public synchronized void dispose() {
            stop();
        }
    }
}