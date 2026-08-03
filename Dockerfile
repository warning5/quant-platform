# ==================== Stage 1: Build ====================
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 先复制 pom 文件利用 Docker 层缓存加速依赖下载
COPY pom.xml ./
COPY common/pom.xml common/pom.xml
COPY platform-core/pom.xml platform-core/pom.xml
COPY stock-service/pom.xml stock-service/pom.xml
COPY backend-mp/pom.xml backend-mp/pom.xml

# 下载依赖（仅当 pom 变化时重新执行）
RUN mvn dependency:go-offline -B

# 复制源码
COPY common/src common/src
COPY platform-core/src platform-core/src
COPY stock-service/src stock-service/src
COPY backend-mp/src backend-mp/src

# 构建（跳过测试，测试在 CI 流水线中执行）
RUN mvn clean package -DskipTests -B

# ==================== Stage 2: Runtime ====================
FROM eclipse-temurin:21-jre

LABEL maintainer="quant-platform"
LABEL description="Quantitative Factor and Strategy Backtesting Platform"

WORKDIR /app

# 从 builder 阶段复制构建产物
COPY --from=builder /build/stock-service/target/*.jar app.jar

# 时区设置
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# JVM 参数（可通过环境变量覆盖）
ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Dfile.encoding=UTF-8"

# Spring Profile（默认 prod，通过环境变量覆盖）
ENV SPRING_PROFILES_ACTIVE=prod

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# 启动
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
