@echo off
setlocal
cd /d "%~dp0"
set "REDE_PY="
if exist "%~dp0runtime\python.exe" (
  "%~dp0runtime\python.exe" -I -c "import tkinter, ssl, sqlite3" >nul 2>&1
  if not errorlevel 1 set "REDE_PY=%~dp0runtime\python.exe"
)
if defined REDE_PY goto iniciar
for /f "delims=" %%P in ('python.exe -c "import sys, tkinter; assert sys.version_info >= (3, 11); print(sys.executable)" 2^>nul') do set "REDE_PY=%%P"
if not defined REDE_PY if exist "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" set "REDE_PY=%LOCALAPPDATA%\Programs\Python\Python313\python.exe"
if defined REDE_PY goto iniciar
where winget.exe >nul 2>&1
if errorlevel 1 goto manual
echo O Conector precisa do Python 3.11 ou mais recente com Tk.
choice /M "Instalar Python oficial pelo Gerenciador de Pacotes do Windows agora"
if errorlevel 2 goto manual
winget install --exact --id Python.Python.3.13 --scope user
if errorlevel 1 goto manual
echo Abra este arquivo novamente quando a instalacao terminar.
pause
exit /b 0

:iniciar
"%REDE_PY%" -I "%~dp0iniciar_conector.py"
if errorlevel 1 pause
exit /b

:manual
echo Instale o Python oficial em https://www.python.org/downloads/windows/
echo Marque Add Python to PATH e mantenha o suporte a Tcl/Tk.
echo Depois abra este arquivo novamente.
pause
exit /b 1
