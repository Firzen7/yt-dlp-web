import os
from flask import Flask, request, jsonify, send_file, render_template, redirect, url_for, session, flash
from werkzeug.security import check_password_hash
import yt_dlp
import uuid
import threading
import secrets

app = Flask(__name__, static_url_path='', static_folder='static')
app.secret_key = secrets.token_hex(16)

DOWNLOAD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "downloads")
USERS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "users.txt")
os.makedirs(DOWNLOAD_DIR, exist_ok=True)

# Create users.txt if it doesn't exist
if not os.path.exists(USERS_FILE):
    open(USERS_FILE, 'w').close()

# Simple in-memory status tracker
# { task_id: { "status": "processing" | "completed" | "error", "file_path": None, "error": None } }
tasks = {}

def check_auth(username, password):
    if not os.path.exists(USERS_FILE):
        return False
    with open(USERS_FILE, 'r') as f:
        for line in f:
            line = line.strip()
            if not line or ':' not in line:
                continue
            u, h = line.split(':', 1)
            if u == username and check_password_hash(h, password):
                return True
    return False

def requires_auth(f):
    def decorated(*args, **kwargs):
        if 'logged_in' not in session:
            if request.path.startswith('/api/'):
                return jsonify({"error": "Unauthorized"}), 401
            return redirect(url_for('login'))
        return f(*args, **kwargs)
    decorated.__name__ = f.__name__
    return decorated

@app.route('/login', methods=['GET', 'POST'])
def login():
    if request.method == 'POST':
        username = request.form['username']
        password = request.form['password']
        if check_auth(username, password):
            session['logged_in'] = True
            session['username'] = username
            return redirect(url_for('index'))
        else:
            flash('Nesprávné jméno nebo heslo.')
    return render_template('login.html')

@app.route('/logout')
def logout():
    session.pop('logged_in', None)
    session.pop('username', None)
    return redirect(url_for('login'))

def download_video(url, format_type, task_id):
    try:
        if format_type == 'mp3':
            ydl_opts = {
                'format': 'bestaudio/best',
                'outtmpl': os.path.join(DOWNLOAD_DIR, f'%(title)s_{task_id}.%(ext)s'),
                'postprocessors': [{
                    'key': 'FFmpegExtractAudio',
                    'preferredcodec': 'mp3',
                    'preferredquality': '192',
                }],
                'quiet': True,
                'no_warnings': True
            }
        else:
            ydl_opts = {
                'format': 'bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best',
                'outtmpl': os.path.join(DOWNLOAD_DIR, f'%(title)s_{task_id}.%(ext)s'),
                'merge_output_format': 'mp4',
                'quiet': True,
                'no_warnings': True
            }

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info_dict = ydl.extract_info(url, download=True)
            
            # Figure out the final downloaded file path
            if format_type == 'mp3':
                # For mp3, yt-dlp might download webm/m4a and then convert it, so we need to
                # predict the final extension. The expected extension after ffmpeg is mp3.
                expected_filename = ydl.prepare_filename(info_dict)
                # Replace the original extension (.webm, .m4a) with .mp3
                base, _ = os.path.splitext(expected_filename)
                final_path = base + '.mp3'
            else:
                final_path = ydl.prepare_filename(info_dict)
                # If merging to mp4, ensure extension is mp4
                base, ext = os.path.splitext(final_path)
                if ext != '.mp4':
                    final_path = base + '.mp4'

            tasks[task_id]['status'] = 'completed'
            tasks[task_id]['file_path'] = final_path

    except Exception as e:
        tasks[task_id]['status'] = 'error'
        tasks[task_id]['error'] = str(e)


@app.route('/')
@requires_auth
def index():
    return render_template('index.html')

@app.route('/api/download', methods=['POST'])
@requires_auth
def start_download():
    data = request.json
    url = data.get('url')
    format_type = data.get('format', 'video')

    if not url:
        return jsonify({"error": "URL is required"}), 400

    task_id = str(uuid.uuid4())
    tasks[task_id] = {"status": "processing"}

    # Start download in a background thread
    thread = threading.Thread(target=download_video, args=(url, format_type, task_id))
    thread.start()

    return jsonify({"task_id": task_id})

@app.route('/api/status/<task_id>', methods=['GET'])
@requires_auth
def get_status(task_id):
    task = tasks.get(task_id)
    if not task:
        return jsonify({"error": "Task not found"}), 404
    
    if task['status'] == 'completed':
        # we can't send the full path over the API securely, so just indicate success
        return jsonify({"status": "completed", "download_url": f"/api/file/{task_id}"})
    
    return jsonify({"status": task['status'], "error": task.get('error')})

@app.route('/api/file/<task_id>', methods=['GET'])
@requires_auth
def get_file(task_id):
    task = tasks.get(task_id)
    if not task or task['status'] != 'completed':
        return "File not ready", 400
    
    file_path = task['file_path']
    if not os.path.exists(file_path):
        return "File not found on server", 404
        
    filename = os.path.basename(file_path)
    return send_file(file_path, as_attachment=True, download_name=filename)

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5000)
