package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvBatchDao;
import com.erp.dao.inv.InvDocTypeDao;
import com.erp.dao.inv.InvSerialDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.inv.InvTransactionDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.entity.inv.InvBatch;
import com.erp.entity.inv.InvDocType;
import com.erp.entity.inv.InvSerial;
import com.erp.entity.inv.InvStock;
import com.erp.entity.inv.InvTransaction;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.sd.Reservation;
import com.erp.ops.OutboxPublisher;
import com.erp.service.inv.StockPostingEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 通用出入库过账引擎实现（spec stock-posting-engine，design D2~D4/D3.5）。
 * IN：锁行 → 批次台账联动建档（效期：行值 ?? 入库日+物料保质期推算）→ 数量累加/首插（INBOUND_DATE 当日、补货不刷新）
 *      → 目标列（AVAILABLE 或让步 QC）→ 流水。
 * OUT：组内 FOR UPDATE 锁行（锁序 wh+item+inbound+create 统一）→ 缺省分配 FIFO+效期（同日在手效期升序）
 *      / 指定批次直扣 / qcFirst 先 QC 后 AVAILABLE → 负库存守卫（可用=AVAILABLE−ACTIVE预留）→ 流水。
 * 同事务写库存变动事件（outbox，BR-4.4-13）。
 */
@Slf4j
@Service
public class StockPostingEngineImpl implements StockPostingEngine {

    private static final DateTimeFormatter TXN_DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private final InvDocTypeDao docTypeDao;
    private final InvStockDao stockDao;
    private final InvBatchDao batchDao;
    private final InvTransactionDao txnDao;
    private final InvSerialDao serialDao;
    private final ReservationDao reservationDao;
    private final MdmItemDao itemDao;
    private final OutboxPublisher outboxPublisher;
    private final com.erp.service.SysParamService sysParamService;
    private final com.erp.dao.inv.InvCountTaskDao countTaskDao;
    private final com.erp.dao.inv.InvCountLineDao countLineDao;

    /** 盘点调整类型（spec count-management：豁免盘点锁校验——调整自身必须能过） */
    private static final java.util.Set<String> COUNT_ADJUST_TYPES =
            java.util.Set.of("ADJUST_IN", "ADJUST_OUT");

    public StockPostingEngineImpl(InvDocTypeDao docTypeDao, InvStockDao stockDao,
                                  InvBatchDao batchDao, InvTransactionDao txnDao,
                                  InvSerialDao serialDao, ReservationDao reservationDao,
                                  MdmItemDao itemDao, OutboxPublisher outboxPublisher,
                                  com.erp.service.SysParamService sysParamService,
                                  com.erp.dao.inv.InvCountTaskDao countTaskDao,
                                  com.erp.dao.inv.InvCountLineDao countLineDao) {
        this.docTypeDao = docTypeDao;
        this.stockDao = stockDao;
        this.batchDao = batchDao;
        this.txnDao = txnDao;
        this.serialDao = serialDao;
        this.reservationDao = reservationDao;
        this.itemDao = itemDao;
        this.outboxPublisher = outboxPublisher;
        this.sysParamService = sysParamService;
        this.countTaskDao = countTaskDao;
        this.countLineDao = countLineDao;
    }

    // ================= 过账入口 =================

    @Override
    @Transactional
    public Result post(Request req) {
        if (req == null || req.lines == null || req.lines.isEmpty()) {
            throw new ServiceException(422, "过账行不能为空");
        }
        InvDocType type = requireEnabledType(req.typeCode);
        // 校验链 ⑤（count-management BR-4.4-36）：行目标仓位在盘点锁中 → 422；
        // ADJUST_IN/ADJUST_OUT 豁免（调整自身必须能过）。按请求全部行的仓位集合一次查询防 N+1。
        checkCountLock(req);
        boolean isIn = InvDocType.DIR_IN.equals(type.getDirection());
        Result out = new Result();
        out.allocations = new ArrayList<>();
        out.txnNos = new ArrayList<>();

        if (isIn) {
            postIn(req, type, out);
        } else {
            postOut(req, type, out);
        }

        // 库存变动事件（BR-4.4-13《库存变动事件日志》，同事务 outbox）
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("typeCode", type.getTypeCode());
        extra.put("direction", type.getDirection());
        extra.put("lineCount", req.lines.size());
        // 同一单据可拆多次 post（逐行调用）——source 带随机段避免幂等键碰撞（C-0-06 教训）
        String source = req.bizDocNo + ":" + java.util.UUID.randomUUID().toString()
                .substring(0, 8);
        outboxPublisher.publishSourced("STOCK.MOVED", source, 1, null,
                "库存变动：" + type.getTypeName() + " " + req.bizDocNo, extra, "inv-engine");
        return out;
    }

    // ================= IN =================

