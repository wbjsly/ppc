package com.erp.service.impl;

import com.erp.service.MaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * 脱敏实现（D6）：规则编译缓存（volatile 一次性加载），applyAll 按 CODE 字典序应用。
 * 非法正则规则跳过并告警日志（不让一条坏规则炸掉整个检索）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MaskServiceImpl implements MaskService {

    private final JdbcTemplate jdbc;

    private volatile List<CompiledRule> cache;

    @Override
    public String applyAll(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        String out = text;
        for (CompiledRule r : rules()) {
            out = r.pattern.matcher(out).replaceAll(r.template);
        }
        return out;
    }

    @Override
    public void evictCache() {
        cache = null;
    }

    private List<CompiledRule> rules() {
        List<CompiledRule> c = cache;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (cache == null) {
                List<CompiledRule> loaded = new CopyOnWriteArrayList<>();
                try {
                    List<String[]> rows = jdbc.queryForList(
                                    "SELECT CONCAT(PATTERN, '|||', MASK_TEMPLATE) FROM erp_ops_mask_rule "
                                            + "WHERE STATUS='1' AND DEL_FLAG='0' ORDER BY SORT_ORDER, CODE",
                                    String.class)
                            .stream()
                            .map(s -> s.split("\\|\\|\\|", -1))
                            .toList();
                    for (String[] row : rows) {
                        try {
                            loaded.add(new CompiledRule(Pattern.compile(row[0]), row[1]));
                        } catch (Exception e) {
                            log.warn("mask rule invalid, skipped: {} -> {}", row[0], e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("mask rule load failed: {}", e.getMessage());
                }
                cache = loaded;
            }
            return cache;
        }
    }

    private record CompiledRule(Pattern pattern, String template) {
    }
}
