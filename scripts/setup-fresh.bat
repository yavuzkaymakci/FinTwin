@echo off
rem FinTwin fresh setup for a new Windows machine (cmd only, no PowerShell needed).
rem Builds everything from scratch and deterministically: infrastructure, dependencies, synthetic data (seed 42),
rem Kafka replay into the twins, model training, portfolio scoring and the preset scenarios.
rem
rem   scripts\setup-fresh.bat           resume: steps that already finished are skipped
rem   scripts\setup-fresh.bat --force   run every step again (regenerates the data set)
rem   scripts\setup-fresh.bat --check   only check the prerequisites and the repository files, change nothing
rem
rem Every step logs to logs\setup-<step>.log; markers of finished steps are in logs\setup-state\.
rem Takes about 45-60 minutes on a 16 GB laptop. Afterwards start the app with scripts\start-all.bat.
setlocal EnableExtensions
cd /d "%~dp0.."
set "ROOT=%CD%"
set "LOGS=%ROOT%\logs"
set "STATE=%LOGS%\setup-state"
set "PY=%ROOT%\ml-service\.venv\Scripts\python.exe"
set "STEPS=%ROOT%\scripts\setup_steps.py"
if not exist "%STATE%" mkdir "%STATE%"
if /i "%~1"=="--force" del /q "%STATE%\*.done" >nul 2>&1

echo ================================================================
echo  FinTwin fresh setup  -  logs in %LOGS%
echo ================================================================

rem ---------------------------------------------------------------- 1. prerequisites
echo [1/12] Checking prerequisites...
where docker >nul 2>&1 || (set "ERR=Docker is not installed. Install Docker Desktop, see docs\setup.md." & goto :fail)
docker info >nul 2>&1 || (set "ERR=Docker is not running. Start Docker Desktop and wait for Engine running." & goto :fail)
where java >nul 2>&1 || (set "ERR=Java not found. Install JDK 21 and set JAVA_HOME, see docs\setup.md." & goto :fail)
java -version 2>&1 | findstr /r /c:"version .21\." >nul || (set "ERR=Java 21 is required; java -version shows another version. Set JAVA_HOME and PATH to JDK 21." & goto :fail)
where node >nul 2>&1 || (set "ERR=Node.js not found. Install Node.js 20 LTS or newer." & goto :fail)
where npm >nul 2>&1 || (set "ERR=npm not found. Reinstall Node.js." & goto :fail)
where curl >nul 2>&1 || (set "ERR=curl not found; it ships with Windows 10 1803 and later." & goto :fail)
set "PYTHON="
for %%C in (py python) do if not defined PYTHON (
  %%C -c "import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)" >nul 2>&1 && set "PYTHON=%%C"
)
if not defined PYTHON (set "ERR=Python 3.11 or newer not found as py or python." & goto :fail)
for %%P in (8080 8000) do (
  netstat -ano | findstr /r /c:":%%P .*LISTENING" >nul && (set "ERR=Port %%P is in use. Stop running services first: scripts\stop-all.bat" & goto :fail)
)
if not defined JAVA_HOME echo        warning: JAVA_HOME is not set; the Maven wrapper then uses java from PATH
echo        Docker, Java 21, Node, Python [%PYTHON%] OK
for %%F in (infra\docker-compose.yml infra\.env.example backend\mvnw.cmd ml-service\requirements-dev.txt frontend\package-lock.json scripts\setup_steps.py docs\reproducibility.json) do (
  if not exist "%%F" (set "ERR=Missing file %%F - is the repository complete?" & goto :fail)
)
if /i "%~1"=="--check" (echo        --check: prerequisites and files OK, nothing changed & exit /b 0)

rem ---------------------------------------------------------------- 2. infrastructure
echo [2/12] Starting PostgreSQL, Kafka and Kafka UI...
if not exist "infra\.env" (
  copy /y "infra\.env.example" "infra\.env" >nul
  echo        created infra\.env from infra\.env.example
)
docker compose -f infra\docker-compose.yml up -d --wait > "%LOGS%\setup-infra.log" 2>&1 || (set "ERR=Docker Compose failed, see logs\setup-infra.log" & goto :fail)

rem ---------------------------------------------------------------- 3. Python environment
echo [3/12] ml-service virtual environment and dependencies...
if not exist "%PY%" (
  %PYTHON% -m venv ml-service\.venv > "%LOGS%\setup-venv.log" 2>&1 || (set "ERR=python -m venv failed, see logs\setup-venv.log" & goto :fail)
)
"%PY%" -m pip install -r ml-service\requirements-dev.txt >> "%LOGS%\setup-venv.log" 2>&1 || (set "ERR=pip install failed, see logs\setup-venv.log" & goto :fail)

rem ---------------------------------------------------------------- 4. frontend
if exist "%STATE%\npm.done" (echo [4/12] Frontend dependencies: already installed, skipped & goto :generate)
echo [4/12] Frontend dependencies [npm ci]...
pushd frontend
call npm ci > "%LOGS%\setup-npm.log" 2>&1
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" (set "ERR=npm ci failed, see logs\setup-npm.log" & goto :fail)
echo done> "%STATE%\npm.done"

:generate