    /**
     * 校验链 ⑤：行显式目标仓位（IN 落位 / OUT 指定仓位）存在 COUNTING 盘点任务 → 422
     * 「仓位盘点冻结中」（BR-4.4-36）；ADJUST 类型豁免（入口已过滤，本方法只查一次集合）。
     * 缺省分配行（bin 空）不在本环拦——由 postOut 挑序剔除锁位行兜底。
     */
    private void checkCountLock(Request req) {
        if (COUNT_ADJUST_TYPES.contains(req.typeCode)) {
            return;
        }
        // 仓库 → 显式仓位集合
        Map<String, java.util.Set<String>> binsByWh = new LinkedHashMap<>();
        for (Line line : req.lines) {
            String bin = str(line.binCode).trim();
            if (bin.isEmpty()) {
                continue;
            }
            binsByWh.computeIfAbsent(str(line.warehouseCode), k -> new java.util.LinkedHashSet<>())
                    .add(bin);
        }
        for (Map.Entry<String, java.util.Set<String>> e : binsByWh.entrySet()) {
            java.util.Set<String> locked = loadCountLockedBins(e.getKey());
            if (locked.isEmpty()) {
                continue;
            }
            for (String bin : e.getValue()) {
                if (locked.contains(bin)) {
                    throw new ServiceException(422, "仓位盘点冻结中：" + e.getKey()
                            + " / " + bin + "（盘点任务进行中，紧急出入库请联系仓库主管申请临时解冻）");
                }
            }
        }
    }

