package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.mrp.MrpMoBomDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpReservationDao;
import com.erp.entity.inv.InvStock;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoBom;
import com.erp.entity.mrp.MrpReservation;
import com.erp.service.SysParamService;
import com.erp.service.mrp.ReservationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 物料预扣实现（change add-dispatch-management，design D2）。
 *
 * 账目模型（对账用例必测）：
 *   派工确认(ON)  : RESERVED = min(可用, 需求)；逐库存行实扣 AVAILABLE（行序 inbound→bin 与引擎锁序一致，
 *                   FOR UPDATE 持锁到事务结束，锁定后可用量稳定 → 实扣必然凑满）
 *   派工确认(OFF) : 同账面值记账、takes 空、库存不动（降级=独立记账表行为）
 *   领料过账      : n = min(领料量, REMAIN)；REMAIN -= n；ON 行按 takes 逐行回补（回补目标=锁所在行，
 *                   行余量恒 ≥ take 剩余，无需 cap）；REMAIN=0 → CLEARED
 *   取消/释放     : 全量回补剩余 takes → RELEASED
 * 回补依据 takes 是否存在（而非开关当前值）：ON 期建立的锁即使随后关闸也必须归位。
 * 幂等锚点：UNIQUE(MO_ID, ITEM_CODE, ACTIVE_FLAG='1')——重复确认跳过，不重复扣减。
 */
@Slf4j
@Service("mrpReservationServiceImpl") // 消歧：sd 域已有 ReservationServiceImpl（sdReturnServiceImpl 同款先例）
public class ReservationServiceImpl implements ReservationService {

    private final MrpReservationDao reservationDao;
    private final MrpMoDao moDao;
    private final MrpMoBomDao bomDao;
    private final InvStockDao stockDao;
    private final SysParamService sysParam;
    private final ObjectMapper objectMapper;

