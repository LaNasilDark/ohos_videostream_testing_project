package com.LaNasil;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * H.264解码器 - 将H.264编码的帧数据解码为RGB图像
 */
public class H264Decoder {

    private FFmpegFrameGrabber grabber;
    private Java2DFrameConverter converter;
    private boolean isInitialized = false;

    public H264Decoder() {
        this.converter = new Java2DFrameConverter();
    }

    /**
     * 初始化解码器
     */
    private void initializeDecoder() throws FrameGrabber.Exception {
        if (!isInitialized) {
            grabber = new FFmpegFrameGrabber(new ByteArrayInputStream(new byte[0]));
            grabber.setFormat("h264");
            grabber.start();
            isInitialized = true;
        }
    }

    /**
     * 解码H.264帧数据为RGB图像
     * 
     * @param h264FrameData H.264编码的帧数据
     * @return RGB格式的BufferedImage，如果解码失败返回null
     */
    public BufferedImage decodeToRGB(byte[] h264FrameData) {
        try {
            // 初始化解码器
            if (!isInitialized) {
                initializeDecoder();
            }

            // 创建输入流
            ByteArrayInputStream inputStream = new ByteArrayInputStream(h264FrameData);

            // 重新创建grabber用于新的输入
            if (grabber != null) {
                grabber.stop();
            }

            grabber = new FFmpegFrameGrabber(inputStream);
            grabber.setFormat("h264");
            grabber.start();

            // 抓取并解码帧
            Frame frame = grabber.grab();
            if (frame != null && frame.image != null) {
                // 转换为BufferedImage
                BufferedImage bufferedImage = converter.convert(frame);
                return bufferedImage;
            }

        } catch (Exception e) {
            System.err.println("H.264解码失败: " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    /**
     * 释放解码器资源
     */
    public void release() {
        try {
            if (grabber != null) {
                grabber.stop();
                grabber.release();
            }
            if (converter != null) {
                converter.close();
            }
            isInitialized = false;
        } catch (Exception e) {
            System.err.println("释放解码器资源时出错: " + e.getMessage());
        }
    }

    /**
     * 检查帧数据是否可以解码
     */
    public boolean canDecode(byte[] frameData) {
        return frameData != null && frameData.length > 10 &&
                H264FrameAssembler.isValidH264Frame(frameData);
    }
}