rem ---------------------------------------------------------------- 5. synthetic data
if exist "%STATE%\generate.done" (echo [5/12] Synthetic data: already generated, skipped & goto :backend)
echo [5/12] Generating the synthetic portfolio, seed 42 - first run downloads Maven dependencies...
del /q "%STATE%\twin.done" "%STATE%\train.done" "%STATE%\score.done" "%STATE%\presets.done" >nul 2>&1
set "GENERATOR_REPORT_PATH=%LOGS%\data-generation-fresh.md"
pushd backend
call .\mvnw.cmd -q spring-boot:run -Dspring-boot.run.profiles=local,generate > "%LOGS%\setup-generate.log" 2>&1
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" (set "ERR=Data generation failed, see logs\setup-generate.log" & goto :fail)
echo done> "%STATE%\generate.done"

rem ---------------------------------------------------------------- 6. backend
:backend
echo [6/12] Starting the backend [profile local] in a minimized window...
start "FinTwin setup backend" /min /d "%ROOT%\backend" cmd /c ".\mvnw.cmd -q spring-boot:run -Dspring-boot.run.profiles=local > ..\logs\setup-backend.log 2>&1"
"%PY%" "%STEPS%" wait-http http://localhost:8080/actuator/health --timeout 600 || (set "ERR=Backend did not start, see logs\setup-backend.log" & goto :fail)

rem ---------------------------------------------------------------- 7. twin
if exist "%STATE%\twin.done" (echo [7/12] Twin: already built, skipped & goto :train)
echo [7/12] Replaying the core banking feed into the twins - about 7-10 minutes...
"%PY%" "%STEPS%" twin --timeout 3600 > "%LOGS%\setup-twin.log" 2>&1 || (set "ERR=Twin build failed, see logs\setup-twin.log" & goto :fail)
echo done> "%STATE%\twin.done"

rem ---------------------------------------------------------------- 8-9. models
:train
if exist "%STATE%\train.done" (echo [8/12] Models: already trained, skipped & goto :mlservice)
echo [8/12] Exporting labels and training PD, churn and NLP models...
pushd ml-service
"%PY%" -m app.labeling.export_transaction_labels > "%LOGS%\setup-export-labels.log" 2>&1 || (popd & set "ERR=Label export failed, see logs\setup-export-labels.log" & goto :fail)
"%PY%" -m app.training.train_risk --target pd --activate > "%LOGS%\setup-train-pd.log" 2>&1 || (popd & set "ERR=PD training failed, see logs\setup-train-pd.log" & goto :fail)
"%PY%" -m app.training.train_risk --target churn --activate > "%LOGS%\setup-train-churn.log" 2>&1 || (popd & set "ERR=Churn training failed, see logs\setup-train-churn.log" & goto :fail)
"%PY%" -m app.training.train_nlp --activate > "%LOGS%\setup-train-nlp.log" 2>&1 || (popd & set "ERR=NLP training failed, see logs\setup-train-nlp.log" & goto :fail)
echo [9/12] Evaluating PD against the hidden stress variable...
"%PY%" -m app.evaluation.stress_relationship > "%LOGS%\setup-evaluate.log" 2>&1 || (popd & set "ERR=Evaluation failed, see logs\setup-evaluate.log" & goto :fail)
popd
del /q "%STATE%\score.done" "%STATE%\presets.done" >nul 2>&1
echo done> "%STATE%\train.done"

rem ---------------------------------------------------------------- 10. ml-service + scoring
:mlservice
echo [10/12] Starting ml-service and scoring the portfolio...
start "FinTwin setup ml-service" /min /d "%ROOT%\ml-service" cmd /c ".venv\Scripts\python -m uvicorn app.main:app --port 8000 > ..\logs\setup-ml-service.log 2>&1"
"%PY%" "%STEPS%" wait-http http://localhost:8000/health --timeout 180 || (set "ERR=ml-service did not start, see logs\setup-ml-service.log" & goto :fail)
if exist "%STATE%\score.done" (echo         portfolio already scored, skipped & goto :presets)
"%PY%" "%STEPS%" score --timeout 900 > "%LOGS%\setup-score.log" 2>&1 || (set "ERR=Portfolio scoring failed, see logs\setup-score.log" & goto :fail)
echo done> "%STATE%\score.done"

rem ---------------------------------------------------------------- 11. preset scenarios
:presets
if exist "%STATE%\presets.done" (echo [11/12] Preset scenarios: already run, skipped & goto :check)
echo [11/12] Running the 5 preset scenarios one at a time - about 15 minutes...
"%PY%" "%STEPS%" presets --timeout 1200 > "%LOGS%\setup-presets.log" 2>&1 || (set "ERR=Preset runs failed, see logs\setup-presets.log" & goto :fail)
echo done> "%STATE%\presets.done"

rem ---------------------------------------------------------------- 12. comparison and shutdown
:check
echo [12/12] Comparing with the reference values [docs\reproducibility.json]...
"%PY%" "%STEPS%" check > "%LOGS%\setup-check.log" 2>&1
type "%LOGS%\setup-check.log"
call :stopservices
echo.
echo ================================================================
echo  Setup finished. Logs: logs\setup-*.log
echo  Start the application with:  scripts\start-all.bat
echo  [Docker Compose keeps running; scripts\stop-all.bat stops it.]
echo ================================================================
exit /b 0

:stopservices
taskkill /FI "WINDOWTITLE eq FinTwin setup backend*" /T /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq FinTwin setup ml-service*" /T /F >nul 2>&1
for %%P in (8080 8000) do (
  for /f "tokens=5" %%I in ('netstat -ano ^| findstr /r /c:":%%P .*LISTENING"') do taskkill /PID %%I /T /F >nul 2>&1
)
exit /b 0

:fail
echo.
echo SETUP FAILED: %ERR%
echo Fix the problem and run scripts\setup-fresh.bat again; finished steps are skipped.
if /i not "%~1"=="--check" call :stopservices
exit /b 1
