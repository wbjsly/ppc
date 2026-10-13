package com.erp.service.mrp;

import com.erp.entity.mrp.OsMaterial;
import com.erp.entity.mrp.OsMaterialLine;

import java.util.List;
import java.util.Map;

/**
 * 外协发料单（change add-outsourcing-processing，spec outsourcing-material-supply
 * 「外协发料单创建与出库」，task 5.1）：甲供外协派工单从仓库调拨原材料至供应商保管。
 *
 * 状态机：DRAFT（可改可删）→ POSTED（已出库，只读）→ RETURNED（余料已退，终态）。
 * 卡控：仅 OS_SUPPLY_TYPE=COMPANY_SUPPLY 可建（自购 422）；出库预扣可用库存，不足 L1 阻断
 * 逐行列缺口；出库过账 OS_MAT_OUT（扣 AVAILABLE_QTY），所有权留厂内仅保管责任转移。
 * 权限：写 = PLANNER/PLAN_MGR/PURCHASER/ADMIN（spec 菜单权限 5.10.3）。
 */
public interface OsMaterialService {

    /**
     * 创建发料单（DRAFT）：校验派工单存在 + 甲供（SUPPLIER_BUY → 422）+ 供应商已维护；
     * 行含物料/批次/仓库/请领量，行号自动分配；返回含行的完整单据。
     */
    OsMaterial create(Map<String, Object> body);

    /** 草稿详情（头 + 行）；不存在 404 */
    Map<String, Object> detail(String id);

    /** 草稿列表（派工单号/供应商/状态筛选），创建时间倒序 */
    List<OsMaterial> list(String dispatchNo, String supplierCode, String status);

    /** 草稿删除（仅 DRAFT，POSTED/RETURNED 422） */
    void delete(String id);

    /**
     * 出库过账（DRAFT → POSTED）：逐行校验可用库存并预扣，任一行不足 422 列出行级缺口
     * （物料/请领/可用/缺口）；过账成功回写 ISSUED_QTY + POST_BY/POST_DATE。
     * 幂等：已 POSTED 再过账 422（状态机守卫）。
     */
    OsMaterial post(String id);

    // ---------- 5.2 收货定额冲减与余料退回（spec「收货定额冲减与余料退回」） ----------

    /**
     * 收货定额冲减（收货过账链同事务调用，task 5.2）：按该派工单 POSTED 发料行的定额耗用
     * （BOM 快照单耗 ×(1+损耗) × 本次收货数，封顶行余量 = 实发−已冲减−已退回）冲减外协在途
     * （OS_QTY 池减 + QTY 总量减，行锁 + 非负护栏）并落冲减台账。
     * 幂等锚 = GR 单号（同 GR 重复过账不双冲）。无发料单/非甲供 → 静默 false，不影响收货主流程。
     *
     * @return 是否发生冲减（至少一行冲减 > 0）
     */
    boolean consumeOnReceipt(String dispatchId, String grNo, java.math.BigDecimal recvQty);

    /**
     * 余料退库过账（发料员操作，task 5.2）：按行指定退回量（≤ 可退余量 = 实发−已冲减−已退回，
     * 超退 422 列出行级缺口），OS_QTY 池减 + 退库过账 OS_MAT_IN（在途 → 工厂可用）；
     * 全部行余量归零 → 发料单置 RETURNED + RETURN_DATE。
     * 幂等由可退余量护栏承载（重复退回 → 余量不足 422，不会双退）。
     *
     * @param rows 行退回清单 [{lineId, qty}]
     * @return 退库后的发料单头
     */
    OsMaterial returnRemaining(String id, List<Map<String, Object>> rows);

    /**
     * 红冲恢复在途（退货出库链同事务调用，task 5.2）：已冲减的收货发生红冲时，
     * 按退货件数 × 定额单耗反向恢复外协在途（封顶各行净冲减 = CONSUME−已 REVERSE），
     * 落 REVERSE 台账。幂等锚 = 退货单号。非外协/无冲减记录 → 静默 false。
     *
     * @return 是否发生恢复（至少一行恢复 > 0）
     */
    boolean reverseConsume(String poNo, String returnNo, java.math.BigDecimal finishedQty);
}
