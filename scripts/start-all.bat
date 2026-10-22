@echo off
rem FinTwin one-click start for Windows cmd (no PowerShell needed).
rem   scripts\start-all.bat              backend with the ai-demo profile (scripted assistant answers, real tools)
rem   scripts\start-all.bat anthropic    real Claude model, needs ANTHROPIC_API_KEY in the environment
rem   scripts\start-all.bat ollama       local Ollama model (ollama serve + the model pulled)
rem Each service runs in its own cmd window; scripts\stop-all.bat stops everything without deleting data.
setlocal EnableExtensions
cd /d "%~dp0.."
set "ROOT=%CD%"

set "MODE=%~1"
if "%MODE%"=="" set "MODE=demo"
if /i "%MODE%"=="demo" (
  set "PROFILES=local,ai-demo"
) else if /i "%MODE%"=="anthropic" (
  set "PROFILES=local,anthropic"
) else if /i "%MODE%"=="ollama" (
  set "PROFILES=local,ollama"
) else (
  echo Unknown mode "%MODE%". Use: start-all.bat [demo^|anthropic^|ollama]
  exit /b 1
)
if /i "%MODE%"=="anthropic" if "%ANTHROPIC_API_KEY%"=="" (
  echo ANTHROPIC_API_KEY is not set. Run:  set ANTHROPIC_API_KEY=sk-ant-...  and start again.
  exit /b 1
)

echo [1/5] Checking Docker...
where docker >nul 2>&1 || goto :nodocker
docker info >nul 2>&1 || goto :nodocker

echo [2/5] Starting PostgreSQL, Kafka and Kafka UI (waiting until healthy)...
docker compose -f infra\docker-compose.yml up -d --wait
if errorlevel 1 (
  echo Docker Compose could not start the infrastructure. See: docker compose -f infra\docker-compose.yml logs
  exit /b 1
)

echo [3/5] Starting ml-service on port 8000...
if not exist "ml-service\.venv\Scripts\python.exe" (
  echo ml-service\.venv is missing. Create it once with:
  echo   cd ml-service ^&^& python -m venv .venv ^&^& .venv\Scripts\python -m pip install -r requirements-dev.txt
  exit /b 1
)
start "FinTwin ml-service" /d "%ROOT%\ml-service" cmd /k ".venv\Scripts\python -m uvicorn app.main:app --port 8000"

echo [4/5] Starting backend on port 8080 (profiles %PROFILES%)...
start "FinTwin backend" /d "%ROOT%\backend" cmd /k ".\mvnw.cmd -q spring-boot:run -Dspring-boot.run.profiles=%PROFILES%"

echo [5/5] Starting frontend on port 5173...
if not exist "frontend\node_modules" (
  echo Installing frontend dependencies once...
  pushd frontend
  call npm install
  popd
)
start "FinTwin frontend" /d "%ROOT%\frontend" cmd /k "npm run dev"

echo Waiting for the backend to report UP (first start can take a minute)...
set /a TRIES=0
:waitbackend
set /a TRIES+=1
curl -s -o nul -f http://localhost:8080/actuator/health && goto :ready
if %TRIES% geq 90 (
  echo The backend did not come up within 3 minutes; check the "FinTwin backend" window.
  goto :open
)
ping -n 3 127.0.0.1 >nul
goto :waitbackend

:ready
echo Backend is up.
:open
start "" http://localhost:5173
echo.
echo FinTwin is running:
echo   Web UI      http://localhost:5173
echo   Backend     http://localhost:8080   (Swagger UI: /swagger-ui/index.html)
echo   ml-service  http://localhost:8000/health
echo   Kafka UI    http://localhost:8085
echo Stop everything with scripts\stop-all.bat (data is kept).
exit /b 0

:nodocker
echo.
echo Docker is not running. Start Docker Desktop, wait until it says "Engine running", then run this script again.
exit /b 1
