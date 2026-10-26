package com.erp.service.fin;

import java.util.List;
import java.util.Map;

/**
 * 会计期间（add-gl-management，8.1.5：全局单轨 + 结账检查清单，design D4）。
 */
public interface FinPeriodService {

    /** 当前期间（无记录则惰性建档 OPEN） */
    Map<String, Object> current();

    /** 期间列表（倒序） */
    List<Map<String, Object>> list();

    /** 期间存在性断言：非 OPEN 抛 422（过账/手工保存前置） */
    void assertOpen(String period);

    /** 结账检查清单四项（未过账手工凭证/试算平衡/资产勾稽 C-4.13-01/汇率覆盖 C-4.1-15） */
    Map<String, Object> checklist(String period);

    /** 结账：清单全绿 → CLOSED + CLOSE_BY/AT；任一失败 422 逐项列示 */
    Map<String, Object> close(String period);
}
