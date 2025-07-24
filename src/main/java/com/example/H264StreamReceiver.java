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

    /**
     * Constructor - initializes the GUI interface
     */
    public H264StreamReceiver() {
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
        hostField = new JTextField(DEFAULT_HOST, 15);
        panel.add(hostField);

        // Port input
        panel.add(new JLabel("端口:"));
        portField = new JTextField(String.valueOf(DEFAULT_PORT), 8);
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

            byte[] sizeBuffer = new byte[4];

            while (isConnected.get() && !Thread.currentThread().isInterrupted()) {

                // Read frame size (4 bytes, little endian)
                int bytesRead = readExact(inputStream, sizeBuffer, 4);
                if (bytesRead != 4) {
                    logMessage("服务器连接已关闭");
                    break;
                }

                // Convert bytes to int (little endian)
                int frameSize = ByteBuffer.wrap(sizeBuffer)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getInt();

                if (frameSize <= 0 || frameSize > 10 * 1024 * 1024) { // Max 10MB per frame
                    logMessage("收到无效的帧大小: " + frameSize + " 字节");
                    continue;
                }

                // Write size to output file
                outputFileStream.write(sizeBuffer);
                outputFileStream.flush();

                // Read frame data
                byte[] frameData = new byte[frameSize];
                bytesRead = readExact(inputStream, frameData, frameSize);

                if (bytesRead != frameSize) {
                    logMessage("帧数据读取不完整，期望: " + frameSize + " 字节，实际: " + bytesRead + " 字节");
                    break;
                }

                // Write frame data to output file
                outputFileStream.write(frameData);
                outputFileStream.flush();

                // Update statistics
                updateStatistics(frameSize + 4); // Include size header

                // Log frame information periodically
                long currentFrame = frameCount.incrementAndGet();
                if (currentFrame % 30 == 0) { // Log every 30 frames
                    logMessage(String.format("接收帧 #%d，大小: %d 字节", currentFrame, frameSize));
                }

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
     * Program entry point
     * 
     * @param args Command line arguments
     */
    public static void main(String[] args) {
        // Set system look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> {
            H264StreamReceiver receiver = new H264StreamReceiver();
            receiver.setVisible(true);
        });
    }
}