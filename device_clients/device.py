#!/usr/bin/env python3
import os
import signal
import time
import socket
import subprocess
import importlib
import traceback
import shutil
import sys

try:
    import requests
except ModuleNotFoundError:
    requests = None

try:
    from PIL import Image
except ModuleNotFoundError:
    Image = None


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
EINK_ORIENTATION = 180
LCD_RELAY_GPIO_PIN = 27
LCD_RELAY_STARTUP_DELAY_SECONDS = 2.5

lcd_current_key = None
eink_current_key = None
viewer = None


DEVICE_SPEC = {
    "name": "Franky",
    "hostname": socket.gethostname(),
    "client_version": "frankenstein-3",
    "screens": {
        "lcd": {
            "type": "lcd",
            "width": LCD_SIZE[0],
            "height": LCD_SIZE[1],
            "color": True,
            "orientation": 0,
            "rotation": 0,
        },
        "eink": {
            "type": "eink",
            "width": EINK_SIZE[0],
            "height": EINK_SIZE[1],
            "color": False,
            "orientation": EINK_ORIENTATION,
            "rotation": EINK_ORIENTATION,
            "driver": "waveshare_epd.epd7in5_V2",
        },
    },
}


def log(*parts):
    print(*parts, flush=True)


def request_shutdown(signum, _frame):
    raise KeyboardInterrupt(f"received signal {signum}")


class LcdRelayController:
    def __init__(self, pin, startup_delay):
        self.pin = pin
        self.startup_delay = startup_delay
        self.gpio = None
        self.available = False

    def setup_low(self):
        try:
            import RPi.GPIO as GPIO
        except ModuleNotFoundError:
            log("[LCD RELAY] RPi.GPIO is not installed; relay control disabled.")
            return
        except Exception as e:
            log("[LCD RELAY] GPIO import failed; relay control disabled.", repr(e))
            return

        try:
            GPIO.setwarnings(False)
            GPIO.setmode(GPIO.BCM)
            GPIO.setup(self.pin, GPIO.OUT, initial=GPIO.LOW)
        except Exception as e:
            log(f"[LCD RELAY] failed to initialise GPIO{self.pin} LOW; relay control disabled.", repr(e))
            return

        self.gpio = GPIO
        self.available = True
        log(f"[LCD RELAY] GPIO{self.pin} initialised LOW; LCD power is off.")

    def power_on(self):
        if not self.available:
            return

        try:
            self.gpio.output(self.pin, self.gpio.HIGH)
            log(f"[LCD RELAY] GPIO{self.pin} set HIGH; LCD power is on.")
        except Exception as e:
            log(f"[LCD RELAY] failed to set GPIO{self.pin} HIGH.", repr(e))

    def power_off(self):
        if not self.available:
            return

        try:
            self.gpio.output(self.pin, self.gpio.LOW)
            log(f"[LCD RELAY] GPIO{self.pin} set LOW; LCD power is off.")
        except Exception as e:
            log(f"[LCD RELAY] failed to set GPIO{self.pin} LOW.", repr(e))

    def power_on_after_startup_delay(self):
        if not self.available:
            return

        log(f"[LCD RELAY] waiting {self.startup_delay:.1f}s before powering LCD.")
        time.sleep(self.startup_delay)
        self.power_on()


def require_dependencies():
    missing = []
    if requests is None:
        missing.append("requests")
    if Image is None:
        missing.append("Pillow")

    if missing:
        package_list = ", ".join(missing)
        log(f"[STARTUP ERROR] Missing Python package(s): {package_list}")
        log("[STARTUP ERROR] Install dependencies with: python3 -m pip install -r requirements.txt")
        sys.exit(1)


def ensure_download_dir():
    try:
        os.makedirs(DOWNLOAD_DIR, exist_ok=True)
    except OSError as e:
        log(f"[STARTUP ERROR] Could not create SHYFTED_DOWNLOAD_DIR={DOWNLOAD_DIR!r}: {e}")
        log("[STARTUP ERROR] Set SHYFTED_DOWNLOAD_DIR to a writable directory and start the client again.")
        sys.exit(1)


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
    return screen_data.get("content_id") or f"{screen_data.get('file')}:{screen_data.get('url')}"


def download(url, target_path, label=None):
    source_url = absolute_url(url)
    if label:
        log(f"[{label} DOWNLOAD] url={source_url}")
        log(f"[{label} DOWNLOAD] save_path={target_path}")

    try:
        response = requests.get(source_url, timeout=REQUEST_TIMEOUT)
        response.raise_for_status()
        content_type = response.headers.get("content-type", "")

        if label and "image" not in content_type.lower():
            preview = response.content[:120].decode("utf-8", errors="replace")
            log(f"[{label} DOWNLOAD] non-image response content_type={content_type} preview={preview!r}")

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


