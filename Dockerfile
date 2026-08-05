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
# 使用 Alpine 基础镜像，显著减小镜像体积（Ubuntu 版 ~300MB+ → Alpine ~120MB 左右）
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="quant-platform"
LABEL description="Quantitative Factor and Strategy Backtesting Platform"

WORKDIR /app

# 从 builder 阶段复制构建产物（已启用 Spring Boot layered jar）
COPY --from=builder /build/stock-service/target/*.jar app.jar

# 以非 root 用户运行，降低容器逃逸影响面（Alpine 使用 addgroup/adduser）
RUN addgroup -S appuser && adduser -S -G appuser appuser \
    # 解压分层 jar，利于 Docker 层缓存与增量更新
    && java -Djarmode=layertools -jar app.jar extract \
    && chown -R appuser:appuser /app
USER appuser

# 时区设置
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# JVM 参数（可通过环境变量覆盖）
ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Dfile.encoding=UTF-8"

# Spring Profile（默认 prod，通过环境变量覆盖）
ENV SPRING_PROFILES_ACTIVE=prod

# 暴露端口
EXPOSE 8080

# 健康检查（stock-service 已引入 spring-boot-starter-actuator，且 context-path=/api，故路径为 /api/actuator/health）
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/actuator/health || exit 1

# 启动（使用分层 jar 的 Launcher，Spring Boot 3.2+）
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
