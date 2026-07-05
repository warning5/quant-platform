#!/bin/bash
# ========== 生产环境启动脚本 ==========
# 用法: ./start-prod.sh
# 需要先设置环境变量或修改下方配置

# ---- 数据库配置（按实际情况修改）----
export MYSQL_HOST=localhost
export MYSQL_PORT=3306
export MYSQL_DATABASE=stock
export MYSQL_USER=admin
export MYSQL_PASSWORD=Hanlu@123

# ---- 小程序Token ----
export MP_TOKEN=quant-mp-2026-secret

# ---- 启动 ----
java -jar backend-mp-1.0.0.jar --spring.profiles.active=prod
