package com.example;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H.264 Stream Receiver
 * Receives and processes H.264 video stream data from specified host
 * Supports command line arguments for automatic connection
 */
public class H264StreamReceiver extends JFrame {

    private static final String DEFAULT_HOST = "192.168.5.114";
    private static final int DEFAULT_PORT = 8000;
    private static final String OUTPUT_FILE = "recv.h264";

    // UI Components
    private JTextArea logArea;
    private JButton connectButton;
    private JButton disconnectButton;
    private JButton clearButton;
    private JLabel statusLabel;
    private JTextField hostField;
    private JTextField portField;
    private JLabel fpsLabel;
    private JLabel dataRateLabel;

    // Network and threading
    private Socket clientSocket;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private Thread receiverThread;
    private FileOutputStream outputFileStream;

    // Statistics tracking
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong frameCount = new AtomicLong(0);
    private long startTime;
    private long lastStatsUpdate;

    // Auto-connect parameters
    private String autoConnectHost;
    private int autoConnectPort;
    private boolean shouldAutoConnect;

    /**
     * Constructor - initializes the GUI interface
     */
    public H264StreamReceiver() {
        initializeGui();
    }

    /**
     * Constructor with auto-connect parameters
     * 
     * @param host Host to auto-connect to
     * @param port Port to auto-connect to
     */
    public H264StreamReceiver(String host, int port) {
        this.autoConnectHost = host;
        this.autoConnectPort = port;
        this.shouldAutoConnect = true;
        initializeGui();
    }

    /**
     * Initialize the graphical user interface
     */
    private void initializeGui() {
        setTitle("H.264 视频流接收器");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Create control panel
        JPanel controlPanel = createControlPanel();

        // Create statistics panel
        JPanel statsPanel = createStatsPanel();

        // Create log display area
        logArea = new JTextArea(20, 60);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        // Status label
        statusLabel = new JLabel("就绪");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        // Layout
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(controlPanel, BorderLayout.NORTH);
        topPanel.add(statsPanel, BorderLayout.SOUTH);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);

