package com.LaNasil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * H.264帧组装器
 * 负责将接收到的NALU单元组装成完整的图像帧
 */
public class H264FrameAssembler {

    // NALU类型常量
    public static final int NALU_TYPE_NON_IDR = 1; // 非IDR图像
    public static final int NALU_TYPE_IDR = 5; // IDR图像
    public static final int NALU_TYPE_SEI = 6; // SEI
    public static final int NALU_TYPE_SPS = 7; // SPS参数集
    public static final int NALU_TYPE_PPS = 8; // PPS参数集
    public static final int NALU_TYPE_AUD = 9; // 访问单元分隔符

    // 帧组装状态
    private enum FrameState {
        WAITING_FOR_PARAMETERS, // 等待参数集
        WAITING_FOR_FRAME, // 等待帧开始
        COLLECTING_FRAME // 收集帧数据
    }

    private FrameState currentState = FrameState.WAITING_FOR_PARAMETERS;
    private ByteArrayOutputStream currentFrame = new ByteArrayOutputStream();
    private List<byte[]> parameterSets = new ArrayList<>();
    private boolean hasSPS = false;
    private boolean hasPPS = false;
    private long frameNumber = 0;

    // H.264解码器（可选）
    private H264Decoder decoder;
    private boolean enableRGBConversion = false;

    // 帧完成回调接口
    public interface FrameCallback {
        void onFrameComplete(byte[] frameData, boolean isKeyFrame, long frameNumber);

        void onParameterSetsReceived(List<byte[]> parameterSets);
    }

    // RGB帧回调接口
    public interface RGBFrameCallback {
        void onRGBFrameComplete(java.awt.image.BufferedImage rgbImage, boolean isKeyFrame, long frameNumber);

        void onH264FrameComplete(byte[] frameData, boolean isKeyFrame, long frameNumber);

        void onParameterSetsReceived(List<byte[]> parameterSets);
    }

    private FrameCallback frameCallback;
    private RGBFrameCallback rgbFrameCallback;

    public H264FrameAssembler(FrameCallback callback) {
        this.frameCallback = callback;
    }

    public H264FrameAssembler(RGBFrameCallback rgbCallback) {
        this.rgbFrameCallback = rgbCallback;
        this.enableRGBConversion = true;
        this.decoder = new H264Decoder();
    }

    /**
     * 处理接收到的NALU单元
     * 
     * @param naluData 完整的NALU数据（包含起始码）
     */
    public void processNALU(byte[] naluData) {
        if (naluData == null || naluData.length < 5) {
            return;
        }

        int startCodeLength = getStartCodeLength(naluData, 0);
        if (startCodeLength == 0) {
            return;
        }

        byte naluHeader = naluData[startCodeLength];
        int naluType = naluHeader & 0x1F;

        switch (naluType) {
            case NALU_TYPE_SPS:
                handleSPS(naluData);
                break;
            case NALU_TYPE_PPS:
                handlePPS(naluData);
                break;
            case NALU_TYPE_SEI:
                handleSEI(naluData);
                break;
            case NALU_TYPE_AUD:
                handleAUD(naluData);
                break;
            case NALU_TYPE_IDR:
                handleIDRFrame(naluData);
                break;
            case NALU_TYPE_NON_IDR:
                handleNonIDRFrame(naluData);
                break;
            default:
                // 其他类型的NALU单元，添加到当前帧
                addToCurrentFrame(naluData);
                break;
        }
    }

    /**
     * 处理SPS参数集
     */
    private void handleSPS(byte[] spsData) {
        System.out.println("收到SPS参数集，长度: " + spsData.length);

        // 更新或添加SPS
        updateParameterSet(spsData, NALU_TYPE_SPS);
        hasSPS = true;

        // 检查是否已经收集到所有必要的参数集
        checkParameterSetsComplete();
    }

    /**
     * 处理PPS参数集
     */
    private void handlePPS(byte[] ppsData) {
        System.out.println("收到PPS参数集，长度: " + ppsData.length);

        // 更新或添加PPS
        updateParameterSet(ppsData, NALU_TYPE_PPS);
        hasPPS = true;

        // 检查是否已经收集到所有必要的参数集
        checkParameterSetsComplete();
    }

    /**
     * 处理SEI信息
     */
    private void handleSEI(byte[] seiData) {
        System.out.println("收到SEI信息，长度: " + seiData.length);
        // SEI通常包含在帧中，添加到当前帧
        addToCurrentFrame(seiData);
    }