    public ReservationServiceImpl(MrpReservationDao reservationDao, MrpMoDao moDao,
                                  MrpMoBomDao bomDao, InvStockDao stockDao,
                                  SysParamService sysParam, ObjectMapper objectMapper) {
        this.reservationDao = reservationDao;
        this.moDao = moDao;
        this.bomDao = bomDao;
        this.stockDao = stockDao;
        this.sysParam = sysParam;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public MrpReservation reserveForMo(String moId, String operator) {
        List<MrpReservation> actives = activeOf(moId);
        if (!actives.isEmpty()) {
            return actives.get(0); // 幂等：已有活动预扣（每物料一行），重复确认零扣减
        }
        MrpMo mo = moDao.selectById(moId);
        if (mo == null) {
            throw new ServiceException(404, "工单不存在：" + moId);
        }
        // 需求基数 = 剩余量（与 5.5 齐套唯一算法同口径：qty − qualifiedQty，空按 0）
        BigDecimal remaining = nvl(mo.getQty()).subtract(nvl(mo.getQualifiedQty()));
        if (remaining.signum() <= 0) {
            remaining = BigDecimal.ZERO;
        }
        List<MrpMoBom> bomLines = bomDao.selectList(new LambdaQueryWrapper<MrpMoBom>()
                .eq(MrpMoBom::getMoId, moId));
        if (bomLines.isEmpty()) {
            return null; // 无 BOM 快照行不建预扣
        }
        Map<String, BigDecimal> reqByItem = new LinkedHashMap<>();
        Map<String, String> nameByItem = new LinkedHashMap<>();
        boolean rework = MrpMo.MO_TYPE_RW.equals(mo.getMoType());
        for (MrpMoBom l : bomLines) {
            if (l.getItemCode() == null || l.getItemCode().isEmpty()) {
                continue;
            }
            // 返工投入行（INPUT）在 WIP 池（转在制已持有），不参与可用库存预扣
            // （change add-rework-management，与 kitting delta 同源口径）
            if (rework && MrpMoBom.LINE_INPUT.equals(l.getLineType())) {
                continue;
            }
            BigDecimal req = remaining
                    .multiply(nvl(l.getUnitQty()))
                    .multiply(BigDecimal.ONE.add(nvl(l.getLossRate())));
            if (req.signum() <= 0) {
                continue;
            }
            reqByItem.merge(l.getItemCode(), req, BigDecimal::add);
            nameByItem.putIfAbsent(l.getItemCode(), l.getItemName());
        }
        if (reqByItem.isEmpty()) {
            return null;
        }
        boolean deduct = "ON".equalsIgnoreCase(sysParam.getValue("DISPATCH_RESERVATION", "ON"));
        MrpReservation saved = null;
        for (Map.Entry<String, BigDecimal> e : reqByItem.entrySet()) {
            saved = createRow(mo, e.getKey(), nameByItem.get(e.getKey()),
                    e.getValue(), deduct, operator);
        }
        return saved;
    }

    /** 单物料建预扣：锁行 → 算可用 → 实扣（ON）→ 落行 */
    private MrpReservation createRow(MrpMo mo, String itemCode, String itemName,
                                     BigDecimal req, boolean deduct, String operator) {
        List<InvStock> rows = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getItemCode, itemCode)
                .gt(InvStock::getAvailableQty, 0)
                // 行锁序 = 挑选序 = 引擎行序（freeze D7 同款，防死锁）
                .orderByAsc(InvStock::getInboundDate)
                .orderByAsc(InvStock::getBinCode)
                .last("FOR UPDATE"));
        BigDecimal avail = rows.stream()
                .map(s -> nvl(s.getAvailableQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reserved = req.min(avail); // 部分预扣：min(可用, 需求)
        List<Take> takes = new ArrayList<>();
        if (deduct && reserved.signum() > 0) {
            BigDecimal left = reserved;
            for (InvStock s : rows) {
                if (left.signum() <= 0) {
                    break;
                }
                BigDecimal rowAvail = nvl(s.getAvailableQty());
                if (rowAvail.signum() <= 0) {
                    continue;
                }
                BigDecimal take = rowAvail.min(left);
                int upd = stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                        .eq(InvStock::getId, s.getId())
                        .ge(InvStock::getAvailableQty, take)
                        .setSql("AVAILABLE_QTY = AVAILABLE_QTY - " + take.toPlainString()));
                if (upd == 0) {
                    throw new ServiceException(409, "库存预扣并发冲突，请重试：" + itemCode);
                }
                takes.add(new Take(s.getId(), take));
                left = left.subtract(take);
            }
            if (left.signum() != 0) {
                throw new ServiceException(409, "库存预扣行可用量不足，请重试：" + itemCode);
            }
        }
        MrpReservation r = new MrpReservation();
        r.setMoId(mo.getId());
        r.setMoNo(mo.getMoNo());
        r.setItemCode(itemCode);
        r.setItemName(itemName);
        r.setReqQty(req);
        r.setReservedQty(reserved);
        r.setRemainQty(reserved);
        r.setStatus(MrpReservation.ST_ACTIVE);
        r.setActiveFlag("1");
        r.setTakesJson(toJson(takes));
        r.setCreateBy(operator);
        r.setUpdateBy(operator);
        reservationDao.insert(r);
        log.info("预扣创建 {} {} req={} reserved={} takes={} deduct={}",
                mo.getMoNo(), itemCode, req, reserved, takes.size(), deduct);
        return r;
    }

