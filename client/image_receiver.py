import socket
import struct
import time
from PyQt6.QtCore import QThread, pyqtSignal
from PyQt6.QtGui import QImage


PORT = 8000

class ImageReceiver(QThread):
    imageReceived = pyqtSignal(QImage)
    fpsUpdated = pyqtSignal(float)

    def __init__(self, host='localhost', port=PORT):
        super().__init__()
        self.host = host
        self.port = port

    def run(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            try:
                sock.connect((self.host, self.port))
                print("Connected to the server.")
            except Exception as e:
                print("Error connecting to the server:")
                print(f"Make sure the server is running on port {self.port} \n and you have forwarded the port using the command 'hdc -t %device_key% fport tcp:{self.port} tcp:{self.port}' \n For more information about the setup please refer to launch.bat file.")
                return

            last_time = time.time()
            frame_count = 0
            with open("recv.h264", 'wb') as f:
                while True:
                    # Receive size of the image
                    data = sock.recv(4)
                    if not data:
                        print("Connection closed by server.")
                        break
                    size = struct.unpack('I', data)[0]

                    f.write(data)
                    f.flush()

                    # Receive the image data
                    image_data = bytearray()
                    while len(image_data) < size:
                        packet = sock.recv(size - len(image_data))
                        if not packet:
                            print("Connection closed unexpectedly.")
                            break

                        f.write(packet)
                        f.flush()

                        image_data.extend(packet)
                    