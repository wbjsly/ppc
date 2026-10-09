package com.erp.service.vmi;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Map;

/**
 * 寄售库存台账（spec vmi-consignment R4，design D4：台账加载时惰性扫描补货建议与处置建议）。
 */
public interface VmiStockService {

    /** 分页台账（供应商/物料筛选），行含库龄与累计领用；加载即触发 REPLENISH 与处置扫描 */
    Page<Map<String, Object>> page(long current, long size, String supplierId, String itemCode);
}
