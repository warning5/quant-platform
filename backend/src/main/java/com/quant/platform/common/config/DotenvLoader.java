package com.quant.platform.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot EnvironmentPostProcessor：在属性占位符解析前加载 .env 文件。
 * 自动从项目父目录（quant-platform/）查找 .env，解析 key=value 并注入到 Environment。
 * 无需任何外部依赖，纯 Java 实现。
 *
 * 查找顺序：backend/.env → ../.env（项目根目录）→ ../../.env
 */
public class DotenvLoader implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DotenvLoader.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = findEnvFile();
        if (envFile == null) {
            log.debug(".env file not found, skipping dotenv loading");
            return;
        }

        try {
            Map<String, Object> envVars = parseEnvFile(envFile);
            if (envVars.isEmpty()) {
                return;
            }

            // 只设置 Spring Environment 中不存在的 key（避免覆盖已有环境变量/命令行参数）
            Map<String, Object> filtered = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : envVars.entrySet()) {
                if (!environment.containsProperty(entry.getKey())) {
                    filtered.put(entry.getKey(), entry.getValue());
                }
            }

            if (!filtered.isEmpty()) {
                environment.getPropertySources()
                        .addFirst(new MapPropertySource("dotenv", filtered));
                log.info("Loaded {} variables from {}", filtered.size(), envFile);
            }
        } catch (IOException e) {
            log.warn("Failed to read .env file: {}", envFile, e);
        }
    }

    private Path findEnvFile() {
        // 从当前工作目录开始向上查找
        Path workDir = Paths.get("").toAbsolutePath();
        List<String> candidates = List.of(".env", "../.env", "../../.env");
        for (String candidate : candidates) {
            Path resolved = workDir.resolve(candidate).normalize();
            if (Files.isRegularFile(resolved)) {
                return resolved;
            }
        }
        return null;
    }

    private Map<String, Object> parseEnvFile(Path file) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(file);
        for (int lineNum = 1; lineNum <= lines.size(); lineNum++) {
            String line = lines.get(lineNum - 1).strip();
            // 跳过空行和注释
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eqIdx = line.indexOf('=');
            if (eqIdx < 0) {
                continue;
            }
            String key = line.substring(0, eqIdx).strip();
            String value = line.substring(eqIdx + 1).strip();
            // 去除引号（支持 key="value" 和 key='value'）
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            } else if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            // 去除行内注释（# 后面有空格的情况）
            int commentIdx = value.indexOf(" #");
            if (commentIdx > 0) {
                value = value.substring(0, commentIdx);
            }
            result.put(key, value);
        }
        return result;
    }
}