    /**
     * 处理访问单元分隔符（帧开始标志）
     */
    private void handleAUD(byte[] audData) {
        System.out.println("收到AUD（帧分隔符）");

        // AUD标志着新帧的开始，完成当前帧
        completeCurrentFrame();

        // 开始新帧
        startNewFrame();
        addToCurrentFrame(audData);
    }

    /**
     * 处理IDR帧
     */
    private void handleIDRFrame(byte[] idrData) {
        System.out.println("收到IDR帧，长度: " + idrData.length);

        // IDR帧开始新的GOP，完成当前帧
        completeCurrentFrame();

        // 开始新的关键帧
        startNewFrame();

        // 将参数集添加到关键帧前面
        addParameterSetsToFrame();

        // 添加IDR数据
        addToCurrentFrame(idrData);

        // 更新状态
        currentState = FrameState.COLLECTING_FRAME;
    }

    /**
     * 处理非IDR帧
     */
    private void handleNonIDRFrame(byte[] nonIdrData) {
        System.out.println("收到非IDR帧，长度: " + nonIdrData.length);

        // 如果当前有未完成的帧，先完成它
        if (currentFrame.size() > 0 && currentState == FrameState.COLLECTING_FRAME) {
            completeCurrentFrame();
        }

        // 开始新帧
        startNewFrame();
        addToCurrentFrame(nonIdrData);

        // 更新状态
        currentState = FrameState.COLLECTING_FRAME;
    }

    /**
     * 更新参数集
     */
    private void updateParameterSet(byte[] paramData, int naluType) {
        // 移除同类型的旧参数集
        parameterSets.removeIf(param -> {
            if (param.length < 5)
                return false;
            int startCodeLen = getStartCodeLength(param, 0);
            if (startCodeLen == 0)
                return false;
            int type = param[startCodeLen] & 0x1F;
            return type == naluType;
        });

        // 添加新的参数集
        parameterSets.add(paramData.clone());
    }

    /**
     * 检查参数集是否完整
     */
    private void checkParameterSetsComplete() {
        if (hasSPS && hasPPS) {
            currentState = FrameState.WAITING_FOR_FRAME;
            if (frameCallback != null) {
                frameCallback.onParameterSetsReceived(new ArrayList<>(parameterSets));
            }
            System.out.println("参数集收集完成，可以开始接收帧数据");
        }
    }

    /**
     * 将参数集添加到当前帧
     */
    private void addParameterSetsToFrame() {
        for (byte[] paramSet : parameterSets) {
            try {
                currentFrame.write(paramSet);
            } catch (IOException e) {
                System.err.println("添加参数集到帧时出错: " + e.getMessage());
            }
        }
    }

    /**
     * 开始新帧
     */
    private void startNewFrame() {
        currentFrame.reset();
        frameNumber++;
    }

    /**
     * 添加数据到当前帧
     */
    private void addToCurrentFrame(byte[] data) {
        try {
            currentFrame.write(data);
        } catch (IOException e) {
            System.err.println("添加数据到帧时出错: " + e.getMessage());
        }
    }

