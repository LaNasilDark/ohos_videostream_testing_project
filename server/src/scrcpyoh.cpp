#include <iostream>
#include <memory>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#include "display_manager.h"
#include "image_utils.h"

using namespace OHOS;
using namespace OHOS::Media;
using namespace OHOS::Rosen;

constexpr int DEFAULT_PORT = 8000;

void SetupSocket(int& server_fd, struct sockaddr_in& address) {
    int opt = 1;

    // Creating socket file descriptor
    server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd == 0) {
        std::cerr << "Socket creation failed" << std::endl;
        exit(EXIT_FAILURE);
    }

    // Forcefully attaching socket to the port
    if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR | SO_REUSEPORT, &opt, sizeof(opt)) != 0) {
        std::cerr << "Set socket options failed" << std::endl;
        exit(EXIT_FAILURE);
    }

    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons(DEFAULT_PORT);

    // Binding the socket
    if (bind(server_fd, (struct sockaddr*)&address, sizeof(address)) < 0) {
        std::cerr << "Bind failed" << std::endl;
        exit(EXIT_FAILURE);
    }

    // Listening for connections
    if (listen(server_fd, 3) < 0) {
        std::cerr << "Listen failed" << std::endl;
        exit(EXIT_FAILURE);
    }
}

int AcceptConnection(int server_fd, struct sockaddr_in& address) {
    int addrlen = sizeof(address);
    int new_socket = accept(server_fd, (struct sockaddr*)&address, (socklen_t*)&addrlen);
    if (new_socket < 0) {
        std::cerr << "Accept connection failed" << std::endl;
        exit(EXIT_FAILURE);
    }
    return new_socket;
}

void HandleScreenshotAndSend(int new_socket, std::shared_ptr<OHOS::Media::PixelMap>& pixelMap, uint8_t* jpegBuf, uint64_t& size) {
    auto displayID = DisplayManager::GetInstance().GetDefaultDisplayId();
    auto display = DisplayManager::GetInstance().GetDisplayById(displayID);
    const Media::Rect rect = {0, 0, display->GetWidth(), display->GetHeight()};
    const Media::Size img_size = {370, 570}; // TODO: Get from client
    constexpr int rotation = 0;

    if (!display) {
        std::cerr << "Error: GetDisplayById failed" << std::endl;
        exit(EXIT_FAILURE);
    }

    while (true) {
        pixelMap = DisplayManager::GetInstance().GetScreenshot(displayID, rect, img_size, rotation);
        if (pixelMap) {
            ImageUtils::PixelMapToJpeg(*pixelMap, &jpegBuf, &size);
            int intSize = static_cast<int>(size);
            send(new_socket, &intSize, sizeof(intSize), 0);
            send(new_socket, jpegBuf, intSize, 0);
        } else {
            std::cerr << "Error: Failed to capture screenshot" << std::endl;
            exit(EXIT_FAILURE);
        }
    }
}

int main() {
    int server_fd, new_socket;
    struct sockaddr_in address;
    std::shared_ptr<OHOS::Media::PixelMap> pixelMap = nullptr;
    uint8_t* jpegBuf = nullptr;
    uint64_t size = 0;

    // Setup the server socket
    SetupSocket(server_fd, address);

    // Accept a new connection
    new_socket = AcceptConnection(server_fd, address);

    // Handle screenshot and send to client
    HandleScreenshotAndSend(new_socket, pixelMap, jpegBuf, size);

    // Clean up
    close(new_socket);
    close(server_fd);
    return 0;
}
