from functools import wraps
from io import BytesIO

from flask import Flask, request, jsonify, render_template, redirect, session, url_for, flash, send_file, abort, g
import os
import json
import hashlib
import secrets
import smtplib
import sqlite3
from datetime import datetime, timedelta
from email.message import EmailMessage
from urllib.parse import urlparse
import click
from PIL import Image, ImageOps
import fitz
from werkzeug.security import check_password_hash, generate_password_hash

app = Flask(__name__)
app.secret_key = os.environ.get("AUTH_SESSION_SECRET") or os.environ.get("SECRET_KEY")
if not app.secret_key:
    raise RuntimeError("Set AUTH_SESSION_SECRET before starting the CMS.")

DATA_DIR = "data"
UPLOAD_FOLDER = os.path.join("static", "uploads")
ORIGINAL_UPLOAD_FOLDER = os.path.join(UPLOAD_FOLDER, "original")
NORMALISED_UPLOAD_FOLDER = os.path.join(UPLOAD_FOLDER, "normalised")
RENDERED_UPLOAD_FOLDER = os.path.join(UPLOAD_FOLDER, "rendered")
LCD_RENDERED_FOLDER = os.path.join(RENDERED_UPLOAD_FOLDER, "lcd")
EINK_RENDERED_FOLDER = os.path.join(RENDERED_UPLOAD_FOLDER, "eink")
THUMBNAIL_FOLDER = os.path.join(UPLOAD_FOLDER, "thumbs")
APP_URL = os.environ.get("APP_URL", "http://localhost:5050").rstrip("/")
DATABASE_URL = os.environ.get("DATABASE_URL", f"sqlite:///{os.path.join(DATA_DIR, 'cms.db')}")
SESSION_LIFETIME_HOURS = int(os.environ.get("SESSION_LIFETIME_HOURS", "8"))
WINDOW_SESSION_STORAGE_KEY = "shyfted_cms_window_token"
RESET_TOKEN_MINUTES = int(os.environ.get("RESET_TOKEN_MINUTES", "30"))
LOGIN_RATE_LIMIT = int(os.environ.get("LOGIN_RATE_LIMIT", "5"))
RESET_RATE_LIMIT = int(os.environ.get("RESET_RATE_LIMIT", "5"))
RATE_LIMIT_WINDOW_MINUTES = int(os.environ.get("RATE_LIMIT_WINDOW_MINUTES", "15"))
COOKIE_SECURE = os.environ.get("SESSION_COOKIE_SECURE", "true").lower() == "true"

app.config.update(
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE="Lax",
    SESSION_COOKIE_SECURE=COOKIE_SECURE,
    PERMANENT_SESSION_LIFETIME=timedelta(hours=SESSION_LIFETIME_HOURS),
)

os.makedirs(DATA_DIR, exist_ok=True)
os.makedirs(UPLOAD_FOLDER, exist_ok=True)
for upload_dir in (
    ORIGINAL_UPLOAD_FOLDER,
):
    os.makedirs(upload_dir, exist_ok=True)

ALLOWED_EXTENSIONS = {"png", "jpg", "jpeg", "pdf"}
MAX_FILE_SIZE = 5 * 1024 * 1024
RENDER_RULE_VERSION = "render-v2"


def allowed_file(filename):
    return "." in filename and filename.rsplit(".", 1)[1].lower() in ALLOWED_EXTENSIONS


def get_extension(filename):
    if "." not in filename:
        return ""
    return filename.rsplit(".", 1)[1].lower()


def cleanup_upload(path):
    if os.path.exists(path):
        os.remove(path)

    thumb = path + ".png"
    if os.path.exists(thumb):
        os.remove(thumb)


def clean_filename(name):
    if not name:
        return ""

    return os.path.basename(name.strip().replace(" ", "_"))


def upload_target_conflict(path):
    if os.path.exists(path):
        return "A file with that name already exists."

    if os.path.exists(path + ".png"):
        return "A thumbnail with that name already exists."

    return None


def converted_pdf_path(path):
    return path.rsplit(".", 1)[0] + ".jpg"


def converted_pdf_filename(filename):
    return filename.rsplit(".", 1)[0] + ".jpg"


def upload_path(folder, filename):
    return os.path.join(folder, filename)


def load_json(filename, default):
    path = os.path.join(DATA_DIR, filename)
    if not os.path.exists(path):
        return default
    with open(path, "r") as f:
        return json.load(f)


def save_json(filename, data):
    with open(os.path.join(DATA_DIR, filename), "w") as f:
        json.dump(data, f, indent=4)


def get_media_catalog():
    return load_json("media.json", {})


def save_media_catalog(catalog):
    save_json("media.json", catalog)


def utc_timestamp():
    return datetime.utcnow().isoformat() + "Z"


def db_path():
    if DATABASE_URL.startswith("sqlite:////"):
        return DATABASE_URL.replace("sqlite://", "", 1)
    if DATABASE_URL.startswith("sqlite:///"):
        return DATABASE_URL.replace("sqlite:///", "", 1)

    parsed = urlparse(DATABASE_URL)
    if parsed.scheme in ("", "sqlite"):
        if parsed.scheme == "":
            return DATABASE_URL
        if parsed.netloc and parsed.netloc != ".":
            return f"/{parsed.netloc}{parsed.path}"
        return parsed.path or os.path.join(DATA_DIR, "cms.db")

    raise RuntimeError("Only sqlite DATABASE_URL values are supported by this CMS MVP.")


