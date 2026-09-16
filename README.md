# AI-Based Fake Identity & Document Screening System

Checks passports, visas and ID cards at a border checkpoint. It reads the document,
verifies the details follow official rules, looks for signs the document was altered,
and compares the photo on the document with the person standing there. Then it gives the
officer a risk score and a plain-English reason for it.

Built for **Smart India Hackathon 2026**, Problem Statement **26188** (Ministry of Home
Affairs, Sashastra Seema Bal). The full problem statement is in [Problem.md](Problem.md).

**New here?** Read [know.md](know.md) — it explains what this is, why it exists, and the
thinking behind the important decisions.

---

## What you need installed

Install these three first. Everything else the start script handles.

| You need | Why | Check it worked |
|---|---|---|
| **Java 21 or newer** | Runs the main screening server | `java -version` |
| **Python 3.10 or newer** | Runs the face matching service | `python --version` |
| **Node.js 20 or newer** | Runs the officer screen | `node --version` |

If a command says "not recognised", that program is not installed — install it and open a
new terminal.

**Optional but recommended:** [Tesseract OCR](https://github.com/UB-Mannheim/tesseract/wiki)
so the system can read text from images. Install it with the default settings; the system
finds it on its own. Without it, document reading still works if you type or paste the
text in, but it cannot read a photo by itself.

---

## Running it

You need **three separate terminal windows** — one for each service. They all must be
running at the same time for the system to work.

### Terminal 1 — Face Verification Service

Open a new terminal and run:

**PowerShell:**
```powershell
cd "C:\Personal Projects\SIH-2026\face-verification-service"
& "C:\Personal Projects\SIH-2026\venv\Scripts\python.exe" -m uvicorn app:app --host 0.0.0.0 --port 5000
```

**Bash (Git Bash / WSL):**
```bash
cd "C:/Personal Projects/SIH-2026/face-verification-service"
"C:/Personal Projects/SIH-2026/venv/Scripts/python.exe" -m uvicorn app:app --host 0.0.0.0 --port 5000
```

Wait until you see `Application startup complete`, then verify it works:

```bash
curl http://localhost:5000/health
```

You should see `{"status":"ok","detector_available":true,"recognizer_available":true}`.

### Terminal 2 — Java Backend (Screening API)

Open a **second** terminal and run:

**PowerShell:**
```powershell
$env:PATH = "C:\Program Files\Java\jdk-27\bin;C:\Program Files\Tesseract-OCR;" + $env:PATH
$env:FACE_SERVICE_URL = "http://localhost:5000"
cd "C:\Personal Projects\SIH-2026\govtId-validation\backend"
.\mvnw spring-boot:run -Pembedded-mongo
```

**Bash (Git Bash / WSL):**
```bash
export PATH="/c/Program Files/Java/jdk-27/bin:/c/Program Files/Tesseract-OCR:$PATH"
export FACE_SERVICE_URL="http://localhost:5000"
cd "C:/Personal Projects/SIH-2026/govtId-validation/backend"
./mvnw spring-boot:run -Pembedded-mongo
```

Wait until you see `Started ScreeningApplication`, then verify:

```bash
curl http://localhost:8080/actuator/health
```

You should see `{"status":"UP"}`.

**What the environment variables do:**
- `PATH` adds Java and Tesseract so the backend can find them
- `FACE_SERVICE_URL` tells the backend where the Python face service is running

### Terminal 3 — React Frontend (Officer Console)

Open a **third** terminal and run:

**PowerShell:**
```powershell
cd "C:\Personal Projects\SIH-2026\govtId-validation\frontend"
npm install
npm run dev
```

**Bash (Git Bash / WSL):**
```bash
cd "C:/Personal Projects/SIH-2026/govtId-validation/frontend"
npm install
npm run dev
```

Wait until you see `Local: http://localhost:5173/`, then open that URL in your browser.

### Verify everything is running

All three terminals should show no errors. Check each:

| Service | Terminal | Verify with | Expected |
|---|---|---|---|
| Face Service | 1 | `curl http://localhost:5000/health` | `{"status":"ok"}` |
| Backend | 2 | `curl http://localhost:8080/actuator/health` | `{"status":"UP"}` |
| Frontend | 3 | Open `http://localhost:5173` | Officer console loads |

### Stopping everything

Press `Ctrl+C` in each terminal, or run:

```powershell
powershell -File "C:\Personal Projects\SIH-2026\stop.ps1"
```

### Quick start (all at once)

If you prefer one command that opens all three services automatically:

**PowerShell:**
```powershell
.\start.ps1
```

**If double-clicking does not work:**
```powershell
powershell -ExecutionPolicy Bypass -File .\start.ps1
```

This opens three terminal tabs and keeps running until you press `Ctrl+C`.

---

## Trying it out

1. Open **http://localhost:5173**
2. Click **Screen**
3. Drag in a photo of a passport or ID card
4. Optionally click **Start camera** and **Capture** to take a live photo
5. Click **Screen document**

You get back one of three recommendations:

| Result | Meaning |
|---|---|
| 🟢 **CLEAR** | Nothing suspicious found |
| 🟡 **REVIEW** | Something needs an officer to look at it — the reason is listed |
| 🔴 **REJECT** | Strong signs of a fake or altered document |

Every finding says what was measured and why it mattered, so you can check the system's
reasoning rather than taking its word for it.

### The other screens

| Screen | What it does |
|---|---|
| **Screen** | Check one document |
| **Face Auth** | Compare a face to a document photo; register known travellers; identify someone |
| **Cases** | Every document checked so far |
| **Watchlist** | Manage the list of stolen, revoked and flagged documents |
| **Dashboard** | How many people came through, how many were referred, and why |

---

## Important: the face matching needs calibrating

The system ships with sensible face-matching thresholds, but **they are a starting point,
not a finished setting.** The right threshold depends on your cameras, your lighting and
your document scanners.

Before relying on it, gather some real photos — several people, a few pictures each —
arranged like this:

```
samples/
  person1/   passport.jpg   photo1.jpg   photo2.jpg
  person2/   passport.jpg   photo1.jpg
```

Then run:

```bash
cd face-verification-service
python calibrate.py samples/
```

It tells you how often the system would wrongly say two different people are the same
person. **If that number is not zero, raise the threshold until it is.** The script tells
you which number to use.

This matters because a wrong "yes" lets an impostor through on someone else's passport,
while a wrong "not sure" only costs an officer a few seconds. Those are not equally bad,
and the settings should not treat them as if they were.

---

## Something went wrong

| Problem | Fix |
|---|---|
| "Port already in use" | Something else is on 8080, 5173 or 5000. Run `stop.ps1` and try again. |
| Face check says "did not run" | The face service is not running. Check its terminal window for errors. |
| Text is not read from images | Tesseract is not installed. Install it, or paste the text in manually. |
| Camera does not start | Your browser blocked it. Click the camera icon in the address bar and allow access. |
| Officer screen is blank | The screening server is still starting. Wait 30 seconds and refresh. |
| `java`/`python`/`node` "not recognised" | That program is not installed, or the terminal was open before you installed it. Open a new terminal. |

---

## How it is put together

Three programs that talk to each other:

```
  Officer's browser
        │
        ▼
  Officer console  (React)          port 5173   what the officer sees
        │
        ▼
  Screening API    (Java/Spring)    port 8080   the four checks, and the risk score
        │
        ├──────────► MongoDB                    case history and evidence images
        │
        ▼
  Face service     (Python)         port 5000   face matching and liveness
```

The four checks each document goes through:

| # | Check | Question it answers |
|---|---|---|
| 1 | **OCR extraction** | What does this document say? |
| 2 | **Document validation** | Do those details follow the official rules? |
| 3 | **Tampering detection** | Has the image been edited? |
| 4 | **Face verification** | Is this the person the document was issued to? |

Plus a **watchlist** stage that checks the document against stolen and revoked lists, and
notices if one document keeps coming back or one person is using several identities.

---

## Project layout

```
.
├── start.bat / start.ps1 / stop.ps1   Start and stop everything
├── docker-compose.yml                 Run everything in Docker instead
├── know.md                            What this is and why - read this first
├── next.md                            What is done, what is left
├── Problem.md                         The original problem statement
│
├── govtId-validation/
│   ├── backend/                       Java screening server
│   ├── frontend/                      React officer console
│   ├── docs/                          Technical documentation
│   └── postman/                       API request collection
│
└── face-verification-service/         Python face matching service
    ├── app.py                         The API
    ├── decision.py                    Match / no-match / unsure logic
    ├── face_quality.py                Rejects photos too poor to judge
    └── calibrate.py                   Threshold calibration tool
```

---

## For developers

Each service has its own setup commands. Run them in separate terminals.

### Face service (Python)

**PowerShell:**
```powershell
cd "C:\Personal Projects\SIH-2026\face-verification-service"
& "C:\Personal Projects\SIH-2026\venv\Scripts\pip.exe" install -r requirements.txt
python -m pytest tests/ -v
```

**Bash:**
```bash
cd "C:/Personal Projects/SIH-2026/face-verification-service"
"C:/Personal Projects/SIH-2026/venv/Scripts/pip.exe" install -r requirements.txt
python -m pytest tests/ -v
```

### Backend (Java)

**PowerShell:**
```powershell
$env:PATH = "C:\Program Files\Java\jdk-27\bin;" + $env:PATH
cd "C:\Personal Projects\SIH-2026\govtId-validation\backend"
.\mvnw test
```

**Bash:**
```bash
export PATH="/c/Program Files/Java/jdk-27/bin:$PATH"
cd "C:/Personal Projects/SIH-2026/govtId-validation/backend"
./mvnw test
```

### Frontend (React)

**PowerShell:**
```powershell
cd "C:\Personal Projects\SIH-2026\govtId-validation\frontend"
npm install
npm run lint
npm run build
```

**Bash:**
```bash
cd "C:/Personal Projects/SIH-2026/govtId-validation/frontend"
npm install
npm run lint
npm run build
```

**API documentation** (with the server running):

- Screening API — http://localhost:8080/swagger-ui.html
- Face service — http://localhost:5000/docs

**Technical documentation** in [`govtId-validation/docs/`](govtId-validation/docs/):

| Document | Covers |
|---|---|
| [ARCHITECTURE.md](govtId-validation/docs/ARCHITECTURE.md) | How the pieces fit together |
| [DESIGN.md](govtId-validation/docs/DESIGN.md) | Decisions made, and alternatives rejected |
| [DETECTION.md](govtId-validation/docs/DETECTION.md) | How tampering detection works |
| [RISK-SCORING.md](govtId-validation/docs/RISK-SCORING.md) | How findings become a score |
| [API.md](govtId-validation/docs/API.md) | Endpoint reference |
| [DATA-MODEL.md](govtId-validation/docs/DATA-MODEL.md) | What is stored |
| [DEPLOYMENT.md](govtId-validation/docs/DEPLOYMENT.md) | Running it for real |
| [TESTING.md](govtId-validation/docs/TESTING.md) | Test strategy |
| [SPECIFICATION.md](govtId-validation/docs/SPECIFICATION.md) | Requirements |
| [USER-JOURNEY.md](govtId-validation/docs/USER-JOURNEY.md) | What the officer actually does |
| [TASKS.md](govtId-validation/docs/TASKS.md) | Delivered work, module by module |

---

## Before this is used on real travellers

This is a hackathon prototype. Three things are deliberately not built yet, and all three
are blocking for real deployment:

1. **No login.** Every endpoint is open to anyone who can reach it. It needs
   authentication and role-based access before it goes anywhere near a network.
2. **Face thresholds are uncalibrated.** Run `calibrate.py` on real photographs first.
3. **Biometric data is stored unencrypted.** Face embeddings and passport numbers need
   encryption at rest.

[next.md](next.md) tracks these and everything else outstanding.
