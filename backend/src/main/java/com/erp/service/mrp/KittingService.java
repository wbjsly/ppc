package com.erp.service.mrp;

import com.erp.entity.mrp.MrpMoShortage;

import java.math.BigDecimal;
import java.util.List;

/**
 * 齐套计算服务（change add-work-order-management，spec「库存预检与缺料清单」/ BR-4.5-04；
 * proposal D5：全系统唯一齐套算法——5.4 创建/释放内嵌调用，5.5 齐套检查菜单落地时复用本服务做独立操作页）。
 *
 * 算法：按工单 BOM 快照行逐行 需求 = 工单数量 × 单位用量 ×（1 + 损耗率），
 * 对比 可用 = 库存 OnHand + 在途 PO；缺料行落 erp_mrp_mo_shortage（重建式），
 * 齐套率 = 满足需求行数 / 总行数 × 100；有缺料 → 置「缺料待料」标记，恢复 → 自动清除。
 * 缺料不阻断创建/释放（L2496 场景）；L1 派工阻断归 5.6（C-4.5-11）。
 */
public interface KittingService {

    /** 对工单执行齐套计算（重建缺料行 + 置/清 SHORTAGE_FLAG），返回齐套率 0~100 */
    BigDecimal check(String moId);

    /** 工单缺料清单（5.4.3 释放页展示、5.5 复用） */
    List<MrpMoShortage> shortages(String moId);
}