def get_db():
    if "db" not in g:
        path = db_path()
        directory = os.path.dirname(path)
        if directory:
            os.makedirs(directory, exist_ok=True)
        g.db = sqlite3.connect(path)
        g.db.row_factory = sqlite3.Row
    return g.db


@app.teardown_appcontext
def close_db(_error=None):
    db = g.pop("db", None)
    if db is not None:
        db.close()


def init_db():
    db = get_db()
    db.executescript(
        """
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            email TEXT NOT NULL UNIQUE,
            name TEXT NOT NULL,
            role TEXT NOT NULL CHECK(role IN ('admin', 'staff')),
            password_hash TEXT NOT NULL,
            is_active INTEGER NOT NULL DEFAULT 1,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            last_login_at TEXT
        );

        CREATE TABLE IF NOT EXISTS password_reset_tokens (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            token_hash TEXT NOT NULL UNIQUE,
            expires_at TEXT NOT NULL,
            used_at TEXT,
            created_at TEXT NOT NULL,
            FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
        );

        CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_hash
            ON password_reset_tokens(token_hash);
        CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user
            ON password_reset_tokens(user_id);
        """
    )
    db.commit()


@app.cli.command("create-admin")
@click.option("--email", prompt=True)
@click.option("--name", prompt=True)
@click.password_option("--password", confirmation_prompt=True)
def create_admin_command(email, name, password):
    """Create an initial admin user without enabling public registration."""
    if len(password) < 10:
        raise click.ClickException("Password must be at least 10 characters.")
    if find_user_by_email(email):
        raise click.ClickException("A user with that email already exists.")

    create_user(email, name, "admin", password)
    click.echo(f"Created admin user {email.strip().lower()}.")


def row_to_user(row):
    return dict(row) if row else None


def find_user_by_email(email):
    row = get_db().execute(
        "SELECT * FROM users WHERE lower(email) = lower(?)",
        ((email or "").strip(),),
    ).fetchone()
    return row_to_user(row)


def find_user_by_id(user_id):
    row = get_db().execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()
    return row_to_user(row)


def current_user():
    user_id = session.get("user_id")
    if not user_id:
        return None

    user = find_user_by_id(user_id)
    if not user or not user["is_active"]:
        session.clear()
        return None

    return user


def start_window_session():
    session.permanent = False
    session["window_token"] = secrets.token_urlsafe(32)
    session["window_session_new"] = True


def hash_password(password):
    return generate_password_hash(password, method="pbkdf2:sha256:600000", salt_length=16)


def create_user(email, name, role, password):
    now = utc_timestamp()
    password_hash = hash_password(password)
    db = get_db()
    cursor = db.execute(
        """
        INSERT INTO users (email, name, role, password_hash, is_active, created_at, updated_at)
        VALUES (?, ?, ?, ?, 1, ?, ?)
        """,
        (email.strip().lower(), name.strip(), role, password_hash, now, now),
    )
    db.commit()
    return cursor.lastrowid


def bootstrap_first_admin():
    db = get_db()
    user_count = db.execute("SELECT COUNT(*) FROM users").fetchone()[0]
    if user_count:
        return

    email = (os.environ.get("CMS_ADMIN_EMAIL") or "").strip()
    password = os.environ.get("CMS_ADMIN_PASSWORD") or ""
    if not email and not password:
        return
    if not email or not password:
        raise RuntimeError("Set both CMS_ADMIN_EMAIL and CMS_ADMIN_PASSWORD to bootstrap the first admin.")
    if "@" not in email:
        raise RuntimeError("CMS_ADMIN_EMAIL must be a valid email address.")
    if len(password) < 10:
        raise RuntimeError("CMS_ADMIN_PASSWORD must be at least 10 characters.")

    create_user(email, "Admin", "admin", password)


with app.app_context():
    init_db()
    bootstrap_first_admin()


def token_hash(token):
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def create_password_reset_token(user_id):
    token = secrets.token_urlsafe(32)
    now = datetime.utcnow()
    expires_at = now + timedelta(minutes=RESET_TOKEN_MINUTES)
    get_db().execute(
        """
        INSERT INTO password_reset_tokens (user_id, token_hash, expires_at, created_at)
        VALUES (?, ?, ?, ?)
        """,
        (user_id, token_hash(token), expires_at.isoformat() + "Z", now.isoformat() + "Z"),
    )
    get_db().commit()
    return token


def send_email(to_email, subject, body):
    host = os.environ.get("SMTP_HOST")
    port = int(os.environ.get("SMTP_PORT", "587"))
    username = os.environ.get("SMTP_USERNAME")
    password = os.environ.get("SMTP_PASSWORD")
    sender = os.environ.get("SMTP_FROM")
    use_tls = os.environ.get("SMTP_USE_TLS", "true").lower() == "true"

    if not host or not sender:
        print("[EMAIL NOT CONFIGURED]", subject)
        return False

    message = EmailMessage()
    message["From"] = sender
    message["To"] = to_email
    message["Subject"] = subject
    message.set_content(body)

    with smtplib.SMTP(host, port, timeout=10) as smtp:
        if use_tls:
            smtp.starttls()
        if username:
            smtp.login(username, password or "")
        smtp.send_message(message)
    return True


