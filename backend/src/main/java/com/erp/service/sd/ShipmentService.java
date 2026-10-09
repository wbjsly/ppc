package com.erp.service.sd;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.sd.Shipment;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 销售发货服务（tasks 9.2~9.9，spec sales-shipment，D8 销售侧策略与确认）。
 *
 * 边界（spec 拣货作业边界）：不生成拣货任务单/仓位扫描/复核单（归库存域 4.7），
 * 拣货信息以发货单行的批次与数量为准；出库批次按简化 FIFO（同仓库同 SKU 按入储时间先老先出）。
 */
public interface ShipmentService {

    /**
     * 9.2 部分发货：按 SO 行未发余量生成发货单，可多次执行。
     *
     * @param lines [{soLineId, qty?}]——qty 缺省 = 该行全部未发余量
     */
    Map<String, Object> generatePartial(String soId, List<Map<String, Object>> lines);

    /**
     * 9.3 合并发货：同客户、同仓库的多张 SO 合并为一张，行级保留来源 SO 引用。
     */
    Map<String, Object> generateMerge(List<String> soIds, String warehouseCode);

    /**
     * 9.4 分批发货：按分批交付方案行（独立交期与预留）逐行生成发货单。
     *
     * @param asOfDate 只生成计划发货日 ≤ asOfDate 的行（空 = 全部分批行）
     */
    Map<String, Object> generateBatch(String soId, LocalDate asOfDate);

    /**
     * 9.6 出库过账：同事务扣 AVAILABLE_QTY（FIFO 选批，不足阻断）+ 消耗预留 +
     * 出库凭证（发货单行 BATCH_ALLOC 留痕）+ 发布 AR.CONFIRMED 应收确认事件。
     */
    Shipment post(String shipId);

    /**
     * 12.6 换货发货（spec sales-return）：依据原 SO 行与退货判定数量生成换货发货单
     * （shipType=EXCHANGE、关联原退货单、保留原交易价格），同事务重走过账（FIFO 锁批），
     * 合格可锁批次不足 → 422 阻断；不发 AR.CONFIRMED（换货不重复确认应收）。
     */
    Map<String, Object> exchangeFromReturn(com.erp.entity.sd.SdReturn ret,
                                           List<com.erp.entity.sd.SdReturnLine> returnLines);

    /**
     * 13.6 框架下达单分批发货（spec sales-framework-agreement 执行视图）：
     * 依框架行锁定价生成 FRAMEWORK 发货单并同事务过账（FIFO 锁批不足 422），
     * 不消耗 SO 预留、不发 AR.CONFIRMED；由 FrameworkService 编排回写已发量。
     */
    Map<String, Object> frameworkShip(com.erp.entity.sd.FrameworkRelease release,
                                      com.erp.entity.sd.Framework fw,
                                      com.erp.entity.sd.FrameworkLine fwLine,
                                      java.math.BigDecimal qty);

    /**
     * 9.7 发货确认：回写 SO 行已发量与状态（部分发货/已发货）、记录发货时间与物流单号，
     * 物流异常记录并通知销售。
     */
    Shipment confirm(String shipId, String logisticsCo, String logisticsNo, String exceptNote);

    /** 9.8 签收回写「已签收」（全部发完的 SO 才推进 SO 状态） */
    Shipment sign(String shipId);

    /** 9.8 拒收：生成退货申请草稿并关联原发货单与 SO 行，通知销售 */
    Shipment reject(String shipId, String reason);

    /** 9.8 超时未签收预警扫描（调度入口），返回处理条数 */
    int sweepSignTimeout();

    /** 9.9 发货单取消：仅草稿可取消（已出库不可反过账，记偏差） */
    Shipment cancel(String shipId, String reason);

    Page<Shipment> page(long current, long size, String keyword, String status,
                        String customerId);

    /** 详情：头 + 行（来源引用、批次分配）+ SO 回写摘要 */
    Map<String, Object> detail(String shipId);
}
