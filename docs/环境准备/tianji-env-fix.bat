@echo off
chcp 936 >nul
title 天机学堂开发环境 - 修复(需管理员)
net session >nul 2>&1
if errorlevel 1 (
  echo.
  echo   [!] 需要管理员权限：请右键此文件 -^> 以管理员身份运行
  echo.
  pause
  exit /b 1
)

echo ============================================================
echo   天机学堂开发环境 - 一键修复
echo ------------------------------------------------------------
echo   作用：把 Windows 动态端口范围恢复成出厂默认 49152-65535
echo   背景：WSL2 / Docker Desktop 的 NAT 组件会把范围改成
echo         1024-15000，导致 3306 / 8848 / 10010 等常用端口
echo         落进「系统可随机预留」区间，被吞后监听失败。
echo   改回出厂值后，所有 49152 以下的端口永久安全。
echo ============================================================
echo.

echo [修复前] 动态端口范围：
netsh int ipv4 show dynamicport tcp
echo.

echo ---- 恢复到出厂默认 49152-65535 ----
net stop winnat
netsh int ipv4 set dynamicport tcp start=49152 num=16384
net start winnat
echo.

echo [修复后] 动态端口范围：
netsh int ipv4 show dynamicport tcp
echo.

echo ============================================================
echo   完成。
echo.
echo   * 若 Docker / WSL 网络异常，重启 Docker Desktop 即可。
echo   * 回退到原样（如需）：
echo       netsh int ipv4 set dynamicport tcp start=1024 num=13977
echo ============================================================
pause