def send_password_reset_email(user, token):
    reset_url = f"{APP_URL}{url_for('reset_password', token=token)}"
    body = (
        f"Hi {user['name']},\n\n"
        "Use this link to reset your Shyfted CMS password. "
        f"The link expires in {RESET_TOKEN_MINUTES} minutes:\n\n"
        f"{reset_url}\n\n"
        "If you did not request this, you can ignore this email."
    )
    return send_email(user["email"], "Reset your Shyfted CMS password", body)


def rate_limit_key(scope):
    forwarded_for = request.headers.get("X-Forwarded-For", "")
    ip = forwarded_for.split(",")[0].strip() or request.remote_addr or "unknown"
    return f"{scope}:{ip}"


def check_rate_limit(scope, max_attempts):
    now = datetime.utcnow()
    window_start = now - timedelta(minutes=RATE_LIMIT_WINDOW_MINUTES)
    limits = session.setdefault("rate_limits", {})
    key = rate_limit_key(scope)
    attempts = [
        item for item in limits.get(key, [])
        if datetime.fromisoformat(item) > window_start
    ]

    if len(attempts) >= max_attempts:
        limits[key] = attempts
        session["rate_limits"] = limits
        return False

    attempts.append(now.isoformat())
    limits[key] = attempts
    session["rate_limits"] = limits
    return True


def generate_csrf_token():
    token = session.get("csrf_token")
    if not token:
        token = secrets.token_urlsafe(32)
        session["csrf_token"] = token
    return token


@app.context_processor
def inject_auth_context():
    return {
        "csrf_token": generate_csrf_token,
        "current_user": current_user(),
        "window_session_storage_key": WINDOW_SESSION_STORAGE_KEY,
    }


def validate_csrf():
    form_token = request.form.get("csrf_token", "")
    session_token = session.get("csrf_token", "")
    if not session_token or not secrets.compare_digest(form_token, session_token):
        abort(400)


@app.before_request
def enforce_session_expiry():
    expires_at = session.get("expires_at")
    if not expires_at:
        return

    try:
        expires = datetime.fromisoformat(expires_at)
    except ValueError:
        session.clear()
        return

    if datetime.utcnow() > expires:
        session.clear()
        flash("Your session expired. Log in again.", "error")


def login_required(view):
    @wraps(view)
    def wrapped_view(*args, **kwargs):
        user = current_user()
        if not user:
            return redirect(url_for("login", next=request.path))
        return view(*args, **kwargs)

    return wrapped_view


def admin_required(view):
    @wraps(view)
    def wrapped_view(*args, **kwargs):
        user = current_user()
        if not user:
            return redirect(url_for("login", next=request.path))
        if user["role"] != "admin":
            abort(403)
        return view(*args, **kwargs)

    return wrapped_view


def convert_pdf(path):
    with fitz.open(path) as doc:
        if doc.page_count < 1:
            raise ValueError("PDF has no pages")

        page = doc.load_page(0)
        pix = page.get_pixmap()
        out = path.rsplit(".", 1)[0] + ".jpg"
        pix.save(out)

    return os.path.basename(out)


def load_source_image(path):
    if get_extension(path) == "pdf":
        with fitz.open(path) as doc:
            if doc.page_count < 1:
                raise ValueError("PDF has no pages")

            page = doc.load_page(0)
            pix = page.get_pixmap(alpha=False)
            return Image.frombytes("RGB", (pix.width, pix.height), pix.samples)

    img = Image.open(path)
    img = ImageOps.exif_transpose(img)
    return img.convert("RGB")


def normalised_source_image(path):
    img = load_source_image(path)
    img.thumbnail((2000, 2000), Image.Resampling.LANCZOS)
    return img


def make_thumb_image(source_path):
    img = normalised_source_image(source_path)
    img.thumbnail((200, 140))
    return img


def legacy_upload_path(filename):
    return upload_path(UPLOAD_FOLDER, filename)


def normalised_upload_path(filename):
    return upload_path(NORMALISED_UPLOAD_FOLDER, filename)


def original_upload_path(filename):
    return upload_path(ORIGINAL_UPLOAD_FOLDER, filename)


def rendered_upload_path(screen, filename):
    folder = LCD_RENDERED_FOLDER if screen == "lcd" else EINK_RENDERED_FOLDER
    extension = "jpg" if screen == "lcd" else "png"
    base = filename.rsplit(".", 1)[0]
    return upload_path(folder, f"{base}.{extension}")


def thumb_upload_path(filename):
    return upload_path(THUMBNAIL_FOLDER, f"{filename}.png")


def existing_source_path(filename):
    if not filename:
        return None

    catalog = get_media_catalog()
    original = (catalog.get(filename) or {}).get("original")
    if original and os.path.isfile(original_upload_path(original)):
        return original_upload_path(original)

    legacy = legacy_upload_path(filename)
    if os.path.isfile(legacy):
        return legacy

    normalised = normalised_upload_path(filename)
    if os.path.isfile(normalised):
        return normalised

    return None


def public_upload_url(folder, filename, version=None):
    url = f"/static/uploads/{folder}/{filename}"
    if version is not None:
        url = f"{url}?v={version}"
    return url


def original_preview_url(filename, catalog, version=None):
    original = (catalog.get(filename) or {}).get("original") or filename
    if os.path.isfile(original_upload_path(original)):
        return public_upload_url("original", original, version)
    return versioned_upload_url(filename, version)


