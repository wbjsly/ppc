package com.erp.service.mrp;

import com.erp.entity.mrp.MrpMo;

import java.util.List;
import java.util.Map;

/**
 * 返工工单服务（change add-rework-management，spec rework-order / 流程 7 FR-4.5-7-1~4）。
 * 职责：判定来源双接入（NCR 处置=返工 / 退货判定=可返修）、创建卡控（C-4.5-13 三查）、
 * 冻结库存转在制三池过账（QC→WIP 平移 + 冻结行钉住，「不解除冻结标记」）、
 * 生命周期阶段推导（COMPLETED 段由检验批+冻结行推导，零状态机）。
 * 派工/报工/齐套/完工走 5.5~5.7 既有链（MO_TYPE=RW 分支），本服务不重复承载。
 */
public interface ReworkOrderService {

    /**
     * 判定来源候选（spec「判定来源双接入」）：srcType=NCR → 处置方式=返工且未关联返工工单的 NCR；
     * srcType=RETURN → 判定结论=可返修且未关联的退货单。返回 {srcNo, productCode, productName,
     * batchNo, qty(批准数量), srcType} 列表，供 5.8.1 选入建单。
     */
    List<Map<String, Object>> sourceCandidates(String srcType);

    /**
     * 创建返工工单（spec「创建卡控」「冻结库存转在制」）：
     * 来源存在且可返工 → 同一 SRC_NO 至多一张 → 数量 ≤ 判定批准量 → 批次质量冻结/待处理状态
     * （财务冻结 L1 阻断）→ 转在制过账（QC−=n / WIP+=n，冻结行 REF_MO_ID 钉住不释放，
     * 库存状态变更流水留痕，三池守恒）→ 插入 RW 工单（PLANNED，生命周期走既有审批/释放链）。
     * head 携带：srcType/srcNo/qty/productCode(校验一致性)/batchNo(可选覆盖)/planStartDate/planEndDate/priority。
     */
    MrpMo create(MrpMo head);

    /**
     * 创建（含追加料清单，BR-4.5-32 返工 BOM）：生成 BOM 快照——恒含 1 行 INPUT
     * （不合格品自身投入，齐套按在制池判满足），ADDL 行来自追加清单（可为空）。
     */
    MrpMo create(MrpMo head, List<com.erp.entity.mrp.MrpMoBom> addlLines);

    /**
     * 阶段推导（spec「生命周期阶段推导」）：工单 COMPLETED 段业务阶段——
     * PENDING_INSP（复检批无合格结论）/ PENDING_STOCK_IN（检验合格未过账，待入库）/ CLOSED（真实关闭）。
     * 非 COMPLETED 工单返回其状态本身。
     */
    String phaseOf(String moId);

    /** 返工工单列表（RW 分池只读；计划侧+质量侧） */
    List<MrpMo> list(String status, String productCode);

    /**
     * 完工触发复检（spec「完工触发复检加严」）：返工工单完工确认成功后调用——
     * 自动创建加严一档复检批（refId=<moId>#REWORK，InspectionLotService.createReworkRecheck），
     * 幂等（已有未放行批直接返回）。qualified=合格产出，批次取判定来源。
     */
    com.erp.entity.qms.InspectionLot onCompleted(MrpMo mo, java.math.BigDecimal qualified);

    /**
     * 发起二次返工审批（spec 返工次数与二次返工审批：NCR 再评审选返工后由 5.8.2 质量侧发起，
     * 节点=ROLE_QUALITY_MGR 签，bizId=判定单号；审批通过是创建第二张返工工单的前置）。
     * 幂等：已有 PENDING 实例直接返回。
     */
    Map<String, Object> applySecond(String srcNo);

    /**
     * 强制报废执行（BR-4.5-02「累计两次返工检验不合格强制报废」）：校验本单为同判定单第 2 张
     * 且其复检批已判不合格 → 在制池扣减（报废出库流水 REWORK_SCRAP）+ 冻结行释放 + 工单关闭终局 +
     * 通知质量主管/计划员；质量损失成本经 NCR/COPQ 既有口径归集。
     */
    void enforceScrap(String moId);

    /**
     * 返工料工费归集（spec 返工成本归集与经济性 FR-4.5-7-7 / BR-4.5-33）：
     * 材料=投料行×物料标准成本、工时=（准备+加工）×人工费率、费用=设备运行时间×设备费率
     * （费率主数据缺失走 sys_param 兜底，偏差 D3）；重建式挂原判定单 COPQ 内部失败
     * （sourceType=NCR/RETURN + remark 标记 REWORK:，不摊正常工单，冲销后重算回拨）；
     * 归集额 > 重置成本基准 → 通知质量主管评估经济性（提示不阻断，NoticeService 幂等）。
     * 返回 {materialCost, laborCost, machineCost, totalCost, unitCostAfter}。
     */
    Map<String, Object> aggregateCost(MrpMo mo, java.math.BigDecimal qualified);

    /**
     * 复检合格入库解冻关单（spec 三件套，FR-4.5-7-8）：合格数入可用 + 在制扣减 +
     * 冻结行 RELEASED（解冻记录）+ 工单关闭；幂等（无活动钉住直接返回）。
     * 由 LotReleaseListener 在 confirmRelease 同事务回调，亦可 5.8.2 待入库重试直接调用。
     */
    void closeFromInspection(String moId);

    /** 返工成本归集明细查询（5.8.2 展示：COPQ REWORK 行 + 单位成本/金额，无归集返回空 Map） */
    Map<String, Object> costOf(String moId);
}