    /**
     * 某仓库 COUNTING 盘点任务的锁仓位集合（每仓 2 次查询：任务→行；'' 未分配位永不锁）。
     * postOut 挑序与显式校验共用。
     */
    private java.util.Set<String> loadCountLockedBins(String warehouseCode) {
        List<com.erp.entity.inv.InvCountTask> tasks = countTaskDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.inv.InvCountTask>()
                        .eq(com.erp.entity.inv.InvCountTask::getWarehouseCode, warehouseCode)
                        .eq(com.erp.entity.inv.InvCountTask::getStatus,
                                com.erp.entity.inv.InvCountTask.ST_COUNTING));
        if (tasks.isEmpty()) {
            return java.util.Set.of();
        }
        List<String> taskIds = new ArrayList<>();
        for (com.erp.entity.inv.InvCountTask t : tasks) {
            taskIds.add(t.getId());
        }
        java.util.Set<String> bins = new java.util.HashSet<>();
        for (com.erp.entity.inv.InvCountLine l : countLineDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.inv.InvCountLine>()
                        .in(com.erp.entity.inv.InvCountLine::getTaskId, taskIds))) {
            String bin = str(l.getBinCode());
            if (!bin.isEmpty()) {
                bins.add(bin);
            }
        }
        return bins;
    }

    private void postIn(Request req, InvDocType type, Result out) {
        List<IndexedLine> ordered = new ArrayList<>();
        for (int i = 0; i < req.lines.size(); i++) {
            ordered.add(new IndexedLine(i, req.lines.get(i)));
        }
        // 锁序统一：wh + item + batch + bin（design D2 位行锁序）
        ordered.sort(Comparator
                .comparing((IndexedLine x) -> str(x.line.warehouseCode))
                .thenComparing(x -> str(x.line.itemCode))
                .thenComparing(x -> str(x.line.batchNo))
                .thenComparing(x -> str(x.line.binCode)));

        for (IndexedLine il : ordered) {
            StockPostingEngine.Line line = il.line;
            if (line.qty == null || line.qty.signum() <= 0) {
                throw new ServiceException(422, "过账数量必须大于 0");
            }
            if (isBlank(line.warehouseCode)) {
                throw new ServiceException(422, "仓库必填");
            }
            MdmItem item = requireItem(line.itemCode);
            String batch = line.batchNo == null ? "" : line.batchNo.trim();
            // IN 目标仓位：空/null = 未分配虚拟位 ''（bin-assignment 强前置由域服务把关）
            String bin = line.binCode == null ? "" : line.binCode.trim();
            boolean needSerial = type.getNeedSerial() != null && type.getNeedSerial() == 1
                    || (item != null && "1".equals(item.getSerialFlag()));

            // 序列判重（C-4.4-02 缺失拒 / BR-4.11-15 重复阻断）
            checkSerialsIn(line, needSerial);

            // 批次台账联动建档（batch-master；效期 D3.5 取值序）
            LocalDate expiry = null;
            if (!batch.isEmpty() && type.getNeedBatch() != null && type.getNeedBatch() == 1) {
                expiry = ensureBatchLedger(req, line, item, batch);
            }

            // 锁行（四维）→ 数量变更
            InvStock row = lockRow(line.warehouseCode, line.itemCode, batch, bin);
            BigDecimal qty = line.qty;
            if (row == null) {
                InvStock fresh = new InvStock();
                fresh.setWarehouseCode(line.warehouseCode);
                fresh.setItemCode(line.itemCode);
                fresh.setItemName(isBlank(line.itemName)
                        ? (item == null ? line.itemCode : item.getItemName()) : line.itemName);
                fresh.setBatchNo(batch);
                fresh.setBinCode(bin);
                fresh.setQty(qty);
                if (line.intoQc) {
                    fresh.setQcQty(qty);
                    fresh.setAvailableQty(BigDecimal.ZERO);
                } else {
                    fresh.setQcQty(BigDecimal.ZERO);
                    fresh.setAvailableQty(qty);
                }
                fresh.setFinQty(BigDecimal.ZERO);
                fresh.setInboundDate(LocalDate.now());   // 首插记当日（FIFO 地基，行=位行）
                try {
                    stockDao.insert(fresh);
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    throw new ServiceException(409, "库存行并发冲突，请重试："
                            + line.itemCode + "/" + batch + "/" + bin);
                }
                out.txnNos.add(writeTxn(InvTransaction.DIR_IN, type, req, line, batch, bin,
                        qty, BigDecimal.ZERO, qty));
            } else {
                BigDecimal before = nvl(row.getQty());
                LambdaUpdateWrapper<InvStock> uw = new LambdaUpdateWrapper<InvStock>()
                        .eq(InvStock::getId, row.getId())
                        .setSql("QTY = QTY + " + strip(qty));
                if (line.intoQc) {
                    uw.setSql("QC_QTY = QC_QTY + " + strip(qty));
                } else {
                    uw.setSql("AVAILABLE_QTY = AVAILABLE_QTY + " + strip(qty));
                }
                // 补货不刷新 INBOUND_DATE（不在 update 中触碰该列）
                if (stockDao.update(null, uw) == 0) {
                    throw new ServiceException(409, "库存行更新冲突，请重试："
                            + line.itemCode + "/" + batch + "/" + bin);
                }
                out.txnNos.add(writeTxn(InvTransaction.DIR_IN, type, req, line, batch, bin,
                        qty, before, before.add(qty)));
            }

            StockPostingEngine.Alloc a = StockPostingEngine.Alloc.of(il.index, batch, qty);
            a.binCode = bin;
            a.expiryDate = expiry;
            a.itemName = isBlank(line.itemName)
                    ? (item == null ? line.itemCode : item.getItemName()) : line.itemName;
            out.allocations.add(a);
        }
    }

    /** IN 序列判重（任一在册即阻断——含历史；缺失拒单见 needSerial 分支） */
    private void checkSerialsIn(StockPostingEngine.Line line, boolean needSerial) {
        List<String> serials = line.serials;
        if ((serials == null || serials.isEmpty())) {
            if (needSerial) {
                throw new ServiceException(422, "请录入有效序列号（C-4.4-02）：" + line.itemCode);
            }
            return;
        }
        List<String> dup = new ArrayList<>();
        for (String sn : serials) {
            if (isBlank(sn)) {
                continue;
            }
            InvSerial hit = serialDao.selectOne(new LambdaQueryWrapper<InvSerial>()
                    .eq(InvSerial::getSerialNo, sn.trim()));
            if (hit != null) {
                dup.add(sn.trim() + "（批次 " + str(hit.getBatchNo()) + "）");
            }
        }
        if (!dup.isEmpty()) {
            throw new ServiceException(422, "序列号重复，阻断入库：" + String.join("、", dup));
        }
    }

    /** 批次台账联动建档；返回生效效期（建档案例/既有不改写，design D3.5） */
    private LocalDate ensureBatchLedger(Request req, StockPostingEngine.Line line,
                                        MdmItem item, String batch) {
        InvBatch exist = batchDao.selectOne(new LambdaQueryWrapper<InvBatch>()
                .eq(InvBatch::getItemCode, line.itemCode)
                .eq(InvBatch::getBatchNo, batch));
        boolean batchManaged = item != null && "1".equals(item.getBatchFlag());
        LocalDate expiry = resolveExpiry(line, item);
        if (exist != null) {
            return exist.getExpiryDate();   // 既有批次不被联动改写（手工优先存在）
        }
        if (batchManaged && expiry == null) {
            throw new ServiceException(422, "批次管理物料必填有效期（BR-4.1-08）：" + line.itemCode);
        }
        InvBatch b = new InvBatch();
        b.setBatchNo(batch);
        b.setItemCode(line.itemCode);
        b.setItemName(isBlank(line.itemName)
                ? (item == null ? line.itemCode : item.getItemName()) : line.itemName);
        b.setExpiryDate(expiry);
        b.setSupplierBatchNo(line.supplierBatchNo);
        b.setSourceDocNo(req.bizDocNo);
        b.setStatus("1");
        boolean inferred = line.expiryDate == null && expiry != null;
        b.setRemark("过账联动建档：" + req.bizDocNo + (inferred ? "（效期按保质期推算）" : ""));
        try {
            batchDao.insert(b);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发同批建档：读回既有即可
            return batchDao.selectOne(new LambdaQueryWrapper<InvBatch>()
                    .eq(InvBatch::getItemCode, line.itemCode)
                    .eq(InvBatch::getBatchNo, batch)).getExpiryDate();
        }
        return expiry;
    }

    /** 效期取值序：行值 → 入库日+shelfLifeDays 推算 → null（design D3.5） */
    private LocalDate resolveExpiry(StockPostingEngine.Line line, MdmItem item) {
        if (line.expiryDate != null) {
            return line.expiryDate;
        }
        if (item != null && "1".equals(item.getBatchFlag()) && item.getShelfLifeDays() != null
                && item.getShelfLifeDays() > 0) {
            return LocalDate.now().plusDays(item.getShelfLifeDays());
        }
        return null;
    }

    // ================= OUT =================

    private void postOut(Request req, InvDocType type, Result out) {
        // 分组处理 + 锁序统一（wh+item 排序后 FOR UPDATE，design D4）
        Map<String, List<IndexedLine>> groups = new LinkedHashMap<>();
        for (int i = 0; i < req.lines.size(); i++) {
            StockPostingEngine.Line line = req.lines.get(i);
            String key = str(line.warehouseCode) + "|" + str(line.itemCode);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new IndexedLine(i, line));
        }
        List<String> groupKeys = new ArrayList<>(groups.keySet());
        groupKeys.sort(Comparator.naturalOrder());

        for (String key : groupKeys) {
            List<IndexedLine> lines = groups.get(key);
            StockPostingEngine.Line first = lines.get(0).line;
            if (isBlank(first.warehouseCode)) {
                throw new ServiceException(422, "仓库必填");
            }
            MdmItem item = requireItem(first.itemCode);

            // 锁定该维度全部位行（锁序：batch + bin 升序，design D2）
            List<InvStock> locked = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                    .eq(InvStock::getWarehouseCode, first.warehouseCode)
                    .eq(InvStock::getItemCode, first.itemCode)
                    .orderByAsc(InvStock::getBatchNo)
                    .orderByAsc(InvStock::getBinCode)
                    .last("FOR UPDATE"));
            // 挑序剔除被盘点仓位位行（count-management design D2）：分配阶段即避开，
            // 不落"分配成功后才 422 盘点锁"；ADJUST 类型豁免。'' 未分配位永不锁。
            boolean countLockExcluded = false;
            if (!COUNT_ADJUST_TYPES.contains(type.getTypeCode())) {
                java.util.Set<String> countLockedBins = loadCountLockedBins(first.warehouseCode);
                if (!countLockedBins.isEmpty()) {
                    java.util.Iterator<InvStock> it = locked.iterator();
                    while (it.hasNext()) {
                        InvStock r = it.next();
                        if (countLockedBins.contains(str(r.getBinCode()))) {
                            it.remove();
                            countLockExcluded = true;
                        }
                    }
                }
            }
            // 行状态（列值 + 锁后读取的批次级 ACTIVE 预留；预留不落位行——按批次预算统一封顶，
            // 跨位多行合计只扣一次，避免批次预留被重复减）
            Map<String, RowState> state = new HashMap<>();
            Map<String, BigDecimal> batchAvail = new HashMap<>();
            Map<String, BigDecimal> batchReserved = new HashMap<>();
            for (InvStock r : locked) {
                RowState st = new RowState();
                st.row = r;
                st.qty = nvl(r.getQty());
                st.avail = nvl(r.getAvailableQty());
                st.qc = nvl(r.getQcQty());
                st.batchKey = str(r.getBatchNo());
                batchAvail.merge(st.batchKey, st.avail, BigDecimal::add);
                batchReserved.putIfAbsent(st.batchKey,
                        nvl(reservationDao.sumActiveOnBatch(first.warehouseCode,
                                first.itemCode, st.batchKey)));
                state.put(r.getId(), st);
            }
            // FEFO 挑选序：inbound → 效期桶 → create；平局保持锁序（batch,bin 升序——
            // 稳定排序，DB 端为锁序、单测 mock 为返回序，不引入额外字母序决胜）
            Map<String, LocalDate> expiryByBatch = loadExpiry(first.itemCode, locked);
            // 效期锁定（spec 校验链 ④ / BR-4.4-20）：指定批次硬拦截、缺省分配剔池
            Map<String, Boolean> lockByBatch = loadExpiryLock(first.itemCode, locked);
            List<RowState> pickOrder = new ArrayList<>();
            for (InvStock r : locked) {
                pickOrder.add(state.get(r.getId()));
            }
            pickOrder.sort(Comparator
                    .comparing((RowState st) -> st.row.getInboundDate() == null
                            ? LocalDate.of(1970, 1, 1) : st.row.getInboundDate())
                    .thenComparing(st -> expiryByBatch.getOrDefault(st.batchKey,
                            LocalDate.of(2999, 12, 31)))
                    .thenComparingLong(st -> st.row.getCreateDate() == null ? 0
                            : st.row.getCreateDate().toLocalDate().toEpochDay()));
            // 批次预算封顶：每批可扣 = Σ行可用 − 批次预留，按挑序逐行封顶（basis 上限）
            Map<String, BigDecimal> batchAssigned = new HashMap<>();
            for (RowState st : pickOrder) {
                BigDecimal budget = batchAvail.get(st.batchKey)
                        .subtract(batchReserved.get(st.batchKey));
                BigDecimal remain = budget.subtract(
                        batchAssigned.getOrDefault(st.batchKey, BigDecimal.ZERO));
                if (remain.signum() < 0) {
                    remain = BigDecimal.ZERO;
                }
                st.capped = st.avail.min(remain);
                batchAssigned.merge(st.batchKey, st.capped, BigDecimal::add);
            }

            for (IndexedLine il : lines) {
                StockPostingEngine.Line line = il.line;
                if (line.qty == null || line.qty.signum() <= 0) {
                    throw new ServiceException(422, "过账数量必须大于 0");
                }
                boolean needSerial = type.getNeedSerial() != null && type.getNeedSerial() == 1
                        || (item != null && "1".equals(item.getSerialFlag()));
                checkSerialsOut(line, needSerial);

                String specified = line.batchNo == null || line.batchNo.trim().isEmpty()
                        ? null : line.batchNo.trim();
                if (specified != null) {
                    // 校验链 ④：指定批次已效期锁定 → 422 硬阻断（C-4.4-03，无库存与流水变动）
                    if (Boolean.TRUE.equals(lockByBatch.get(specified))) {
                        throw new ServiceException(422, "批次已效期锁定，禁止正常出库："
                                + line.itemCode + " / " + specified);
                    }
                    // 指定批次：组内该批次全部位行按挑序逐行扣减（跨位拆行）
                    List<RowState> batchRows = rowsOfBatch(pickOrder, specified);
                    if (batchRows.isEmpty()) {
                        // 指定批次全部位行都在被盘点锁仓位（挑序已剔除）→ 盘点锁文案而非"批次不存在"
                        throw new ServiceException(422, countLockExcluded
                                ? "仓位盘点冻结中，可用未锁库存不足：" + line.itemCode
                                    + " 批次 " + specified
                                : "库存批次不存在：" + line.itemCode + " / " + specified);
                    }
                    // 指定仓位（line.binCode 非空，4.6.3 回写/行级人工指定，design D3）：
                    // 收窄到单个位行——不足 422，不静默跨位补（spec OUT 批次分配 指定仓位场景）
                    String wantBin = line.binCode == null ? "" : line.binCode.trim();
                    if (!wantBin.isEmpty()) {
                        List<RowState> binRows = new ArrayList<>();
                        for (RowState st : batchRows) {
                            if (wantBin.equals(str(st.row.getBinCode()))) {
                                binRows.add(st);
                            }
                        }
                        if (binRows.isEmpty()) {
                            throw new ServiceException(422, "指定仓位无库存行："
                                    + line.itemCode + " 批次 " + specified + " 仓位 " + wantBin);
                        }
                        batchRows = binRows;
                    }
                    out.allocations.addAll(deductBatch(req, type, line, il.index,
                            batchRows, specified, line.qty));
                } else if (line.qcFirst) {
                    throw new ServiceException(422, "核销锁定量的出库须指定批次");
                } else {
                    // 缺省分配：FIFO+效期（位行序），扣减可用=AVAILABLE−ACTIVE预留（批次预算）；
                    // 效期锁定批次剔池（不参与分配、不计入可用，BR-4.4-20）
                    BigDecimal need = line.qty;
                    BigDecimal totalBasis = BigDecimal.ZERO;
                    for (RowState st : pickOrder) {
                        if (Boolean.TRUE.equals(lockByBatch.get(st.batchKey))) {
                            continue;
                        }
                        BigDecimal basis = st.basis();
                        totalBasis = totalBasis.add(basis.max(BigDecimal.ZERO));
                        if (need.signum() <= 0 || basis.signum() <= 0) {
                            continue;
                        }
                        BigDecimal take = basis.min(need);
                        out.allocations.add(deductRow(req, type, line, il.index,
                                st, take));
                        need = need.subtract(take);
                    }
                    if (need.signum() > 0) {
                        // 有位行因盘点锁被剔除且未锁库存不够 → 盘点锁文案细化（design D2）
                        throw new ServiceException(422, countLockExcluded
                                ? "仓位盘点冻结中，可用未锁库存不足，当前可用量为 "
                                    + strip(totalBasis.max(BigDecimal.ZERO)) + "："
                                    + line.itemCode + "（仓库 " + line.warehouseCode
                                    + "，需求 " + strip(line.qty) + "）"
                                : "库存不足，当前可用量为 "
                                    + strip(totalBasis.max(BigDecimal.ZERO)) + "："
                                    + line.itemCode + "（仓库 " + line.warehouseCode
                                    + "，需求 " + strip(line.qty) + "）");
                    }
                }
            }
        }
    }

    /** 按挑序取某批次的全部位行 */
    private List<RowState> rowsOfBatch(List<RowState> pickOrder, String batch) {
        List<RowState> rows = new ArrayList<>();
        for (RowState st : pickOrder) {
            if (batch.equals(str(st.row.getBatchNo()))) {
                rows.add(st);
            }
        }
        return rows;
    }

    /**
     * 指定批次扣减（跨位拆行）：批次级守卫（qcFirst 先核销 QC 再扣可用），
     * 行级按挑序逐行执行，一行一条位级流水；qcFirst 时 QC 与 AVAILABLE 可分落不同位行，
     * 各行恒等独立成立。
     */
    private List<StockPostingEngine.Alloc> deductBatch(Request req, InvDocType type,
                                                       StockPostingEngine.Line line, int lineIndex,
                                                       List<RowState> batchRows, String batch,
                                                       BigDecimal take) {
        BigDecimal qcSum = BigDecimal.ZERO;
        BigDecimal basisSum = BigDecimal.ZERO;
        for (RowState st : batchRows) {
            qcSum = qcSum.add(st.qc);
            basisSum = basisSum.add(st.basis());
        }
        BigDecimal qcNeed = line.qcFirst ? take.min(qcSum) : BigDecimal.ZERO;
        BigDecimal avNeed = take.subtract(qcNeed);
        if (basisSum.compareTo(avNeed) < 0) {
            throw new ServiceException(422, "库存不足，当前可用量为 "
                    + strip(basisSum.max(BigDecimal.ZERO)) + "："
                    + line.itemCode + " 批次 " + batch + "（本批扣量 " + strip(take) + "）");
        }
        if (line.qcFirst && qcNeed.signum() > 0 && qcSum.compareTo(qcNeed) < 0) {
            throw new ServiceException(422, "锁定量核销超出：" + line.itemCode + " 批次 " + batch);
        }

        List<StockPostingEngine.Alloc> res = new ArrayList<>();
        // 同一行的 QC 核销与可用扣减合并为一次 update / 一条位级流水（与单行旧行为一致）
        BigDecimal qcRem = qcNeed;
        BigDecimal avRem = avNeed;
        for (RowState st : batchRows) {
            if (qcRem.signum() <= 0 && avRem.signum() <= 0) {
                break;
            }
            BigDecimal rowQc = BigDecimal.ZERO;
            if (qcRem.signum() > 0 && st.qc.signum() > 0) {
                rowQc = st.qc.min(qcRem);
            }
            BigDecimal rowAv = BigDecimal.ZERO;
            if (avRem.signum() > 0 && st.basis().signum() > 0) {
                rowAv = st.basis().min(avRem);
            }
            if (rowQc.signum() <= 0 && rowAv.signum() <= 0) {
                continue;
            }
            res.add(deductRowParts(req, type, line, lineIndex, st, batch, rowQc, rowAv));
            qcRem = qcRem.subtract(rowQc);
            avRem = avRem.subtract(rowAv);
        }
        return res;
    }

    /** 缺省分配单行扣减（take 已按批次预算封顶，design D2） */
    private StockPostingEngine.Alloc deductRow(Request req, InvDocType type,
                                               StockPostingEngine.Line line, int lineIndex,
                                               RowState st, BigDecimal take) {
        return deductRowParts(req, type, line, lineIndex, st, str(st.row.getBatchNo()),
                BigDecimal.ZERO, take);
    }

    /**
     * 位行级扣减原语：rowQcTake（QC_QTY 减）与 rowAvTake（AVAILABLE_QTY 减），
     * 行扣量 = 两者之和；同一 update 原子扣 QC/AVAIL/QTY，位级 before/after 流水。
     */
    private StockPostingEngine.Alloc deductRowParts(Request req, InvDocType type,
                                                    StockPostingEngine.Line line, int lineIndex,
                                                    RowState st, String batch,
                                                    BigDecimal rowQcTake, BigDecimal rowAvTake) {
        BigDecimal rowTake = rowQcTake.add(rowAvTake);
        if (rowTake.signum() <= 0) {
            throw new ServiceException(422, "扣减数量必须大于 0");
        }
        if (rowAvTake.signum() > 0 && st.avail.compareTo(rowAvTake) < 0) {
            throw new ServiceException(422, "库存不足，当前可用量为 "
                    + strip(st.avail.max(BigDecimal.ZERO)) + "："
                    + line.itemCode + " 批次 " + batch);
        }
        if (rowQcTake.signum() > 0 && st.qc.compareTo(rowQcTake) < 0) {
            throw new ServiceException(422, "锁定量核销超出：" + line.itemCode + " 批次 " + batch);
        }

        LambdaUpdateWrapper<InvStock> uw = new LambdaUpdateWrapper<InvStock>()
                .eq(InvStock::getId, st.row.getId());
        if (rowQcTake.signum() > 0) {
            uw.ge(InvStock::getQcQty, rowQcTake)
              .setSql("QC_QTY = QC_QTY - " + strip(rowQcTake));
        }
        if (rowAvTake.signum() > 0) {
            // 行级守卫（批次预算已在挑序封顶，锁内执行）
            uw.ge(InvStock::getAvailableQty, rowAvTake)
              .setSql("AVAILABLE_QTY = AVAILABLE_QTY - " + strip(rowAvTake));
        }
        uw.setSql("QTY = QTY - " + strip(rowTake));
        if (stockDao.update(null, uw) == 0) {
            throw new ServiceException(409, "库存并发冲突，请重试："
                    + line.itemCode + " 批次 " + batch + " 仓位 " + str(st.row.getBinCode()));
        }
        BigDecimal before = st.qty;
        st.qty = st.qty.subtract(rowTake);
        st.avail = st.avail.subtract(rowAvTake);
        st.qc = st.qc.subtract(rowQcTake);

        String bin = str(st.row.getBinCode());
        writeTxn(InvTransaction.DIR_OUT, type, req, line, batch, bin,
                rowTake, before, st.qty);
        StockPostingEngine.Alloc a = StockPostingEngine.Alloc.of(lineIndex, batch, rowTake);
        a.binCode = bin;
        a.inboundDate = st.row.getInboundDate();
        a.itemName = st.row.getItemName();
        return a;
    }

    private void checkSerialsOut(StockPostingEngine.Line line, boolean needSerial) {
        List<String> serials = line.serials;
        if (serials == null || serials.isEmpty()) {
            if (needSerial) {
                throw new ServiceException(422, "请录入有效序列号（C-4.4-02）：" + line.itemCode);
            }
            return;
        }
        List<String> bad = new ArrayList<>();
        for (String sn : serials) {
            if (isBlank(sn)) {
                continue;
            }
            InvSerial row = serialDao.selectOne(new LambdaQueryWrapper<InvSerial>()
                    .eq(InvSerial::getSerialNo, sn.trim()));
            boolean usable = row != null
                    && line.itemCode.equals(row.getItemCode())
                    && InvSerial.ST_IN_STOCK.equals(row.getStatus());
            if (!usable) {
                bad.add(sn.trim());
            }
        }
        if (!bad.isEmpty()) {
            throw new ServiceException(422, "序列号不可用（不在可用清单，BR-4.4-22）："
                    + String.join("、", bad));
        }
    }

    // ================= 缺省分配 dry-run（创建期配批） =================

    @Override
    public List<Alloc> allocate(String warehouseCode, String itemCode, BigDecimal qty) {
        if (isBlank(warehouseCode) || isBlank(itemCode) || qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "分配参数必填且数量大于 0");
        }
        List<InvStock> rows = new ArrayList<>(stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, warehouseCode)
                .eq(InvStock::getItemCode, itemCode)
                .orderByAsc(InvStock::getInboundDate)
                .orderByAsc(InvStock::getCreateDate)));
        Map<String, LocalDate> expiryByBatch = loadExpiry(itemCode, rows);
        // 效期锁定剔池（BR-4.4-20）：配批/试算与过账缺省分配同口径剔除锁定批次
        Map<String, Boolean> lockByBatch = loadExpiryLock(itemCode, rows);
        rows.sort(Comparator
                .comparing((InvStock r) -> r.getInboundDate() == null
                        ? LocalDate.of(1970, 1, 1) : r.getInboundDate())
                .thenComparing(r -> expiryByBatch.getOrDefault(str(r.getBatchNo()),
                        LocalDate.of(2999, 12, 31)))
                .thenComparingLong(r -> r.getCreateDate() == null ? 0
                        : r.getCreateDate().toLocalDate().toEpochDay()));

        // 批次预算：每批可扣 = Σ行可用 − 批次预留（预留批次级，只扣一次，不按行重复减）
        Map<String, BigDecimal> batchAvail = new HashMap<>();
        Map<String, BigDecimal> batchReserved = new HashMap<>();
        for (InvStock r : rows) {
            String b = str(r.getBatchNo());
            batchAvail.merge(b, nvl(r.getAvailableQty()), BigDecimal::add);
            batchReserved.putIfAbsent(b, nvl(reservationDao.sumActiveOnBatch(
                    warehouseCode, itemCode, b)));
        }

        List<Alloc> out = new ArrayList<>();
        BigDecimal need = qty;
        BigDecimal totalBasis = BigDecimal.ZERO;
        Map<String, BigDecimal> assigned = new HashMap<>();
        for (InvStock r : rows) {
            String b = str(r.getBatchNo());
            if (Boolean.TRUE.equals(lockByBatch.get(b))) {
                continue;   // 锁定批次不参与配批、不计入可用（缺口口径同步剔除）
            }
            BigDecimal budget = batchAvail.get(b).subtract(batchReserved.get(b));
            BigDecimal remain = budget.subtract(assigned.getOrDefault(b, BigDecimal.ZERO));
            BigDecimal basis = nvl(r.getAvailableQty()).min(
                    remain.signum() > 0 ? remain : BigDecimal.ZERO);
            totalBasis = totalBasis.add(basis);
            if (need.signum() <= 0 || basis.signum() <= 0) {
                continue;
            }
            BigDecimal take = basis.min(need);
            assigned.merge(b, take, BigDecimal::add);
            Alloc a = Alloc.of(0, b, take);
            a.binCode = str(r.getBinCode());
            a.inboundDate = r.getInboundDate();
            a.expiryDate = expiryByBatch.get(b);
            a.itemName = r.getItemName();
            out.add(a);
            need = need.subtract(take);
        }
        if (need.signum() > 0) {
            // material-issue 创建预检语义：缺口 422（提示性，不预占）
            throw new ServiceException(422, "可用量不足：" + itemCode + " 缺口 "
                    + strip(need) + "，可用合计 " + strip(totalBasis));
        }
        return out;
    }

    // ================= helpers =================

    private InvDocType requireEnabledType(String code) {
        InvDocType type = isBlank(code) ? null : docTypeDao.selectByCode(code);
        if (type == null) {
            throw new ServiceException(422, "出入库业务类型不存在：" + code);
        }
        if (type.getEnabled() == null || type.getEnabled() != 1) {
            throw new ServiceException(422, "业务类型已停用：" + type.getTypeName());
        }
        return type;
    }

    /** 物料读取（mdm 缺行不阻断：batchFlag/serialFlag 校验跳过，兼容测试夹具无主数据行） */
    private MdmItem requireItem(String itemCode) {
        if (isBlank(itemCode)) {
            throw new ServiceException(422, "物料必填");
        }
        return itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, itemCode));
    }

    private InvStock lockRow(String wh, String item, String batch, String bin) {
        return stockDao.selectOne(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, wh)
                .eq(InvStock::getItemCode, item)
                .eq(InvStock::getBatchNo, batch)
                .eq(InvStock::getBinCode, bin == null ? "" : bin)
                .last("FOR UPDATE"));
    }

    private Map<String, LocalDate> loadExpiry(String itemCode, List<InvStock> rows) {
        Map<String, LocalDate> map = new HashMap<>();
        List<String> batches = new ArrayList<>();
        for (InvStock r : rows) {
            String b = str(r.getBatchNo());
            if (!b.isEmpty() && !batches.contains(b)) {
                batches.add(b);
            }
        }
        if (batches.isEmpty()) {
            return map;
        }
        List<InvBatch> ledgers = batchDao.selectList(new LambdaQueryWrapper<InvBatch>()
                .eq(InvBatch::getItemCode, itemCode)
                .in(InvBatch::getBatchNo, batches));
        for (InvBatch b : ledgers) {
            // 效期可空（无保质期物料）：跳过 null，避免比较器 NPE（getOrDefault 对 null 值不兜底）
            if (b.getExpiryDate() != null) {
                map.put(b.getBatchNo(), b.getExpiryDate());
            }
        }
        return map;
    }

    /** 批次效期锁定标记（spec 校验链 ④）：EXPIRY_LOCK_FLAG='1' 的批次 → true */
    /**
     * 校验链 ④ 锁定判定（spec expiry-management 需求⑤ / C-4.4-03，design D3 实时口径）：
     * 拦截 = （flag=1 或 实时计算到线）且 非有效豁免。
     * 豁免（EVAL_EXEMPT_UNTIL ≥ 今日）优先放行——即使 flag 未刷新；
     * 豁免过期即拦——即使次日扫描尚未把 flag 刷回 1（关闭 D2 的 24h 窗口）。
     */
    private Map<String, Boolean> loadExpiryLock(String itemCode, List<InvStock> rows) {
        Map<String, Boolean> map = new HashMap<>();
        List<String> batches = new ArrayList<>();
        for (InvStock r : rows) {
            String b = str(r.getBatchNo());
            if (!b.isEmpty() && !batches.contains(b)) {
                batches.add(b);
            }
        }
        if (batches.isEmpty()) {
            return map;
        }
        List<InvBatch> ledgers = batchDao.selectList(new LambdaQueryWrapper<InvBatch>()
                .eq(InvBatch::getItemCode, itemCode)
                .in(InvBatch::getBatchNo, batches));
        java.time.LocalDate today = java.time.LocalDate.now();
        BigDecimal lockRatio = sysParamService.getRate("EXPIRY_LOCK_RATIO", new BigDecimal("0.5"));
        for (InvBatch b : ledgers) {
            // 1) 有效豁免 → 放行（让步放行生效，扫描标记位可能尚未刷新）
            if (b.getEvalExemptUntil() != null && !b.getEvalExemptUntil().isBefore(today)) {
                map.put(b.getBatchNo(), false);
                continue;
            }
            // 2) 标记位（扫描落位，含 MANUAL 锁）
            boolean locked = "1".equals(str(b.getExpiryLockFlag()));
            // 3) 实时到线计算兜底（豁免过期后 flag 未刷新也即拦）
            if (!locked && b.getProductionDate() != null && b.getExpiryDate() != null) {
                long total = java.time.temporal.ChronoUnit.DAYS
                        .between(b.getProductionDate(), b.getExpiryDate());
                long remaining = java.time.temporal.ChronoUnit.DAYS
                        .between(today, b.getExpiryDate());
                if (total > 0) {
                    BigDecimal threshold = BigDecimal.valueOf(total).multiply(lockRatio)
                            .setScale(0, java.math.RoundingMode.DOWN);
                    locked = BigDecimal.valueOf(remaining).compareTo(threshold) < 0;
                }
            }
            map.put(b.getBatchNo(), locked);
        }
        return map;
    }

    /**
     * 流水写入（TX+yyMMdd+6位，唯一冲突重试 ≤5）。
     * 位级一维一条：batch/bin/qty 为本条实际变动维度（拆批/跨位拆行逐条），before/after 为位行余额。
     */
    private String writeTxn(String direction, InvDocType type, Request req,
                            StockPostingEngine.Line line, String batch, String bin,
                            BigDecimal qty, BigDecimal before, BigDecimal after) {
        String prefix = "TX" + LocalDate.now().format(TXN_DAY) + "-";
        // MAX+1 抗空洞（COUNT+1 在历史行缺失时撞已存在号，5 次重试耗尽即失败；
        // 并发下仍靠唯一键+重试兜底）
        long base = txnDao.maxSeqByPrefix(prefix);
        for (int attempt = 0; attempt < 5; attempt++) {
            String no = prefix + String.format("%06d", base + 1 + attempt);
            InvTransaction t = new InvTransaction();
            t.setTxnNo(no);
            t.setDirection(direction);
            t.setTypeCode(type.getTypeCode());
            t.setBizDocType(req.bizDocType);
            t.setBizDocNo(req.bizDocNo);
            t.setWarehouseCode(line.warehouseCode);
            t.setItemCode(line.itemCode);
            t.setBatchNo(batch == null ? "" : batch);
            t.setBinCode(bin == null ? "" : bin);
            t.setQty(qty.abs());
            t.setBeforeQty(before);
            t.setAfterQty(after);
            t.setRemark(req.remark);
            try {
                txnDao.insert(t);
                return no;
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                // 序列撞号重试
            }
        }
        throw new ServiceException(409, "流水号生成冲突，请重试");
    }

    /** 行状态（锁后读数 + 运行扣减镜像；capped=批次预算封顶后的行可扣上限） */
    private static final class RowState {
        InvStock row;
        String batchKey;
        BigDecimal qty;
        BigDecimal avail;
        BigDecimal qc;
        BigDecimal capped = BigDecimal.ZERO;

        BigDecimal basis() {
            return capped;
        }
    }

    private static final class IndexedLine {
        final int index;
        final StockPostingEngine.Line line;

        IndexedLine(int index, StockPostingEngine.Line line) {
            this.index = index;
            this.line = line;
        }
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