def source_version(filename):
    path = existing_source_path(filename)
    if not path or not os.path.exists(path):
        return None

    stat = os.stat(path)
    digest = hashlib.sha256(f"{filename}:{stat.st_size}:{stat.st_mtime_ns}".encode("utf-8")).hexdigest()
    return digest[:16]


def preview_upload_url(variant, filename, version=None):
    if not filename:
        return None

    url = f"/preview/{variant}/{filename}"
    if version is not None:
        url = f"{url}?v={version}"
    return url


def upload_exists(filename):
    if not filename:
        return False

    return existing_source_path(filename) is not None


def list_uploads():
    files = []
    seen = set()
    catalog = get_media_catalog()

    for filename in sorted(catalog.keys()):
        path = existing_source_path(filename)
        if not path:
            continue

        version = source_version(filename)
        files.append({
            "name": filename,
            "version": version,
            "thumb_url": preview_upload_url("thumb", filename, version),
            "original_url": original_preview_url(filename, catalog, version),
            "normalised_url": preview_upload_url("normalised", filename, version),
            "lcd_url": preview_upload_url("lcd", filename, version),
            "eink_url": preview_upload_url("eink", filename, version),
        })
        seen.add(filename)

    if os.path.isdir(NORMALISED_UPLOAD_FOLDER):
        for filename in os.listdir(NORMALISED_UPLOAD_FOLDER):
            path = normalised_upload_path(filename)
            if filename in seen or not os.path.isfile(path):
                continue

            version = source_version(filename)
            files.append({
                "name": filename,
                "version": version,
                "thumb_url": preview_upload_url("thumb", filename, version),
                "original_url": original_preview_url(filename, catalog, version),
                "normalised_url": preview_upload_url("normalised", filename, version),
                "lcd_url": preview_upload_url("lcd", filename, version),
                "eink_url": preview_upload_url("eink", filename, version),
            })
            seen.add(filename)

    for filename in os.listdir(UPLOAD_FOLDER):
        path = legacy_upload_path(filename)
        if filename in seen or not os.path.isfile(path):
            continue
        if filename.endswith(".png") and os.path.isfile(legacy_upload_path(filename[:-4])):
            continue
        version = source_version(filename)
        files.append({
            "name": filename,
            "version": version,
            "thumb_url": preview_upload_url("thumb", filename, version),
            "original_url": versioned_upload_url(filename, version),
            "normalised_url": preview_upload_url("normalised", filename, version),
            "lcd_url": preview_upload_url("lcd", filename, version),
            "eink_url": preview_upload_url("eink", filename, version),
        })

    return sorted(files, key=lambda item: item["name"])


def versioned_upload_url(filename, version=None):
    if not filename:
        return None

    path = os.path.join(UPLOAD_FOLDER, filename)
    if version is None and os.path.exists(path):
        version = int(os.path.getmtime(path))

    url = f"/static/uploads/{filename}"
    if version is not None:
        url = f"{url}?v={version}"

    return url


def rendered_upload_url(device_id, screen, filename, version=None):
    if not filename:
        return None

    url = f"/device/{device_id}/render/{screen}/{filename}"
    if version is not None:
        url = f"{url}?v={version}"

    return url


def screen_config(device, screen):
    screens = (device or {}).get("screens") or {}
    config = screens.get(screen) or {}
    defaults = {
        "lcd": {"width": 800, "height": 480, "type": "lcd", "color": True, "orientation": 0},
        "eink": {"width": 800, "height": 480, "type": "eink", "color": False, "orientation": 0},
    }

    merged = {**defaults.get(screen, {}), **config}
    merged["width"] = int(merged.get("width") or 800)
    merged["height"] = int(merged.get("height") or 480)
    orientation = config.get(
        "orientation",
        config.get("rotation", defaults.get(screen, {}).get("orientation", 0)),
    )
    merged["orientation"] = int(orientation or 0) % 360
    merged["rotation"] = merged["orientation"]
    return merged


def effective_device(device):
    if device:
        return device

    return {
        "screens": {
            "lcd": screen_config(None, "lcd"),
            "eink": screen_config(None, "eink"),
        }
    }


def render_preview_device():
    devices = get_devices()
    if devices:
        first_device_id = sorted(devices.keys())[0]
        return effective_device(devices[first_device_id])

    return effective_device(None)


def fit_to_screen(path, size, background):
    img = load_source_image(path)
    img.thumbnail(size, Image.Resampling.LANCZOS)

    canvas = Image.new("RGB", size, background)
    x = (size[0] - img.width) // 2
    y = (size[1] - img.height) // 2
    canvas.paste(img, (x, y))
    return canvas


def render_for_screen(filename, screen, device):
    if not upload_exists(filename):
        abort(404)

    rendered = render_screen_image(filename, screen, device)
    output = BytesIO()
    if screen == "eink":
        rendered.save(output, "PNG")
        mimetype = "image/png"
        extension = "png"
    else:
        rendered.save(output, "JPEG", quality=92)
        mimetype = "image/jpeg"
        extension = "jpg"

    output.seek(0)
    return output, mimetype, extension


def render_screen_image(filename, screen, device=None):
    config = screen_config(device, screen)
    size = (config["width"], config["height"])
    source_path = existing_source_path(filename)
    if not source_path:
        abort(404)

    if screen == "eink" or config.get("type") == "eink":
        rotation = int(config["rotation"] or 0) % 360
        fit_size = size if rotation in (0, 180) else (size[1], size[0])
        rendered = fit_to_screen(source_path, fit_size, (255, 255, 255))
        if rotation:
            rendered = rendered.rotate(rotation, expand=True)
        return rendered.convert("L").convert("1", dither=Image.Dither.FLOYDSTEINBERG)

    return fit_to_screen(source_path, size, (0, 0, 0))


