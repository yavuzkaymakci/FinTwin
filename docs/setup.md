# Setting up FinTwin on a new Windows machine

This guide takes a clean Windows 10/11 machine to a running FinTwin with the full synthetic data set, trained
models and preset scenario results. Everything runs from `cmd`; PowerShell is not needed.

## 1. Requirements

| Item | Version / note |
|------|----------------|
| RAM | 16 GB (the memory limits in CLAUDE.md are sized for it) |
| Disk | ~25 GB free (Docker images and the PostgreSQL volume ~10 GB, Maven/npm/pip caches, models) |
| Windows | 10 22H2 or 11, virtualisation enabled in the BIOS |
| Internet | for the first run (Docker images, Maven, npm and pip packages) |

## 2. Install the tools

1. **WSL 2**: in an administrator `cmd`, run `wsl --install`, then reboot.
2. **Limit WSL memory** (Docker runs inside WSL 2). Create `%UserProfile%\.wslconfig`:

   ```ini
   [wsl2]
   memory=6GB
   swap=4GB
   ```

   Apply it with `wsl --shutdown` and restart Docker Desktop.
3. **Docker Desktop** (WSL 2 backend). Start it and wait until it shows *Engine running*. In
   *Settings → Resources* nothing else needs changing; the compose file limits each container.
4. **JDK 21**: Eclipse Temurin 21 (MSI). In the installer, enable *Set JAVA_HOME* and *Add to PATH*. Check in
   a **new** `cmd`: `java -version` must print `21.x`. If an older Java comes first in PATH, set
   `JAVA_HOME` to the Temurin folder and put `%JAVA_HOME%\bin` at the front of PATH.
5. **Node.js LTS** (20 or newer, includes npm). Check: `node -v`.
6. **Python 3.11 or newer** from python.org; tick *Add python.exe to PATH* (the `py` launcher is installed too).
   Check: `py --version`. Disable the Microsoft Store aliases for `python.exe` in *App execution aliases* if
   `python` opens the Store.
7. **Git**. Check: `git --version`.

Maven is not needed (the repository ships the Maven wrapper). `curl` is part of Windows.

## 3. Get the code

```bat
cd %UserProfile%\Desktop
git clone <repository-url> FinTwin
cd FinTwin
```

Use a path without non-ASCII characters if possible; spaces are supported.

## 4. Build everything: `scripts\setup-fresh.bat`

```bat
rem only checks tools and repository files, changes nothing
scripts\setup-fresh.bat --check
rem full setup
scripts\setup-fresh.bat
```

| Step | What happens | Log | Time (16 GB laptop) |
|------|--------------|-----|---------------------|
| 1 | prerequisites: Docker running, Java 21, Node, npm, curl, Python ≥ 3.11, ports 8080/8000 free | console | seconds |
| 2 | `infra\.env` from `.env.example` if missing; PostgreSQL, Kafka, Kafka UI up and healthy | `setup-infra.log` | 1–3 min (image download) |
| 3 | `ml-service\.venv` + `pip install -r requirements-dev.txt` | `setup-venv.log` | 2–5 min |
| 4 | `npm ci` in `frontend` | `setup-npm.log` | 1–2 min |
| 5 | synthetic data, seed 42 (Flyway migrations run first; the report goes to `logs\data-generation-fresh.md`) | `setup-generate.log` | 3–8 min (first Maven download) |
| 6 | backend started minimized (profile `local`) | `setup-backend.log` | 1 min |
| 7 | twin reset, outbox, bulk Kafka replay, wait until every event is applied | `setup-twin.log` | 7–10 min |
| 8 | label export, PD / churn / NLP training, activation | `setup-export-labels.log`, `setup-train-*.log` | 5–10 min |
| 9 | PD vs hidden stress evaluation | `setup-evaluate.log` | < 1 min |
| 10 | ml-service started minimized, portfolio scoring | `setup-ml-service.log`, `setup-score.log` | < 1 min |
| 11 | 5 preset scenarios through the backend, one at a time (BASE, then the others against BASE) | `setup-presets.log` | ~15 min |
| 12 | comparison with [reproducibility.json](reproducibility.json), backend and ml-service stopped | `setup-check.log` | seconds |

Total: roughly 45–60 minutes. All logs are in `logs\`.

**Re-running**: finished steps leave a marker in `logs\setup-state\` and are skipped, so after fixing a problem
simply run the script again. `scripts\setup-fresh.bat --force` runs everything again and regenerates the data
set (generation truncates the generated tables; the twin is reset before the replay). Steps 2–4 always run but
are fast when nothing changed.

**When it fails** it prints `SETUP FAILED: <reason>` with the log to read, and stops the backend/ml-service it
started. Typical causes: Docker Desktop not started or paused, an old Java in PATH, a port taken by a running
backend (`scripts\stop-all.bat`), no internet for Maven/npm/pip.

**Comparison warnings** at the end: table counts and twin numbers must match exactly (same seed); model metrics
and scenario results may drift within the tolerances. See [reproducibility.md](reproducibility.md).

## 5. Daily use

```bat
rem start with ai-demo assistant answers (default); stop everything, keeping the data
scripts\start-all.bat
scripts\stop-all.bat
```

`start-all.bat` opens http://localhost:5173 when the backend is up. Kafka UI: http://localhost:8085,
Swagger UI: http://localhost:8080/swagger-ui/index.html.

## 6. Optional: a real chat model

- **Anthropic**: `set ANTHROPIC_API_KEY=sk-ant-...` in the same `cmd` (never in a file of the repository), then
  `scripts\start-all.bat anthropic`.
- **Ollama**: install Ollama for Windows, `ollama pull qwen2.5:7b-instruct`, keep `ollama serve` running, then
  `scripts\start-all.bat ollama`. A 7B model needs ~5 GB RAM on top of FinTwin; close other programs.

## 7. Memory notes

With WSL capped at 6 GB, the containers use ≤ 3.5 GB, the backend ≤ 1.5 GB heap, ml-service ~0.5 GB during a
simulation, the Vite dev server ~0.3 GB. Do not run the backend test suite (`mvnw test`, it starts its own
containers) while setup-fresh or start-all is running; stop the compose stack first (see CLAUDE.md).
