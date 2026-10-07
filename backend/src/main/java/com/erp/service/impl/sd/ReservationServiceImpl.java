package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.entity.inv.InvStock;
import com.erp.entity.sd.Reservation;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoLine;
import com.erp.service.sd.ReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 批次预留实现（tasks 7.7/8.6/8.7，spec sales-atp-reservation）。
 *
 * 并发口径：确认时对候选库存批次行加 FOR UPDATE 锁，可锁量 = 库存可用量 − 该批次已存在的
 * ACTIVE 预留量（确认不直接扣 AVAILABLE_QTY，ATP 由预留汇总得出）；同批次两单串行进入，
 * 先到先得以 LOCK_AT 时间戳裁决（BR-4.3-24）。
 */
@Slf4j
@Service
public class ReservationServiceImpl implements ReservationService {

    private final ReservationDao reservationDao;
    private final SoLineDao soLineDao;
    private final InvStockDao stockDao;

    public ReservationServiceImpl(ReservationDao reservationDao,
                                  SoLineDao soLineDao,
                                  InvStockDao stockDao) {
        this.reservationDao = reservationDao;
        this.soLineDao = soLineDao;
        this.stockDao = stockDao;
    }

    @Override
    @Transactional
    public List<Reservation> reserveForSo(So so, List<SoLine> lines) {
        List<Reservation> created = new ArrayList<>();
        for (SoLine line : lines) {
            if (SoLine.LS_CANCELLED.equals(line.getLineStatus())) {
                continue;
            }
            // 幂等/增量：该行已有 ACTIVE 预留 → 只补锁差额（变更增数量复用同一入口）
            BigDecimal existing = nvl(reservationDao.sumActiveByLine(line.getId()));
            if (existing.compareTo(line.getQty()) >= 0) {
                continue;   // 已足额（重复确认不重复锁）
            }
            String wh = line.getWarehouseCode() == null || line.getWarehouseCode().isEmpty()
                    ? InvStock.DEFAULT_WH : line.getWarehouseCode();
            BigDecimal need = line.getQty().subtract(existing);

            // FOR UPDATE 锁候选批次行（同批次确认串行 → 先到先得时间戳裁决）
            List<InvStock> stocks = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                    .eq(InvStock::getWarehouseCode, wh)
                    .eq(InvStock::getItemCode, line.getItemCode())
                    .gt(InvStock::getAvailableQty, BigDecimal.ZERO)
                    .orderByAsc(InvStock::getCreateDate)
                    .last("FOR UPDATE"));

            BigDecimal lockable = BigDecimal.ZERO;
            Map<String, BigDecimal> freeByBatch = new LinkedHashMap<>();
            for (InvStock s : stocks) {
                String batch = s.getBatchNo() == null ? "" : s.getBatchNo();
                BigDecimal activeOnBatch = nvl(reservationDao.sumActiveOnBatch(
                        wh, line.getItemCode(), batch));
                BigDecimal free = nvl(s.getAvailableQty()).subtract(activeOnBatch);
                if (free.signum() <= 0) {
                    continue;
                }
                freeByBatch.put(batch, free);
                lockable = lockable.add(free);
            }

            if (lockable.compareTo(need) < 0) {
                // 8.7 锁不足硬阻断：回显可锁/需要；调用方事务回滚（不生成部分预留）
                throw new ServiceException(422, "批次锁定不足：" + line.getItemCode()
                        + "（仓库 " + wh + "）可锁 " + strip(lockable)
                        + "，需要 " + strip(need)
                        + "。请调整分批交付方案（3.4.2）或等待到货后再确认（BR-4.3-29 / 8.7）");
            }

            LocalDateTime now = LocalDateTime.now();
            for (Map.Entry<String, BigDecimal> e : freeByBatch.entrySet()) {
                if (need.signum() <= 0) {
                    break;
                }
                BigDecimal take = e.getValue().min(need);
                Reservation r = new Reservation();
                r.setId(uuid());
                r.setSoId(so.getId());
                r.setSoNo(so.getSoNo());
                r.setLineId(line.getId());
                r.setLineNo(line.getLineNo());
                r.setItemCode(line.getItemCode());
                r.setWarehouseCode(wh);
                r.setBatchNo(e.getKey());
                r.setQty(take);
                r.setStatus(Reservation.ST_ACTIVE);
                r.setLockAt(now);
                reservationDao.insert(r);
                created.add(r);
                need = need.subtract(take);
            }

            line.setReservedQty(line.getQty());
            soLineDao.updateById(line);
        }
        log.info("SO {} reserved {} lines ({} reservations)", so.getSoNo(),
                lines.size(), created.size());
        return created;
    }

    @Override
    @Transactional
    public int releaseBySo(String soId, String reason) {
        List<Reservation> active = reservationDao.selectActiveBySo(soId);
        if (active.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Reservation r : active) {
            r.setStatus(Reservation.ST_RELEASED);
            r.setReleasedAt(now);
            r.setReleaseReason(reason);
            reservationDao.updateById(r);
        }
        // 行 RESERVED_QTY 清零
        for (SoLine line : soLineDao.selectList(new LambdaQueryWrapper<SoLine>()
                .eq(SoLine::getSoId, soId))) {
            if (nvl(line.getReservedQty()).signum() > 0) {
                line.setReservedQty(BigDecimal.ZERO);
                soLineDao.updateById(line);
            }
        }
        log.info("SO {} released {} reservations ({})", soId, active.size(), reason);
        return active.size();
    }

    @Override
    @Transactional
    public int releaseByLine(String lineId, BigDecimal keepQty, String reason) {
        List<Reservation> active = reservationDao.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getLineId, lineId)
                .eq(Reservation::getStatus, Reservation.ST_ACTIVE)
                .orderByDesc(Reservation::getLockAt));
        if (active.isEmpty()) {
            return 0;
        }
        BigDecimal activeQty = active.stream().map(Reservation::getQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal toRelease = keepQty == null
                ? activeQty : activeQty.subtract(keepQty).max(BigDecimal.ZERO);
        if (toRelease.signum() <= 0) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Reservation r : active) {   // 从最新锁的开始释放（后到先释）
            if (toRelease.signum() <= 0) {
                break;
            }
            BigDecimal take = r.getQty().min(toRelease);
            if (take.compareTo(r.getQty()) == 0) {
                r.setStatus(Reservation.ST_RELEASED);
                r.setReleasedAt(now);
                r.setReleaseReason(reason);
                reservationDao.updateById(r);
            } else {
                // 部分释放：缩减本行、剩余保持 ACTIVE
                r.setQty(r.getQty().subtract(take));
                r.setReleaseReason((r.getReleaseReason() == null ? "" : r.getReleaseReason() + ";")
                        + reason + " 部分释放 " + strip(take));
                reservationDao.updateById(r);
            }
            toRelease = toRelease.subtract(take);
        }
        SoLine line = soLineDao.selectById(lineId);
        if (line != null) {
            line.setReservedQty(keepQty == null ? BigDecimal.ZERO : keepQty);
            soLineDao.updateById(line);
        }
        return 1;
    }

    @Override
    public BigDecimal sumActive(String itemCode, String warehouseCode) {
        return nvl(reservationDao.sumActive(itemCode, warehouseCode));
    }

    @Override
    public List<Reservation> activeBySo(String soId) {
        return reservationDao.selectActiveBySo(soId);
    }

    // ---------- helpers ----------

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
