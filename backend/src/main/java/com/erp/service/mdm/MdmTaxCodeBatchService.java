package com.erp.service.mdm;

import java.util.List;
import java.util.Map;

/**
 * 税码批量导入能力契约（税码管理 1.6.3，FR-4.1-3-3 + BR-4.1-19，沿汇率批量模式）。
 */
public interface MdmTaxCodeBatchService {

    /** 行级预检（dry-run 不落库）：逐行校验 + 同税码批内衔接，返回 rowNo/valid/reason/normalized */
    Map<String, Object> preview(List<Map<String, Object>> rows, String defaultPolicyNo);

    /** 按行独立提交（部分失败不回滚成功行），返回 total/succeeded/failed + 逐行明细 */
    Map<String, Object> batch(List<Map<String, Object>> rows, String defaultPolicyNo);
}
