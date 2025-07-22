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
    def __init__(self):
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
        # self.image_label.setScaledContents(True) uncomment this and comment resize part in display if need full resize without ratio

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
                # Set an initial size for the window

        # Rest of your initialization code...
        self.receiver = ImageReceiver()
        self.receiver.imageReceived.connect(self.update_image)
        self.receiver.fpsUpdated.connect(self.update_fps)
        self.receiver.start()
        self.is_dragging = False
        self.last_position = None

        # Store the last image for resizing
        self.last_image = None

    def keyPressEvent(self, event: QKeyEvent):
        key = event.text()  # Get the key pressed as text (for printable characters)

    
        key_code = event.key()
        
        if key:
            print(f"Key pressed: {key}")
            handle_key_press(key)
        else:
            print(f"Unhandled key code: {key_code}")

    @pyqtSlot(QImage)
    def update_image(self, image):
        self.last_image = image  # Store the image for use in resizeEvent
        self.display_image()
    
    def display_image(self):
        if self.last_image is None:
            return

        label_width = self.image_label.width()
        label_height = self.image_label.height()

        # Always scale the image to fit the label
        scaled_image = self.last_image.scaled(
            label_width,
            label_height,
            Qt.AspectRatioMode.KeepAspectRatio,
            Qt.TransformationMode.SmoothTransformation
        )

        pixmap = QPixmap.fromImage(scaled_image)
        self.image_label.setPixmap(pixmap)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self.display_image()  # Update the image when the window is resized

    @pyqtSlot(float)
    def update_fps(self, fps):
        # Update window title to include FPS
        self.setWindowTitle(f"{self.baseTitle} with FPS: {fps:.2f}")

    def mousePressEvent(self, event: QMouseEvent):
        """Handle mouse press events for sending touch commands or initiating scroll."""
        if event.button() == Qt.MouseButton.LeftButton:
            self.is_dragging = True
            x, y = event.position().x(), event.position().y()
            image_x, image_y = self.map_to_image_coordinates(x, y)
            if image_x is not None:
                # device resolution is double the image resolution
                send_down_command(int(image_x* 2), int(image_y* 2))
            self.last_position = (x, y)

    def mouseReleaseEvent(self, event: QMouseEvent):
        """Handle mouse release events for sending touch commands or initiating scroll."""
        if event.button() == Qt.MouseButton.LeftButton:
            self.is_dragging = False
            x, y = event.position().x(), event.position().y()
            print("event position", x, y)
            image_x, image_y = self.map_to_image_coordinates(x, y)
            if image_x is not None:
                send_up_command(int(image_x* 2), int(image_y* 2))

    def mouseMoveEvent(self, event: QMouseEvent):
       if self.is_dragging:
        x, y = event.position().x(), event.position().y()
        image_x, image_y = self.map_to_image_coordinates(x, y)
        last_x, last_y = self.last_position
        last_image_x, last_image_y = self.map_to_image_coordinates(last_x, last_y)
        if image_x is not None and last_image_x is not None:
            send_move_command(
                int(last_image_x),
                int(last_image_y),
                int(image_x),
                int(image_y)
            )
            self.last_position = (x, y)

    def map_to_image_coordinates(self, x, y):
        # Get the size of the label and the pixmap
        label_width = self.image_label.width()
        label_height = self.image_label.height()

        pixmap = self.image_label.pixmap()
        if pixmap is None:
            return None, None

        pixmap_width = pixmap.width()
        pixmap_height = pixmap.height()

        # Calculate scaling factors
        scale_x = pixmap_width / label_width
        scale_y = pixmap_height / label_height
        print("pixmap width", pixmap_width, "pixmap height", pixmap_height)
        print("label width", label_width, "label height", label_height)
        # Map the position to the original image coordinates
        print("scaling factors", scale_x, scale_y)
        image_x = x * scale_x
        image_y = y * scale_y
        return image_x, image_y
    
if __name__ == '__main__':
    app = QApplication(sys.argv)
    window = MainWindow()
    window.show()
    sys.exit(app.exec())
