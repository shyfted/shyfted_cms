import os
import time
import socket
import subprocess

import requests
from PIL import Image


CMS = os.environ.get("SHYFTED_CMS_URL", "https://cms.shyfted.com.au").rstrip("/")
DEVICE_ID = os.environ.get("SHYFTED_DEVICE_ID", "device_001")

CONFIG_URL = f"{CMS}/device/{DEVICE_ID}/config"
HEARTBEAT_URL = f"{CMS}/device/{DEVICE_ID}/heartbeat"

DOWNLOAD_DIR = os.environ.get("SHYFTED_DOWNLOAD_DIR", "/home/pi/downloads")
LCD_RENDERED = os.path.join(DOWNLOAD_DIR, "lcd_rendered.jpg")
EINK_RENDERED = os.path.join(DOWNLOAD_DIR, "eink_rendered.png")

POLL_SECONDS = 5
HEARTBEAT_SECONDS = 60
REQUEST_TIMEOUT = 10

LCD_SIZE = (800, 480)
EINK_SIZE = (800, 480)

os.makedirs(DOWNLOAD_DIR, exist_ok=True)

lcd_current_key = None
eink_current_key = None
viewer = None


DEVICE_SPEC = {
    "name": "The frankenstein",
    "hostname": socket.gethostname(),
    "client_version": "frankenstein-2",
    "screens": {
        "lcd": {
            "type": "lcd",
            "width": LCD_SIZE[0],
            "height": LCD_SIZE[1],
            "color": True,
            "rotation": 0,
        },
        "eink": {
            "type": "eink",
            "width": EINK_SIZE[0],
            "height": EINK_SIZE[1],
            "color": False,
            "rotation": 0,
            "driver": "waveshare_epd.epd7in5_V2",
        },
    },
}


def absolute_url(url):
    if not url:
        return None

    if url.startswith("http://") or url.startswith("https://"):
        return url

    return CMS + url


def send_heartbeat():
    try:
        response = requests.post(
            HEARTBEAT_URL,
            json=DEVICE_SPEC,
            timeout=REQUEST_TIMEOUT,
        )
        response.raise_for_status()
    except Exception as e:
        print("[HEARTBEAT ERROR]", e)


def poll_config():
    response = requests.get(CONFIG_URL, timeout=REQUEST_TIMEOUT)
    response.raise_for_status()
    return response.json()


def image_key(screen_data, timestamp):
    return f"{screen_data.get('file')}:{screen_data.get('url')}:{timestamp}"


def download(url, target_path):
    try:
        response = requests.get(absolute_url(url), timeout=REQUEST_TIMEOUT)
        response.raise_for_status()

        with open(target_path, "wb") as f:
            f.write(response.content)

        return target_path

    except Exception as e:
        print("[DOWNLOAD ERROR]", e)
        return None


def stop_lcd():
    global viewer

    if viewer:
        viewer.terminate()
        try:
            viewer.wait(timeout=3)
        except Exception:
            viewer.kill()
        viewer = None

    subprocess.call(["pkill", "-f", "feh"])


def show_lcd(path):
    global viewer

    stop_lcd()

    viewer = subprocess.Popen(
        [
            "feh",
            "--fullscreen",
            "--hide-pointer",
            "--auto-zoom",
            path,
        ]
    )


def show_eink(path):
    from waveshare_epd import epd7in5_V2

    epd = epd7in5_V2.EPD()
    epd.init()

    img = Image.open(path)

    if img.size != EINK_SIZE:
        img = img.resize(EINK_SIZE)

    img = img.convert("1")

    epd.display(epd.getbuffer(img))
    epd.sleep()


print(f"Device running against {CMS} as {DEVICE_ID}...")
send_heartbeat()

last_heartbeat = time.time()

while True:
    try:
        now = time.time()

        if now - last_heartbeat >= HEARTBEAT_SECONDS:
            send_heartbeat()
            last_heartbeat = now

        data = poll_config()
        timestamp = data.get("timestamp")

        lcd = data.get("lcd", {})
        lcd_file = lcd.get("file")
        lcd_url = lcd.get("url")
        lcd_key = image_key(lcd, timestamp)

        if lcd_file and lcd_url and lcd_key != lcd_current_key:
            path = download(lcd_url, LCD_RENDERED)
            if path:
                show_lcd(path)
                lcd_current_key = lcd_key

        eink = data.get("eink", {})
        eink_file = eink.get("file")
        eink_url = eink.get("url")
        eink_key = image_key(eink, timestamp)

        if eink_file and eink_url and eink_key != eink_current_key:
            path = download(eink_url, EINK_RENDERED)
            if path:
                show_eink(path)
                eink_current_key = eink_key

        time.sleep(POLL_SECONDS)

    except KeyboardInterrupt:
        stop_lcd()
        print("Device stopped.")
        break

    except Exception as e:
        print("[LOOP ERROR]", e)
        time.sleep(POLL_SECONDS)
