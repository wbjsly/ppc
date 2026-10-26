package com.erp.service.mrp;

import com.erp.entity.mrp.MrpReservation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 物料预扣（change add-dispatch-management，spec dispatch-management「物料预扣/核销与释放」，
 * design D2：实扣 AVAILABLE_QTY + 核销余量护栏）。
 *
 * 生命周期：
 *   派工确认 → {@link #reserveForMo}（幂等：已有活动行跳过；ON 实扣 + takes 明细，OFF 仅记账）
 *   领料过账 → {@link #consumeOnIssue}（min(领料量, 余量) 核销；ON 行按 takes 回补库存）
 *   取消/关闭/驳回 → {@link #releaseByMo}（全量释放余量，ON 行按 takes 回补）
 *
 * 核销回补依据 takes 是否存在（而非开关当前值）——ON 期间建立的锁即使随后关闸也必须归位，
 * 保证账目自洽。调用方事务传播 REQUIRED（跟随领料过账/派工确认事务）。
 */
public interface ReservationService {

    /**
     * 派工确认时按工单 BOM 创建活动预扣（同工单同物料唯一活动行，幂等）。
     * 需求 = BOM 快照行 ×（计划数量 − 合格产出）× 用量 ×（1+损耗）按物料聚合；
     * 实扣 = min(当前可用, 需求)，逐库存行（inbound→bin 序，与引擎行锁序一致）扣减。
     *
     * @return 活动预扣行；工单无 BOM 快照行时返回 null
     */
    MrpReservation reserveForMo(String moId, String operator);

    /**
     * 领料过账核销：对指定工单（MO_NO 匹配）的活动预扣，按领料行释放锁
     * rel = min(行数量, 该物料核销余量)，递减余量并按 takes 回补可用量；
     * <b>释放优先落到领料行将扣减的批次</b>（行携带批次时），其余按 takes 序兜底，
     * 保证过账引擎的行级可用量复核恰好通过。余量归零的行置 CLEARED。
     * 物料无活动预扣时静默跳过（无关工单零变化）。
     * <p>
     * <b>时序硬约束：本方法 MUST 在领料引擎扣减之前调用</b>——预扣实扣后本工单自己的
     * 领料会被引擎可用量复核挡住（锁挡住自己），先归位锁再过账（同事务失败整体回滚）。
     *
     * @param parts 领料行（物料、批次、数量；VMI 行批次为寄售批次 → 自然落入 takes 序兜底）
     * @return 实际核销量（按物料；无预扣的物料不出现）
     */
    Map<String, BigDecimal> consumeOnIssue(String moNo, List<IssuePart> parts);

    /**
     * 本单活动预扣的锁批次占位（物料 → takes 序的 [{batchNo, qty}]）。
     * 领料创建拆分分配与预检用（options A：无锁量走引擎 FIFO、锁内量从本单 takes 批次占位）。
     * OFF 记账行 takes 为空 → 不占位（库存未扣、可用量本就包含该量）。
     */
    Map<String, List<LockedBatch>> lockedBatchesOf(String moNo);

    /** 领料行轻量参数（物料、批次、数量） */
    record IssuePart(String itemCode, String batchNo, BigDecimal qty) {
    }

    /** 锁批次占位（批次、锁余量） */
    record LockedBatch(String batchNo, BigDecimal qty) {
    }

    /** 全量释放该工单活动预扣（取消/关闭/驳回兜底），余量回补、置 RELEASED 并留原因 */
    void releaseByMo(String moId, String reason, String operator);

    /** 工单活动预扣行（每物料一行；无则空列表） */
    List<MrpReservation> activeOf(String moId);

    /** 工单全部预扣行（含历史，展示用） */
    List<MrpReservation> historyOf(String moId);
}
