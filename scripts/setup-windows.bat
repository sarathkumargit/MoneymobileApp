@echo off
setlocal enabledelayedexpansion
title MoneyMobile - Phone Setup
cd /d "%~dp0"

echo.
echo  =====================================================
echo    MoneyMobile - Phone Setup
echo  =====================================================
echo.

:: -- config --------------------------------------------
if not exist config.txt (
  echo [ERROR] config.txt is missing. Download it from the website
  echo         ^(Settings ^> Device setup^) and keep it in this folder.
  pause & exit /b 1
)
set SHOP_ID=
set SHOP_SECRET=
set SERVER_URL=
for /f "usebackq tokens=1,* delims==" %%a in ("config.txt") do (
  set "key=%%a"
  set "key=!key: =!"
  if /i "!key!"=="SHOP_ID" set "SHOP_ID=%%b"
  if /i "!key!"=="SHOP_SECRET" set "SHOP_SECRET=%%b"
  if /i "!key!"=="SERVER_URL" set "SERVER_URL=%%b"
)
set "SHOP_ID=%SHOP_ID: =%"
set "SHOP_SECRET=%SHOP_SECRET: =%"
if "%SHOP_ID%"=="" ( echo [ERROR] config.txt is missing SHOP_ID or SHOP_SECRET. & pause & exit /b 1 )
if "%SHOP_SECRET%"=="" ( echo [ERROR] config.txt is missing SHOP_ID or SHOP_SECRET. & pause & exit /b 1 )

if not exist MoneyMobile.apk ( echo [ERROR] MoneyMobile.apk is missing from this folder. & pause & exit /b 1 )

:: -- adb (download platform-tools if missing) ----------
set "ADB=adb"
where adb >nul 2>&1
if %errorlevel% neq 0 (
  if exist "platform-tools\adb.exe" (
    set "ADB=platform-tools\adb.exe"
  ) else (
    echo [1/5] Android tools not found - downloading ^(one time^)...
    powershell -NoProfile -Command "try { Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile 'platform-tools.zip' } catch { exit 1 }"
    if !errorlevel! neq 0 ( echo [ERROR] Could not download Android tools. Check the internet connection. & pause & exit /b 1 )
    powershell -NoProfile -Command "Expand-Archive -Force 'platform-tools.zip' '.'"
    set "ADB=platform-tools\adb.exe"
    echo [OK] Tools ready.
  )
)

echo.
echo [2/5] Waiting for the phone ^(plug in via USB, allow USB debugging^)...
"%ADB%" wait-for-device
for /f "tokens=*" %%s in ('"%ADB%" get-state 2^>nul') do set DEVICE_STATE=%%s
if not "!DEVICE_STATE!"=="device" (
  echo [ERROR] Phone not ready. Enable USB Debugging and tap Allow on the phone.
  pause & exit /b 1
)
for /f "delims=" %%i in ('"%ADB%" shell getprop ro.product.manufacturer 2^>nul') do set BRAND=%%i
for /f "delims=" %%i in ('"%ADB%" shell getprop ro.product.model 2^>nul') do set MODEL=%%i
echo [OK] Connected: !BRAND! !MODEL!

:: Already managed by MoneyMobile? Then this run is just an app UPDATE (keeps customer + lock status).
"%ADB%" shell "dumpsys device_policy | grep -A3 'Device Owner'" 2>nul | findstr /C:"com.moneymobile.installment" >nul
if !errorlevel! equ 0 (
  echo.
  echo [3/3] This phone is already set up - updating the app only...
  "%ADB%" install -r -g MoneyMobile.apk > "%TEMP%\mm_upd.txt" 2>&1
  type "%TEMP%\mm_upd.txt"
  echo.
  echo  =====================================================
  findstr /I /C:"Success" "%TEMP%\mm_upd.txt" >nul
  if !errorlevel! equ 0 (
    set "URL=!SERVER_URL!"
    if "!URL!"=="" (
      for /f "usebackq delims=" %%a in (`powershell -NoProfile -Command "(Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -ne $null } | Select-Object -First 1).IPv4Address.IPAddress"`) do set "PC_IP=%%a"
      if not "!PC_IP!"=="" set "URL=http://!PC_IP!:3000/api"
    )
    if not "!URL!"=="" (
      "%ADB%" shell am start -n com.moneymobile.installment/.ui.EnrollmentActivity --es action "UPDATE_SERVER_URL" --es server_url "!URL!" >nul 2>&1
      echo    Server address refreshed: !URL!
    ) else (
      "%ADB%" shell am start -n com.moneymobile.installment/.ui.SplashActivity >nul 2>&1
    )
    echo    DONE - !BRAND! !MODEL! updated.
    echo    Customer, registration and lock status are unchanged.
    echo.
    echo    Phone still shows Pending, or you deleted it on the website?
    choice /C RN /T 10 /D N /M "   Press R to register it again (N or wait = skip)"
    if !errorlevel! equ 1 (
      "%ADB%" shell am start -n com.moneymobile.installment/.ui.EnrollmentActivity --es action "REGISTER_DEVICE" --es shop_id "!SHOP_ID!" --es enroll_secret "!SHOP_SECRET!" --es server_url "!URL!" >nul 2>&1
      echo    Registration sent. The phone appears in Devices on the website in about a minute.
      echo    Then assign it to the customer again.
    )
  ) else (
    findstr /I /C:"DOWNGRADE" "%TEMP%\mm_upd.txt" >nul
    if !errorlevel! equ 0 ( echo    Already up to date - the phone has this version or newer. ) else ( echo    [ERROR] Update failed - see the message above. )
  )
  echo  =====================================================
  echo.
  pause & exit /b 0
)