        // Add window close event handler
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                disconnectFromServer();
                System.exit(0);
            }
        });

        // Auto-connect if parameters provided
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
     * Create control panel with connection controls
     * 
     * @return Panel containing control buttons and input fields
     */
    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new FlowLayout());

        // Host input
        panel.add(new JLabel("服务器地址:"));
        hostField = new JTextField(shouldAutoConnect ? autoConnectHost : DEFAULT_HOST, 15);
        panel.add(hostField);

        // Port input
        panel.add(new JLabel("端口:"));
        portField = new JTextField(String.valueOf(shouldAutoConnect ? autoConnectPort : DEFAULT_PORT), 8);
        panel.add(portField);

        // Connect button
        connectButton = new JButton("连接");
        connectButton.addActionListener(new ConnectAction());
        panel.add(connectButton);

        // Disconnect button
        disconnectButton = new JButton("断开连接");
        disconnectButton.setEnabled(false);
        disconnectButton.addActionListener(e -> disconnectFromServer());
        panel.add(disconnectButton);

        // Clear log button
        clearButton = new JButton("清空日志");
        clearButton.addActionListener(e -> {
            logArea.setText("");
            resetStatistics();
        });
        panel.add(clearButton);

        return panel;
    }

    /**
     * Create statistics display panel
     * 
     * @return Panel containing statistics labels
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
     * Action handler for connect button
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
     * Connect to the H.264 stream server
     * 
     * @param host Server host address
     * @param port Server port number
     */
    private void connectToServer(String host, int port) {
        if (isConnected.get()) {
            return;
        }

        try {
            // Create output file stream
            outputFileStream = new FileOutputStream(OUTPUT_FILE);

            // Connect to server
            clientSocket = new Socket();
            clientSocket.connect(new InetSocketAddress(host, port), 5000); // 5 second timeout

            isConnected.set(true);

            // Update UI state
            connectButton.setEnabled(false);
            disconnectButton.setEnabled(true);
            hostField.setEnabled(false);
            portField.setEnabled(false);

            // Initialize statistics
            resetStatistics();
            startTime = System.currentTimeMillis();
            lastStatsUpdate = startTime;

            logMessage("成功连接到服务器 " + host + ":" + port);
            logMessage("开始接收H.264视频流数据，保存到文件: " + OUTPUT_FILE);
            updateStatus("已连接 - 接收数据中");

            // Start receiver thread
            receiverThread = new Thread(() -> receiveH264Stream());
            receiverThread.start();

        } catch (IOException e) {
            showError("无法连接到服务器 " + host + ":" + port + " - " + e.getMessage());
            cleanupConnection();
        }
    }

    /**
     * Main method to receive H.264 stream data
     */
    private void receiveH264Stream() {
        try (BufferedInputStream inputStream = new BufferedInputStream(clientSocket.getInputStream())) {

            byte[] buffer = new byte[4];

            while (isConnected.get() && !Thread.currentThread().isInterrupted()) {

                // Read 4 bytes at a time
                int bytesRead = inputStream.read(buffer, 0, 4);

                if (bytesRead == -1) {
                    logMessage("服务器连接已关闭");
                    break;
                }

                if (bytesRead == 0) {
                    continue;
                }

                // Check if data starts with H.264 frame marker (0x00 0x00 0x00 0x01)
                if (bytesRead == 4 &&
                        buffer[0] == 0x00 &&
                        buffer[1] == 0x00 &&
                        buffer[2] == 0x00 &&
                        buffer[3] == 0x01) {
                    logMessage("接收到帧起始标识符");
                    frameCount.incrementAndGet();
                }

                // Log received data in hex format for debugging
                StringBuilder hexString = new StringBuilder();
                for (int i = 0; i < bytesRead; i++) {
                    hexString.append(String.format("0x%02X ", buffer[i] & 0xFF));
                }
                logMessage("接收数据: " + hexString.toString());

                // Write data to output file
                outputFileStream.write(buffer, 0, bytesRead);
                outputFileStream.flush();

                // Update statistics
                updateStatistics(bytesRead);

                // Update statistics display every second
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastStatsUpdate >= 1000) {
                    updateStatsDisplay();
                    lastStatsUpdate = currentTime;
                }
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
     * Read exact number of bytes from input stream
     * 
     * @param inputStream Input stream to read from
     * @param buffer      Buffer to store data
     * @param length      Number of bytes to read
     * @return Number of bytes actually read
     * @throws IOException If an I/O error occurs
     */
    private int readExact(BufferedInputStream inputStream, byte[] buffer, int length) throws IOException {
        int totalRead = 0;
        while (totalRead < length) {
            int bytesRead = inputStream.read(buffer, totalRead, length - totalRead);
            if (bytesRead == -1) {
                break; // End of stream
            }
            totalRead += bytesRead;
        }
        return totalRead;
    }

    /**
     * Update statistics with received data
     * 
     * @param bytesReceived Number of bytes received in this update
     */
    private void updateStatistics(int bytesReceived) {
        totalBytesReceived.addAndGet(bytesReceived);
    }

    /**
     * Update statistics display in UI
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
     * Reset all statistics counters
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
     * Disconnect from server and cleanup resources
     */
    private void disconnectFromServer() {
        if (!isConnected.get()) {
            return;
        }

        isConnected.set(false);

        // Interrupt receiver thread
        if (receiverThread != null) {
            receiverThread.interrupt();
        }

        cleanupConnection();

        logMessage("已断开服务器连接");
        updateStatus("就绪");
    }

    /**
     * Cleanup connection resources
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

        // Reset UI state
        SwingUtilities.invokeLater(() -> {
            connectButton.setEnabled(true);
            disconnectButton.setEnabled(false);
            hostField.setEnabled(true);
            portField.setEnabled(true);
        });
    }

    /**
     * Log message to log area with timestamp
     * 
     * @param message Message to log
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
     * Update status label
     * 
     * @param status Status message
     */
    private void updateStatus(String status) {
        statusLabel.setText(status);
    }

    /**
     * Show error message dialog
     * 
     * @param message Error message
     */
    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, message, "错误", JOptionPane.ERROR_MESSAGE);
            logMessage("错误: " + message);
        });
    }

    /**
     * Validate IP address format
     * 
     * @param ip IP address string to validate
     * @return true if valid, false otherwise
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
     * Validate port number
     * 
     * @param port Port number to validate
     * @return true if valid, false otherwise
     */
    private static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    /**
     * Print usage information
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
     * Program entry point
     * 
     * @param args Command line arguments: [host] [port]
     */
    public static void main(String[] args) {
        // Set system look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Parse command line arguments
        if (args.length == 2) {
            try {
                String host = args[0].trim();
                int port = Integer.parseInt(args[1].trim());

                // Validate parameters
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

                // Create receiver with auto-connect parameters
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
            // Normal startup without parameters
            SwingUtilities.invokeLater(() -> {
                H264StreamReceiver receiver = new H264StreamReceiver();
                receiver.setVisible(true);
            });
        } else {
            // Invalid number of arguments
            System.err.println("错误: 参数数量不正确");
            printUsage();
        }
    }
}