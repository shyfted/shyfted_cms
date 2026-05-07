# Shyfted CMS

Independent CMS service for `cms.shyfted.com.au`.

## Configuration

Copy `.env.example` to `.env` in the deployment environment and set real values. Required production settings:

- `APP_URL=https://cms.shyfted.com.au`
- `DATABASE_URL=sqlite:///data/cms.db`
- `AUTH_SESSION_SECRET` set to a long random value
- `CMS_ADMIN_EMAIL` and `CMS_ADMIN_PASSWORD` for first-admin bootstrap
- SMTP settings for password reset email delivery

Do not commit `.env`, the SQLite database, uploaded media, or generated runtime state.

## Local Setup

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
export AUTH_SESSION_SECRET="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
export SESSION_COOKIE_SECURE=false
export CMS_ADMIN_EMAIL=admin@example.com
export CMS_ADMIN_PASSWORD='replace-with-a-long-temporary-password'
flask --app app run --host 0.0.0.0 --port 5050
```

The app creates database tables automatically on startup. If no CMS users exist yet and `CMS_ADMIN_EMAIL` and `CMS_ADMIN_PASSWORD` are set, the app creates the first admin account from those environment variables. If users already exist, those variables are ignored. There is no public registration; after bootstrap, admins can create staff or admin users from `/users`.

## Production Run

Install dependencies in a virtual environment, provide environment variables through the host/process manager, and run:

```bash
gunicorn --bind 127.0.0.1:5050 app:app
```

Place a reverse proxy in front of Gunicorn for `https://cms.shyfted.com.au`, terminate TLS there, and forward requests to `127.0.0.1:5050`. Keep `SESSION_COOKIE_SECURE=true` in production.

## Auth Foundation

- Email/password login with secure password hashing.
- Logout clears the server-side session cookie data.
- Dashboard routes require an active authenticated user.
- Admin-only `/users` page can view, create, enable, disable, and delete users.
- Roles are `admin` and `staff`.
- Forgot/reset password uses random reset tokens; only token hashes are stored.
- Login and reset requests are rate limited per session/IP window for MVP pilot use.
- Sessions expire according to `SESSION_LIFETIME_HOURS`.
- The auth model is centralized around user/session helpers so 2FA can be added later without public registration.
