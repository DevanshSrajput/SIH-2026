@echo off
:: ──────────────────────────────────────────────────────
::  AI-Based Fake Identity & Document Screening System
::  Double-click this file to start everything.
:: ──────────────────────────────────────────────────────

title SIH - Screening System

echo.
echo  ╔══════════════════════════════════════════════════╗
echo  ║   AI-Based Fake Identity ^& Document Screening   ║
echo  ║              Starting all services...            ║
echo  ╚══════════════════════════════════════════════════╝
echo.

cd /d "%~dp0"

:: Check execution policy
powershell -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*

if %ERRORLEVEL% neq 0 (
    echo.
    echo  [ERROR] Start script exited with code %ERRORLEVEL%
    echo.
    pause
)
