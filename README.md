# Keyboard Hawk

An autonomous AI agent that reads your screen and controls your Android device via Accessibility Service + Custom Keyboard.

## How It Works

1. You type a command ("Open YouTube and search for cats")
2. The AI reads the screen's UI tree
3. It decides what to tap, scroll, type, or swipe
4. It does it — autonomously — step by step
5. A floating status bubble shows progress

## Setup (one-time)

1. Install the APK (see **Building** below)
2. Open the app and follow the 3 setup steps:
   - Enable Accessibility Service (Settings → Accessibility → Keyboard Hawk)
   - Enable Keyboard Hawk as an input method
   - Switch to Keyboard Hawk as your keyboard
3. Start your Colab server (see **Colab Server** below)

## Using It

1. Open any app on your device
2. Tap any text field → Keyboard Hawk keyboard slides up
3. Type your command in the command box
4. Press ▶ — the AI starts working
5. A floating bubble shows each step
6. Press ■ to stop at any time

## Model Selection

Tap the **LOCAL / FAST / SMART** button to cycle between:

| Mode | Model | Needs |
|------|-------|-------|
| LOCAL | Llama 3.1 8B (on Colab) | Colab server running |
| FAST | Groq llama-3.1-8b-instant | Groq API key |
| SMART | Groq llama-3.3-70b-versatile | Groq API key |

## Building

The GitHub Actions workflow builds your APK automatically on every push:

1. Push this code to `main` or `master`
2. Go to **Actions** tab in GitHub
3. Wait for the build to complete (~3 min)
4. Download the APK from **Artifacts**
5. Install on your Android device (enable "Install unknown apps" for your browser/Files app)

## Colab Server (LOCAL mode)

Run these 3 cells in Google Colab:

**Cell 1 — Mount Drive:**
```python
from google.colab import drive
drive.mount('/content/drive', force_remount=True)
```

**Cell 2 — Install packages:**
```python
import subprocess, ctypes
subprocess.run(["apt-get", "install", "-y", "-q", "libcublas-12-4"])
subprocess.run(["pip", "install", "-q", "llama-cpp-python", "flask", "flask-cors", "pyngrok"])
print("Packages ready!")
ctypes.CDLL("/usr/local/lib/python3.12/dist-packages/ale_py.libs/libcudart-*.so.12")
print("CUDA ready!")
```

**Cell 3 — Start server (keep running):**
```python
from llama_cpp import Llama
from flask import Flask, request, jsonify
from flask_cors import CORS
from pyngrok import ngrok
import time, threading

GGUF = "/content/drive/MyDrive/Llama-3.1-8B-Instruct-GGUF/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"
DRIVE = "/content/drive/MyDrive"

llm = Llama(model_path=GGUF, n_ctx=4096, n_threads=8, n_gpu_layers=-1, verbose=False)
app = Flask(__name__)
CORS(app)

@app.route('/v1/chat/completions', methods=['POST'])
def chat():
    data = request.json
    response = llm.create_chat_completion(messages=data.get('messages', []), max_tokens=80, temperature=0.05)
    return jsonify({"choices": [{"message": {"role": "assistant", "content": response['choices'][0]['message']['content']}}]})

@app.route('/health')
def health():
    return {"status": "running"}

threading.Thread(target=lambda: app.run(host='0.0.0.0', port=5000, threaded=True, use_reloader=False)).start()
time.sleep(3)

ngrok.set_auth_token("YOUR_NGROK_TOKEN")
tunnel = ngrok.connect(5000, domain="stucco-parade-prowling.ngrok-free.dev")
url = tunnel.public_url
open(f"{DRIVE}/llama_api_url.txt", "w").write(url)
print(f"HAWK AI URL: {url}")

import requests as req
try:
    req.get(f"https://hawk-proxyout.onrender.com/update-url/{url.replace('https://', '')}", timeout=5)
    print("Render proxy updated!")
except:
    print("Render update failed — use direct ngrok URL if needed")

while True:
    time.sleep(300)
    print(f"[{time.strftime('%H:%M:%S')}] Still running...")
```

## Hybrid AI Architecture (Groq + Llama)

When using **FAST** or **SMART** mode, the agent runs a three-stage hybrid loop:

| Stage | Model | What it does |
|-------|-------|-------------|
| **Plan** | Groq (1 call) | Reads initial screen + your memories → writes a numbered step plan |
| **Execute** | Llama (unlimited) | Follows the plan, one UI action per step |
| **Re-plan** | Groq (if stuck) | If the screen doesn't change 3× in a row, Groq rewrites the plan |
| **Learn** | Groq (1 call on done) | Extracts what worked → saves to local memory + Google Sheets |

In **LOCAL** mode Groq is skipped entirely — Llama runs all steps without a pre-plan.

The full UI node tree is archived to **Google Drive** every step (no truncation).  
Memories and task logs are stored in **Google Sheets** with unlimited rows.

---

## Google Drive + Sheets Setup (Unlimited Memory)

This is optional but gives the AI unlimited context — no node truncation, unlimited memories.

### Step 1 — Create the Apps Script proxy

1. Go to https://script.google.com → **New project**
2. Delete the default code, paste the contents of `hawk_apps_script.js`
3. At the top of the script, fill in:
   ```js
   const SECRET_TOKEN   = "choose-any-password";
   const SHEETS_ID      = "your-google-sheet-id";      // from the URL of your sheet
   const DRIVE_FOLDER_ID = "your-drive-folder-id";     // from the URL of your folder
   ```
4. Create a **Google Sheet** (any name) → copy the long ID from its URL
5. Create a **Google Drive folder** for tree archives → copy its ID from the URL
6. Click **Deploy → New deployment**
   - Type: **Web app**
   - Execute as: **Me**
   - Who has access: **Anyone**
7. Click **Deploy** → copy the deployment URL

### Step 2 — Paste config into the app

Open `HawkCloudStorage.kt` and fill in:
```kotlin
object HawkCloudConfig {
    const val SCRIPT_URL   = "https://script.google.com/macros/s/YOUR_ID/exec"
    const val SCRIPT_TOKEN = "choose-any-password"   // must match Step 1
    const val SHEETS_ID    = "your-google-sheet-id"
    const val DRIVE_FOLDER = "your-drive-folder-id"
}
```

That's it. The app will automatically:
- Upload full UI node trees (no 80-node cap) to Drive on every step
- Save/search memories in Sheets (unlimited rows)
- Log completed tasks to Drive as a text file

If cloud is not configured, the app silently falls back to local-only mode.

---

## MEM Key — Memory Panel

The **MEM** key (bottom-left, where `?123` used to be) opens the **Memory Panel**:

- **Tap a memory** → pastes it into the command bar
- **Long-press a memory** → deletes it (also from cloud)
- **Type in the "Add memory…" box + press +** → saves a new user memory
- **Long-press the MEM key itself** → quick-saves the current command bar text as a memory
- **CLEAR ALL** button → wipes local memory (cloud Sheets stays)

Memories are automatically saved after every completed task (AI extracts what it learned).

---

## Adding Your Groq API Key

Open `HawkAccessibilityService.kt` and replace:
```kotlin
const val API_KEY = "gsk_PASTE_YOUR_GROQ_KEY_HERE"
```
with your full key from https://console.groq.com/keys

## Known Issues & Fixes

- **502 from Render proxy**: Colab restarted — run the "Update Render URL" cell
- **Accessibility Service disconnected**: Go to Settings → Accessibility and re-enable
- **Keyboard doesn't appear**: Make sure Keyboard Hawk is selected as the active input method
- **AI not responding**: Check Colab is still running (Cell 3), or switch to FAST mode
