@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo ============================================================
echo    停止 OfferPilot 离线演示服务
echo ============================================================
echo.

rem ---------- [1/2] 停止演示服务进程 ----------
echo [1/2] 停止正在运行的演示服务...
powershell -NoProfile -Command "$demo = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*offerpilot-backend.jar*' }; $found = $false; foreach($p in $demo){ $found = $true; try{ Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop; Write-Host ('  已停止演示服务进程 PID=' + $p.ProcessId) } catch { Write-Host ('  无法停止进程 PID=' + $p.ProcessId + '，请手动结束') } }; if(-not $found){ $ls = Get-NetTCPConnection -LocalPort 8083,8085,8087 -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique; if($ls){ foreach($procId in $ls){ try{ Stop-Process -Id $procId -Force -ErrorAction Stop; Write-Host ('  已停止占用 8083/8085/8087 端口的进程 PID=' + $procId + '（未识别为演示程序，可能为其它软件）') } catch { Write-Host ('  无法停止进程 PID=' + $procId + '，请手动结束') } } } else { Write-Host '  未发现正在运行的演示服务。' } }"

rem ---- 等待端口释放（最多约 30 秒）----
set /a W=0
:WAITCHECK
set /a W+=1
if %W% GTR 15 goto WAITTIMEOUT
ping -n 2 127.0.0.1 >nul
powershell -NoProfile -Command "$ls = Get-NetTCPConnection -LocalPort 8083,8085,8087 -State Listen -ErrorAction SilentlyContinue; if($ls){exit 1}else{exit 0}"
if errorlevel 1 goto WAITCHECK
goto WAITDONE

:WAITTIMEOUT
echo   [警告] 服务进程未在预期时间内释放端口。
echo          请关闭演示服务窗口后重新双击本脚本。
goto ENDPAUSE

:WAITDONE
echo   演示服务已停止。
echo   提示：直接关闭标题为「OfferPilot 演示服务」的窗口也能停止服务。
echo.

rem ---------- [2/2] 清理运行期残留（可选） ----------
echo [2/2] 清理 data\session 目录运行期残留（锁/临时文件）...
del /q "%~dp0data\session\*.tmp" >nul 2>&1
del /q "%~dp0data\session\*.lock" >nul 2>&1
echo   完成。
echo.
echo ============================================================
echo    停止完成：OfferPilot 演示服务已停止
echo ============================================================
echo    - 演示服务已停止，8083/8085/8087 端口已释放
echo    - data\session 中已完成的面试会话将保留，便于下次演示回看
echo    下次演示直接双击「启动演示.bat」即可。
echo.
goto ENDPAUSE

:ENDPAUSE
pause
exit /b 0
