package com.erp.service.qms;

import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.entity.qms.InspectionLot;
import com.erp.entity.qms.Strictness;

import java.util.List;
import java.util.Map;

/**
 * 检验批（spec inspection-lot）：GR 登记提交同事务生成、抽样取严、免检与严格度联动。
 * 1 个 GR 行 = 1 个检验批；生成即固化标准版本与抽样快照（BR-4.12-56）。
 */
public interface InspectionLotService {

    /**
     * 登记提交时同事务生成检验批（IQC）：
     * 免检命中 → SKIPPED；无生效标准 → BLOCKED 占位；否则按取严口径算样本量并落检验项快照。
     * 同步回写 GR 行 {@code QC_STATUS}。
     */
    List<InspectionLot> generateForGr(GoodsReceipt gr, List<GoodsReceiptLine> lines);

    /** BLOCKED 批次激活（标准发布后一键加载有效版本转待检，BR-4.12-07 例外口） */
    InspectionLot activate(String lotId);

    /** 标准发布后批量激活该标准可命中的 BLOCKED 批次，返回激活数 */
    int activateForStandard(String standardId);

    /**
     * 免检命中查询：物料 + 供应商（供方空 = 不限）且 STATUS=ACTIVE。
     * 命中返回免检记录（id/status），未命中返回空 Map。
     */
    Map<String, Object> hitExempt(String materialCode, String supplierId);

    /** 任一批次不合格自动取消免检（BR-4.12-12） */
    void disableExempt(String materialCode, String supplierId, String reason);

    /** 当前严格度：TIGHTENED（SCAR 发出或连 2 批不合格）/ NORMAL / RELAXED */
    String currentStrictness(String materialCode, String supplierId);

    /** 检验判定完成后驱动严格度（连 2 批不合格转加严；加严下连 3 批合格解除） */
    void onLotJudged(InspectionLot lot, String result);

    // ================= 录入 / 判定 / 放行（group 5） =================

    /**
     * 检验录入（BR-4.12-14/15，spec inspection-lot）：
     * 按检验项写实测值/判定；CTQ 必录实测值（缺 → 422）；器具校准状态非有效 → 422（C-4.12-08）；
     * 数值偏离 ≥10×公差 → 返回 abnormal 清单要求二次确认（L4，非阻断）。
     *
     * @return {lot, items, abnormal:[...]}
     */
    java.util.Map<String, Object> inputItems(String lotId, java.util.List<java.util.Map<String, Object>> rows,
                                             boolean abnormalConfirm);

    /** 自动判定（BL-4.12-01/05）：CTQ 不合格或不合格数 ≥Re → FAIL；=Ac（>0）→ 边界复核；否则 PASS 待确认 */
    java.util.Map<String, Object> judge(String lotId);

    /** 边界复核（BR-4.12-17，L2）：通过 → 回到待确认；退回 → 重录 */
    java.util.Map<String, Object> reviewBoundary(String lotId, boolean pass, String opinion);

    /**
     * 质检员确认合格放行（BR-4.12-20 / C-4.12-06 / BR-4.12-19）：
     * 前置校验（CTQ 全检、记录完整、不在可疑清单、无未关闭 NCR、快照绑定）逐条列出未通过项；
     * 通过 → RELEASED + GR 行 QC_STATUS=RELEASED；放行不可撤回。
     */
    java.util.Map<String, Object> confirmRelease(String lotId);

    /** 待检看板（2.4.2 数据源 = 检验批，D1 起算点 = 登记提交时间）。
     *  samplingDone：null = 不过滤（现状，6.2 三叶）；false = 仅未抽样批（6.3.1 抽样模式）。
     *  releaseMode：null = 不过滤（现状）；true = 仅待放行批（6.4.1 放行模式，RESULT=PASS 且 STATUS=INPUTTING）。 */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<java.util.Map<String, Object>>
            board(long current, long size, String status, Boolean samplingDone, Boolean releaseMode);

    /** 兼容重载：不过滤抽样/放行模式（等价参数全 null） */
    default com.baomidou.mybatisplus.extension.plugins.pagination.Page<java.util.Map<String, Object>>
            board(long current, long size, String status, Boolean samplingDone) {
        return board(current, size, status, samplingDone, null);
    }

    /** 兼容重载：不过滤（等价参数全 null） */
    default com.baomidou.mybatisplus.extension.plugins.pagination.Page<java.util.Map<String, Object>>
            board(long current, long size, String status) {
        return board(current, size, status, null, null);
    }

