import os
import time
import socket
import subprocess
import importlib

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


def log(*parts):
    print(*parts, flush=True)


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
        log("[HEARTBEAT ERROR]", e)


def poll_config():
    response = requests.get(CONFIG_URL, timeout=REQUEST_TIMEOUT)
    response.raise_for_status()
    return response.json()


def image_key(screen_data, timestamp):
    return f"{screen_data.get('file')}:{screen_data.get('url')}:{timestamp}"


def download(url, target_path, label=None):
    source_url = absolute_url(url)
    if label:
        log(f"[{label} DOWNLOAD] url={source_url}")
        log(f"[{label} DOWNLOAD] save_path={target_path}")

    try:
        response = requests.get(source_url, timeout=REQUEST_TIMEOUT)
        response.raise_for_status()

        with open(target_path, "wb") as f:
            f.write(response.content)

        if label:
            log(
                f"[{label} DOWNLOAD] success bytes={len(response.content)} "
                f"content_type={response.headers.get('content-type')}"
            )
        return target_path

    except Exception as e:
        if label:
            log(f"[{label} DOWNLOAD] failure", e)
        else:
            log("[DOWNLOAD ERROR]", e)
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


def show_eink(path, screen_config=None):
    screen_config = screen_config or {}
    driver = screen_config.get("driver") or DEVICE_SPEC["screens"]["eink"]["driver"]

    log(f"[EINK INIT] importing {driver}")
    epd_driver = importlib.import_module(driver)

    log("[EINK INIT] creating Waveshare EPD instance")
    epd = epd_driver.EPD()
    log("[EINK INIT] initialising Waveshare e-ink hardware")
    epd.init()
    log(
        "[EINK INIT] ready "
        f"driver={driver} width={getattr(epd, 'width', EINK_SIZE[0])} "
        f"height={getattr(epd, 'height', EINK_SIZE[1])}"
    )

    img = Image.open(path)
    target_size = (
        int(getattr(epd, "width", EINK_SIZE[0])),
        int(getattr(epd, "height", EINK_SIZE[1])),
    )
    log(f"[EINK REFRESH] loaded image path={path} mode={img.mode} size={img.size}")

    if img.size != target_size:
        log(f"[EINK REFRESH] resizing image from {img.size} to {target_size}")
        img = img.resize(target_size)

    img = img.convert("1")

    log("[EINK REFRESH] physical refresh start")
    epd.display(epd.getbuffer(img))
    epd.sleep()
    log("[EINK REFRESH] physical refresh complete")


log(f"Device running against {CMS} as {DEVICE_ID}...")
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
        device = data.get("device") or {}
        screens = device.get("screens") or DEVICE_SPEC["screens"]
        log(
            "[CONFIG] received "
            f"timestamp={timestamp} "
            f"lcd_file={(data.get('lcd') or {}).get('file')} "
            f"eink_file={(data.get('eink') or {}).get('file')}"
        )

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
        log(f"[EINK] selected_url={absolute_url(eink_url)} file={eink_file}")

        if eink_file and eink_url and eink_key != eink_current_key:
            path = download(eink_url, EINK_RENDERED, "EINK")
            if path:
                try:
                    show_eink(path, screens.get("eink"))
                    eink_current_key = eink_key
                except Exception as e:
                    log("[EINK REFRESH] physical refresh failure", e)

        time.sleep(POLL_SECONDS)

    except KeyboardInterrupt:
        stop_lcd()
        log("Device stopped.")
        break

    except Exception as e:
        log("[LOOP ERROR]", e)
        time.sleep(POLL_SECONDS)
