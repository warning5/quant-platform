@echo off
chcp 65001 > nul
echo ========================================
echo  量化因子策略平台 - 前端启动脚本
echo ========================================
echo.

echo [1/2] 安装前端依赖 (首次运行需要几分钟)...
cd /d "%~dp0frontend"
call npm install

echo.
echo [2/2] 启动前端开发服务器...
echo 前端地址：http://localhost:3000
echo.

call npm start

pause