def screen_content_id(filename, screen, device):
    version = source_version(filename)
    if not filename or not version:
        return None

    config = screen_config(device, screen)
    payload = {
        "filename": filename,
        "source": version,
        "screen": screen,
        "rules": RENDER_RULE_VERSION,
        "config": {
            "width": config["width"],
            "height": config["height"],
            "type": config.get("type"),
            "color": config.get("color"),
            "rotation": config["rotation"],
        },
    }
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()[:20]


# ===== DISPLAY (LIVE) =====
def get_display():
    return load_json("display.json", {"lcd": None, "eink": None})


def set_display(data):
    data["timestamp"] = datetime.now().isoformat()
    save_json("display.json", data)


def get_device_assignments():
    return load_json("device_assignments.json", {})


def save_device_assignments(assignments):
    save_json("device_assignments.json", assignments)


def get_device_display(device_id):
    return get_device_assignments().get(device_id, {})


def effective_display(device_id):
    state = get_display()
    device_state = get_device_display(device_id)

    return {
        **state,
        **{screen: device_state.get(screen) for screen in ("lcd", "eink") if screen in device_state},
        "timestamp": device_state.get("timestamp") or state.get("timestamp"),
    }


def set_device_screen(device_id, screen, filename):
    assignments = get_device_assignments()
    device_state = assignments.get(device_id, {})
    device_state[screen] = filename
    device_state["timestamp"] = datetime.now().isoformat()
    assignments[device_id] = device_state
    save_device_assignments(assignments)
    return device_state


def clear_device_screen(device_id, screen):
    assignments = get_device_assignments()
    device_state = assignments.get(device_id)
    if not device_state or screen not in device_state:
        return False

    device_state.pop(screen, None)
    if any(key in device_state for key in ("lcd", "eink")):
        device_state["timestamp"] = datetime.now().isoformat()
        assignments[device_id] = device_state
    else:
        assignments.pop(device_id, None)

    save_device_assignments(assignments)
    return True


# ===== STAGING =====
def get_staging():
    return load_json("staging.json", {"lcd": None, "eink": None})


def set_staging(lcd=None, eink=None):
    state = get_staging()

    if lcd:
        print(f"[STAGE LCD] {lcd}")
        state["lcd"] = lcd

    if eink:
        print(f"[STAGE EINK] {eink}")
        state["eink"] = eink

    save_json("staging.json", state)


def clean_staging_for_publish(staging):
    cleaned = {}
    invalid = []

    for screen in ("lcd", "eink"):
        filename = staging.get(screen)
        if filename and upload_exists(filename):
            cleaned[screen] = filename
        else:
            cleaned[screen] = None
            if filename:
                invalid.append(f"{screen.upper()} ({filename})")

    return cleaned, invalid


def clear_file_references(filename):
    cleared = []

    display = get_display()
    display_changed = False
    for screen in ("lcd", "eink"):
        if display.get(screen) == filename:
            display[screen] = None
            display_changed = True
            cleared.append(f"live {screen.upper()}")

    if display_changed:
        set_display(display)

    staging = get_staging()
    staging_changed = False
    for screen in ("lcd", "eink"):
        if staging.get(screen) == filename:
            staging[screen] = None
            staging_changed = True
            cleared.append(f"staged {screen.upper()}")

    if staging_changed:
        save_json("staging.json", staging)

    assignments = get_device_assignments()
    assignments_changed = False
    for device_id, device_state in assignments.items():
        for screen in ("lcd", "eink"):
            if device_state.get(screen) == filename:
                device_state[screen] = None
                device_state["timestamp"] = datetime.now().isoformat()
                assignments_changed = True
                cleared.append(f"{device_id} {screen.upper()}")

    if assignments_changed:
        save_device_assignments(assignments)

    return cleared


# ===== DEVICES =====
def get_devices():
    return load_json("devices.json", {})


def save_devices(devices):
    save_json("devices.json", devices)


def get_device(device_id):
    return get_devices().get(device_id)


def normalise_screens(screens):
    if not isinstance(screens, dict):
        return {}

    clean = {}
    for screen_id, screen in screens.items():
        if not isinstance(screen, dict):
            continue

        clean_screen = {}
        for key in ("type", "width", "height", "color", "orientation", "rotation", "driver"):
            if key in screen:
                clean_screen[key] = screen[key]

        clean[str(screen_id)] = clean_screen

    return clean


def register_device(device_id, payload):
    devices = get_devices()
    existing = devices.get(device_id, {})
    now = utc_timestamp()

    device = {
        **existing,
        "id": device_id,
        "name": payload.get("name") or existing.get("name") or device_id,
        "hostname": payload.get("hostname") or existing.get("hostname"),
        "last_seen": now,
        "status": "online",
        "ip": request.headers.get("X-Forwarded-For", request.remote_addr),
        "screens": normalise_screens(payload.get("screens", existing.get("screens", {}))),
    }

    if "client_version" in payload:
        device["client_version"] = payload.get("client_version")

    battery = payload.get("battery")
    if isinstance(battery, dict):
        device["battery"] = {
            "percentage": battery.get("percentage"),
            "charging": battery.get("charging"),
            "plugged": battery.get("plugged"),
        }

    devices[device_id] = device
    save_devices(devices)
    return device