    /**
     * 外协收货检验工作台（change add-outsourcing-processing，spec outsourcing-execution
     * 「外协收货检验工作台」）：数据源 = 检验批，筛选来源为外协 PO 的批次
     * （GR → PO → 外协派工单回联 poNo），聚合供应商、派工单号、工单/工序、供料方式上下文；
     * 非外协批次不出现。检验动作复用既有检验批 API，本方法仅查询。
     */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<java.util.Map<String, Object>>
            outsourceBoard(long current, long size, String status);

    // ================= IPQC / OQC（group 5，偏差 D5） =================

    /**
     * 手工创建 IPQC/OQC 检验批（偏差 D5：工单报工与发货触发挂桩，本期手工 + 巡检计划）。
     * 与 IQC 同一套取严与快照引擎；无标准同样 BLOCKED 占位。
     */
    InspectionLot createManual(java.util.Map<String, Object> body);

    /** 严格度记录（物料 + 供应商维度：加严/解除历史） */
    java.util.List<Strictness> strictnessList(String materialCode, String supplierId);

    // ================= 复检批（NCR 挑选/返工完成后，tasks 6.3） =================

    /**
     * 复检批生成：挑选/返工处置完成后同事务创建（sourceType=RECHECK、refType=NCR）。
     * 复检不豁免免检（不合格批必须复检）；无标准 → BLOCKED 占位。
     * 返回新批次；同 NCR 已有未放行复检批时幂等返回既有批。
     */
    InspectionLot createRecheck(com.erp.entity.qms.Ncr ncr);

    /**
     * 返工复检批（change add-rework-management，FR-4.5-7-6：返工产出完工触发、
     * 按原检验方案加严一档 TIGHTENED）：refType=WORKORDER、refId=<moId>#REWORK、
     * sourceType=RECHECK；同 refId 未放行批幂等返回。batchNo 可空。
     */
    InspectionLot createReworkRecheck(String refMoId, String itemCode, String itemName,
                                      String batchNo, java.math.BigDecimal qty);

    /**
     * 试产产出检验批（change add-trial-production-management，spec trial-execution
     * 「产出入样品仓与放行前置」：完工触发 OQC，refType=WORKORDER、refId=<moId>#TRIAL、
     * sourceType=MANUAL；严格度沿物料既有方案 NORMAL（试产不加严）；同 refId 未放行批幂等返回。
     * 放行经 confirmRelease → LotReleaseListener 试产分支（QC→AVAILABLE + 计价冲回）。
     */
    InspectionLot createTrialLot(String refMoId, String itemCode, String itemName,
                                 String batchNo, java.math.BigDecimal qty);

    // ================= IPQC 巡检计划（5.10，偏差 D5） =================

    java.util.List<com.erp.entity.qms.PatrolPlan> patrolPlans();

    com.erp.entity.qms.PatrolPlan createPatrolPlan(java.util.Map<String, Object> body);

    /** 启用 / 暂停切换 */
    com.erp.entity.qms.PatrolPlan togglePatrolPlan(String id);

    /** 到点计划生成 IPQC 批次（scheduler 驱动，幂等），返回生成批数 */
    int runPatrolPlans();

    // ================= 分派与超时（change rework-inspection-tasks，design D2/D3） =================

    /**
     * 超时扫描（scheduler 驱动）：在途批（PENDING/INPUTTING/REVIEWING）超过时限未出结论
     * → OVERDUE_FLAG=1 + 通知质检员（IPQC 追加通知车间主管）；无改派记录 → 自动改派在手最少
     * 他人 + 通知质量经理；已改派过 → 仅升级通知。返回处理批数（spec inspection-lot 超时扫描）。
     */
    int sweepOverdue();

    // ================= OQC 完工全链（change rework-inspection-tasks design D1） =================

    /**
     * 正常工单完工 OQC 批（spec OQC 完工触发与放行联动入库）：refType=WORKORDER、
     * refId=<moId>#FG、sourceType=FG；无标准 → BLOCKED 占位不抛错；同 refId 未取消批幂等返回。
     * moPriority=1（最高档）→ URGENT + 时限从严。
     */
    InspectionLot createFinishedGoodsOqc(String refMoId, String itemCode, String itemName,
                                         String batchNo, java.math.BigDecimal qty, Integer moPriority);

    /**
     * 完工冲销联动（spec 正常工单 OQC 建批挂钩）：取消 refId=<moId>#FG 的未结论批
     * （RELEASED 不回滚，仅提示人工红冲）。返回取消批数。
     */
    int cancelFgLotsForMo(String refMoId);

    /** 批次合格证（OQC 放行生成；未放行返回 null） */
    com.erp.entity.qms.Certificate certificateOf(String lotId);
}
