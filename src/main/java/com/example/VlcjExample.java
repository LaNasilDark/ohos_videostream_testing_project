package com.example;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * 简单的VLC-J媒体播放器示例
 * 演示如何使用VLC-J播放视频文件或网络流
 */
public class VlcjExample {

    private final JFrame frame;
    private final EmbeddedMediaPlayerComponent mediaPlayerComponent;
    private final JTextField urlField;

    /**
     * 构造函数
     *
     * @param vlcPath VLC播放器的安装路径
     */
    public VlcjExample(String vlcPath) {
        // 创建主窗口
        frame = new JFrame("VLC-J 简单播放器");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(800, 600);
        frame.setLocationRelativeTo(null);

        // 提前初始化urlField，以避免在createControlPanel中出现空指针
        // 更新默认值为TCP H264流的示例
        urlField = new JTextField("tcp://127.0.0.1:8554");

        // 通过指定VLC路径的工厂来创建媒体播放器组件
        // 这是解决 "VLC not found" 问题的推荐方法
        MediaPlayerFactory mediaPlayerFactory = new MediaPlayerFactory(vlcPath);
        mediaPlayerComponent = new EmbeddedMediaPlayerComponent(mediaPlayerFactory, null, null, null, null);

        // 创建控制面板
        JPanel controlPanel = createControlPanel();

        // 布局
        frame.setLayout(new BorderLayout());
        frame.add(mediaPlayerComponent, BorderLayout.CENTER);
        frame.add(controlPanel, BorderLayout.SOUTH);

        // 窗口关闭事件，确保资源被正确释放
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // 释放媒体播放器资源
                mediaPlayerComponent.release();
                System.exit(0);
            }
        });
    }

    /**
     * 创建包含播放控件的面板
     *
     * @return 控制面板
     */
    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new FlowLayout());

        // MRL输入 (MRL: Media Resource Locator)
        panel.add(new JLabel("媒体 MRL:"));
        urlField.setPreferredSize(new Dimension(400, 25));
        panel.add(urlField);

        // 播放按钮
        JButton playButton = new JButton("播放");
        playButton.addActionListener(e -> {
            String url = urlField.getText().trim();
            if (!url.isEmpty()) {
                playMedia(url);
            }
        });
        panel.add(playButton);

        // 暂停按钮
        JButton pauseButton = new JButton("暂停");
        pauseButton.addActionListener(e -> pauseMedia());
        panel.add(pauseButton);

        // 停止按钮
        JButton stopButton = new JButton("停止");
        stopButton.addActionListener(e -> stopMedia());
        panel.add(stopButton);

        return panel;
    }

    /**
     * 播放指定的媒体
     *
     * @param mediaPath 媒体的路径或MRL
     */
    private void playMedia(String mediaPath) {
        MediaPlayer player = mediaPlayerComponent.mediaPlayer();

        // 对于TCP H264流，添加优化选项以减少延迟
        if (mediaPath.startsWith("tcp/h264")) {
            // :network-caching, :live-caching, :file-caching 设置缓存大小（毫秒）
            // 较小的值可以降低延迟，但可能增加卡顿风险
            String[] options = { ":network-caching=150", ":live-caching=150", ":file-caching=150" };
            player.media().play(mediaPath, options);
        } else {
            player.media().play(mediaPath);
        }

        System.out.println("正在播放: " + mediaPath);
    }

    /**
     * 暂停/恢复播放
     */
    private void pauseMedia() {
        MediaPlayer player = mediaPlayerComponent.mediaPlayer();
        player.controls().pause();
        System.out.println("播放已暂停/恢复");
    }

    /**
     * 停止播放
     */
    private void stopMedia() {
        MediaPlayer player = mediaPlayerComponent.mediaPlayer();
        player.controls().stop();
        System.out.println("播放已停止");
    }

    /**
     * 显示播放器窗口
     */
    public void show() {
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        // 设置系统外观
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // !!! 核心修改：使用相对路径来定位捆绑的VLC文件夹 !!!
        // "./VLC" 表示在当前程序运行目录下的 "VLC" 文件夹。
        // 这与您项目中的文件夹名称 "VLC" 保持一致。
        final String vlcPath = "./vlc-module";

        // 在事件分发线程(EDT)中创建和显示GUI
        SwingUtilities.invokeLater(() -> {
            try {
                // 增加一个检查，确保VLC目录存在，以便提供更清晰的错误信息
                java.io.File vlcDir = new java.io.File(vlcPath);
                if (!vlcDir.exists() || !vlcDir.isDirectory()) {
                    throw new RuntimeException(
                            "捆绑的VLC目录未找到！\n请确保 'VLC' 文件夹与您的JAR文件位于同一目录下。\n预期路径: " + vlcDir.getAbsolutePath());
                }

                VlcjExample player = new VlcjExample(vlcPath);
                player.show();
            } catch (Exception e) {
                e.printStackTrace();
                // 更新错误对话框，以反映新的捆绑模式
                JOptionPane.showMessageDialog(null,
                        "无法启动播放器，请检查以下几点：\n" +
                                "1. 'VLC' 文件夹是否与程序在同一目录下且文件完整。\n" +
                                "2. 捆绑的VLC与Java的架构是否匹配（同为64位或32位）。\n\n" +
                                "错误: " + e.getMessage(),
                        "启动错误",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}