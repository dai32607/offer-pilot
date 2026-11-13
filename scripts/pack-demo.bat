@echo off
chcp 936 >nul
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0.."
set "PROJ=%CD%"
title OfferPilot 离线演示包 - 一键重新打包
set "PYTHONIOENCODING=gbk:replace"

rem 打包开始时间（Unix 秒，用于总耗时统计）
for /f %%i in ('powershell -NoProfile -Command "[int]((Get-Date) - (Get-Date '1970-01-01Z')).TotalSeconds"') do set "T0=%%i"

rem ==================== 可配置项（环境变量优先，无需改脚本） ====================
rem JDK：优先 OFFERPILOT_JDK，其次 JAVA_HOME
set "JDK=%JAVA_HOME%"
if defined OFFERPILOT_JDK set "JDK=%OFFERPILOT_JDK%"
rem Maven：优先 OFFERPILOT_MVN，其次 MAVEN_HOME，最后从 PATH 自动探测
set "MVN="
if defined OFFERPILOT_MVN set "MVN=%OFFERPILOT_MVN%"
if not defined MVN if defined MAVEN_HOME set "MVN=%MAVEN_HOME%\bin\mvn.cmd"
if not defined MVN for /f "delims=" %%i in ('where mvn 2^>nul') do if not defined MVN set "MVN=%%i"
rem Node：设置 NODE_HOME 时自动并入 PATH
if defined NODE_HOME set "PATH=%NODE_HOME%;%PATH%"
rem 精简 Java 运行时来源：优先 OFFERPILOT_RUNTIME，其次复用桌面演示包内已有 runtime
set "RUNTIME_SRC=%OFFERPILOT_RUNTIME%"
if not defined RUNTIME_SRC if exist "%USERPROFILE%\Desktop\OfferPilot-Demo\runtime\bin\java.exe" set "RUNTIME_SRC=%USERPROFILE%\Desktop\OfferPilot-Demo\runtime"
set "DEMO_DIR=%USERPROFILE%\Desktop\OfferPilot-Demo"
set "DEMO_ZIP=%USERPROFILE%\Desktop\OfferPilot-Demo.zip"
rem ==========================================================================

set "TEMPLATE=%PROJ%\tools\demo-package"
set "DIST=%PROJ%\frontend\dist"
set "STATIC=%PROJ%\backend\src\main\resources\static"
set "JAR=%PROJ%\backend\target\offerpilot-backend.jar"

echo ============================================================
echo    OfferPilot 离线演示包 - 一键重新打包
echo ============================================================
echo    项目目录 : %PROJ%
echo    演示包   : %DEMO_DIR%
echo    压缩包   : %DEMO_ZIP%
echo    说明     : 重新构建前端与后端单 jar，并同步到演示包与 zip
echo ------------------------------------------------------------
echo.

rem ---------- [1/7] 环境检查 ----------
echo [1/7] 检查构建环境...
if not exist "%JDK%\bin\java.exe" (
    echo   [错误] 未找到 JDK21：%JDK%
    echo          请确认路径，或修改本脚本顶部的 JDK 配置项。
    goto :FAIL
)
if not exist "%MVN%" (
    echo   [错误] 未找到 Maven：%MVN%
    goto :FAIL
)
if exist "%NODE_DIR%\npm.cmd" set "PATH=%NODE_DIR%;%PATH%"
where npm >nul 2>&1
if errorlevel 1 (
    echo   [错误] 未找到 npm，请确认已安装 Node.js。
    goto :FAIL
)
if not exist "%RUNTIME_SRC%\bin\java.exe" (
    echo   [错误] 未找到精简运行时源：%RUNTIME_SRC%
    echo          请设置环境变量 OFFERPILOT_RUNTIME 指向含 bin\java.exe 的运行时目录。
    goto :FAIL
)
if not exist "%TEMPLATE%\启动演示.bat" (
    echo   [错误] 未找到演示模板：%TEMPLATE%\启动演示.bat
    goto :FAIL
)
echo   环境检查通过。
echo.

rem ---------- [2/7] 停止正在运行的演示服务 ----------
echo [2/7] 停止可能正在运行的 OfferPilot 演示服务...
powershell -NoProfile -Command "$ps = Get-NetTCPConnection -LocalPort 8083,8085,8087 -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique; if($ps){ foreach($procId in $ps){ try{ Stop-Process -Id $procId -Force -ErrorAction Stop; Write-Host ('  [已停止] 演示服务进程 PID=' + $procId) } catch { Write-Host ('  [警告] 无法停止进程 PID=' + $procId + '，请手动结束') } } } else { Write-Host '  未发现运行中的演示服务。' }"
ping -n 3 127.0.0.1 >nul 2>&1
echo.

rem ---------- [3/7] 前端构建 ----------
echo [3/7] 构建前端（npm run build）...
pushd "%PROJ%\frontend"
if not exist "node_modules" (
    echo   首次打包需要安装前端依赖，请稍候...
    call npm install
    if errorlevel 1 (
        popd
        echo   [错误] npm install 失败，请检查 Node.js 环境。
        goto :FAIL
    )
)
call npm run build
if errorlevel 1 (
    popd
    echo   [错误] 前端构建失败（npm run build）。
    goto :FAIL
)
popd
if not exist "%DIST%\index.html" (
    echo   [错误] 未找到前端构建产物 frontend\dist\index.html 。
    goto :FAIL
)
echo   前端构建完成。
echo.

