package com.erp.service.hr;

import com.erp.entity.hr.HrLeaveBalanceBatch;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 假期余额池（leave-management D5：调休+年假批次共用 FEFO；年假年度发放与清零）。 */
public interface HrLeaveBalanceService {

    /** 员工余额（按类型分组：批次明细 FEFO 序 + 合计）。 */
    Map<String, Object> balances(String empId);

    /**
     * FEFO 扣减（到期日升序跨批次拆分）：类型内独立扣减；余额不足 L1。
     * 返回扣减留痕 [{batchId, batchNo, qty}]。
     */
    List<Map<String, Object>> consume(String empId, String typeCode, BigDecimal qty);

    /** FEFO 扣减回滚（请假驳回/更正时还原批次 remain）。 */
    void rollback(String empId, String typeCode, List<Map<String, Object>> consumeTrace);

    /**
     * 年假年度发放扫描（每年 1 月 1 日执行）：按入职周年工龄适用 ANNUAL_LEAVE_LADDER 阶梯生成批次；
     * 同年已有 ANNUAL_GRANT 批次幂等跳过。
     */
    Map<String, Object> annualGrantScan();

    /** 到期清零扫描（年假当年 12-31；调休到期由 HrCompTimeService 处理——此处只清 ANNUAL）。 */
    Map<String, Object> annualExpireScan();
}