# ===== ROUTES =====

@app.route("/login", methods=["GET", "POST"])
def login():
    error = None

    if request.method == "POST":
        validate_csrf()
        email = request.form.get("email", "")
        password = request.form.get("password", "")

        if not check_rate_limit("login", LOGIN_RATE_LIMIT):
            error = "Too many login attempts. Try again later."
            return render_template("login.html", error=error)

        user = find_user_by_email(email)
        if user and user["is_active"] and check_password_hash(user["password_hash"], password):
            session.clear()
            start_window_session()
            session["user_id"] = user["id"]
            session["user_role"] = user["role"]
            session["expires_at"] = (datetime.utcnow() + timedelta(hours=SESSION_LIFETIME_HOURS)).isoformat()
            generate_csrf_token()
            get_db().execute(
                "UPDATE users SET last_login_at = ?, updated_at = ? WHERE id = ?",
                (utc_timestamp(), utc_timestamp(), user["id"]),
            )
            get_db().commit()
            next_url = request.args.get("next") or url_for("index")
            if not next_url.startswith("/"):
                next_url = url_for("index")
            return redirect(next_url)

        error = "Invalid email or password"

    return render_template("login.html", error=error)


@app.route("/window-session/confirm", methods=["POST"])
@login_required
def confirm_window_session():
    validate_csrf()
    session.pop("window_session_new", None)
    return jsonify({"success": True})


@app.route("/logout", methods=["POST"])
def logout():
    validate_csrf()
    session.clear()
    return redirect(url_for("login"))


@app.route("/forgot-password", methods=["GET", "POST"])
def forgot_password():
    sent = False
    error = None

    if request.method == "POST":
        validate_csrf()
        if not check_rate_limit("password-reset", RESET_RATE_LIMIT):
            error = "Too many reset requests. Try again later."
            return render_template("forgot_password.html", error=error, sent=sent)

        email = request.form.get("email", "")
        user = find_user_by_email(email)
        if user and user["is_active"]:
            token = create_password_reset_token(user["id"])
            try:
                send_password_reset_email(user, token)
            except Exception as e:
                print("[PASSWORD RESET EMAIL ERROR]", e)

        sent = True

    return render_template("forgot_password.html", error=error, sent=sent)


@app.route("/reset-password/<token>", methods=["GET", "POST"])
def reset_password(token):
    reset = get_db().execute(
        """
        SELECT password_reset_tokens.*, users.email, users.name, users.is_active
        FROM password_reset_tokens
        JOIN users ON users.id = password_reset_tokens.user_id
        WHERE token_hash = ?
        """,
        (token_hash(token),),
    ).fetchone()

    valid = False
    if reset and not reset["used_at"] and reset["is_active"]:
        expires_at = datetime.fromisoformat(reset["expires_at"].replace("Z", ""))
        valid = datetime.utcnow() <= expires_at

    if not valid:
        return render_template("reset_password.html", invalid=True)

    error = None
    if request.method == "POST":
        validate_csrf()
        password = request.form.get("password", "")
        confirm_password = request.form.get("confirm_password", "")

        if len(password) < 10:
            error = "Password must be at least 10 characters."
        elif password != confirm_password:
            error = "Passwords do not match."
        else:
            now = utc_timestamp()
            password_hash = hash_password(password)
            db = get_db()
            db.execute(
                "UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?",
                (password_hash, now, reset["user_id"]),
            )
            db.execute(
                "UPDATE password_reset_tokens SET used_at = ? WHERE id = ?",
                (now, reset["id"]),
            )
            db.commit()
            flash("Password updated. Log in with your new password.", "success")
            return redirect(url_for("login"))

    return render_template("reset_password.html", error=error, invalid=False)


@app.route("/")
@login_required
def index():
    files = list_uploads()
    return render_template(
        "index.html",
        files=files,
        live=get_display(),
        staging=get_staging(),
        devices=get_devices(),
        device_assignments=get_device_assignments(),
    )


@app.route("/users")
@admin_required
def users():
    user_rows = get_db().execute(
        "SELECT id, email, name, role, is_active, created_at, last_login_at FROM users ORDER BY name, email"
    ).fetchall()
    return render_template("users.html", users=[dict(row) for row in user_rows])


@app.route("/users/create", methods=["GET", "POST"])
@admin_required
def create_user_route():
    error = None
    values = {"email": "", "name": "", "role": "staff"}

    if request.method == "POST":
        validate_csrf()
        values = {
            "email": request.form.get("email", "").strip(),
            "name": request.form.get("name", "").strip(),
            "role": request.form.get("role", "staff"),
        }
        password = request.form.get("password", "")
        confirm_password = request.form.get("confirm_password", "")

        if values["role"] not in ("admin", "staff"):
            error = "Choose a valid role."
        elif not values["email"] or "@" not in values["email"]:
            error = "Enter a valid email address."
        elif not values["name"]:
            error = "Enter the user's name."
        elif len(password) < 10:
            error = "Password must be at least 10 characters."
        elif password != confirm_password:
            error = "Passwords do not match."
        elif find_user_by_email(values["email"]):
            error = "A user with that email already exists."
        else:
            create_user(values["email"], values["name"], values["role"], password)
            flash(f"Created user {values['email']}.", "success")
            return redirect(url_for("users"))

    return render_template("user_form.html", error=error, values=values)


