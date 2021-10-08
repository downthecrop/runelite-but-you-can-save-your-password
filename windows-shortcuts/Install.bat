@echo off

:: BatchGotAdmin
:-------------------------------------
REM  --> Check for permissions
    IF "%PROCESSOR_ARCHITECTURE%" EQU "amd64" (
>nul 2>&1 "%SYSTEMROOT%\SysWOW64\cacls.exe" "%SYSTEMROOT%\SysWOW64\config\system"
) ELSE (
>nul 2>&1 "%SYSTEMROOT%\system32\cacls.exe" "%SYSTEMROOT%\system32\config\system"
)

REM --> If error flag set, we do not have admin.
if '%errorlevel%' NEQ '0' (
    echo Requesting administrative privileges...
    goto UACPrompt
) else ( goto gotAdmin )

:UACPrompt
    echo Set UAC = CreateObject^("Shell.Application"^) > "%temp%\getadmin.vbs"
    set params= %*
    echo UAC.ShellExecute "cmd.exe", "/c ""%~s0"" %params:"=""%", "", "runas", 1 >> "%temp%\getadmin.vbs"

    "%temp%\getadmin.vbs"
    del "%temp%\getadmin.vbs"
    exit /B

:gotAdmin
    pushd "%CD%"
    CD /D "%~dp0"
:--------------------------------------    
set INSTALLDIR=%APPDATA%\..\Local\RuneLite-BYCSYP

if exist %INSTALLDIR% del /q /s %INSTALLDIR%
mkdir %INSTALLDIR%

xcopy /s * %INSTALLDIR% /Y
del %INSTALLDIR%\Install.bat

xcopy /s %INSTALLDIR%\RuneLite.lnk "%CD:~0,3%ProgramData\Microsoft\Windows\Start Menu\Programs" /Y
xcopy /s %INSTALLDIR%\RuneLite.lnk %CD:~0,3%%HOMEPATH%\Desktop\ /Y

echo.
echo.
echo.
echo RuneLite-BYCSYP Installed to AppData\Local. Shortcuts added to Start Menu and Desktop
echo You can safely delete this installer and its files

pause