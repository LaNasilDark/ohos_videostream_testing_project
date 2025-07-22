#include "image_utils.h"
#include <cerrno>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <image_type.h>
#include <iostream>
#include <csetjmp>
#include <pixel_map.h>
#include "jpeglib.h"
#include <securec.h>
#include <string>
#include <sys/time.h>

using namespace OHOS::Rosen;

namespace OHOS {

constexpr int32_t RGB565_PIXEL_BYTES = 2;
constexpr int32_t RGB888_PIXEL_BYTES = 3;
constexpr int32_t RGBA8888_PIXEL_BYTES = 4;
constexpr uint8_t B_INDEX = 0;
constexpr uint8_t G_INDEX = 1;
constexpr uint8_t R_INDEX = 2;
constexpr uint8_t SHIFT_2_BIT = 2;
constexpr uint8_t SHIFT_3_BIT = 3;
constexpr uint8_t SHIFT_5_BIT = 5;
constexpr uint8_t SHIFT_8_BIT = 8;
constexpr uint8_t SHIFT_11_BIT = 11;
constexpr uint8_t SHIFT_16_BIT = 16;

constexpr uint16_t RGB565_MASK_BLUE = 0xF800;
constexpr uint16_t RGB565_MASK_GREEN = 0x07E0;
constexpr uint16_t RGB565_MASK_RED = 0x001F;
constexpr uint32_t RGBA8888_MASK_BLUE = 0x000000FF;
constexpr uint32_t RGBA8888_MASK_GREEN = 0x0000FF00;
constexpr uint32_t RGBA8888_MASK_RED = 0x00FF0000;

struct MissionErrorMgr : public jpeg_error_mgr {
    jmp_buf environment;
};

void mission_error_exit(j_common_ptr cinfo) {
    if (cinfo == nullptr || cinfo->err == nullptr) {
        std::cerr << "Error: Invalid parameters for JPEG error management." << std::endl;
        return;
    }
    auto err = (MissionErrorMgr*)cinfo->err;
    longjmp(err->environment, 1);
}

bool ImageUtils::PixelMapToJpeg(Media::PixelMap &pixelMap, uint8_t **jpegBuf, uint64_t *size) {
    uint32_t width = static_cast<uint32_t>(pixelMap.GetWidth());
    uint32_t height = static_cast<uint32_t>(pixelMap.GetHeight());
    const uint8_t *data = pixelMap.GetPixels();
    uint32_t stride = static_cast<uint32_t>(pixelMap.GetRowBytes());
    Media::PixelFormat format = pixelMap.GetPixelFormat();

    bool ret = false;
    if (format == Media::PixelFormat::RGBA_8888) {
        int32_t rgb888Size = stride * height * RGB888_PIXEL_BYTES / RGBA8888_PIXEL_BYTES;
        auto rgb888 = std::unique_ptr<uint8_t[]>(new uint8_t[rgb888Size]);
        ret = RGBA8888ToRGB888(data, rgb888.get(), rgb888Size / RGB888_PIXEL_BYTES);
        if (ret) {
            std::cout << "Converted RGBA8888 to RGB888 successfully." << std::endl;
            ret = RGB888ToJpeg(width, height, rgb888.get(), jpegBuf, size);
        }
    } else if (format == Media::PixelFormat::RGB_565) {
        int32_t rgb888Size = stride * height * RGB888_PIXEL_BYTES / RGB565_PIXEL_BYTES;
        auto rgb888 = std::unique_ptr<uint8_t[]>(new uint8_t[rgb888Size]);
        ret = RGB565ToRGB888(data, rgb888.get(), rgb888Size / RGB888_PIXEL_BYTES);
        if (ret) {
            std::cout << "Converted RGB565 to RGB888 successfully." << std::endl;
            ret = RGB888ToJpeg(width, height, rgb888.get(), jpegBuf, size);
        }
    } else if (format == Media::PixelFormat::RGB_888) {
        ret = RGB888ToJpeg(width, height, data, jpegBuf, size);
    }
    return ret;
}

bool ImageUtils::RGBA8888ToRGB888(const uint8_t *rgba8888Buf, uint8_t *rgb888Buf, int32_t size) {
    if (rgba8888Buf == nullptr || rgb888Buf == nullptr || size <= 0) {
        std::cerr << "Invalid parameters for RGBA8888 to RGB888 conversion." << std::endl;
        return false;
    }
    const uint32_t *rgba8888 = reinterpret_cast<const uint32_t *>(rgba8888Buf);
    for (int32_t i = 0; i < size; i++) {
        int basePixel = i * RGB888_PIXEL_BYTES;
        rgb888Buf[basePixel + R_INDEX] = (rgba8888[i] & RGBA8888_MASK_RED) >> SHIFT_16_BIT;
        rgb888Buf[basePixel + G_INDEX] = (rgba8888[i] & RGBA8888_MASK_GREEN) >> SHIFT_8_BIT;
        rgb888Buf[basePixel + B_INDEX] = rgba8888[i] & RGBA8888_MASK_BLUE;
    }
    return true;
}

bool ImageUtils::RGB565ToRGB888(const uint8_t *rgb565Buf, uint8_t *rgb888Buf, int32_t size) {
    if (rgb565Buf == nullptr || rgb888Buf == nullptr || size <= 0) {
        std::cerr << "Invalid parameters for RGB565 to RGB888 conversion." << std::endl;
        return false;
    }
    const uint16_t *rgb565 = reinterpret_cast<const uint16_t *>(rgb565Buf);
    for (int32_t i = 0; i < size; i++) {
        int basePixel = i * RGB888_PIXEL_BYTES;
        rgb888Buf[basePixel + R_INDEX] = (rgb565[i] & RGB565_MASK_RED) << SHIFT_3_BIT;
        rgb888Buf[basePixel + G_INDEX] = ((rgb565[i] & RGB565_MASK_GREEN) >> SHIFT_5_BIT) << SHIFT_2_BIT;
        rgb888Buf[basePixel + B_INDEX] = ((rgb565[i] & RGB565_MASK_BLUE) >> SHIFT_11_BIT) << SHIFT_3_BIT;
    }
    return true;
}

bool ImageUtils::RGB888ToJpeg(uint32_t width, uint32_t height, const uint8_t* rgb888Buf, uint8_t **jpegBuf, uint64_t *size) {
    if (rgb888Buf == nullptr) {
        std::cerr << "Error: RGB buffer is null, cannot encode to JPEG." << std::endl;
        return false;
    }

    jpeg_compress_struct jpeg;
    MissionErrorMgr jerr;
    jpeg.err = jpeg_std_error(&jerr);
    jerr.error_exit = mission_error_exit;
    if (setjmp(jerr.environment)) {
        jpeg_destroy_compress(&jpeg);
        std::cerr << "JPEG compression failed due to internal error." << std::endl;
        return false;
    }

    jpeg_create_compress(&jpeg);
    jpeg.image_width = width;
    jpeg.image_height = height;
    jpeg.input_components = RGB888_PIXEL_BYTES;
    jpeg.in_color_space = JCS_RGB;
    jpeg_set_defaults(&jpeg);

    constexpr int32_t quality = 50;  // Set JPEG compression quality to 50
    jpeg_set_quality(&jpeg, quality, TRUE);

    jpeg_mem_dest(&jpeg, jpegBuf, size);
    jpeg_start_compress(&jpeg, TRUE);
    JSAMPROW rowPointer[1];
    while (jpeg.next_scanline < jpeg.image_height) {
        rowPointer[0] = const_cast<uint8_t *>(rgb888Buf + jpeg.next_scanline * width * RGB888_PIXEL_BYTES);
        jpeg_write_scanlines(&jpeg, rowPointer, 1);
    }

    jpeg_finish_compress(&jpeg);
    jpeg_destroy_compress(&jpeg);
    return true;
}

} // namespace OHOS