@app.route("/users/<int:user_id>/disable", methods=["POST"])
@admin_required
def disable_user(user_id):
    validate_csrf()
    user = find_user_by_id(user_id)
    if not user:
        abort(404)
    if user["id"] == session.get("user_id"):
        flash("You cannot disable your own account.", "error")
        return redirect(url_for("users"))

    now = utc_timestamp()
    get_db().execute(
        "UPDATE users SET is_active = 0, updated_at = ? WHERE id = ?",
        (now, user_id),
    )
    get_db().commit()
    flash(f"Disabled {user['email']}.", "success")
    return redirect(url_for("users"))


@app.route("/users/<int:user_id>/enable", methods=["POST"])
@admin_required
def enable_user(user_id):
    validate_csrf()
    user = find_user_by_id(user_id)
    if not user:
        abort(404)

    now = utc_timestamp()
    get_db().execute(
        "UPDATE users SET is_active = 1, updated_at = ? WHERE id = ?",
        (now, user_id),
    )
    get_db().commit()
    flash(f"Enabled {user['email']}.", "success")
    return redirect(url_for("users"))


@app.route("/users/<int:user_id>/delete", methods=["POST"])
@admin_required
def delete_user(user_id):
    validate_csrf()
    user = find_user_by_id(user_id)
    if not user:
        abort(404)
    if user["id"] == session.get("user_id"):
        flash("You cannot delete your own account.", "error")
        return redirect(url_for("users"))

    get_db().execute("DELETE FROM users WHERE id = ?", (user_id,))
    get_db().commit()
    flash(f"Deleted {user['email']}.", "success")
    return redirect(url_for("users"))


@app.route("/upload", methods=["POST"])
@login_required
def upload():
    validate_csrf()
    file = request.files.get("file")

    if not file or not allowed_file(file.filename):
        flash("Upload a PNG, JPG, JPEG, or PDF file.", "error")
        return redirect("/")

    file.seek(0, os.SEEK_END)
    size = file.tell()
    file.seek(0)

    if size == 0:
        flash("File is empty. Choose a valid PNG, JPG, JPEG, or PDF file.", "error")
        return redirect("/")

    if size > MAX_FILE_SIZE:
        flash("File too large. Maximum size is 5MB.", "error")
        return redirect("/")

    filename = clean_filename(file.filename)
    if not filename:
        flash("Choose a valid file to upload.", "error")
        return redirect("/")

    original_filename = filename
    extension = get_extension(original_filename)
    filename = converted_pdf_filename(original_filename) if extension == "pdf" else original_filename
    original_path = original_upload_path(original_filename)

    conflicts = [
        original_path,
        legacy_upload_path(filename),
        legacy_upload_path(f"{filename}.png"),
    ]
    catalog = get_media_catalog()
    if filename in catalog or any(os.path.exists(path) for path in conflicts):
        flash("A file with that name already exists.", "error")
        return redirect("/")

    file.save(original_path)

    try:
        normalised_source_image(original_path)

        catalog[filename] = {
            "original": original_filename,
            "created_at": utc_timestamp(),
        }
        save_media_catalog(catalog)
    except Exception as e:
        print("[UPLOAD ERROR]", e)
        cleanup_upload(original_path)
        flash("That file could not be processed. Upload a valid PNG, JPG, JPEG, or PDF under 5MB.", "error")
        return redirect("/")

    flash(f"Uploaded {filename}.", "success")
    return redirect("/")


@app.route("/stage_lcd", methods=["POST"])
@login_required
def stage_lcd():
    validate_csrf()
    f = clean_filename(request.form.get("file"))
    if not f:
        flash("Choose a file to stage for LCD.", "error")
        return redirect("/")

    if not upload_exists(f):
        flash(f"Cannot stage {f}; that upload no longer exists.", "error")
        return redirect("/")

    set_staging(lcd=f)
    flash(f"Staged {f} for LCD.", "success")
    return redirect("/")


@app.route("/stage_eink", methods=["POST"])
@login_required
def stage_eink():
    validate_csrf()
    f = clean_filename(request.form.get("file"))
    if not f:
        flash("Choose a file to stage for E-Ink.", "error")
        return redirect("/")

    if not upload_exists(f):
        flash(f"Cannot stage {f}; that upload no longer exists.", "error")
        return redirect("/")

    set_staging(eink=f)
    flash(f"Staged {f} for E-Ink.", "success")
    return redirect("/")


@app.route("/push_live", methods=["POST"])
@login_required
def push_live():
    validate_csrf()
    staging = get_staging()
    cleaned, invalid = clean_staging_for_publish(staging)

    if invalid:
        save_json("staging.json", cleaned)
        flash(f"Could not push missing staged file(s): {', '.join(invalid)}.", "error")
        return redirect("/")

    if not cleaned.get("lcd") and not cleaned.get("eink"):
        flash("Nothing staged to push live.", "error")
        return redirect("/")

    set_display(cleaned)
    print("[PUSH LIVE]", cleaned)
    flash("Pushed staged content live.", "success")
    return redirect("/")