echo.
echo [3/5] New phone - installing the app...
"%ADB%" install -r -g MoneyMobile.apk
if %errorlevel% neq 0 ( echo [ERROR] Install failed. & pause & exit /b 1 )
echo [OK] Installed.

echo.
echo [4/5] Enabling full management ^(Device Owner^)...
set ALREADY=
"%ADB%" shell "dumpsys device_policy | grep -A3 'Device Owner'" 2>nul | findstr /C:"com.moneymobile.installment" >nul
if !errorlevel! equ 0 ( echo [OK] Already set. & set ALREADY=1 )
if not defined ALREADY (
  "%ADB%" shell dpm set-device-owner com.moneymobile.installment/.admin.DeviceAdminReceiver > "%TEMP%\mm_dpm.txt" 2>&1
  type "%TEMP%\mm_dpm.txt"
  findstr /I /C:"Success" "%TEMP%\mm_dpm.txt" >nul
  if !errorlevel! neq 0 (
    echo.
    echo [ERROR] Could not enable management. Usually an account is still signed in.
    echo  Remove accounts in Settings ^> Passwords ^& accounts ^(WhatsApp can stay^), then run again:
    "%ADB%" shell dumpsys account 2>nul | findstr /C:"Account {" | findstr /V /C:"Session:"
    pause & exit /b 1
  )
)

echo.
echo [5/5] Registering the phone with the shop...
"%ADB%" logcat -c >nul 2>&1
rem Server address for the phone: SERVER_URL from config.txt, or - if the MoneyMobile server
rem runs on THIS computer - this computer's current network IP.
if "!SERVER_URL!"=="" (
  curl -s -m 3 -o nul http://localhost:3000/login >nul 2>&1
  if !errorlevel! equ 0 (
    for /f "usebackq delims=" %%a in (`powershell -NoProfile -Command "(Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -ne $null } | Select-Object -First 1).IPv4Address.IPAddress"`) do set "PC_IP=%%a"
    if not "!PC_IP!"=="" set "SERVER_URL=http://!PC_IP!:3000/api"
  ) else (
    echo       [!] The MoneyMobile server is not running on this computer - set SERVER_URL in config.txt.
  )
)
set "SERVER_ARG="
if not "!SERVER_URL!"=="" (
  echo       Server for the phone: !SERVER_URL!
  set "SERVER_ARG=--es server_url !SERVER_URL!"
)
"%ADB%" shell am start -W -n com.moneymobile.installment/.ui.EnrollmentActivity --es action "REGISTER_DEVICE" --es shop_id "%SHOP_ID%" --es enroll_secret "%SHOP_SECRET%" !SERVER_ARG! > "%TEMP%\mm_start.txt" 2>&1
findstr /I /C:"Error" "%TEMP%\mm_start.txt" >nul
if !errorlevel! equ 0 (
  echo [ERROR] Could not open the MoneyMobile app on the phone:
  type "%TEMP%\mm_start.txt"
  pause & exit /b 1
)

rem The app saves the shop details immediately and reports the result in the log - tag MMEnroll.
set "MMDONE="
for /L %%i in (1,1,25) do (
  if not defined MMDONE (
    "%ADB%" logcat -d -s MMEnroll:I MMEnroll:W > "%TEMP%\mm_enroll.txt" 2>nul
    findstr /C:"RESULT=OK" "%TEMP%\mm_enroll.txt" >nul && set "MMDONE=OK"
    if not defined MMDONE findstr /C:"RESULT=FAIL" "%TEMP%\mm_enroll.txt" >nul && set "MMDONE=FAIL"
    if not defined MMDONE timeout /t 2 /nobreak >nul
  )
)

echo.
echo  =====================================================
if "!MMDONE!"=="OK" (
  echo    DONE - !BRAND! !MODEL! is registered to your shop.
  echo    Find it by its IMEI on the website and assign it to
  echo    a customer. You can unplug the phone now.
) else if "!MMDONE!"=="FAIL" (
  echo    [ERROR] The server refused the registration:
  findstr /C:"RESULT=FAIL" "%TEMP%\mm_enroll.txt"
  echo    Download a fresh config.txt from the website
  echo    - Settings ^> Device setup - and run this again.
) else (
  echo    ALMOST DONE - the app is installed and the shop ID is
  echo    SAVED on the phone, but it can't reach the server yet.
  findstr /C:"RESULT=NETWORK" "%TEMP%\mm_enroll.txt"
  echo    Connect the phone to the same Wi-Fi as the server and
  echo    make sure the server is running. The phone registers
  echo    by itself - no need to type anything or run this again.
)
echo  =====================================================
echo.
pause
