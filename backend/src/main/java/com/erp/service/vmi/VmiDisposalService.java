package com.erp.service.vmi;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Map;

/**
 * 寄售库龄处置建议单（BR-4.2-39，L4 不阻断结算，spec vmi-consignment，design D4）。
 * 惰性扫描：库龄 > VMI_AGING_LIMIT_DAYS 且累计领用 = 0 → OPEN 建议单（同批次去重）。
 */
public interface VmiDisposalService {

    /** 扫描生成（台账加载/结算生成时调用），返回新建建议单数 */
    int scan();

    /** 分页（状态/建议类型筛选） */
    Page<Map<String, Object>> page(long current, long size, String status, String suggestType);

    /** 处理完成（ADMIN/PM）：OPEN → DONE，实际处置方式/处理人/时间/备注留痕 */
    void resolve(String id, Map<String, Object> body);

    /** 作废建议单（ADMIN/PM，原因必填） */
    void cancel(String id, String reason);
}
