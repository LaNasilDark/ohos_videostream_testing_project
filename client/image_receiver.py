import socket
import struct
import time
from PyQt6.QtCore import QThread, pyqtSignal
from PyQt6.QtGui import QImage
import queue

PORT = 8000

class ImageReceiver(QThread):
    imageReceived = pyqtSignal(QImage)
    fpsUpdated = pyqtSignal(float)

    def __init__(self, host='localhost', port=PORT):
        super().__init__()
        self.host = host
        self.port = port

    def run(self):
        # 如果host是localhost，尝试获取设备的实际IP
        if self.host == 'localhost':
            device_ip = self.get_device_ip()
            if device_ip:
                self.host = device_ip
                print(f"Using device IP: {device_ip}")
            else:
                print("Failed to get device IP, falling back to localhost")

        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            try:
                sock.connect((self.host, self.port))
                print(f"Connected to the server at {self.host}:{self.port}")
            except Exception as e:
                print("Error connecting to the server:")
                print(f"Error: {e}")
                if self.host != 'localhost':
                    print(f"Make sure the server is running on device {self.host}:{self.port}")
                    print("and that the device is connected to the same network.")
                else:
                    print(f"Make sure the server is running on port {self.port}")
                    print(f"and you have forwarded the port using: hdc -t %device_key% fport tcp:{self.port} tcp:{self.port}")
                return

            last_time = time.time()
            frame_count = 0
            video_buffer = queue.Queue(maxsize=4)
            
            
            with open("recv.h264", 'wb') as f:
            # with open("test.txt", 'wb') as f:
                while True:
                    # Receive size of the image
                    data = sock.recv(4)
                    if data.startswith(b'\x00\x00\x00\x01'):
                        print("Received start of frame marker")   
                    if not data:
                        print("Connection closed by server.")
                        break
                    print(data)
                    # size = struct.unpack('!I', data)[0]
                    # print(size)
                    
                    # for i in range(4):
                    #     f.write(str(data[i:i+1]).encode('utf-8'))
                    #     f.flush()
                    f.write(data)
                    f.flush()    

                    # Receive the image data
                    # image_data = bytearray()
                    # while len(image_data) < size:
                    #     packet = sock.recv(size - len(image_data))
                    #     if not packet:
                    #         print("Connection closed unexpectedly.")
                    #         break

                    #     f.write(packet)
                    #     f.flush()

                    #     image_data.extend(packet)

    def get_device_ip(self):
        """获取设备的IP地址"""
        import subprocess
        try:
            # 使用hdc获取设备IP地址
            result = subprocess.run(['hdc', 'shell', 'ifconfig'], 
                                  capture_output=True, text=True, timeout=10)
            if result.returncode == 0:
                # 解析ifconfig输出，查找wlan0或eth0接口的IP
                lines = result.stdout.split('\n')
                for i, line in enumerate(lines):
                    if 'wlan0' in line or 'eth0' in line:
                        # 查找后续行中的inet addr
                        for j in range(i, min(i+10, len(lines))):
                            if 'inet addr:' in lines[j]:
                                ip = lines[j].split('inet addr:')[1].split()[0]
                                if not ip.startswith('127.'):
                                    return ip
                            elif 'inet ' in lines[j] and not 'inet6' in lines[j]:
                                # 现代ifconfig格式
                                parts = lines[j].strip().split()
                                for k, part in enumerate(parts):
                                    if part == 'inet' and k+1 < len(parts):
                                        ip = parts[k+1]
                                        if not ip.startswith('127.'):
                                            return ip
        except Exception as e:
            print(f"Error getting device IP: {e}")
        return None