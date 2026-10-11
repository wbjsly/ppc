package com.erp.service.mrp;

import com.erp.entity.mrp.MrpMoShortage;

import java.math.BigDecimal;
import java.util.List;

/**
 * 齐套计算服务（change add-work-order-management，spec「库存预检与缺料清单」/ BR-4.5-04；
 * proposal D5：全系统唯一齐套算法——5.4 创建/释放内嵌调用，5.5 齐套检查菜单落地时复用本服务做独立操作页）。
 *
 * 算法：按工单 BOM 快照行逐行 需求 = 工单剩余数量（计划数量−合格产出） × 单位用量 ×（1 + 损耗率），
 * 对比 可用 = 库存 OnHand + 在途 PO（KITTING_TRANSIT_DATE_FILTER=ON 时仅计计划开工日前到货），
 * 缺料行落 erp_mrp_mo_shortage（重建式），齐套率 = 满足需求行数 / 总行数 × 100；
 * 有缺料 → 置「缺料待料」标记，恢复 → 自动清除。
 * 缺料不阻断创建/释放（L2496 场景）；L1 派工阻断归 5.6（C-4.5-11）。
 * 口径修正见 change add-kitting-check design D1（剩余量基数 + 派工日前在途过滤）。
 */
public interface KittingService {

    /** 对工单执行齐套计算（重建缺料行 + 置/清 SHORTAGE_FLAG），返回齐套率 0~100；人工操作源（默认静默） */
    default BigDecimal check(String moId) {
        return check(moId, false);
    }

    /**
     * 对工单执行齐套计算（change add-kitting-check design D2）。
     * notifyOnFlip=true（调度器源）时，缺料/恢复标记翻转 → 推站内通知
     * （bizType=MO_SHORTAGE/MO_KITTING_RESTORED、bizId=moId，接收方计划两方+仓库，偏差 D1）；
     * false（创建/释放/拆分/手动批量）由页面 warnings 反馈（偏差 D2）。
     */
    BigDecimal check(String moId, boolean notifyOnFlip);

    /**
     * 人工批量校验（design D3：串行防重建互踩 + 批次内工单号去重，偏差 D2 静默源）。
     * 返回每张结果 {moId, moNo, rate, shortageFlag}（LinkedHashMap 保序）。
     */
    java.util.List<java.util.Map<String, Object>> checkBatch(java.util.List<String> moIds);

    /** 工单缺料清单（5.4.3 释放页展示、5.5 复用） */
    List<MrpMoShortage> shortages(String moId);

    /**
     * 齐套逐行明细（change add-kitting-check 任务 5.1，spec「单工单齐套校验工作台」）：
     * 只读计算不落库——返回每行 需求/可用/缺料 与整体齐套率；缺料行附替代候选
     * （读源 BOM 替代关系 + 替代料当前可用量 + 比例折算可抵量，不参与判定，偏差 D4）。
     */
    java.util.Map<String, Object> detail(String moId);

    /**
     * 用户入口批量校验（design D9 权限门：计划员/计划主管/仓库/管理员；偏差 D2 人工源静默）。
     * 内部串行去重后逐张 check，返回每张 {moId, moNo, rate, shortageFlag}。
     */
    java.util.List<java.util.Map<String, Object>> checkUser(java.util.List<String> moIds);

    /**
     * 缺料聚合看板（5.5.2，spec「缺料预警聚合看板」）：跨工单未关闭缺料按物料 group——
     * 缺料工单数、总缺料量、各单明细（下钻用）；itemCode/moNo 模糊筛选，按总缺料量降序。
     */
    java.util.List<java.util.Map<String, Object>> aggregate(String itemCode, String moNo);
}
