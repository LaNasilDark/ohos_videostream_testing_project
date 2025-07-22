// This implementation is from cleefun/ohscrcpy 
// It is included here for reference only.
// https://gitee.com/cleefun/ohscrcpy/blob/master/server/scrcpy/src/scrcpy_server.cpp
#include <ctime>
#include <errno.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
#include <iostream>

#include "image_utils.h"
#include "screen_manager.h"
#include "surface_reader.h"
#include "surface_reader_handler_impl.h"
using namespace OHOS;
using namespace OHOS::Rosen;
using namespace OHOS::Media;

#define DEFAULT_PORT 8000
#define MAXLINE 4096

const int SLEEP_US = 5 * 1000; // 5ms
// const int MAX_SNAPSHOT_COUNT = 10;
const int MAX_WAIT_COUNT = 200;
static VirtualScreenOption InitOption(ScreenId mainId, SurfaceReader &surfaceReader) {
    auto defaultScreen = ScreenManager::GetInstance().GetScreenById(mainId);
    VirtualScreenOption option = {
        .name_ = "virtualScreen",
        .width_ = defaultScreen->GetWidth(),
        .height_ = defaultScreen->GetHeight(),
        .density_ = 1.0,
        .surface_ = surfaceReader.GetSurface(),
        .flags_ = 0,
        .isForShot_ = true,
    };
    return option;
}

int main(int argc, char **argv) {
    int socket_fd, connect_fd;
    struct sockaddr_in servaddr;

    // 初始化Socket
    if ((socket_fd = socket(AF_INET, SOCK_STREAM, 0)) == -1) {
        printf("create socket error: %s(errno: %d)\n", strerror(errno), errno);
        exit(-1);
    }

    // 初始化
    memset(&servaddr, 0, sizeof(servaddr));
    servaddr.sin_family = AF_INET;
    servaddr.sin_addr.s_addr = htonl(INADDR_ANY); // IP地址设置成INADDR_ANY，让系统自动获取本机的IP地址。
    servaddr.sin_port = htons(DEFAULT_PORT);      // 设置的端口为DEFAULT_PORT

    // 设置端口复用
    int reuse = 1;
    if (setsockopt(socket_fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse)) == -1) {
        printf("setsockopt error: %s(errno: %d)\n", strerror(errno), errno);
        exit(1);
    }

    // 将本地地址绑定到所创建的套接字上
    if (bind(socket_fd, (struct sockaddr *)&servaddr, sizeof(servaddr)) == -1) {
        printf("bind socket error: %s(errno: %d)\n", strerror(errno), errno);
        exit(1);
    }

    // 开始监听是否有客户端连接
    if (listen(socket_fd, 1) == -1) {
        printf("listen socket error: %s(errno: %d)\n", strerror(errno), errno);
        exit(1);
    }

    printf("waiting for client's request\n");

    if ((connect_fd = accept(socket_fd, (struct sockaddr *)NULL, NULL)) == -1) {
        printf("accept socket error: %s(errno: %d)", strerror(errno), errno);
        exit(1);
    }

    SurfaceReader surfaceReader;
    if (!surfaceReader.Init()) {
        printf("surface reader init failed");
        exit(1);
    }

    // 截屏逻辑参考此文件：foundation/window/window_manager/test/demo/demo_snapshot_virtual_screen.cpp
    sptr<SurfaceReaderHandlerImpl> surfaceReaderHandler = new SurfaceReaderHandlerImpl();
    surfaceReader.SetHandler(surfaceReaderHandler);
    ScreenId mainId = static_cast<ScreenId>(DisplayManager::GetInstance().GetDefaultDisplayId());
    VirtualScreenOption option = InitOption(mainId, surfaceReader);
    ScreenId virtualScreenId = ScreenManager::GetInstance().CreateVirtualScreen(option);
    std::vector<ScreenId> mirrorIds{virtualScreenId};
    ScreenId screenGroupId = static_cast<ScreenId>(1);
    ScreenManager::GetInstance().MakeMirror(mainId, mirrorIds, screenGroupId);

    uint8_t *jpegBuf = nullptr;
    uint64_t size = 0;
    while (true) {
        auto start_wait = std::chrono::high_resolution_clock::now();
        int waitCount = 0;
        while (!surfaceReaderHandler->IsImageOk()) {
            waitCount++;
            if (waitCount >= MAX_WAIT_COUNT) {
                std::cout << "wait image overtime" << std::endl;
                break;
            }
            usleep(SLEEP_US);
        }
        if (waitCount >= MAX_WAIT_COUNT) {
            continue;
        }
          auto end_wait = std::chrono::high_resolution_clock::now();
        std::chrono::duration<double> wait_dur = end_wait - start_wait;
        std::cout << "wait " << wait_dur.count() << std::endl;
       

        auto start_get = std::chrono::high_resolution_clock::now();
        auto pixelMap = surfaceReaderHandler->GetPixelMap();
          auto end_get = std::chrono::high_resolution_clock::now();
        std::chrono::duration<double> get_dur = end_get- start_get;
        std::cout << "get " << get_dur.count() << std::endl;
        
        auto start_jpg = std::chrono::high_resolution_clock::now();
        if (!ImageUtils::PixelMapToJpeg(*pixelMap, &jpegBuf, &size) || size < 0 || size > 0x7fffffff) {
            printf("pixel map to jpeg error, size=%lu\n", size);
            exit(1);
        }
         auto end_jpg = std::chrono::high_resolution_clock::now();
        std::chrono::duration<double> jpeg_conversion_duration = end_jpg - start_jpg;
        std::cout << "JPEG " << jpeg_conversion_duration.count() << std::endl;
        int intSize = size;
        // 发送图片大小
        send(connect_fd, &intSize, 4, 0);
        // 发送图片数据
        send(connect_fd, jpegBuf, intSize, 0);

        surfaceReaderHandler->ResetFlag();
    }

    close(connect_fd);
    close(socket_fd);
    ScreenManager::GetInstance().DestroyVirtualScreen(virtualScreenId);
}