def log_trace(label, error):
    log(label, repr(error))
    log(traceback.format_exc())


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

    if shutil.which("feh") is None:
        log("[LCD ERROR] feh is not installed or not on PATH; cannot display LCD image.")
        return

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


def screen_specs(screen):
    return {
        "type": screen.get("type"),
        "width": screen.get("width"),
        "height": screen.get("height"),
        "color": screen.get("color"),
        "driver": screen.get("driver"),
    }


def log_startup_config():
    lcd = DEVICE_SPEC["screens"]["lcd"]
    eink = DEVICE_SPEC["screens"]["eink"]
    log(f"[STARTUP] declared LCD specs={screen_specs(lcd)}")
    log(f"[STARTUP] declared e-ink specs={screen_specs(eink)}")
    log(f"[STARTUP] declared e-ink orientation={eink['orientation']}")


def prepare_eink_image(path, target_size, _screen_config=None):
    img = Image.open(path)
    log(f"[EINK REFRESH] loaded image path={path} mode={img.mode} size={img.size}")

    if img.size != target_size:
        log(f"[EINK REFRESH] final CMS image size {img.size} differs from panel size {target_size}; resizing defensively")
        img = img.resize(target_size, Image.Resampling.LANCZOS)

    if img.mode != "1":
        log(f"[EINK REFRESH] final CMS image mode is {img.mode}; converting defensively to 1")
        img = img.convert("1")

    return img


def clear_eink(epd):
    clear = getattr(epd, "Clear", None)
    if not callable(clear):
        return

    try:
        clear()
        return
    except TypeError:
        pass

    try:
        clear(0xFF)
    except Exception as e:
        log("[EINK REFRESH] clear skipped", repr(e))


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

    target_size = (
        int(getattr(epd, "width", EINK_SIZE[0])),
        int(getattr(epd, "height", EINK_SIZE[1])),
    )
    img = prepare_eink_image(path, target_size, screen_config)

    log("[EINK REFRESH] physical refresh start")
    clear_eink(epd)
    epd.display(epd.getbuffer(img))
    epd.sleep()
    log("[EINK REFRESH] physical refresh complete")


def main():
    global lcd_current_key, eink_current_key

    signal.signal(signal.SIGTERM, request_shutdown)
    signal.signal(signal.SIGINT, request_shutdown)

    lcd_relay = LcdRelayController(
        LCD_RELAY_GPIO_PIN,
        LCD_RELAY_STARTUP_DELAY_SECONDS,
    )

    try:
        lcd_relay.setup_low()

        require_dependencies()
        ensure_download_dir()

        log(f"Device running against {CMS} as {DEVICE_ID}...")
        log_startup_config()
        lcd_relay.power_on_after_startup_delay()
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
                    f"lcd_content_id={(data.get('lcd') or {}).get('content_id')} "
                    f"eink_file={(data.get('eink') or {}).get('file')} "
                    f"eink_content_id={(data.get('eink') or {}).get('content_id')}"
                )

                lcd = data.get("lcd", {})
                lcd_file = lcd.get("file")
                lcd_url = lcd.get("url")
                lcd_key = image_key(lcd, timestamp)

                if lcd_file and lcd_url and lcd_key != lcd_current_key:
                    log(f"[LCD] rendered_cms_url={absolute_url(lcd_url)} file={lcd_file}")
                    path = download(lcd_url, LCD_RENDERED, "LCD")
                    if path:
                        show_lcd(path)
                        lcd_current_key = lcd_key

                eink = data.get("eink", {})
                eink_file = eink.get("file")
                eink_url = eink.get("url")
                eink_key = image_key(eink, timestamp)
                log(f"[EINK] rendered_cms_url={absolute_url(eink_url)} file={eink_file}")

                if eink_file and eink_url and eink_key != eink_current_key:
                    path = download(eink_url, EINK_RENDERED, "EINK")
                    if path:
                        try:
                            show_eink(path, screens.get("eink"))
                            eink_current_key = eink_key
                        except Exception as e:
                            log_trace("[EINK REFRESH] physical refresh failure", e)

                time.sleep(POLL_SECONDS)

            except KeyboardInterrupt:
                log("Device stopped.")
                break

            except Exception as e:
                log_trace("[LOOP ERROR]", e)
                try:
                    time.sleep(POLL_SECONDS)
                except KeyboardInterrupt:
                    log("Device stopped.")
                    break

    finally:
        stop_lcd()
        lcd_relay.power_off()


if __name__ == "__main__":
    main()
