@echo off
cd /d "C:\Bankoapp-main\BankIntel-v2\backend-java"
set PORT=8081
call .\gradlew.bat bootRun > "C:\Bankoapp-main\BankIntel-v2\outputs\qa-nocni-test-2026-08-30\win-backend.log" 2>&1
