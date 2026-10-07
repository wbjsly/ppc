package com.erp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.system.SysParamDao;
import com.erp.entity.system.SysParam;
import com.erp.service.SysParamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用业务参数：全表一次性载入进程内缓存，写入后整体失效（表小、阈值读取高频）。
 * design D13：本轮 SO 审批档、毛利阈值、安全库存、折扣叠加模式等统一从这里取。
 */
@Slf4j
@Service
public class SysParamServiceImpl implements SysParamService {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SysParamDao paramDao;

    /** 键 → 值缓存；loaded=false 时下次读取重新全量加载 */
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    public SysParamServiceImpl(SysParamDao paramDao) {
        this.paramDao = paramDao;
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            cache.clear();
            for (SysParam p : paramDao.selectAllActive()) {
                cache.put(p.getParamKey(), p.getParamValue());
            }
            loaded = true;
        }
    }

    private void invalidate() {
        loaded = false;
    }

    @Override
    public String getValue(String key) {
        if (key == null) {
            return null;
        }
        ensureLoaded();
        return cache.get(key);
    }

    @Override
    public String getValue(String key, String defaultValue) {
        String v = getValue(key);
        return v == null || v.isEmpty() ? defaultValue : v;
    }

    @Override
    public BigDecimal getRate(String key, BigDecimal defaultValue) {
        return parseDecimal(getValue(key), defaultValue);
    }

    @Override
    public int getInt(String key, int defaultValue) {
        String v = getValue(key);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        try {
            return new BigDecimal(v.trim()).intValue();
        } catch (NumberFormatException e) {
            log.warn("参数 {} 值 {} 非整数，回退默认值 {}", key, v, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public BigDecimal getAmount(String key, BigDecimal defaultValue) {
        return parseDecimal(getValue(key), defaultValue);
    }

    private BigDecimal parseDecimal(String raw, BigDecimal defaultValue) {
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("参数值 {} 非数字，回退默认值 {}", raw, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public Map<String, String> toMap() {
        ensureLoaded();
        return Collections.unmodifiableMap(new LinkedHashMap<>(cache));
    }

    @Override
    public List<SysParam> listAll() {
        return paramDao.selectAllActive();
    }

    @Override
    @Transactional
    public void setValue(String key, String value, String valueType, String paramGroup, String remark, String operator) {
        SysParam existing = paramDao.selectByKey(key);
        String at = LocalDateTime.now().format(AT);
        String by = operator == null ? "system" : operator;
        if (existing == null) {
            SysParam p = new SysParam();
            p.setParamKey(key);
            p.setParamValue(value);
            p.setValueType(valueType == null ? "STRING" : valueType);
            p.setParamGroup(paramGroup == null ? "COMMON" : paramGroup);
            p.setRemark(remark);
            p.setHistoryJson("[{\"old\":null,\"new\":\"" + escape(value) + "\",\"by\":\"" + escape(by)
                    + "\",\"at\":\"" + at + "\"}]");
            paramDao.insert(p);
        } else {
            String old = existing.getParamValue();
            existing.setParamValue(value);
            if (valueType != null) {
                existing.setValueType(valueType);
            }
            if (paramGroup != null) {
                existing.setParamGroup(paramGroup);
            }
            if (remark != null) {
                existing.setRemark(remark);
            }
            existing.setHistoryJson(appendHistory(existing.getHistoryJson(), old, value, by, at));
            paramDao.updateById(existing);
        }
        invalidate();
    }

    private String appendHistory(String history, String old, String newVal, String by, String at) {
        String entry = "{\"old\":\"" + escape(old) + "\",\"new\":\"" + escape(newVal)
                + "\",\"by\":\"" + escape(by) + "\",\"at\":\"" + at + "\"}";
        if (history == null || history.trim().isEmpty()) {
            return "[" + entry + "]";
        }
        String h = history.trim();
        if (!h.startsWith("[")) {
            return "[" + entry + "]";
        }
        String body = h.substring(1, h.length() - 1).trim();
        return body.isEmpty() ? "[" + entry + "]" : "[" + body + "," + entry + "]";
    }

    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
