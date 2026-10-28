package com.erp.service.scm;

import java.util.List;
import java.util.Map;

/**
 * 伙伴引入申请单业务能力契约（add-partner-onboarding，菜单 12.1.1/12.1.2，FR-4.9-1-1）。
 * 状态机 DRAFT → APPROVING → APPROVED / REJECTED / CLOSED；驳回≥3 次自动关闭。
 */
public interface PartnerApplyService {

    /** 保存草稿：信用代码 18 位+重复 L1（BR-4.9-08，无 force 绕过）；证照行附件必传+有效期卡点（BR-4.9-09）；风险判定落值 */
    Map<String, Object> save(Map<String, Object> payload);

    /** 提交审批：DRAFT → APPROVING，按 RISK_LEVEL 动态组链（HIGH 三级/NORMAL 两级）挂审批底座 */
    Map<String, Object> submit(String id);

    /** 撤回：APPROVING → DRAFT（审批实例取消） */
    Map<String, Object> withdraw(String id);

    /** 分页（status/keyword 筛选） */
    Map<String, Object> page(long current, long size, String status, String keyword);

    /** 详情：申请单头 + 证照行 + 激活链接状态 + 审批日志摘要 */
    Map<String, Object> detail(String id);

    /** 申请单列表（供通道开通页聚合入驻状态用） */
    List<Map<String, Object>> listByStatus(String status);

    /** 保存证照行（applyId + 类型/编号/日期；附件经 common/attachments 上传后回填 ATTACH_COUNT） */
    Map<String, Object> saveCert(Map<String, Object> payload);

    /** 删除证照行（仅 DRAFT 态） */
    void deleteCert(String certId);

    /** 证照行回填附件计数（附件上传/删除回调） */
    void syncCertAttachCount(String certId);
}
