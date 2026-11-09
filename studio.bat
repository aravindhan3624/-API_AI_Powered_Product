@echo off
rem API Quality Studio - local Test Studio + built-in assistant (no internet, no API key needed)
title API Quality Studio (keep this window open)
cd /d "%~dp0"

where java >nul 2>nul || (echo Java was not found on PATH. Install Java 17+ and try again. & goto fail)
where mvn >nul 2>nul || (echo Maven was not found on PATH. Install Maven and try again. & goto fail)

netstat -ano | findstr /R /C:":8099 .*LISTENING" >nul
if not errorlevel 1 (
  echo Studio is already running. Opening http://localhost:8099
  start "" http://localhost:8099
  goto end
)

echo Building (first run downloads dependencies, please wait)...
if not exist target\lib\rest-assured*.jar (
  call mvn -q compile dependency:copy-dependencies -DoutputDirectory=target\lib || goto fail
)
call mvn -q compile || goto fail

echo.
echo Starting API Quality Studio on http://localhost:8099  (Ctrl+C to stop)
rem open the browser a few seconds later, when the server is up
start "" /min cmd /c "timeout /t 6 /nobreak >nul & start http://localhost:8099"
java -Dstudio.port=8099 -cp "target\classes;target\lib\*" com.api.product.studio.StudioMain
if errorlevel 1 goto fail
goto end

:fail
echo.
echo Studio could not start. Read the messages above.
pause
:end
