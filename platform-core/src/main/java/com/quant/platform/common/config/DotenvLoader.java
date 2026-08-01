package com.quant.platform.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot EnvironmentPostProcessor：在属性占位符解析前加载 .env 文件。
 * 解析 key=value 并注入到 Environment，无需任何外部依赖。
 *
 * 查找顺序：
 * 1. classpath:.env（jar 内置，打包时从 src/main/resources/.env 加载）
 * 2. 当前目录 .env → ../.env → ../../.env（外部覆盖，如部署时放在 jar 同级目录）
 */
public class DotenvLoader implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DotenvLoader.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> envVars = new LinkedHashMap<>();

        // 1. 从 classpath 加载（jar 内置 .env）
        try {
            ClassPathResource cpr = new ClassPathResource(".env");
            if (cpr.exists()) {
                Map<String, Object> classpathVars = parseEnvStream(cpr.getInputStream());
                envVars.putAll(classpathVars);
                log.debug("Found .env on classpath with {} entries", classpathVars.size());
            }
        } catch (IOException e) {
            log.debug("No .env on classpath: {}", e.getMessage());
        }

        // 2. 从文件系统加载（外部 .env，优先级高于 classpath）
        Path fsFile = findEnvFileOnDisk();
        if (fsFile != null) {
            try {
                Map<String, Object> fsVars = parseEnvStream(Files.newInputStream(fsFile));
                envVars.putAll(fsVars); // 覆盖 classpath 的同名 key
                log.debug("Found external .env at {} with {} entries", fsFile, fsVars.size());
            } catch (IOException e) {
                log.warn("Failed to read .env file: {}", fsFile, e);
            }
        }

        if (envVars.isEmpty()) {
            log.debug(".env not found (classpath or filesystem), skipping dotenv loading");
            return;
        }

        // 注入 .env 变量，优先级低于命令行参数(-D)和系统环境变量，但高于 yml 占位符默认值。
        // 判断是否需要注入：
        //  - Environment 中不存在该 key，或
        //  - 已存在但值为空，或
        //  - 已存在但是未解析的占位符字面量（如 yml 里的 ${CREDENTIAL_AES_KEY:}）
        // 这样 yml 中以 `${KEY:}` 形式声明的占位符（默认空值）可被 .env 的真实值覆盖，
        // 而命令行/系统环境显式设置的非空值仍保持最高优先级（不被覆盖）。
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : envVars.entrySet()) {
            String existing = environment.getProperty(entry.getKey());
            boolean unresolved = existing != null && existing.contains("${");
            if (existing == null || existing.isEmpty() || unresolved) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }

        if (!filtered.isEmpty()) {
            environment.getPropertySources()
                    .addFirst(new MapPropertySource("dotenv", filtered));
            log.info("Loaded {} variables from .env", filtered.size());
        }
    }

    private Path findEnvFileOnDisk() {
        Path workDir = Paths.get("").toAbsolutePath();
        for (String candidate : List.of(".env", "../.env", "../../.env")) {
            Path resolved = workDir.resolve(candidate).normalize();
            if (Files.isRegularFile(resolved)) {
                return resolved;
            }
        }
        return null;
    }

    private Map<String, Object> parseEnvStream(InputStream is) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eqIdx = line.indexOf('=');
                if (eqIdx < 0) continue;
                String key = line.substring(0, eqIdx).strip();
                String value = line.substring(eqIdx + 1).strip();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                } else if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                int commentIdx = value.indexOf(" #");
                if (commentIdx > 0) {
                    value = value.substring(0, commentIdx);
                }
                result.put(key, value);
            }
        }
        return result;
    }
}
