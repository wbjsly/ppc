package com.erp.service;

import com.erp.entity.system.SysParam;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 通用业务参数读写（design D13：本轮阈值统一入 erp_sys_param）。
 * 读取走进程内缓存；写入后使缓存失效，保证同进程内立即生效。
 */
public interface SysParamService {

    /** 读原始字符串，未登记返回 null */
    String getValue(String key);

    /** 读原始字符串，未登记返回默认值 */
    String getValue(String key, String defaultValue);

    /** 读比率（如 0.05），解析失败或未登记返回默认值 */
    BigDecimal getRate(String key, BigDecimal defaultValue);

    /** 读整数（天数、小时数等），解析失败或未登记返回默认值 */
    int getInt(String key, int defaultValue);

    /** 读金额（元），解析失败或未登记返回默认值 */
    BigDecimal getAmount(String key, BigDecimal defaultValue);

    /** 全部生效参数（键 → 值） */
    Map<String, String> toMap();

    /** 全部参数实体（含说明与调整历史），供管理页展示 */
    List<SysParam> listAll();

    /** 写入参数（不存在则新建），追加调整历史并使缓存失效 */
    void setValue(String key, String value, String valueType, String paramGroup, String remark, String operator);
}
