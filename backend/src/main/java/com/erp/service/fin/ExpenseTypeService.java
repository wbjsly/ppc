package com.erp.service.fin;

import java.util.List;
import java.util.Map;

/**
 * 费用类型-科目映射（M8-8，spec expense-reimbursement，FR-4.6-7-5 映射缺失 422）。
 */
public interface ExpenseTypeService {

    /** 全部费用类型（含停用，历史报销回看需要） */
    List<Map<String, Object>> list();

    /** 新增/修改映射：类型编码唯一、科目必须存在且 DR 方向 */
    Map<String, Object> save(Map<String, Object> payload);

    /** 启用/停用（停用后新报销行不可引用，历史不受影响） */
    void updateStatus(String id, String status);

    /**
     * 取映射科目（报销提交/凭证生成用）：类型不存在、已停用或科目为空
     * → 422 阻断并提示补录映射（FR-4.6-7-5 字面）。
     */
    String resolveAccountCode(String typeCode);
}
