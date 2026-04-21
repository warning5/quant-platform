@echo off
chcp 65001 > nul
echo ========================================
echo  量化因子策略平台 - 启动脚本
echo ========================================
echo.

echo [1/3] 检查 Java 版本...
java -version 2>&1 | findstr "21\|22\|23\|24"
if errorlevel 1 (
    echo [警告] 未检测到 Java 21+，请确认已安装 Java 21
    echo 下载地址：https://adoptium.net/
)

echo.
echo [2/3] 启动后端服务 (Spring Boot 3 + Java 21)...
echo 后端地址：http://localhost:8080/api
echo Swagger UI：http://localhost:8080/api/swagger-ui.html
echo.

cd /d "%~dp0backend"
call mvn spring-boot:run -Dspring-boot.run.jvmArguments="--enable-preview"

pause
