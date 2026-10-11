package com.erp.service.mrp;

import com.erp.entity.mrp.MrpMoFreeze;

import java.util.Map;

/**
 * 良率冻结台账（change add-reporting-management，spec in-process-quality
 * 「良率超差分级冻结」+「冻结解除与通知闭环」，design D4 活动行模式）。
 *
 * 触发：报工提交后良率偏差 > YIELD_TOLERANCE → upsert 活动行（MO_ID 唯一）
 *      + 按 YIELD_FREEZE_SCOPE 定 MO/WC + push YIELD_ALERT + 自动建 NCR；
 *      NCR 严重度 ≥ Major → 升 WC 级。
 * 挂点：派工生成/改派、完工确认双入口 assertNotFrozen（兑现 5.6 D12）。
 * 解除：冲销回拨 ≤ 阈值 或 NCR 关闭 → 释放活动行 + 通知原接收人（NOTICE_IDS 幂等）。
 */
public interface MoFreezeService {

    /**
     * 良率超差判定与冻结（报工提交后调用）：偏差超阈值 → 冻结（幂等 upsert）+ 通知 + 建 NCR；
     * 未超且存在活动冻结 → 回拨解冻。
     *
     * @return {frozen, scope, deviation, ncrId}（未超时 frozen=false）
     */
    Map<String, Object> evaluateAndFreeze(String moId, Integer opSeq, String rptNo);

    /** 活动冻结断言（派工生成/改派、完工确认入口）：冻结中 → L1 422 含原因与单号 */
    void assertNotFrozen(String moId, String wcCode);

    /** 当前活动冻结（无则 null） */
    MrpMoFreeze activeOf(String moId);

    /**
     * 解除冻结（NCR 关闭回调 / 冲销回拨显式调用）：释放活动行 + 通知原接收人（幂等）。
     * 返回是否确有活动行被释放。
     */
    boolean release(String moId, String reason, String operator);

    /**
     * NCR 在制冻结（spec「在制冻结不冻库存」）：报工来源 NCR 创建时 upsert 活动冻结行
     * （REASON=NCR、SCOPE=MO、挂 ncrId），挡后续派工与完工——不写库存冻结行。
     * 已有活动冻结时仅回填 ncrId（不重复建行）。返回活动行。
     */
    MrpMoFreeze freezeForNcr(String moId, String ncrId);

    /**
     * NCR 关闭/作废解冻：仅当活动行 ncrId 匹配（或未挂 NCR）时释放——
     * 防止误解冻挂其他 NCR/YIELD 原因的冻结。返回是否释放。
     */
    boolean releaseForNcr(String moId, String ncrId, String reason);
}
