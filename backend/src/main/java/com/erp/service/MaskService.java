package com.erp.service;

/**
 * 审计内容脱敏（add-audit-log-console design D6）：
 * 启用规则（erp_ops_mask_rule STATUS=1）按序对自由文本应用正则掩码。
 * 应用点：检索响应映射 + 取证 CSV 生成（同一实现，口径一致）。
 */
public interface MaskService {

    /** 对文本应用全部启用规则（无规则命中或文本为空则原样返回） */
    String applyAll(String text);

    /** 使规则缓存失效（规则 CRUD/启停后调用，新规则即刻生效） */
    void evictCache();
}
