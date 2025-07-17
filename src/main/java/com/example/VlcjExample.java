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
 * 演示如何使用VLC-J播放视频文件
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
        urlField = new JTextField("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4");

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

        // URL输入
        panel.add(new JLabel("媒体URL:"));
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
     * @param mediaPath 媒体的路径或URL
     */
    private void playMedia(String mediaPath) {
        MediaPlayer player = mediaPlayerComponent.mediaPlayer();
        player.media().play(mediaPath);
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

        // !!! 重要：在这里设置您的VLC安装路径 !!!
        // 默认的64位VLC安装路径。如果您的路径不同，或者您安装的是32位版本，请修改此字符串。
        // 例如: "C:\\Program Files (x86)\\VideoLAN\\VLC"
        final String vlcPath = "C:\\Program Files\\VideoLAN\\VLC";

        // 在事件分发线程(EDT)中创建和显示GUI
        SwingUtilities.invokeLater(() -> {
            try {
                VlcjExample player = new VlcjExample(vlcPath);
                player.show();
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null,
                        "无法启动播放器，请检查以下几点：\n" +
                                "1. VLC安装路径是否正确: " + vlcPath + "\n" +
                                "2. VLC与Java的架构是否匹配（同为64位或32位）。\n\n" +
                                "错误: " + e.getMessage(),
                        "启动错误",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}