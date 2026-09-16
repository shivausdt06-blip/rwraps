@echo off
rem Invoked by start-local-test.bat. Local development only. No secrets.
cd /d "%~dp0.."
title ARL Backend
rem Bind all interfaces so the Android emulator can reach the API at http://10.0.2.2:8080.
rem load-env.js does not override variables already set. Production still uses Render HOST/TLS.
set "HOST=0.0.0.0"
echo Starting existing workspace command: npm run dev
npm run dev
