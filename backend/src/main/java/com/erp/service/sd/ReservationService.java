package com.erp.service.sd;

import com.erp.entity.sd.Reservation;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoLine;

import java.math.BigDecimal;
import java.util.List;

/**
 * SO 批次预留服务（tasks 7.7/8.6/8.7，spec sales-atp-reservation）。
 *
 * 口径（探索第 26/27 题拍板）：
 *  - SO 确认时即按行锁定具体批次（erp_sd_reservation：SO行+SKU+仓库+批次+数量）；
 *  - 同批次先到先得在确认时裁决（BR-4.3-24，LOCK_AT 时间戳）；
 *  - 锁不够硬阻断确认（L1，回显可锁/需要），不生成部分预留（8.7）；
 *  - ATP 的 Reserved = SUM(ACTIVE)，确认使 ATP↓（不直接改库存 AVAILABLE_QTY）；
 *  - 无批次物料以空批次 '' 占位（8.6）；
 *  - 信用冻结不释放预留（解冻回"已确认"时须有货可发）。
 */
public interface ReservationService {

    /**
     * SO 确认：按行锁批次生成 ACTIVE 预留（同事务，须 FOR UPDATE 行锁防并发超卖）。
     * 合格批次不足 → ServiceException 422（可锁 X / 需要 Y），调用方事务整体回滚（7.7/8.7）。
     *
     * @return 生成的预留行
     */
    List<Reservation> reserveForSo(So so, List<SoLine> lines);

    /**
     * 释放 SO 全部 ACTIVE 预留（取消 / 关闭余量 / 变更减量）；回写行 RESERVED_QTY=0。
     *
     * @return 释放条数
     */
    int releaseBySo(String soId, String reason);

    /**
     * 释放指定行的全部 ACTIVE 预留（行取消 / 行减量）。
     *
     * @param keepQty 保留量（减量时 = 新数量，释放超出部分）；null = 全部释放
     * @return 释放条数
     */
    int releaseByLine(String lineId, BigDecimal keepQty, String reason);

    /** ATP 的 Reserved 因子（BR-4.3-19）：按 SKU + 仓库汇总 ACTIVE 预留量 */
    BigDecimal sumActive(String itemCode, String warehouseCode);

    /** SO 的 ACTIVE 预留行 */
    List<Reservation> activeBySo(String soId);
}
