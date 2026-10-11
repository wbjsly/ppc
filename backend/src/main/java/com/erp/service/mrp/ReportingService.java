package com.erp.service.mrp;

import com.erp.entity.mrp.MrpReporting;

import java.util.List;
import java.util.Map;

/**
 * 车间报工（change add-reporting-management，spec shop-floor-reporting：
 * 报工单生命周期 / 开工准入 / 投料即扣 / 双模式产出与超产分级 / 工时分项 /
 * 整单红字冲销 / 角色与菜单权限）。
 *
 * 状态机：OPEN（可增改投料/产出/工时）→ CONFIRMED（只读，BR-4.5-25 仅可冲销）
 *        → REVERSED（冲销终态，红字单 REVERSES_ID 指向原单）。
 * 权限：录入接口 = SHOP_FLOOR/ADMIN；冲销申请/超产审批 = PLANNER/PLAN_MGR/ADMIN。
 */
public interface ReportingService {

    /**
     * 开工并创建报工单（OPEN）：扫码/选择定位派工单（FR-4.5-5-1）。
     * 2.1 先落状态机主干；准入细则（终态阻断/超期黄提示/人单提示确认）由 2.2 补齐。
     */
    MrpReporting open(String dispatchId, String mode);

    /**
     * 提交确认（OPEN → CONFIRMED）：CAS 更新，重复确认 409；
     * 首件/IPQC/超产/工时异常等卡控在 2.4/2.5/3.6/3.7 接入提交链。
     */
    MrpReporting confirm(String id);

    /** 放弃草稿（仅 OPEN 可删）；CONFIRMED/REVERSED → 422「已确认，仅可通过冲销纠正」 */
    void discard(String id);

    MrpReporting get(String id);

    /** 记录查询（5.7.2 管理台）：工单/工序/状态筛选，创建时间倒序 */
    List<MrpReporting> list(String moId, Integer opSeq, String status);

    /** 写操作守卫：非 OPEN → 422（CONFIRMED 后只读的统一入口，投料/产出/工时共用） */
    void requireOpen(String id);

    /**
     * 同工序已确认累计（CONFIRMED 单口径，供良率统计 3.2 消费——design D2 唯一口径的
     * 数据侧）：{goodQty, ngQty, rptCount}；REVERSED 单自动剔除（冲销回拨）。
     */
    Map<String, Object> confirmedTotals(String moId, Integer opSeq);

    /**
     * 投料行保存并即扣（FR-4.5-5-2，design D3）：`AVAILABLE_QTY −= qty` 行锁 FIFO 扣减，
     * 任一行不足 422 余量护栏（扣成负数即拒绝）；FLOW_TYPE=HAND。
     * 仅 OPEN 可投料（requireOpen 统一守卫）。
     */
    com.erp.entity.mrp.MrpFeed addFeed(String rptId, String itemCode, String batchCode,
                                       java.math.BigDecimal qty);

    /** 报工单投料行列表（录入台回显） */
    List<com.erp.entity.mrp.MrpFeed> feedsOf(String rptId);

    /**
     * 投料回加（冲销/回退调用，design D3/D7）：该报工单未回加的 HAND/BFLUSH 行
     * 逐行 `AVAILABLE_QTY += qty`（恢复原账），RETURNED_FLAG 幂等锚点防双退。
     * 返回回加行数。
     */
    int reverseFeeds(String rptId, String reason);

    /**
     * 产出记录与超产分级（FR-4.5-5-4，spec「阈值内二次确认」「超阈值挂起审批」）：
     * 覆盖式保存该报工单产出行（逐件/批量同结构，前端累加）。超产基数 = 工单计划数量
     * （MO.QTY，「产出超过投入」的计划投入口径，design D2 补）：
     * ① 无超产 → 直接入账；② 超产率 ≤ `OVERPROD_TOLERANCE` → 需 overprodAck 二次确认
     * （否则 422 返回超产明细）；③ 超阈值 → 置 OVERPROD_FLAG 并挂 `OverProd` 审批
     * （PLAN_MGR 签），通过才置 PROD_CONFIRM_FLAG（confirm 前置校验）。
     */
    com.erp.entity.mrp.MrpOutput saveOutput(String rptId, java.math.BigDecimal goodQty,
                                            java.math.BigDecimal ngQty, String ngDispose,
                                            boolean overprodAck);

    /** 该报工单产出行（无则 null） */
    com.erp.entity.mrp.MrpOutput outputOf(String rptId);

    /** 超产审批回调落点：通过 → PROD_CONFIRM_FLAG=1；驳回 → 留痕（报工单保持挂起） */
    void approveOverProd(String rptId, boolean pass, String opinion);

    /**
     * 工时记录与异常确认（FR-4.5-5-6，spec「超限生成主管待办」「未超限直接计入」）：
     * 分项保存准备/加工/设备三工时；任一分项偏离该工序快照标准工时
     * （SETUP_HOURS/RUN_HOURS）超过 `WORKHOUR_DEVIATION_ALERT` → 生成主管待办
     * （bizType `WORKHOUR_ALERT`，不入账——HOUR_ALERT_FLAG=1 且 HOUR_CONFIRM 空即待确认态，
     * confirm 前置阻断）；未超限直接入账。
     * 设备运行时间无标准值可比（偏差 D4/D11），仅记录。
     */
    com.erp.entity.mrp.MrpReporting saveHours(String rptId, java.math.BigDecimal setupHours,
                                               java.math.BigDecimal runHours,
                                               java.math.BigDecimal machineHours);

    /** 工时异常主管确认入账（待办确认接口）：置 HOUR_CONFIRM_BY/AT，放行 confirm */
    void confirmHours(String rptId);

    /**
     * 冲销申请（BR-4.5-25，spec「冲销生成红字」「原因必填」）：仅 CONFIRMED 可申请，
     * 原因必填（空 422）；提交 `ReportReversal` 审批（PLAN_MGR 单签，PLANNER/PLAN_MGR/ADMIN
     * 可发起）。通过回调 {@link #applyReversal} 执行五步反向（design D7）。
     */
    Map<String, Object> applyReversal(String rptId, String reason);

    /**
     * 冲销执行（审批通过回调，同事务五步反向）：① 投料回加（reverseFeeds）
     * ② 产出行反向（红字镜像）③ 工时归零 ④ 原单置 REVERSED + 生成 REVERSES_ID 镜像留痕
     * ⑤ 良率回拨（累计自动剔除）。REVERSED 单不可再次冲销（幂等 422）。
     * 若工单已完工且冲销后不再满足完工条件（存在未完成工序 / 末道累计合格 < 完工基线）
     * → 状态回退 RELEASED + 倒冲补投 BFLUSH 行回加库存（completion-backflush「完工状态回退」）。
     */
    MrpReporting applyReversal(String rptId);

    /**
     * 完工确认接管（FR-4.5-5-7，spec completion-backflush「完工确认接管」，5.4.5 过渡承诺兑现）：
     * 校验 ①全工序 OP_STATUS=DONE（列出未完成项）②首件/IPQC 无未决（各自 ENFORCE 开关）
     * ③无活动冻结（MoFreezeService 活动行）。通过 → Step1 倒冲补足（同事务）→
     * Step2 工单置 Completed（复用 MoService.complete，合格产出 = 末道工序累计合格）。
     * 任一不满足 MUST 422 并列出未决项。
     */
    Map<String, Object> completeMo(String moId);
}