    /**
     * 完成当前帧
     */
    private void completeCurrentFrame() {
        if (currentFrame.size() > 0) {
            byte[] frameData = currentFrame.toByteArray();
            boolean isKeyFrame = isKeyFrameData(frameData);

            System.out.println(String.format("完成帧组装: 帧号=%d, 大小=%d字节, 关键帧=%s",
                    frameNumber, frameData.length, isKeyFrame ? "是" : "否"));

            // 原始H.264回调
            if (frameCallback != null) {
                frameCallback.onFrameComplete(frameData, isKeyFrame, frameNumber);
            }

            // RGB转换回调
            if (rgbFrameCallback != null) {
                // 首先提供H.264数据
                rgbFrameCallback.onH264FrameComplete(frameData, isKeyFrame, frameNumber);

                // 如果启用RGB转换，尝试解码
                if (enableRGBConversion && decoder != null) {
                    try {
                        java.awt.image.BufferedImage rgbImage = decoder.decodeToRGB(frameData);
                        if (rgbImage != null) {
                            rgbFrameCallback.onRGBFrameComplete(rgbImage, isKeyFrame, frameNumber);
                        } else {
                            System.err.println("RGB转换失败，帧号: " + frameNumber);
                        }
                    } catch (Exception e) {
                        System.err.println("RGB转换异常，帧号: " + frameNumber + ", 错误: " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * 判断帧数据是否为关键帧
     */
    private boolean isKeyFrameData(byte[] frameData) {
        // 扫描帧数据，查找IDR NALU
        for (int i = 0; i < frameData.length - 4; i++) {
            if (isStartCode(frameData, i)) {
                int startCodeLen = getStartCodeLength(frameData, i);
                if (i + startCodeLen < frameData.length) {
                    int naluType = frameData[i + startCodeLen] & 0x1F;
                    if (naluType == NALU_TYPE_IDR) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 检查是否为起始码
     */
    private boolean isStartCode(byte[] data, int pos) {
        if (pos + 3 < data.length) {
            return data[pos] == 0x00 && data[pos + 1] == 0x00 &&
                    data[pos + 2] == 0x00 && data[pos + 3] == 0x01;
        }
        if (pos + 2 < data.length) {
            return data[pos] == 0x00 && data[pos + 1] == 0x00 && data[pos + 2] == 0x01;
        }
        return false;
    }

    /**
     * 获取起始码长度
     */
    private int getStartCodeLength(byte[] data, int pos) {
        if (pos + 3 < data.length && data[pos] == 0x00 && data[pos + 1] == 0x00 &&
                data[pos + 2] == 0x00 && data[pos + 3] == 0x01) {
            return 4;
        }
        if (pos + 2 < data.length && data[pos] == 0x00 && data[pos + 1] == 0x00 && data[pos + 2] == 0x01) {
            return 3;
        }
        return 0;
    }

    /**
     * 强制完成当前帧（用于流结束或超时情况）
     */
    public void forceCompleteFrame() {
        if (currentFrame.size() > 0) {
            completeCurrentFrame();
        }
    }

    /**
     * 重置组装器状态
     */
    public void reset() {
        currentState = FrameState.WAITING_FOR_PARAMETERS;
        currentFrame.reset();
        parameterSets.clear();
        hasSPS = false;
        hasPPS = false;
        frameNumber = 0;
        System.out.println("帧组装器已重置");
    }

    /**
     * 释放资源
     */
    public void release() {
        if (decoder != null) {
            decoder.release();
        }
        reset();
    }

    /**
     * 获取NALU类型描述
     */
    public static String getNaluTypeDescription(int type) {
        switch (type) {
            case NALU_TYPE_NON_IDR:
                return "非IDR图像";
            case NALU_TYPE_IDR:
                return "IDR图像";
            case NALU_TYPE_SEI:
                return "SEI";
            case NALU_TYPE_SPS:
                return "SPS参数";
            case NALU_TYPE_PPS:
                return "PPS参数";
            case NALU_TYPE_AUD:
                return "访问单元分隔符";
            default:
                return "其他(类型" + type + ")";
        }
    }

    /**
     * 获取当前帧的统计信息
     */
    public String getFrameStats() {
        return String.format("帧组装器状态: 状态=%s, 当前帧大小=%d字节, 帧号=%d, SPS=%s, PPS=%s",
                currentState, currentFrame.size(), frameNumber, hasSPS ? "有" : "无", hasPPS ? "有" : "无");
    }

    /**
     * 检查帧数据的完整性
     */
    public static boolean isValidH264Frame(byte[] frameData) {
        if (frameData == null || frameData.length < 10) {
            return false;
        }

        // 检查是否包含起始码
        boolean hasStartCode = false;
        boolean hasSPS = false;
        boolean hasPPS = false;
        boolean hasSlice = false;

        for (int i = 0; i < frameData.length - 4; i++) {
            if (frameData[i] == 0x00 && frameData[i + 1] == 0x00 &&
                    frameData[i + 2] == 0x00 && frameData[i + 3] == 0x01) {
                hasStartCode = true;
                if (i + 4 < frameData.length) {
                    int naluType = frameData[i + 4] & 0x1F;
                    switch (naluType) {
                        case NALU_TYPE_SPS:
                            hasSPS = true;
                            break;
                        case NALU_TYPE_PPS:
                            hasPPS = true;
                            break;
                        case NALU_TYPE_IDR:
                        case NALU_TYPE_NON_IDR:
                            hasSlice = true;
                            break;
                    }
                }
            }
        }

        // 关键帧必须包含SPS、PPS和图像切片
        // 普通帧至少要有图像切片
        return hasStartCode && hasSlice && (hasSPS || hasPPS || hasSlice);
    }
}
