package com.erp.service.proc;

import java.util.List;
import java.util.Map;

/**
 * 模拟 MRP 净算（2.1.1，FR-4.5-2-3 公式 + FR-4.2-1-1 生成 PR）。
 * 输入为模拟值（真实需求/供给源 4.5/WMS 未落地，接口留桩口径），preview 不落库。
 */
public interface ProcMrpService {

    /** 预检（dry-run）：净算 + 过量/逾期标记 + 物料/供应商校验，返回逐行建议 */
    Map<String, Object> preview(List<Map<String, Object>> rows);

    /** 生成：合法行合并为一张 PR（行级失败入报告不阻断整批；合法行 0 则整批失败） */
    Map<String, Object> generate(List<Map<String, Object>> rows);
}
