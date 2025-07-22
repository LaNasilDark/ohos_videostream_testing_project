import subprocess
import time

#TODO
  # Handle special keys using key codes
        # if key_code == Qt.Key_Return:
        #     print("Enter key pressed")
        #     handle_key_press('enter')
        # elif key_code == Qt.Key_Backspace:
        #     print("Backspace key pressed")
        #     handle_key_press('del')  # 'del' mapped to 2055
        # elif key_code == Qt.Key_Space:
        #     print("Spacebar key pressed")
        #     handle_key_press('space')  # 'space' mapped to 2050
        # elif key_code == Qt.Key_VolumeUp:
        #     print("Volume Up key pressed")
        #     handle_key_press('volume_up')
        # elif key_code == Qt.Key_VolumeDown:
        #     print("Volume Down key pressed")
        #     handle_key_press('volume_down')
        # # Add more special keys if needed

        # else:
# Key mappings from 'a' to 'z' and '0' to '9'
key_mappings = {
    'a': 2017,
    'b': 2018,
    'c': 2019,
    'd': 2020,
    'e': 2021,
    'f': 2022,
    'g': 2023,
    'h': 2024,
    'i': 2025,
    'j': 2026,
    'k': 2027,
    'l': 2028,
    'm': 2029,
    'n': 2030,
    'o': 2031,
    'p': 2032,
    'q': 2033,
    'r': 2034,
    's': 2035,
    't': 2036,
    'u': 2037,
    'v': 2038,
    'w': 2039,
    'x': 2040,
    'y': 2041,
    'z': 2042,
    '0': 2000,
    '1': 2001,
    '2': 2002,
    '3': 2003,
    '4': 2004,
    '5': 2005,
    '6': 2006,
    '7': 2007,
    '8': 2008,
    '9': 2009,
    # You can add more mappings if needed
    ',': 2043,
    '.': 2044,
    'back': 2,  # KEYCODE_BACK
    'volume_up': 16,  # KEYCODE_VOLUME_UP
    'dow': 17,  # DOW
    '*': 2010,  # KEYCODE_STAR
    '#': 2011,  # KEYCODE_POUND
    'space': 2050,  # KEYCODE_SPACE
    'enter': 2054,  # KEYCODE_ENTER
    'del': 2055,  # KEYCODE_DEL (Backspace)
    'copy': 2620,  # KEYCODE_COPY
    'open': 2621,  # KEYCODE_OPEN
    'paste': 2622,  # KEYCODE_PASTE
}

# Function to execute shell commands
def execute_command(command):
    """Executes a shell command and returns the output."""
    result = subprocess.run(command, shell=True, text=True, capture_output=True)
    return result.stdout.strip()

# Function to handle key press and send uinput command
def handle_key_press(key):
    if key in key_mappings:
        mapped_value = key_mappings[key]
        command = f"hdc shell uinput -K -d {mapped_value} -u {mapped_value}"
        print(f"Executing command: {command}")
        execute_command(command)
    else:
        print(f"Key '{key}' not mapped.")

# Example of how to trigger key press and send command
if __name__ == "__main__":
 # Test the script with some keys
    keys_to_test = ['a', 'b', 'c']

    for key in keys_to_test:
        value = key_mappings.get(key)
        handle_key_press(key)
        # time.sleep(0.2)  # Adding a delay for testing purposes