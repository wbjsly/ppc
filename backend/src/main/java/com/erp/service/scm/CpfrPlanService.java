package com.erp.service.scm;

import java.util.Map;

/** CPFR 联合补货计划与预测订单（spec cpfr-collaboration R5/R6，C-4.9-08）。 */
public interface CpfrPlanService {

    /** 计划清单（含行与 ZONE） */
    java.util.List<Map<String, Object>> list(String agreementId, String periodTag);

    /**
     * 计划员创建联合补货计划：从已推送预测行（同周期）生成计划行，
     * 按协议参数标注 ZONE（FREEZE/ELASTIC/FREE）。同协议同周期幂等（422）。
     */
    Map<String, Object> create(Map<String, Object> payload);

    /**
     * 确认联合补货计划（side=SUPPLIER / INTERNAL）。
     * 未达成例外 → 422；双方确认后状态=双方确认并生成订单态。
     */
    Map<String, Object> confirm(String planId, String side, String operator);

    /**
     * 冻结期/弹性调整：FREEZE 区或弹性区超比例 → L2 审批（PMGR 节点）；
     * 弹性区内未超比例/自由区 → 直接生效（留痕免审批）。
     */
    Map<String, Object> adjustLine(String lineId, Map<String, Object> payload);

    /** 转化登记（实际到货量），写 CONVERT 留痕 */
    Map<String, Object> convert(String lineId, Map<String, Object> payload);

    /** 生成 PO 跳转参数（URL 预填，不落采购表，丙决策） */
    Map<String, Object> poJumpParams(String lineId);
}
