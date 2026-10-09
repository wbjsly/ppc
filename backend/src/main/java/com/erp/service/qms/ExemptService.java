package com.erp.service.qms;

import com.erp.entity.qms.Exempt;

import java.util.List;
import java.util.Map;

/**
 * 免检管理（BR-4.12-12，spec inspection-lot）：
 * 物料 + 供应商维度申请 → 质量经理审批（ApprovalEngine BIZ_TYPE=Exempt）→ ACTIVE；
 * 任一批次不合格由检验批侧自动停用并恢复按风险等级抽样。
 */
public interface ExemptService {

    List<Exempt> list(String status, String materialCode);

    /** 提交免检申请（PENDING + 审批实例），重复在途申请 422 */
    Map<String, Object> apply(Exempt body);
}
