#ifndef IMAGE_UTILS_H
#define IMAGE_UTILS_H

#include <cstdint>
#include <pixel_map.h>
#include <string>

#include "display_manager.h"
#include "dm_common.h"

namespace OHOS {

class ImageUtils {
public:
    static bool PixelMapToJpeg(Media::PixelMap &pixelMap, uint8_t **jpegBuf, uint64_t *size);

private:
    static bool RGBA8888ToRGB888(const uint8_t *rgba8888Buf, uint8_t *rgb888Buf, int32_t size);
    static bool RGB565ToRGB888(const uint8_t* rgb565Buf, uint8_t *rgb888Buf, int32_t size);
    static bool RGB888ToJpeg(uint32_t width, uint32_t height, const uint8_t* rgb888Buf, uint8_t **jpegBuf, uint64_t *size);
};

}

#endif // IMAGE_UTILS_H