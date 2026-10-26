package com.erp.service.hr;

import com.erp.entity.hr.HrSecondment;

import java.util.List;
import java.util.Map;

/**
 * 外派/借调管理（secondment-management，FR-4.7-7）：
 * 发起 → 双方确认（比例锁定）→ HR 合规审核 → 双人复核（C-4.7-11）→ 审批 → 生效分叉
 * →（借调）返岗/延期/到期告警（BR-4.7-05 期限卡控）。
 */
public interface HrSecondmentService {

    /**
     * 发起（FR-4.7-7-1）：双类型采集，借调必填协议编号与分摊比例；
     * 在途防重（L1）；期限 >12 月 L4 警示（返回 warnings，不阻断）；
     * 补贴按标准表带出快照（差异标注）。
     */
    Map<String, Object> submit(Map<String, Object> payload);

    /** 调出主体确认（FR-4.7-7-2）：agree=false 置 REJECTED 终态 */
    HrSecondment confirmOut(String secId, String opinion, boolean agree);

    /** 调入主体确认；双方确认完成 → 锁定分摊比例（SHARE_LOCKED=1） */
    HrSecondment confirmIn(String secId, String opinion, boolean agree);

    /**
     * HR 合规审核（FR-4.7-7-3）：变更缴纳地须目标城市已维护属地政策（L1）；
     * 缴纳地与用工主体注册地不一致 → L4 警示（warnings）。通过 → REVIEWING。
     */
    Map<String, Object> hrReview(String secId, String opinion);

    /** 双人复核签署（C-4.7-11）：五要素独立确认，同人不可双签，不一致挂起 */
    HrSecondment signReview(String secId, Map<String, Object> payload);

    /** 复核差异裁决（HR 经理）：APPROVE → APPROVING，否则 REJECTED */
    HrSecondment adjudicateDiff(String secId, String decision, String note);

    /** 审批（APPROVING → APPROVED 待生效 / REJECTED） */
    HrSecondment approve(String secId, boolean approveFlag, String opinion);

    /**
     * 生效执行（FR-4.7-7-5，幂等）：外派 → LEGAL_ENTITY/合同/缴纳地写回 + 社保减员增员双单；
     * 借调 → 主档零改动；联动失败降级 LINK_PENDING。
     */
    HrSecondment effect(String secId);

    /** 返岗确认（FR-4.7-7-6）：RETURNED + 关闭在途标记 */
    HrSecondment returnPost(String secId, String note);

    /** 延期申请（BR-4.7-05：原期+历次延期累计 >24 月 L1 拒绝）→ EXTENDING */
    HrSecondment extend(String secId, int months, String reason);

    /** 延期审批（L2）：通过 → END_DATE 顺延 + 累计月数入账 → EFFECTED；驳回 → 回 EFFECTED */
    HrSecondment approveExtend(String secId, boolean approveFlag, String note);

    /**
     * 每日扫描：到期前 SECONDMENT_REMIND_DAYS（默认 15）天返岗提醒（防重）；
     * 过期未返岗未延期 → EXPIRED_ALERT + 告警。返回 {reminded, expiredAlerted}。
     */
    Map<String, Object> remindScan();

    List<HrSecondment> list(String chgType, String status);

    Map<String, Object> detail(String secId);

    /** 员工视图标记：在途单（EFFECTED/EXTENDING/RETURNING/EXPIRED_ALERT）→ 「借调中·XX公司」 */
    Map<String, String> inFlightFlags();

    /** 补贴标准查询（标准表维护/带出预览用） */
    List<Map<String, Object>> allowanceStdList(String place);
}
