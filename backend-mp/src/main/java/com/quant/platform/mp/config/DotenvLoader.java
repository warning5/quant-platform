package com.quant.platform.mp.config;

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
 *
 * 查找顺序：
 * 1. classpath:.env（jar 内置）
 * 2. 当前目录 .env → ../.env → ../../.env（外部覆盖）
 */
public class DotenvLoader implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DotenvLoader.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> envVars = new LinkedHashMap<>();

        // 1. classpath .env
        try {
            ClassPathResource cpr = new ClassPathResource(".env");
            if (cpr.exists()) {
                envVars.putAll(parseEnvStream(cpr.getInputStream()));
            }
        } catch (IOException e) {
            log.debug("No .env on classpath");
        }

        // 2. 文件系统 .env（覆盖 classpath）
        Path fsFile = findEnvFileOnDisk();
        if (fsFile != null) {
            try {
                envVars.putAll(parseEnvStream(Files.newInputStream(fsFile)));
            } catch (IOException e) {
                log.warn("Failed to read .env: {}", fsFile, e);
            }
        }

        if (envVars.isEmpty()) return;

        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : envVars.entrySet()) {
            if (!environment.containsProperty(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        if (!filtered.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("dotenv", filtered));
            log.info("Loaded {} variables from .env", filtered.size());
        }
    }

    private Path findEnvFileOnDisk() {
        Path workDir = Paths.get("").toAbsolutePath();
        for (String candidate : List.of(".env", "../.env", "../../.env")) {
            Path resolved = workDir.resolve(candidate).normalize();
            if (Files.isRegularFile(resolved)) return resolved;
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
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2)
                    value = value.substring(1, value.length() - 1);
                else if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2)
                    value = value.substring(1, value.length() - 1);
                int commentIdx = value.indexOf(" #");
                if (commentIdx > 0) value = value.substring(0, commentIdx);
                result.put(key, value);
            }
        }
        return result;
    }
}
