package com.erp.service.eam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.eam.EamReworkItem;
import com.erp.entity.eam.EamReworkOrder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 资产技改大修（add-asset-rework，4.13 流程七）。
 *
 * 状态机：DRAFT → PENDING_JUDGE → JUDGED（判定，BR-38/已提足复核走财务经理审批）
 *         → IN_REWORK（转在建+1:1 CIP 项目，停提/特批续提）→ COMPLETED（验收）
 *         → REBUILT（转资+原卡 REPLACED+新卡重建）→ CLOSED
 *         JUDGED → EXPENSED（费用化凭证）→ CLOSED；驳回/复核不过回 DRAFT。
 */
public interface EamReworkService {

    /** 创建技改单（一卡一单；D6 前置断言逐项列示，已提足置复核标记） */
    Map<String, Object> create(EamReworkOrder order, List<EamReworkItem> items);

    /** 编辑草稿（仅 DRAFT/驳回态） */
    void update(EamReworkOrder order, List<EamReworkItem> items);

    /** 提交判定：BR-38 防拆分标记计算（12 月同卡笔数/合计），推进 PENDING_JUDGE */
    Map<String, Object> submit(String id);

    /** 财务资产会计判定（BR-37 三条件量化+分类下限）；命中复核标记则转财务经理审批 */
    Map<String, Object> judge(String id, String capJudge, String judgeNote,
                              String judgeEvidence, List<EamReworkItem> items);

    /** 争议裁定留痕（财务经理） */
    void arbitrate(String id, String note);

    /** 审批通过回调（判定复核/防拆分） */
    Map<String, Object> onApprovalPassed(String id);

    /** 审批驳回回调（退回 DRAFT 重判） */
    void onApprovalRejected(String id, String reason);

    /** 特批续提请求（BR-39：技改期间继续运行须财务经理特批） */
    Map<String, Object> requestContinueDepr(String id, String reason);

    /** 特批续提审批通过回调（写 CONTINUE_DEPR_FLAG） */
    void onContinueApproved(String id);

    /** 特批续提驳回回调 */
    void onContinueRejected(String id, String reason);

    /** 转在建：REWORK_TRANSFER 凭证（借1604 NBV+借累计折旧+贷原值，失败回滚）+ 1:1 CIP 项目 + 卡片 IN_REWORK */
    Map<String, Object> toInRework(String id);

    /** 完工验收：CIP complete → COMPLETED（acceptDate 锚；C-4.13-06 超期监控起点） */
    Map<String, Object> accept(String id, LocalDate acceptDate);

    /** 转资重建：BR-40 新原值=转入时NBV+已结算；REWORK_REBUILD 凭证失败回滚；原卡 REPLACED+新卡重建 → REBUILT */
    Map<String, Object> rebuild(String id, Integer newLifeYears);

    /** 费用化：REWORK_EXPENSE 凭证（借660201/贷2202，失败回滚判定）→ EXPENSED */
    Map<String, Object> expense(String id);

    /** 结案归档（REBUILT/EXPENSED → CLOSED） */
    void close(String id);

    /** 整单冲回（仅 IN_REWORK 且归集为零；逆序冲凭证+卡片恢复+项目作废） */
    Map<String, Object> reverse(String id);

    /** C-4.13-06 超期扫描：验收后超 30 天未转资预警、超 60 天升级财务经理 */
    Map<String, Object> scanOverdue();

    /** 分页（状态筛选，含超期天数） */
    Page<EamReworkOrder> page(long current, long size, String keyword, String status);

    /** 详情（单据+判定依据+CIP 项目概要） */
    Map<String, Object> detail(String id);
}
