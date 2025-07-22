import subprocess
import os

def execute_command(command):
    """Executes a shell command and returns the output."""
    return subprocess.run(command, shell=True, text=True, capture_output=True)

def delete_file(filename):
    """Deletes a file if it exists."""
    if os.path.exists(filename):
        os.remove(filename)

def send_down_command(x, y):
    """Sends a down command to the device."""
    # print(f"Touched at ({x}, {y}), sending uinput to {scaled_x}, {scaled_y} ")
    command = f"hdc shell uinput -T -d {x} {y}"
    execute_command(command)
def send_up_command(x, y):
    """Sends an up command to the device."""
    print(f"Touched at ({x}, {y})")
    command = f"hdc shell uinput -T -u {x} {y}"
    execute_command(command)

def send_move_command(x1, y1, x2, y2):
    """Sends a move command to the device at the specified coordinates, scaled for full resolution."""
    print(f"moving from ({x1}, {y1}) to {x2}, {y2} ")
    command = f"hdc shell uinput -T -m {x1} {y1} {x2} {y2} 200"
    execute_command(command)

def send_touch_command(x, y):
    """Sends a touch command to the device at the specified coordinates, scaled for full resolution."""
    # print(f"Touched at ({x}, {y}), sending uinput to {scaled_x}, {scaled_y} ")
    command = f"hdc shell uinput -T -c {x} {y}"
    execute_command(command)

def get_screenshot_filename(command_output):
    """Extracts the filename from the command output."""
    marker = "write to "
    try:
        start = command_output.index(marker) + len(marker)
        end = command_output.index(' ', start)
        return command_output[start:end]
    except ValueError:
        print("Failed to parse filename from output")
        return None

def download_file(remote_filename, local_filename):
    """Downloads a file from the device to the local system."""
    recv_command = f"hdc file recv {remote_filename} {local_filename}"
    execute_command(recv_command)

def get_screenshot():
    """Takes a screenshot using hdc, retrieves it, and returns the local file path."""
    local_filename = "screenshot.jpg"
    delete_file(local_filename)

    command = "hdc shell snapshot_display"
    result = execute_command(command)
    output = result.stdout + result.stderr

    filename = get_screenshot_filename(output)
    if filename:
        download_file(filename, local_filename)
        return local_filename
    return None
def scroll_up():
    """Simulates a scroll up gesture."""
    commands = [
        "hdc shell uinput -T -i 100",
        "hdc shell uinput -T -d 300 900",  # Touch down at the starting point of the scroll
        "hdc shell uinput -T -m 300 900 300 600 300",  # Move to complete the scroll up gesture
        "hdc shell uinput -T -u 300 600",  # Touch up to end the gesture
        "hdc shell uinput -T -i 100"
    ]
    for command in commands:
        execute_command(command)

def scroll_down():
    """Simulates a scroll down gesture."""
    commands = [
        "hdc shell uinput -T -i 100",
        "hdc shell uinput -T -d 300 600",  # Touch down at the starting point of the scroll
        "hdc shell uinput -T -m 300 600 300 900 300",  # Move to complete the scroll down gesture
        "hdc shell uinput -T -u 300 900",  # Touch up to end the gesture
        "hdc shell uinput -T -i 100"
    ]
    for command in commands:
        execute_command(command)
        
# Example of profiling the modular functions
if __name__ == "__main__":
    import cProfile
    import pstats

    profiler = cProfile.Profile()
    profiler.enable()
    print(get_screenshot())  # Execute the function to be profiled
    profiler.disable()

    stats = pstats.Stats(profiler)
    stats.sort_stats('cumtime').print_stats()  # Sort and print the stats based on cumulative time