@app.route("/assign_device_screen", methods=["POST"])
@login_required
def assign_device_screen():
    validate_csrf()
    device_id = (request.form.get("device_id") or "").strip()
    screen = (request.form.get("screen") or "").strip()
    filename = clean_filename(request.form.get("file"))

    if screen not in ("lcd", "eink"):
        flash("Choose LCD or E-Ink for the device assignment.", "error")
        return redirect("/")

    device = get_device(device_id)
    if not device:
        flash(f"Device {device_id} has not reported in yet.", "error")
        return redirect("/")

    screens = (device.get("screens") or {})
    if screen not in screens:
        flash(f"{device.get('name') or device_id} does not report a {screen.upper()} screen.", "error")
        return redirect("/")

    if not filename:
        flash("Choose a file to assign.", "error")
        return redirect("/")

    if not upload_exists(filename):
        flash(f"Cannot assign {filename}; that upload no longer exists.", "error")
        return redirect("/")

    set_device_screen(device_id, screen, filename)
    print(f"[ASSIGN DEVICE] device={device_id} screen={screen} file={filename}")
    flash(f"Assigned {filename} to {device.get('name') or device_id} {screen.upper()}.", "success")
    return redirect("/")


@app.route("/clear_device_screen", methods=["POST"])
@login_required
def clear_device_screen_route():
    validate_csrf()
    device_id = (request.form.get("device_id") or "").strip()
    screen = (request.form.get("screen") or "").strip()

    if screen not in ("lcd", "eink"):
        flash("Choose LCD or E-Ink for the device override to clear.", "error")
        return redirect("/")

    device = get_device(device_id)
    if not device:
        flash(f"Device {device_id} has not reported in yet.", "error")
        return redirect("/")

    if clear_device_screen(device_id, screen):
        flash(f"Cleared {device.get('name') or device_id} {screen.upper()} override.", "success")
    else:
        flash(f"{device.get('name') or device_id} has no {screen.upper()} override to clear.", "error")

    return redirect("/")


@app.route("/delete", methods=["POST"])
@login_required
def delete():
    validate_csrf()
    f = clean_filename(request.form.get("file"))
    if not f:
        flash("Choose a file to delete.", "error")
        return redirect("/")

    catalog = get_media_catalog()
    original = (catalog.get(f) or {}).get("original")
    paths = [
        normalised_upload_path(f),
        rendered_upload_path("lcd", f),
        rendered_upload_path("eink", f),
        thumb_upload_path(f),
        legacy_upload_path(f),
        legacy_upload_path(f"{f}.png"),
    ]
    if original:
        paths.append(original_upload_path(original))

    removed = False

    for path in paths:
        if os.path.exists(path):
            os.remove(path)
            removed = True

    if f in catalog:
        catalog.pop(f)
        save_media_catalog(catalog)

    cleared = clear_file_references(f)

    if removed and cleared:
        flash(f"Deleted {f} and cleared {', '.join(cleared)}.", "success")
    elif removed:
        flash(f"Deleted {f}.", "success")
    else:
        flash(f"{f} was not found.", "error")

    return redirect("/")


@app.route("/preview/<variant>/<path:filename>")
@login_required
def preview_upload(variant, filename):
    filename = clean_filename(filename)
    source_path = existing_source_path(filename)
    if not source_path:
        abort(404)

    if variant == "thumb":
        rendered = make_thumb_image(source_path)
        output = BytesIO()
        rendered.save(output, "PNG")
        output.seek(0)
        return send_file(output, mimetype="image/png", download_name=f"{filename}.png", max_age=3600)

    if variant == "normalised":
        rendered = normalised_source_image(source_path)
        output = BytesIO()
        rendered.save(output, "JPEG", quality=90)
        output.seek(0)
        return send_file(output, mimetype="image/jpeg", download_name=f"{filename}.jpg", max_age=3600)

    if variant in ("lcd", "eink"):
        device = render_preview_device()
        output, mimetype, extension = render_for_screen(filename, variant, device)
        base = filename.rsplit(".", 1)[0]
        return send_file(
            output,
            mimetype=mimetype,
            download_name=f"{base}-{variant}.{extension}",
            max_age=3600,
        )

    abort(404)


@app.route("/device/<device_id>/config")
def config(device_id):
    state = effective_display(device_id)
    device = effective_device(get_device(device_id))
    timestamp = state.get("timestamp")
    lcd_content_id = screen_content_id(state.get("lcd"), "lcd", device)
    eink_content_id = screen_content_id(state.get("eink"), "eink", device)

    return jsonify({
        "lcd": {
            "file": state.get("lcd"),
            "url": rendered_upload_url(device_id, "lcd", state.get("lcd"), lcd_content_id),
            "content_id": lcd_content_id,
        },
        "eink": {
            "file": state.get("eink"),
            "url": rendered_upload_url(device_id, "eink", state.get("eink"), eink_content_id),
            "content_id": eink_content_id,
        },
        "timestamp": timestamp,
        "device": device
    })


@app.route("/device/<device_id>/render/<screen>/<path:filename>")
def render_upload(device_id, screen, filename):
    if screen not in ("lcd", "eink"):
        abort(404)

    filename = clean_filename(filename)
    device = effective_device(get_device(device_id))
    output, mimetype, extension = render_for_screen(filename, screen, device)

    base = filename.rsplit(".", 1)[0]
    return send_file(
        output,
        mimetype=mimetype,
        download_name=f"{base}-{screen}.{extension}",
        max_age=0,
    )


@app.route("/device/<device_id>/heartbeat", methods=["POST"])
def heartbeat(device_id):
    payload = request.get_json(silent=True) or {}
    device = register_device(device_id, payload)
    return jsonify({"success": True, "device": device})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5050)
