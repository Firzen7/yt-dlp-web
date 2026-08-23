# yt-dlp-web

yt-dlp-web is a self-hosted web frontend for [yt-dlp](https://github.com/yt-dlp/yt-dlp). It is intended to be as simple as possible to configure and use: install the required tools, create one configuration file and user account, then start the embedded web server.

## Features

- Authenticated browser interface for downloading video or audio.
- Best-quality video downloads or selection from resolutions reported by yt-dlp.
- Fast M4A audio downloads and optional MP3 conversion.
- Download progress, cancellation, custom filenames, and automatic title lookup.
- Czech and English Web UI localization.
- Per-user action logs containing the resolved client IP address.
- Browser settings remembered locally, including language and download preferences.
- No external database or application server required.

## Requirements

- Java 17 or newer.
- `yt-dlp` available on `PATH`.
- `ffmpeg` available on `PATH`.
- A JavaScript runtime supported by yt-dlp. The default configuration uses Node.js at `/usr/bin/node`.
- Write access to the configured download, log, and user-file locations.

## Building

The Gradle wrapper downloads the required Gradle version automatically.

```bash
./gradlew test
./gradlew buildFatJar
```

The runnable fat JAR is created as `build/libs/yt-dlp-web-v<version>.jar`. The commands below use `yt-dlp-web.jar` as a generic name for this built or downloaded JAR.

## Initial Setup

The packaged application expects its configuration at `/opt/yt-dlp-web/config.conf`. Create the application and log directories, copy the defaults, and make them writable by the account that will run the server:

```bash
sudo mkdir -p /opt/yt-dlp-web/logs
sudo cp src/main/resources/defaults.conf /opt/yt-dlp-web/config.conf
sudo editor /opt/yt-dlp-web/config.conf
```

For a packaged installation, an absolute user-file path is recommended:

```properties
auth.users_file=/opt/yt-dlp-web/users.conf
```

Create the first account from an interactive terminal:

```bash
java -jar yt-dlp-web.jar adduser
```

Start the server:

```bash
java -jar yt-dlp-web.jar server
```

Open `http://<server-address>:8080/`, or use the port configured below.

## Configuration

The main configuration file is:

```text
/opt/yt-dlp-web/config.conf
```

Values are read at startup. JVM system properties have the highest priority, followed by environment variables and then the configuration file. For example, `-Dserver.port=5000` overrides `SERVER_PORT`, which overrides `server.port` in the file.

The default configuration is:

```properties
server.port=8080

fs.download_directory=/tmp/yt-dlp-web
fs.log_directory=/opt/yt-dlp-web/logs
fs.js_runtime_type=node
fs.js_runtime_path=/usr/bin/node

auth.users_file=./users.conf

process.timeout=1200
```

| Field | Environment variable | Default | Description |
| --- | --- | --- | --- |
| `server.port` | `SERVER_PORT` | `8080` | TCP port used by the embedded Netty HTTP server. |
| `fs.download_directory` | `FS_DOWNLOAD_DIRECTORY` | `/tmp/yt-dlp-web` | Parent directory for downloads. Each task receives its own UUID-named subdirectory. |
| `fs.log_directory` | `FS_LOG_DIRECTORY` | `/opt/yt-dlp-web/logs` | Directory for persistent per-user action logs. It must already exist and be writable. |
| `fs.js_runtime_type` | `FS_JS_RUNTIME_TYPE` | `node` | Runtime name passed to yt-dlp through `--js-runtimes`. |
| `fs.js_runtime_path` | `FS_JS_RUNTIME_PATH` | `/usr/bin/node` | Executable path paired with `fs.js_runtime_type`. |
| `auth.users_file` | `AUTH_USERS_FILE` | `./users.conf` | Credential file used by the CLI and Web UI. Relative paths are resolved from the process working directory. |
| `process.timeout` | `PROCESS_TIMEOUT` | `1200` | Maximum child-process runtime in seconds. It is also used for the ffmpeg network timeout. |

Example override:

```bash
SERVER_PORT=5000 java -Dprocess.timeout=1800 -jar yt-dlp-web.jar server
```

## CLI

### Start the server

```bash
java -jar yt-dlp-web.jar server
```

The process runs in the foreground until stopped.

### Add a user

```bash
java -jar yt-dlp-web.jar adduser
java -jar yt-dlp-web.jar adduser <username>
```

Without an argument, the command interactively asks for a username, password, and confirmation. When a username is supplied, it displays that username and asks only for the password and confirmation. Usernames must be non-empty, cannot contain `:`, and cannot be the reserved name `unknown`.

### Change a user's password

```bash
java -jar yt-dlp-web.jar passwd <username>
```

The user must already exist. The `adduser` and `passwd` commands require a real interactive console; piped input and environments without `System.console()` are not supported.

### List users

```bash
java -jar yt-dlp-web.jar listusers
```

### Delete a user

```bash
java -jar yt-dlp-web.jar deluser <username>
```

The user must already exist. Deletion does not require interactive confirmation.

### Show CLI help

```bash
java -jar yt-dlp-web.jar --help
```

Passwords are stored as salted scrypt hashes in a Werkzeug-compatible text format. Plaintext passwords are not written to disk.

## Web UI

Users sign in with an account created through the CLI. Authentication uses an HTTP-only `SESSION` cookie with a 30-day lifetime.

Video mode downloads the best available format by default. Users can explicitly request available resolutions; yt-dlp is queried only when the selection button is pressed. Playlists and live streams are intentionally excluded.

Audio mode provides two choices:

- **Fastest** downloads the best available M4A stream. If that fails, the server retries using MP3 conversion.
- **MP3** always extracts and converts the audio to MP3.

The filename control looks up the media title and allows it to be edited. Characters that are unsafe in filenames are removed. Active downloads report progress and can be cancelled; cancellation also terminates the active yt-dlp/ffmpeg process tree.

Users can change their own password in the Web UI. A successful change ends the current session and requires signing in again.

The Web UI supports Czech and English. The browser stores the selected language, video/audio mode, audio conversion method, and preferred video resolution in local storage under `yt-dlp-web.preferences`. No preference cookie is used.

## Downloads and Logs

Each download is written to:

```text
<fs.download_directory>/<task-id>/
```

Downloaded files are not deleted automatically by the application. Task state is held in memory, so restarting the server loses access to existing task IDs even though their files remain on disk.

Diagnostic messages are printed to standard output. Persistent actions are appended to:

```text
<fs.log_directory>/<username>.log
```

Unauthenticated actions use `unknown.log`. Entries include severity, timestamp, client IP address, and action. Ensure the log directory exists before startup.

## Reverse Proxy

The application serves plain HTTP. For public deployments, place it behind an HTTPS reverse proxy. The server understands standard `X-Forwarded-*` headers and uses the last proxy-added address from `X-Forwarded-For` for logging.

Minimal Nginx location example:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

Do not expose the backend port directly when using forwarded headers. Restrict it with a firewall or host-level network rules so clients cannot bypass Nginx and supply spoofed proxy headers.

## HTTP API

The Web UI uses a small JSON API. Routes marked **session** require a valid login cookie.

| Method | Path | Authentication | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/login` | Public | Accepts `username` and `password`; creates a session. |
| `POST` | `/api/logout` | Public | Clears the current session. |
| `GET` | `/api/user` | Session | Returns the signed-in username. |
| `POST` | `/api/download` | Session | Starts a download and returns `task_id`. |
| `POST` | `/api/cancel/{taskId}` | Session | Cancels an active task and its process tree. |
| `GET` | `/api/status/{taskId}` | Session | Returns task status, progress, errors, or the completed file URL. |
| `GET` | `/api/file/{taskId}` | Session | Serves a completed file as an attachment. |
| `GET` | `/api/version` | Public | Returns the application version embedded by Gradle. |
| `POST` | `/api/title` | Session | Accepts `url` and returns its media title. |
| `POST` | `/api/resolutions` | Session | Accepts `url` and returns unique video widths and heights. |
| `POST` | `/api/decode` | Session | Accepts `base64` and returns the decoded URL. |
| `POST` | `/api/change-password` | Session | Accepts `currentPassword`, `newPassword`, and `confirmNewPassword`. |
| `POST` | `/api/password-entropy` | Public | Accepts `password` and returns its estimated entropy. |

The download request accepts this shape:

```json
{
  "url": "https://example.com/video",
  "format": "video",
  "audioConversion": "fastest",
  "resolution": { "width": 1920, "height": 1080 },
  "filename": "Optional custom name"
}
```

`format` is either `video` or `mp3`. `audioConversion` is either `fastest` or `mp3` and only affects audio downloads. `resolution` is optional and only affects video downloads.

The root page also preserves a `url` query parameter containing a Base64-encoded media URL. After login, the Web UI decodes it, fills the URL field, and opens the filename editor.

## Repository Scripts

The repository's shell scripts reflect one specific Linux deployment and contain hardcoded paths. Review and adapt them before use:

- `build.sh` builds a fat JAR, copies the newest artifact to `releases/`, and stages it in Git.
- `helper_scripts/deploy.sh` installs a JAR under `/var/www/yt-dlp-web`, updates the `yt.jar` symlink, and restarts a Supervisor service named `yt-dlp-web`.
- `helper_scripts/start_server.sh` starts `/var/www/yt-dlp-web/yt.jar` and forwards termination signals.
- `helper_scripts/maintenance.sh` removes download directories older than three days from `/tmp/yt-dlp-web` and runs `yt-dlp -U`.

The maintenance script uses a fixed directory rather than `fs.download_directory`.

## Development

```bash
./gradlew test
./gradlew compileKotlin
./gradlew buildFatJar
```

Main source locations:

- `src/main/kotlin/` contains the CLI, server, download process management, authentication, and logging.
- `src/main/resources/static/` contains the Web UI and JSON localization catalogs.
- `src/main/resources/defaults.conf` contains the default configuration template.
- `src/test/kotlin/` contains the Kotlin test suite.