rem ---------- [4/7] 合并前端到后端静态资源 ----------
echo [4/7] 将前端 dist 复制到后端静态资源目录...
if exist "%STATIC%" rmdir /s /q "%STATIC%"
xcopy "%DIST%\*" "%STATIC%\" /E /I /Q /Y >nul
if errorlevel 1 (
    echo   [错误] 复制前端产物到静态资源失败。
    goto :FAIL
)
echo   静态资源准备完成。
echo.

rem ---------- [5/7] 后端构建 ----------
echo [5/7] 构建后端单 jar（JDK21 + Maven，跳过测试）...
set "JAVA_HOME=%JDK%"
pushd "%PROJ%\backend"
set "MAVEN_OPTS=-Dfile.encoding=GBK"
call "%MVN%" clean package -DskipTests
if errorlevel 1 (
    popd
    echo   [错误] 后端构建失败，请查看上方 Maven 输出。
    goto :FAIL
)
popd
if not exist "%JAR%" (
    echo   [错误] 未生成后端程序包 backend\target\offerpilot-backend.jar 。
    goto :FAIL
)
echo   后端构建完成，开始恢复源码（删除临时 static 目录）...
if exist "%STATIC%" rmdir /s /q "%STATIC%"
echo   源码已恢复原样。
echo.

rem ---------- [6/7] 组装演示包 ----------
echo [6/7] 组装 OfferPilot-Demo 文件夹...
if not exist "%DEMO_DIR%\app" mkdir "%DEMO_DIR%\app"
if not exist "%DEMO_DIR%\runtime" mkdir "%DEMO_DIR%\runtime"
if not exist "%DEMO_DIR%\demo-cache" mkdir "%DEMO_DIR%\demo-cache"
if not exist "%DEMO_DIR%\demo-snapshots" mkdir "%DEMO_DIR%\demo-snapshots"
if not exist "%DEMO_DIR%\data\session" mkdir "%DEMO_DIR%\data\session"
if not exist "%DEMO_DIR%\samples" mkdir "%DEMO_DIR%\samples"

copy /y "%JAR%" "%DEMO_DIR%\app\offerpilot-backend.jar" >nul
if errorlevel 1 (
    echo   [错误] 复制 jar 失败。
    goto :FAIL
)

xcopy "%RUNTIME_SRC%\*" "%DEMO_DIR%\runtime\" /E /I /Q /Y >nul
if not exist "%DEMO_DIR%\runtime\bin\java.exe" (
    echo   [错误] 精简运行时复制失败。
    goto :FAIL
)

if exist "%PROJ%\backend\demo-cache" (
    xcopy "%PROJ%\backend\demo-cache\*" "%DEMO_DIR%\demo-cache\" /E /I /Q /Y >nul
)
if exist "%PROJ%\backend\demo-snapshots" (
    xcopy "%PROJ%\backend\demo-snapshots\*" "%DEMO_DIR%\demo-snapshots\" /E /I /Q /Y >nul
)
if exist "%PROJ%\samples" (
    xcopy "%PROJ%\samples\*" "%DEMO_DIR%\samples\" /E /I /Q /Y >nul
)

copy /y "%TEMPLATE%\启动演示.bat" "%DEMO_DIR%\启动演示.bat" >nul
copy /y "%TEMPLATE%\停止演示.bat" "%DEMO_DIR%\停止演示.bat" >nul
copy /y "%TEMPLATE%\README.txt" "%DEMO_DIR%\README.txt" >nul

echo   演示包组装完成。
echo.

rem ---------- [7/7] 打包 zip ----------
echo [7/7] 打包 OfferPilot-Demo.zip...
if exist "%DEMO_ZIP%" del /q "%DEMO_ZIP%"
powershell -NoProfile -Command "Compress-Archive -Path '%DEMO_DIR%\*' -DestinationPath '%DEMO_ZIP%' -Force"
if errorlevel 1 (
    echo   [错误] zip 打包失败。
    goto :FAIL
)
if not exist "%DEMO_ZIP%" (
    echo   [错误] 未生成压缩包：%DEMO_ZIP%
    goto :FAIL
)
echo   压缩包打包完成。
echo.

echo.
echo ============================================================
echo    [完成] OfferPilot 演示包已更新到最新构建
echo ============================================================
echo    文件夹 : %DEMO_DIR%
echo    压缩包 : %DEMO_ZIP%
for /f %%i in ('powershell -NoProfile -Command "[int]((Get-Date) - (Get-Date '1970-01-01Z')).TotalSeconds"') do set "T1=%%i"
set /a ELAPSED=T1-T0
echo    本次打包总耗时 : %ELAPSED% 秒
echo.
echo    验证方式：双击 %DEMO_DIR%\启动演示.bat 即可演示；
echo              也可把压缩包拷给别人，解压后双击启动演示.bat 使用。
echo              若需复验冒烟：启动后访问 http://localhost:8083，
echo              用 samples 示例输入发起分析（命中 SAMPLE 离线快照）。
echo.
pause
exit /b 0

:FAIL
echo.
echo ============================================================
echo    [失败] 本次打包已中止
echo ============================================================
echo    桌面上的旧演示包在此步骤之前保持原样，未被破坏；
echo    请根据上方 [错误] 提示排查后重新双击本脚本。
echo.
pause
exit /b 1
