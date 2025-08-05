package com.LaNasil;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * H.264 NALU Splitter
 * Parses H.264 video files and splits them into individual NAL Units
 */
public class H264NaluSplitter {

    /**
     * Data structure to store NALU position information
     */
    static class NaluData {
        int start;
        int end;

        NaluData(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            return;
        }

        String inputFile = args[0];
        String outputDir = args.length >= 2 ? args[1] : "nalu_output";

        // Validate input file
        if (!Files.exists(Paths.get(inputFile))) {
            System.err.println("错误: 输入文件不存在: " + inputFile);
            return;
        }

        if (!inputFile.toLowerCase().endsWith(".h264")) {
            System.out.println("警告: 输入文件不是 .h264 格式，将尝试解析...");
        }

        try {
            // Read entire H.264 file into byte array
            byte[] data = Files.readAllBytes(Paths.get(inputFile));
            System.out.println("读取文件: " + inputFile + " (大小: " + data.length + " 字节)");

            // Validate H.264 format
            if (!isValidH264File(data)) {
                System.err.println("错误: 文件不是有效的H.264格式");
                return;
            }

            List<NaluData> naluList = parseNalus(data);

            if (naluList.isEmpty()) {
                System.err.println("错误: 未找到任何NALU单元");
                return;
            }

            // Create output directory
            Files.createDirectories(Paths.get(outputDir));

            // Process and output each NAL Unit information
            System.out.println("\n=== NALU 解析结果 ===");
            System.out.println("序号    类型  描述           大小(字节)  起始位置    结束位置");
            System.out.println("------------------------------------------------------------");

            for (int i = 0; i < naluList.size(); i++) {
                NaluData nalu = naluList.get(i);
                int start = nalu.start;
                int end = nalu.end;
                int length = end - start + 1;
                byte[] naluBytes = Arrays.copyOfRange(data, start, end + 1);

                // Parse start code and NALU type
                int startCodeLen = getStartCodeLength(data, start);
                byte nalHeader = data[start + startCodeLen];
                int nalType = nalHeader & 0x1F; // Get lower 5 bits

                // Output information to console
                System.out.printf("%-6d  %-4d  %-12s  %-10d  %-10d  %-10d%n",
                        i + 1,
                        nalType,
                        getNaluTypeDescription(nalType),
                        length,
                        start,
                        end);

                // Write to individual file
                String outputPath = outputDir + "/nalu_" + String.format("%04d", i + 1) +
                        "_type" + nalType + ".h264";
                Files.write(Paths.get(outputPath), naluBytes);
            }

            // Summary statistics
            printSummaryStatistics(naluList, data, outputDir);

        } catch (Exception e) {
            System.err.println("处理失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Validate if the file is a valid H.264 format
     * 
     * @param data File data bytes
     * @return true if valid H.264 format, false otherwise
     */
    private static boolean isValidH264File(byte[] data) {
        if (data.length < 4) {
            return false;
        }

        // Check for H.264 start code in first 1000 bytes
        for (int i = 0; i < Math.min(data.length - 3, 1000); i++) {
            if (isStartCode(data, i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if position contains a start code
     * 
     * @param data Byte array
     * @param pos  Position to check
     * @return true if start code found
     */
    private static boolean isStartCode(byte[] data, int pos) {
        if (pos + 2 < data.length) {
            // Check for 3-byte start code (00 00 01)
            if (data[pos] == 0x00 && data[pos + 1] == 0x00 && data[pos + 2] == 0x01) {
                return true;
            }
        }
        if (pos + 3 < data.length) {
            // Check for 4-byte start code (00 00 00 01)
            if (data[pos] == 0x00 && data[pos + 1] == 0x00 &&
                    data[pos + 2] == 0x00 && data[pos + 3] == 0x01) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the length of start code at given position
     * 
     * @param data Byte array
     * @param pos  Start position
     * @return Length of start code (3 or 4)
     */
    private static int getStartCodeLength(byte[] data, int pos) {
        if (pos + 2 < data.length && data[pos + 2] == 0x01) {
            return 3;
        }
        return 4;
    }

    /**
     * Core state machine to parse NALU list
     * 
     * @param data H.264 file data
     * @return List of NALU positions
     */
    private static List<NaluData> parseNalus(byte[] data) {
        List<NaluData> naluList = new ArrayList<>();
        int state = 0;
        int lastStart = -1; // Last start code position
        int len = data.length;

        for (int i = 0; i < len; i++) {
            switch (state) {
                case 0:
                    if (data[i] == 0x00)
                        state = 1;
                    break;
                case 1:
                    if (data[i] == 0x00)
                        state = 2;
                    else
                        state = 0;
                    break;
                case 2:
                    if (data[i] == 0x00)
                        state = 3;
                    else if (data[i] == 0x01) {
                        if (lastStart != -1)
                            naluList.add(new NaluData(lastStart, i - 3));
                        lastStart = i - 2;
                        state = 0;
                    } else
                        state = 0;
                    break;
                case 3:
                    if (data[i] == 0x01) {
                        if (lastStart != -1)
                            naluList.add(new NaluData(lastStart, i - 4));
                        lastStart = i - 3;
                        state = 0;
                    } else if (data[i] != 0x00)
                        state = 0;
                    break;
            }
        }

        // Add the last NALU
        if (lastStart != -1 && lastStart < len) {
            naluList.add(new NaluData(lastStart, len - 1));
        }
        return naluList;
    }

    /**
     * Get NALU type description
     * 
     * @param type NALU type value
     * @return Human-readable description
     */
    private static String getNaluTypeDescription(int type) {
        switch (type) {
            case 1:
                return "非IDR帧";
            case 2:
                return "数据分区A";
            case 3:
                return "数据分区B";
            case 4:
                return "数据分区C";
            case 5:
                return "IDR帧(I帧)";
            case 6:
                return "SEI信息";
            case 7:
                return "SPS参数";
            case 8:
                return "PPS参数";
            case 9:
                return "AU分隔符";
            case 10:
                return "序列结束";
            case 11:
                return "流结束";
            case 12:
                return "填充数据";
            default:
                return "其他类型";
        }
    }

    /**
     * Print usage information
     */
    private static void printUsage() {
        System.out.println("H.264 NALU 分割器");
        System.out.println("用途: 分析H.264视频文件并提取NAL单元");
        System.out.println();
        System.out.println("使用方法:");
        System.out.println("  java H264NaluSplitter <input.h264> [outputDirectory]");
        System.out.println();
        System.out.println("参数说明:");
        System.out.println("  input.h264      - 输入的H.264视频文件");
        System.out.println("  outputDirectory - 输出目录（可选，默认为 'nalu_output'）");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java H264NaluSplitter video.h264");
        System.out.println("  java H264NaluSplitter recv.h264 output_nalus");
        System.out.println();
        System.out.println("输出文件命名格式: nalu_XXXX_typeY.h264");
        System.out.println("  XXXX - NAL单元序号 (0001, 0002, ...)");
        System.out.println("  Y    - NAL单元类型 (1=非IDR帧, 5=IDR帧, 7=SPS, 8=PPS, ...)");
    }

    /**
     * Print summary statistics
     * 
     * @param naluList  List of NALUs
     * @param data      Original file data
     * @param outputDir Output directory
     */
    private static void printSummaryStatistics(List<NaluData> naluList, byte[] data, String outputDir) {
        System.out.println("\n=== 统计信息 ===");
        System.out.println("总NALU数量: " + naluList.size());
        System.out.println("文件总大小: " + data.length + " 字节");

        // Count by type
        Map<Integer, Integer> typeCount = new HashMap<>();
        for (NaluData nalu : naluList) {
            int startCodeLen = getStartCodeLength(data, nalu.start);
            byte nalHeader = data[nalu.start + startCodeLen];
            int nalType = nalHeader & 0x1F;
            typeCount.put(nalType, typeCount.getOrDefault(nalType, 0) + 1);
        }

        System.out.println("\n各类型NALU统计:");
        for (Map.Entry<Integer, Integer> entry : typeCount.entrySet()) {
            int type = entry.getKey();
            int count = entry.getValue();
            System.out.printf("  类型 %2d (%s): %d 个%n",
                    type, getNaluTypeDescription(type), count);
        }

        System.out.println("\n输出目录: " + new File(outputDir).getAbsolutePath());
        System.out.println("解析完成!");
    }
}