    @Override
    @Transactional
    public Map<String, BigDecimal> consumeOnIssue(String moNo, List<IssuePart> parts) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (moNo == null || moNo.isEmpty() || parts == null || parts.isEmpty()) {
            return out;
        }
        List<MrpReservation> actives = reservationDao.selectList(new LambdaQueryWrapper<MrpReservation>()
                .eq(MrpReservation::getMoNo, moNo)
                .eq(MrpReservation::getStatus, MrpReservation.ST_ACTIVE));
        if (actives.isEmpty()) {
            return out;
        }
        // takes 批次映射（id → batchNo）：释放优先落到领料行将扣的批次
        Map<String, String> batchOf = batchOfTakes(actives);
        for (MrpReservation row : actives) {
            BigDecimal remain = nvl(row.getRemainQty());
            if (remain.signum() <= 0) {
                continue;
            }
            List<Take> takes = fromJson(row.getTakesJson());
            BigDecimal consumed = BigDecimal.ZERO;
            for (IssuePart p : parts) {
                if (!row.getItemCode().equals(p.itemCode()) || p.qty() == null
                        || p.qty().signum() <= 0 || remain.signum() <= 0) {
                    continue;
                }
                BigDecimal rel = p.qty().min(remain); // 余量护栏
                if (rel.signum() <= 0) {
                    continue;
                }
                BigDecimal left = rel;
                // ① 优先释放到领料行批次（过账引擎行级复核恰好通过）
                if (p.batchNo() != null && !p.batchNo().isEmpty()) {
                    for (Take t : takes) {
                        if (left.signum() <= 0) {
                            break;
                        }
                        if (!p.batchNo().equals(batchOf.get(t.id)) || nvl(t.qty).signum() <= 0) {
                            continue;
                        }
                        BigDecimal step = nvl(t.qty).min(left);
                        restoreRow(t.id, step, row.getItemCode());
                        t.qty = nvl(t.qty).subtract(step);
                        left = left.subtract(step);
                    }
                }
                // ② 其余按 takes 序兜底
                for (Take t : takes) {
                    if (left.signum() <= 0) {
                        break;
                    }
                    if (nvl(t.qty).signum() <= 0) {
                        continue;
                    }
                    BigDecimal step = nvl(t.qty).min(left);
                    restoreRow(t.id, step, row.getItemCode());
                    t.qty = nvl(t.qty).subtract(step);
                    left = left.subtract(step);
                }
                remain = remain.subtract(rel);
                consumed = consumed.add(rel);
            }
            if (consumed.signum() <= 0) {
                continue;
            }
            row.setRemainQty(remain);
            row.setTakesJson(toJson(takes));
            if (remain.signum() == 0) {
                row.setStatus(MrpReservation.ST_CLEARED);
                // 同 releaseByMo：核销清零也要显式释放唯一索引槽位（updateById 吞 null）
                reservationDao.update(null, new LambdaUpdateWrapper<MrpReservation>()
                        .eq(MrpReservation::getId, row.getId())
                        .set(MrpReservation::getRemainQty, remain)
                        .set(MrpReservation::getTakesJson, toJson(takes))
                        .set(MrpReservation::getStatus, MrpReservation.ST_CLEARED)
                        .set(MrpReservation::getActiveFlag, null));
                out.put(row.getItemCode(), consumed);
                log.info("预扣核销 {} {} consumed={} remain={}", moNo, row.getItemCode(), consumed, remain);
                continue;
            }
            reservationDao.updateById(row);
            out.put(row.getItemCode(), consumed);
            log.info("预扣核销 {} {} consumed={} remain={}", moNo, row.getItemCode(), consumed, remain);
        }
        return out;
    }

    @Override
    public Map<String, List<LockedBatch>> lockedBatchesOf(String moNo) {
        Map<String, List<LockedBatch>> out = new LinkedHashMap<>();
        if (moNo == null || moNo.isEmpty()) {
            return out;
        }
        List<MrpReservation> actives = reservationDao.selectList(new LambdaQueryWrapper<MrpReservation>()
                .eq(MrpReservation::getMoNo, moNo)
                .eq(MrpReservation::getStatus, MrpReservation.ST_ACTIVE));
        if (actives.isEmpty()) {
            return out;
        }
        Map<String, String> batchOf = batchOfTakes(actives);
        for (MrpReservation row : actives) {
            for (Take t : fromJson(row.getTakesJson())) {
                if (nvl(t.qty).signum() <= 0) {
                    continue;
                }
                String batch = batchOf.get(t.id);
                if (batch == null) {
                    continue; // 库存行不存在（异常）→ 不占位
                }
                out.computeIfAbsent(row.getItemCode(), k -> new ArrayList<>())
                        .add(new LockedBatch(batch, t.qty));
            }
        }
        return out;
    }

    /** takes 的库存行 id → 批次号映射（一次查询） */
    private Map<String, String> batchOfTakes(List<MrpReservation> rows) {
        List<String> ids = new ArrayList<>();
        for (MrpReservation row : rows) {
            for (Take t : fromJson(row.getTakesJson())) {
                if (t.id != null) {
                    ids.add(t.id);
                }
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (InvStock s : stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .in(InvStock::getId, ids))) {
            out.put(s.getId(), s.getBatchNo() == null ? "" : s.getBatchNo());
        }
        return out;
    }

    /** 单 take 回补（行不存在 → 409 随调用方事务整体回滚） */
    private void restoreRow(String stockId, BigDecimal rel, String itemCode) {
        int upd = stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                .eq(InvStock::getId, stockId)
                .setSql("AVAILABLE_QTY = AVAILABLE_QTY + " + rel.toPlainString()));
        if (upd == 0) {
            throw new ServiceException(409, "预扣核销回补失败（库存行不存在）：" + itemCode);
        }
    }

    @Override
    @Transactional
    public void releaseByMo(String moId, String reason, String operator) {
        List<MrpReservation> actives = reservationDao.selectList(new LambdaQueryWrapper<MrpReservation>()
                .eq(MrpReservation::getMoId, moId)
                .eq(MrpReservation::getStatus, MrpReservation.ST_ACTIVE));
        for (MrpReservation row : actives) {
            BigDecimal remain = nvl(row.getRemainQty());
            if (remain.signum() > 0) {
                restoreTakes(row, remain, "释放");
            }
            // 显式 UpdateWrapper：updateById 的 NOT_NULL 策略会吞掉 setActiveFlag(null)，
            // 导致唯一索引槽位未释放 → 重派再确认撞 UK_MRP_RESV_ACTIVE（5.7 ReportingDbTest 踩出）
            reservationDao.update(null, new LambdaUpdateWrapper<MrpReservation>()
                    .eq(MrpReservation::getId, row.getId())
                    .set(MrpReservation::getRemainQty, BigDecimal.ZERO)
                    .set(MrpReservation::getStatus, MrpReservation.ST_RELEASED)
                    .set(MrpReservation::getActiveFlag, null)
                    .set(MrpReservation::getReleaseReason, reason)
                    .set(MrpReservation::getUpdateBy, operator));
            log.info("预扣释放 {} {} released={} reason={}", row.getMoNo(), row.getItemCode(), remain, reason);
        }
    }

    @Override
    public List<MrpReservation> activeOf(String moId) {
        return reservationDao.selectList(new LambdaQueryWrapper<MrpReservation>()
                .eq(MrpReservation::getMoId, moId)
                .eq(MrpReservation::getStatus, MrpReservation.ST_ACTIVE));
    }

    @Override
    public List<MrpReservation> historyOf(String moId) {
        return reservationDao.selectList(new LambdaQueryWrapper<MrpReservation>()
                .eq(MrpReservation::getMoId, moId)
                .orderByDesc(MrpReservation::getCreateDate));
    }

    /**
     * 按 takes 逐行回补可用量（回补目标 = 锁所在行：行余量恒 ≥ take 剩余，无需 cap；
     * takes 为空 = OFF 记账行 → 只减余量不动库存）。回补后重写 takesJson（支持部分核销的续扣）。
     */
    private void restoreTakes(MrpReservation row, BigDecimal n, String phase) {
        List<Take> takes = fromJson(row.getTakesJson());
        if (takes.isEmpty()) {
            return;
        }
        BigDecimal left = n;
        for (Take t : takes) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal have = nvl(t.qty);
            if (have.signum() <= 0) {
                continue;
            }
            BigDecimal rel = have.min(left);
            int upd = stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                    .eq(InvStock::getId, t.id)
                    .setSql("AVAILABLE_QTY = AVAILABLE_QTY + " + rel.toPlainString()));
            if (upd == 0) {
                // 核销/释放失败随调用方事务整体回滚（spec material-issue MODIFIED 步骤④）
                throw new ServiceException(409,
                        "预扣" + phase + "回补失败（库存行不存在）：" + row.getItemCode());
            }
            t.qty = have.subtract(rel);
            left = left.subtract(rel);
        }
        row.setTakesJson(toJson(takes));
    }

    // ---------- helpers ----------

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String toJson(List<Take> takes) {
        try {
            return objectMapper.writeValueAsString(takes);
        } catch (JsonProcessingException e) {
            throw new ServiceException(500, "预扣明细序列化失败：" + e.getMessage());
        }
    }

    private List<Take> fromJson(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) {
            return new ArrayList<>();
        }
        try {
            Take[] arr = objectMapper.readValue(json, Take[].class);
            return new ArrayList<>(java.util.Arrays.asList(arr));
        } catch (JsonProcessingException e) {
            throw new ServiceException(500, "预扣明细解析失败：" + e.getMessage());
        }
    }

    /** 逐库存行实扣明细（Jackson 公有字段直序列化） */
    public static class Take {
        public String id;
        public BigDecimal qty;

        public Take() {
        }

        public Take(String id, BigDecimal qty) {
            this.id = id;
            this.qty = qty;
        }
    }
}
