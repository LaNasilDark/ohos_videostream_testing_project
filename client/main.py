import sys
import time
import socket
import struct
from PyQt6.QtWidgets import QApplication, QLabel, QMainWindow, QVBoxLayout, QWidget, QSizePolicy
from PyQt6.QtCore import pyqtSlot, Qt
from PyQt6.QtGui import QPixmap, QImage, QMouseEvent, QKeyEvent
from image_receiver import ImageReceiver
from hdc_communicator import send_down_command, send_up_command, send_move_command
from uinput_executor import handle_key_press

PORT = 8000
imageX = 1920
imageY = 1080

class MainWindow(QMainWindow):
    def __init__(self, device_ip='localhost'):
        super().__init__()
        self.baseTitle = 'Live Image Stream'  # Base title for the window
        self.setWindowTitle(self.baseTitle)

        # Create the main layout and adjust margins and spacing
        layout = QVBoxLayout()
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        # Create the image label and adjust size policies
        self.image_label = QLabel(self)
        self.image_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        # self.image_label.setScaledContents(True) # uncomment this and comment resize part in display if need full resize without ratio

        # Set size policy to ignore size hint
        self.image_label.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Ignored)
        self.image_label.setMinimumSize(1, 1)  # Avoid zero size to prevent issues

        layout.addWidget(self.image_label)

        # Create the central widget, set its layout, and adjust size policies
        widget = QWidget()
        widget.setLayout(layout)
        widget.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        widget.setMinimumSize(0, 0)
        self.setCentralWidget(widget)

        # Allow the main window to shrink
        self.resize(imageX, imageY)  # Or any default size

        # Rest of your initialization code...
        self.receiver = ImageReceiver(host=device_ip)
        self.receiver.imageReceived.connect(self.update_image)
        self.receiver.fpsUpdated.connect(self.update_fps)
        self.receiver.start()
        self.is_dragging = False
        self.last_position = None

        # Store the last image for resizing
        self.last_image = None

    @pyqtSlot(QImage)
    def update_image(self, image):
        """Slot function to update the UI with a new image."""
        if not image.isNull():
            self.last_image = image  # Store the original image for resizing
            # Scale the image to the label's size, keeping aspect ratio
            pixmap = QPixmap.fromImage(image)
            scaled_pixmap = pixmap.scaled(self.image_label.size(), Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            self.image_label.setPixmap(scaled_pixmap)

    @pyqtSlot(float)
    def update_fps(self, fps):
        """Slot function to update the FPS in the window title."""
        self.setWindowTitle(f"{self.baseTitle} - FPS: {fps:.2f}")

    def resizeEvent(self, event):
        """Handle window resize events to rescale the image."""
        super().resizeEvent(event)
        if self.last_image and not self.last_image.isNull():
            # On resize, rescale the last received image
            pixmap = QPixmap.fromImage(self.last_image)
            scaled_pixmap = pixmap.scaled(self.image_label.size(), Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            self.image_label.setPixmap(scaled_pixmap)

    def mousePressEvent(self, event: QMouseEvent):
        if event.button() == Qt.MouseButton.LeftButton:
            self.is_dragging = True
            self.last_position = event.position()
            send_down_command(int(self.last_position.x()), int(self.last_position.y()))

    def mouseMoveEvent(self, event: QMouseEvent):
        if self.is_dragging:
            current_position = event.position()
            #send_move_command(int(current_position.x()), int(current_position.y()))
            self.last_position = current_position

    def mouseReleaseEvent(self, event: QMouseEvent):
        if event.button() == Qt.MouseButton.LeftButton:
            self.is_dragging = False
            pos = event.position()
            send_up_command(int(pos.x()), int(pos.y()))

    def keyPressEvent(self, event: QKeyEvent):
        handle_key_press(event.key())

if __name__ == '__main__':
    app = QApplication(sys.argv)
    
    # Support specifying device IP via command line
    device_ip = 'localhost'
    if len(sys.argv) > 1:
        device_ip = sys.argv[1]
    
    window = MainWindow(device_ip)
    window.show()
    sys.exit(app.exec())