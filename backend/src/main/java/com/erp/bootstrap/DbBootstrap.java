package com.erp.bootstrap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 数据库迁移引导（01 文档 1.3）：启动时扫描 classpath:db/mysql/*.sql 按文件名排序执行，
 * erp_ops_bootstrap_marker 表记录已执行脚本。
 */
@Slf4j
@Component
public class DbBootstrap implements InitializingBean, ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    @Value("${app.db.bootstrap.enabled:true}")
    private boolean bootstrapEnabled;

    @Value("${app.db.bootstrap.location:classpath:db/mysql/}")
    private String bootstrapLocation;

    @Value("${app.db.bootstrap.skip-scripts:}")
    private String skipScripts;

    public DbBootstrap(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        runMigrations();
    }

    @Override
    public void run(ApplicationArguments args) {
        runMigrations();
    }

    private void runMigrations() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        if (!bootstrapEnabled) {
            log.info("DB Bootstrap is disabled.");
            return;
        }

        log.info("=== Starting DB Bootstrap ===");

        createMarkerTable();

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(bootstrapLocation + "*.sql");

            List<String> skipList = Arrays.stream(skipScripts.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            List<Resource> sortedResources = Arrays.stream(resources)
                    .sorted((a, b) -> {
                        try {
                            return a.getFilename().compareTo(b.getFilename());
                        } catch (Exception e) {
                            return 0;
                        }
                    })
                    .collect(Collectors.toList());

            for (Resource resource : sortedResources) {
                String filename = resource.getFilename();
                if (skipList.contains(filename)) {
                    log.info("Skipping script (configured): {}", filename);
                    continue;
                }

                if (isExecuted(filename)) {
                    log.info("Skipping script (already executed): {}", filename);
                    continue;
                }

                log.info("Executing SQL script: {}", filename);
                String sql = readResource(resource);
                executeSqlScript(sql);
                markExecuted(filename);
                log.info("SQL script executed successfully: {}", filename);
            }

        } catch (Exception e) {
            log.error("Failed to execute bootstrap scripts", e);
            throw new RuntimeException("DB Bootstrap failed", e);
        }

        log.info("=== DB Bootstrap Complete ===");
    }

    private void createMarkerTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS erp_ops_bootstrap_marker (" +
                        "  SCRIPT_NAME VARCHAR(128) NOT NULL PRIMARY KEY, " +
                        "  EXECUTED_AT DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        );
    }

    private boolean isExecuted(String scriptName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM erp_ops_bootstrap_marker WHERE SCRIPT_NAME = ?",
                Integer.class, scriptName
        );
        return count != null && count > 0;
    }

    private void markExecuted(String scriptName) {
        jdbcTemplate.update(
                "INSERT INTO erp_ops_bootstrap_marker (SCRIPT_NAME, EXECUTED_AT) VALUES (?, NOW())",
                scriptName
        );
    }

    private String readResource(Resource resource) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private void executeSqlScript(String sql) {
        // 支持 DELIMITER 指令（191 触发器体含分号，朴素 split(';') 会切碎）：
        // 行首 DELIMITER <token> 切换语句结束符；缓冲累积至以当前结束符结尾才作为一条语句执行
        String delimiter = ";";
        StringBuilder buf = new StringBuilder();
        for (String line : sql.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase().startsWith("DELIMITER ")) {
                delimiter = trimmed.substring("DELIMITER ".length()).trim();
                continue;
            }
            buf.append(line).append('\n');
            String content = buf.toString().trim();
            if (content.endsWith(delimiter)) {
                executeOneStatement(content.substring(0, content.length() - delimiter.length()));
                buf.setLength(0);
            }
        }
        String rest = buf.toString().trim();
        if (!rest.isEmpty()) {
            executeOneStatement(rest);
        }
    }

    private void executeOneStatement(String stmt) {
        String cleaned = stmt.replaceAll("(?m)^--.*$", "").trim();
        if (!cleaned.isEmpty()) {
            try {
                jdbcTemplate.execute(cleaned);
            } catch (Exception e) {
                log.warn("SQL execution warning: {}", e.getMessage());
            }
        }
    }
}
