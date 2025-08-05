package com.LaNasil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Network data monitoring tool
 * Monitors and outputs data sent from specified source address to local machine
 */
public class NetworkListener extends JFrame {

    private static final String TARGET_HOST = "192.168.5.114";
    private static final int TARGET_PORT = 8000;

    // Remove final keyword from UI components since they are initialized in
    // initializeGui()
    private JTextArea logArea;
    private JButton startButton;
    private JButton stopButton;
    private JButton clearButton;
    private JLabel statusLabel;
    private JTextField hostField;
    private JTextField portField;

    private ServerSocket serverSocket;
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private Thread listenerThread;

    /**
     * Constructor - initializes GUI interface
     */
    public NetworkListener() {
        initializeGui();
    }

    /**
     * Initialize graphical user interface
     */
    private void initializeGui() {
        setTitle("网络数据监听工具");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Create control panel
        JPanel controlPanel = createControlPanel();

        // Create log display area
        logArea = new JTextArea(20, 50);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        // Status label
        statusLabel = new JLabel("就绪");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        // Layout
        add(controlPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);

        // Add default close event handler
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                stopListening();
                System.exit(0);
            }
        });
    }

    /**
     * Create control panel
     * 
     * @return Panel containing control buttons and input fields
     */
    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new FlowLayout());

        // Target host input
        panel.add(new JLabel("目标主机:"));
        hostField = new JTextField(TARGET_HOST, 15);
        panel.add(hostField);

        // Listen port input
        panel.add(new JLabel("监听端口:"));
        portField = new JTextField(String.valueOf(TARGET_PORT), 8);
        panel.add(portField);

        // Start listening button
        startButton = new JButton("开始监听");
        startButton.addActionListener(new StartListenerAction());
        panel.add(startButton);

        // Stop listening button
        stopButton = new JButton("停止监听");
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> stopListening());
        panel.add(stopButton);

        // Clear log button
        clearButton = new JButton("清空日志");
        clearButton.addActionListener(e -> logArea.setText(""));
        panel.add(clearButton);

        return panel;
    }

    /**
     * Action handler for start listening button
     */
    private class StartListenerAction implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            try {
                String host = hostField.getText().trim();
                int port = Integer.parseInt(portField.getText().trim());
                startListening(host, port);
            } catch (NumberFormatException ex) {
                showError("端口号必须是有效的数字");
            } catch (Exception ex) {
                showError("启动监听失败: " + ex.getMessage());
            }
        }
    }

    /**
     * Start listening for data from specified host and port
     * 
     * @param targetHost Target host IP address
     * @param listenPort Listening port
     */
    private void startListening(String targetHost, int listenPort) {
        if (isListening.get()) {
            return;
        }

        try {
            serverSocket = new ServerSocket(listenPort);
            isListening.set(true);

            // Update UI state
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            hostField.setEnabled(false);
            portField.setEnabled(false);

            logMessage("开始监听端口 " + listenPort + "，等待来自 " + targetHost + " 的连接...");
            updateStatus("监听中 - 端口 " + listenPort);

            // Handle connections in new thread
            listenerThread = new Thread(() -> handleConnections(targetHost));
            listenerThread.start();

        } catch (IOException e) {
            showError("无法绑定端口 " + listenPort + ": " + e.getMessage());
            resetUI();
        }
    }

    /**
     * Handle incoming network connections
     * 
     * @param expectedHost Expected source host address
     */
    private void handleConnections(String expectedHost) {
        while (isListening.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Socket clientSocket = serverSocket.accept();
                String clientAddress = clientSocket.getInetAddress().getHostAddress();

                logMessage("收到来自 " + clientAddress + ":" + clientSocket.getPort() + " 的连接");

                // Check if connection is from expected host
                if (!clientAddress.equals(expectedHost)) {
                    logMessage("警告: 连接来自意外的主机 " + clientAddress + "，期望 " + expectedHost);
                }

                // Handle client data in new thread
                Thread clientHandler = new Thread(() -> handleClientData(clientSocket, clientAddress));
                clientHandler.start();

            } catch (IOException e) {
                if (isListening.get()) {
                    logMessage("监听过程中发生错误: " + e.getMessage());
                }
                break;
            }
        }
    }

    /**
     * Handle data sent by client
     * 
     * @param clientSocket  Client socket
     * @param clientAddress Client address
     */
    private void handleClientData(Socket clientSocket, String clientAddress) {
        try (BufferedInputStream inputStream = new BufferedInputStream(clientSocket.getInputStream())) {

            logMessage("开始接收来自 " + clientAddress + " 的数据");

            byte[] buffer = new byte[4096];
            int totalBytesReceived = 0;
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1 && isListening.get()) {
                totalBytesReceived += bytesRead;

                // Log received data information
                logMessage(String.format("从 %s 接收到 %d 字节数据 (总计: %d 字节)",
                        clientAddress, bytesRead, totalBytesReceived));

                // Display data in hexadecimal format (first 64 bytes)
                int displayLength = Math.min(bytesRead, 64);
                StringBuilder hexString = new StringBuilder();
                for (int i = 0; i < displayLength; i++) {
                    hexString.append(String.format("%02X ", buffer[i] & 0xFF));
                    if ((i + 1) % 16 == 0) {
                        hexString.append("\n        ");
                    }
                }

                logMessage("数据内容 (十六进制): " + hexString.toString());

                // Update status display
                final int finalTotal = totalBytesReceived;
                SwingUtilities.invokeLater(() -> updateStatus("监听中 - 已接收 " + finalTotal + " 字节"));
            }

            logMessage("来自 " + clientAddress + " 的连接已关闭，总计接收 " + totalBytesReceived + " 字节");

        } catch (IOException e) {
            logMessage("处理来自 " + clientAddress + " 的数据时发生错误: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                logMessage("关闭客户端连接时发生错误: " + e.getMessage());
            }
        }
    }

    /**
     * Stop listening
     */
    private void stopListening() {
        if (!isListening.get()) {
            return;
        }

        isListening.set(false);

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            if (listenerThread != null) {
                listenerThread.interrupt();
            }
        } catch (IOException e) {
            logMessage("停止监听时发生错误: " + e.getMessage());
        }

        logMessage("监听已停止");
        resetUI();
    }

    /**
     * Reset user interface state
     */
    private void resetUI() {
        SwingUtilities.invokeLater(() -> {
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            hostField.setEnabled(true);
            portField.setEnabled(true);
            updateStatus("就绪");
        });
    }

    /**
     * Log message to log area
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
     * @param status Status information
     */
    private void updateStatus(String status) {
        statusLabel.setText(status);
    }

    /**
     * Show error message
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
            NetworkListener listener = new NetworkListener();
            listener.setVisible(true);
        });
    }
}