package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.mrp.MrpDispatchDao;
import com.erp.dao.mrp.MrpMoBomDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpWorkCenterDao;
import com.erp.dao.mrp.OsConsumeDao;
import com.erp.dao.mrp.OsMaterialDao;
import com.erp.dao.mrp.OsMaterialLineDao;
import com.erp.entity.inv.InvBatch;
import com.erp.entity.inv.InvStock;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoBom;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.entity.mrp.OsConsume;
import com.erp.entity.mrp.OsMaterial;
import com.erp.entity.mrp.OsMaterialLine;
import com.erp.service.inv.StockPostingEngine;
import com.erp.service.mrp.OsMaterialService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外协发料单实现（change add-outsourcing-processing，spec outsourcing-material-supply
 * 「外协发料单创建与出库」，task 5.1）。
 *
 * 甲供链路：外协派工单（OS_SUPPLY_TYPE=COMPANY_SUPPLY）→ 发料单 DRAFT → 出库过账 POSTED。
 * 出库过账扣减工厂可用库存，物料所有权留厂内、保管责任转移供应商（在途量由发料单行 ISSUED_QTY
 * 累计承载，收货冲减/余料退回在 task 5.2 落地）。过账走通用引擎 OS_MAT_OUT 类型。
 */
@Slf4j
@Service
public class OsMaterialServiceImpl implements OsMaterialService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OsMaterialDao osmDao;
    private final OsMaterialLineDao lineDao;
    private final MrpDispatchDao dispatchDao;
    private final MrpWorkCenterDao workCenterDao;
    private final MdmSupplierDao supplierDao;
    private final InvStockDao stockDao;
    private final StockPostingEngine stockPostingEngine;
    /** 5.2 收货定额冲减：BOM 快照单耗 + 冲减台账 */
    private final MrpMoDao moDao;
    private final MrpMoBomDao moBomDao;
    private final OsConsumeDao consumeDao;
    /** 余料退回入库沿用原批次台账效期（BR-4.1-08：批次管理物料 IN 必须可解析有效期） */
    private final com.erp.dao.inv.InvBatchDao batchDao;

    public OsMaterialServiceImpl(OsMaterialDao osmDao,
                                 OsMaterialLineDao lineDao,
                                 MrpDispatchDao dispatchDao,
                                 MrpWorkCenterDao workCenterDao,
                                 MdmSupplierDao supplierDao,
                                 InvStockDao stockDao,
                                 StockPostingEngine stockPostingEngine,
                                 MrpMoDao moDao,
                                 MrpMoBomDao moBomDao,
                                 OsConsumeDao consumeDao,
                                 com.erp.dao.inv.InvBatchDao batchDao) {
        this.osmDao = osmDao;
        this.lineDao = lineDao;
        this.dispatchDao = dispatchDao;
        this.workCenterDao = workCenterDao;
        this.supplierDao = supplierDao;
        this.stockDao = stockDao;
        this.stockPostingEngine = stockPostingEngine;
        this.moDao = moDao;
        this.moBomDao = moBomDao;
        this.consumeDao = consumeDao;
        this.batchDao = batchDao;
    }

    // ---------- 创建（DRAFT） ----------

    @Override
    @Transactional
    public OsMaterial create(Map<String, Object> body) {
        requireRole("创建外协发料单");
        MrpDispatch dispatch = requireDispatch(str(body.get("dispatchId")));
        // 甲供前置校验（spec：SUPPLIER_BUY → 422）
        if (!OsMaterial.SUPPLY_COMPANY.equals(dispatch.getOsSupplyType())) {
            throw new ServiceException(422, "该派工单供料方式为 "
                    + dispatch.getOsSupplyType() + "，仅甲供（COMPANY_SUPPLY）可发料");
        }
        if (dispatch.getOutsourceFlag() == null || !"1".equals(dispatch.getOutsourceFlag())) {
            throw new ServiceException(422, "仅外协派工单可创建外协发料单");
        }
        // 供应商随外协 WC 解析（WC 未维护供应商 → 422）
        MrpWorkCenter wc = workCenterDao.selectOne(new LambdaQueryWrapper<MrpWorkCenter>()
                .eq(MrpWorkCenter::getWcCode, dispatch.getWcCode()).last("LIMIT 1"));
        String supCode = wc == null ? null : wc.getSupplierCode();
        if (supCode == null || supCode.isEmpty()) {
            throw new ServiceException(422, "外协工作中心未维护供应商，无法发料：" + dispatch.getWcCode());
        }
        MdmSupplier sup = supplierDao.selectOne(new LambdaQueryWrapper<MdmSupplier>()
                .eq(MdmSupplier::getSupplierCode, supCode).last("LIMIT 1"));
        if (sup == null) {
            throw new ServiceException(422, "供应商不存在：" + supCode);
        }

        List<Map<String, Object>> rows = rows(body.get("lines"));

        // 库存校验前置（spec：请领量超过可用量 → L1 阻断列出行级缺口）：不合格不落单
        List<Map<String, Object>> shortfalls = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String itemCode = requireText(r.get("itemCode"), "物料编码必填");
            String warehouseCode = requireText(r.get("warehouseCode"), "仓库必填");
            String batchNo = str(r.get("batchNo")) == null ? "" : str(r.get("batchNo"));
            BigDecimal reqQty = positive(r.get("reqQty"), "请领数量");
            BigDecimal avail = availableOf(warehouseCode, itemCode, batchNo);
            if (avail.compareTo(reqQty) < 0) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("itemCode", itemCode);
                s.put("batchNo", batchNo);
                s.put("warehouseCode", warehouseCode);
                s.put("reqQty", reqQty);
                s.put("availableQty", avail);
                s.put("shortfall", reqQty.subtract(avail));
                shortfalls.add(s);
            }
        }
        if (!shortfalls.isEmpty()) {
            throw new ServiceException(422, "可用库存不足，无法发料：" + shortfalls);
        }

        OsMaterial h = new OsMaterial();
        h.setOsmNo(nextOsmNo());
        h.setDispatchId(dispatch.getId());
        h.setDispatchNo(dispatch.getDispatchNo());
        h.setMoNo(dispatch.getMoNo());
        h.setOpSeq(dispatch.getOpSeq());
        h.setSupplierCode(supCode);
        h.setSupplierName(sup.getSupplierName());
        h.setStatus(OsMaterial.ST_DRAFT);
        h.setRemark(str(body.get("remark")));
        h.setCreateBy(SecurityUtils.getCurrentUserId());
        osmDao.insert(h);

        int no = 1;
        for (Map<String, Object> r : rows) {
            OsMaterialLine l = new OsMaterialLine();
            l.setOsmId(h.getId());
            l.setLineNo(no++);
            l.setItemCode(requireText(r.get("itemCode"), "物料编码必填"));
            l.setItemName(str(r.get("itemName")));
            l.setBatchNo(str(r.get("batchNo")) == null ? "" : str(r.get("batchNo")));
            l.setWarehouseCode(requireText(r.get("warehouseCode"), "仓库必填"));
            l.setUnit(str(r.get("unit")));
            l.setReqQty(positive(r.get("reqQty"), "请领数量"));
            l.setIssuedQty(BigDecimal.ZERO);
            l.setConsumedQty(BigDecimal.ZERO);
            l.setReturnedQty(BigDecimal.ZERO);
            lineDao.insert(l);
        }
        log.info("外协发料单 {} created dispatch={} supplier={} lines={}",
                h.getOsmNo(), dispatch.getDispatchNo(), supCode, no - 1);
        return h;
    }

    // ---------- 查询 ----------

    @Override
    public Map<String, Object> detail(String id) {
        requireRead();
        OsMaterial h = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("osm", h);
        out.put("lines", linesOf(h.getId()));
        return out;
    }

    @Override
    public List<OsMaterial> list(String dispatchNo, String supplierCode, String status) {
        requireRead();
        List<OsMaterial> heads = osmDao.selectList(new LambdaQueryWrapper<OsMaterial>()
                .eq(hasText(dispatchNo), OsMaterial::getDispatchNo, trim(dispatchNo))
                .eq(hasText(supplierCode), OsMaterial::getSupplierCode, trim(supplierCode))
                .eq(hasText(status), OsMaterial::getStatus, trim(status))
                .orderByDesc(OsMaterial::getCreateDate));
        // 回填待退回余量（仅已出库单有余量可言；草稿未发无在途）
        for (OsMaterial h : heads) {
            if (OsMaterial.ST_DRAFT.equals(h.getStatus())) {
                h.setRemainQty(BigDecimal.ZERO);
                continue;
            }
            BigDecimal remain = BigDecimal.ZERO;
            for (OsMaterialLine l : linesOf(h.getId())) {
                remain = remain.add(nvl(l.getIssuedQty())
                        .subtract(nvl(l.getConsumedQty())).subtract(nvl(l.getReturnedQty())));
            }
            h.setRemainQty(remain.signum() < 0 ? BigDecimal.ZERO : remain);
        }
        return heads;
    }

    // ---------- 草稿删除 ----------

    @Override
    @Transactional
    public void delete(String id) {
        requireRole("删除外协发料单");
        OsMaterial h = require(id);
        if (!OsMaterial.ST_DRAFT.equals(h.getStatus())) {
            throw new ServiceException(422, "仅草稿发料单可删除，当前 " + h.getStatus());
        }
        osmDao.deleteById(id);
        log.info("外协发料单 {} deleted", h.getOsmNo());
    }

    // ---------- 出库过账（DRAFT → POSTED，单事务） ----------

    @Override
    @Transactional
    public OsMaterial post(String id) {
        requireRole("外协发料出库过账");
        OsMaterial h = require(id);
        if (!OsMaterial.ST_DRAFT.equals(h.getStatus())) {
            throw new ServiceException(422, "仅草稿发料单可过账，当前 " + h.getStatus());
        }
        List<OsMaterialLine> lines = linesOf(h.getId());
        if (lines.isEmpty()) {
            throw new ServiceException(422, "发料单无明细行");
        }
        // 逐行复核当前可用量（防并发）→ 收集缺口，全部行通过才过账（L1 阻断列出行级缺口）
        List<Map<String, Object>> shortfalls = new ArrayList<>();
        for (OsMaterialLine l : lines) {
            BigDecimal avail = availableOf(l.getWarehouseCode(), l.getItemCode(), l.getBatchNo());
            if (avail.compareTo(l.getReqQty()) < 0) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("itemCode", l.getItemCode());
                s.put("batchNo", l.getBatchNo());
                s.put("warehouseCode", l.getWarehouseCode());
                s.put("reqQty", l.getReqQty());
                s.put("availableQty", avail);
                s.put("shortfall", l.getReqQty().subtract(avail));
                shortfalls.add(s);
            }
        }
        if (!shortfalls.isEmpty()) {
            throw new ServiceException(422, "可用库存不足，无法发料：" + shortfalls);
        }
        // 逐行引擎过账（OS_MAT_OUT 扣 AVAILABLE_QTY，指定批次直扣）
        for (OsMaterialLine l : lines) {
            StockPostingEngine.Line el = new StockPostingEngine.Line();
            el.warehouseCode = l.getWarehouseCode();
            el.itemCode = l.getItemCode();
            el.itemName = l.getItemName();
            el.batchNo = l.getBatchNo();
            el.qty = l.getReqQty();
            el.batchSpecified = true;
            stockPostingEngine.post(StockPostingEngine.Request.of(
                    "OS_MAT_OUT", "OS_MATERIAL", h.getOsmNo(), List.of(el)));
            // 在途入池（spec 5.1 场景「工厂可用量减少、在途量增加」）：OS_QTY 增 + QTY 增
            // （引擎 OUT 已减 QTY/AVAILABLE，入池后总量守恒、可用转在途）
            addOsPool(l.getWarehouseCode(), l.getItemCode(), l.getBatchNo(), l.getReqQty(),
                    "发料出库 " + h.getOsmNo());
            l.setIssuedQty(l.getReqQty());
            lineDao.updateById(l);
        }
        h.setStatus(OsMaterial.ST_POSTED);
        h.setPostBy(SecurityUtils.getCurrentUserId());
        h.setPostDate(LocalDateTime.now());
        h.setUpdateBy(SecurityUtils.getCurrentUserId());
        osmDao.updateById(h);
        log.info("外协发料单 {} posted lines={} supplier={}", h.getOsmNo(), lines.size(), h.getSupplierCode());
        return h;
    }

    // ---------- 5.2 收货定额冲减与余料退回 ----------

    @Override
    @Transactional
    public boolean consumeOnReceipt(String dispatchId, String grNo, BigDecimal recvQty) {
        if (!hasText(dispatchId) || !hasText(grNo)
                || recvQty == null || recvQty.signum() <= 0) {
            return false;
        }
        // 幂等：同 GR 已有 CONSUME 台账 → 跳过（同事务唯一键为兜底防线）
        Long hit = consumeDao.selectCount(new LambdaQueryWrapper<OsConsume>()
                .eq(OsConsume::getAnchorNo, grNo)
                .eq(OsConsume::getConsumeType, OsConsume.TYPE_CONSUME));
        if (hit != null && hit > 0) {
            return false;
        }
        // 该派工单 POSTED 发料单（甲供链路通常一张；多张按创建序逐单冲减）
        List<OsMaterial> heads = osmDao.selectList(new LambdaQueryWrapper<OsMaterial>()
                .eq(OsMaterial::getDispatchId, dispatchId)
                .eq(OsMaterial::getStatus, OsMaterial.ST_POSTED)
                .orderByAsc(OsMaterial::getCreateDate));
        if (heads.isEmpty()) {
            return false; // 供应商自购/未发料 → 静默
        }
        MrpDispatch d = dispatchDao.selectById(dispatchId);
        boolean any = false;
        for (OsMaterial h : heads) {
            for (OsMaterialLine l : linesOf(h.getId())) {
                BigDecimal perUnit = bomUnitQty(d == null ? null : d.getMoId(), l.getItemCode());
                if (perUnit.signum() <= 0) {
                    continue; // 非 BOM 消耗物料不冲减
                }
                BigDecimal take = perUnit.multiply(recvQty);
                BigDecimal remain = nvl(l.getIssuedQty()).subtract(nvl(l.getConsumedQty()))
                        .subtract(nvl(l.getReturnedQty()));
                if (remain.signum() <= 0) {
                    continue;
                }
                take = take.min(remain); // 封顶行余量（超定额发料不冲成负数）
                if (take.signum() <= 0) {
                    continue;
                }
                consumeDao.insert(ledger(OsConsume.TYPE_CONSUME, grNo, h, l, take));
                l.setConsumedQty(nvl(l.getConsumedQty()).add(take));
                lineDao.updateById(l);
                deductOsPool(l.getWarehouseCode(), l.getItemCode(), l.getBatchNo(), take,
                        "收货定额冲减 GR:" + grNo);
                any = true;
            }
        }
        log.info("外协甲供冲减 dispatch={} gr={} recvQty={} applied={}", dispatchId, grNo, recvQty, any);
        return any;
    }

    @Override
    @Transactional
    public OsMaterial returnRemaining(String id, List<Map<String, Object>> rows) {
        requireRole("外协余料退回");
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException(422, "退回行清单必填");
        }
        OsMaterial h = require(id);
        if (!OsMaterial.ST_POSTED.equals(h.getStatus())) {
            throw new ServiceException(422, "仅已出库发料单可退回余料，当前 " + h.getStatus());
        }
        List<OsMaterialLine> lines = linesOf(h.getId());
        Map<String, OsMaterialLine> byId = new HashMap<>();
        for (OsMaterialLine l : lines) {
            byId.put(l.getId(), l);
        }
        // 校验行归属 + 可退余量（= 实发 − 已冲减 − 已退回），超退逐行列缺口 422
        List<Map<String, Object>> shortfalls = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String lineId = str(r.get("lineId"));
            OsMaterialLine l = lineId == null ? null : byId.get(lineId);
            if (l == null) {
                throw new ServiceException(422, "退回行不属于该发料单：" + lineId);
            }
            BigDecimal qty = positive(r.get("qty"), "退回数量");
            BigDecimal remain = nvl(l.getIssuedQty()).subtract(nvl(l.getConsumedQty()))
                    .subtract(nvl(l.getReturnedQty()));
            if (qty.compareTo(remain) > 0) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("lineId", l.getId());
                s.put("itemCode", l.getItemCode());
                s.put("batchNo", l.getBatchNo());
                s.put("returnQty", qty);
                s.put("remainQty", remain);
                s.put("gap", qty.subtract(remain));
                shortfalls.add(s);
            }
        }
        if (!shortfalls.isEmpty()) {
            throw new ServiceException(422, "可退余量不足：" + shortfalls);
        }
        // 执行退回：在途池减（OS−/QTY−）→ 退库过账 OS_MAT_IN（引擎 IN：QTY+/AVAILABLE+）
        for (Map<String, Object> r : rows) {
            OsMaterialLine l = byId.get(str(r.get("lineId")));
            BigDecimal qty = positive(r.get("qty"), "退回数量");
            deductOsPool(l.getWarehouseCode(), l.getItemCode(), l.getBatchNo(), qty,
                    "余料退回 " + h.getOsmNo());
            StockPostingEngine.Line el = new StockPostingEngine.Line();
            el.warehouseCode = l.getWarehouseCode();
            el.itemCode = l.getItemCode();
            el.itemName = l.getItemName();
            el.batchNo = l.getBatchNo();
            el.qty = qty;
            el.batchSpecified = true;
            // 退回沿用原入库批次台账效期（BR-4.1-08：批次管理物料 IN 须可解析有效期；
            // 退回是同批次回库，不重新按保质期推算，否则效期随退回日漂移）
            InvBatch origin = batchDao.selectOne(new LambdaQueryWrapper<InvBatch>()
                    .eq(InvBatch::getItemCode, l.getItemCode())
                    .eq(InvBatch::getBatchNo, l.getBatchNo() == null ? "" : l.getBatchNo())
                    .last("LIMIT 1"));
            if (origin != null && origin.getExpiryDate() != null) {
                el.expiryDate = origin.getExpiryDate();
            }
            stockPostingEngine.post(StockPostingEngine.Request.of(
                    "OS_MAT_IN", "OS_MATERIAL", h.getOsmNo(), List.of(el)));
            l.setReturnedQty(nvl(l.getReturnedQty()).add(qty));
            lineDao.updateById(l);
        }
        // 全退判定：全部行余量归零 → RETURNED 终态
        boolean allDone = true;
        for (OsMaterialLine l : linesOf(h.getId())) {
            BigDecimal remain = nvl(l.getIssuedQty()).subtract(nvl(l.getConsumedQty()))
                    .subtract(nvl(l.getReturnedQty()));
            if (remain.signum() > 0) {
                allDone = false;
                break;
            }
        }
        if (allDone) {
            h.setStatus(OsMaterial.ST_RETURNED);
            h.setReturnDate(LocalDateTime.now());
        }
        h.setUpdateBy(SecurityUtils.getCurrentUserId());
        osmDao.updateById(h);
        log.info("外协余料退回 {} lines={} allDone={}", h.getOsmNo(), rows.size(), allDone);
        return h;
    }

    @Override
    @Transactional
    public boolean reverseConsume(String poNo, String returnNo, BigDecimal finishedQty) {
        if (!hasText(poNo) || !hasText(returnNo)
                || finishedQty == null || finishedQty.signum() <= 0) {
            return false;
        }
        MrpDispatch d = dispatchDao.selectOne(new LambdaQueryWrapper<MrpDispatch>()
                .eq(MrpDispatch::getPoNo, poNo.trim())
                .last("LIMIT 1"));
        if (d == null || !"1".equals(d.getOutsourceFlag())) {
            return false;
        }
        // 幂等：同退货单已恢复 → 跳过
        Long hit = consumeDao.selectCount(new LambdaQueryWrapper<OsConsume>()
                .eq(OsConsume::getAnchorNo, returnNo)
                .eq(OsConsume::getConsumeType, OsConsume.TYPE_REVERSE));
        if (hit != null && hit > 0) {
            return false;
        }
        // 恢复预算按物料 = 定额单耗 × 退货件数；逐发料行（POSTED/RETURNED 头）封顶恢复
        List<OsMaterial> heads = osmDao.selectList(new LambdaQueryWrapper<OsMaterial>()
                .eq(OsMaterial::getDispatchId, d.getId())
                .in(OsMaterial::getStatus, OsMaterial.ST_POSTED, OsMaterial.ST_RETURNED)
                .orderByAsc(OsMaterial::getCreateDate));
        if (heads.isEmpty()) {
            return false;
        }
        Map<String, BigDecimal> budgets = new HashMap<>();
        boolean any = false;
        for (OsMaterial h : heads) {
            for (OsMaterialLine l : linesOf(h.getId())) {
                BigDecimal perUnit = bomUnitQty(d.getMoId(), l.getItemCode());
                if (perUnit.signum() <= 0) {
                    continue;
                }
                BigDecimal budget = budgets.computeIfAbsent(l.getItemCode(),
                        k -> perUnit.multiply(finishedQty));
                if (budget.signum() <= 0) {
                    continue;
                }
                BigDecimal net = nvl(l.getConsumedQty()); // 行净冲减（CONSUME 累计 − 已 REVERSE）
                if (net.signum() <= 0) {
                    continue;
                }
                BigDecimal take = budget.min(net);
                if (take.signum() <= 0) {
                    continue;
                }
                consumeDao.insert(ledger(OsConsume.TYPE_REVERSE, returnNo, h, l, take));
                l.setConsumedQty(net.subtract(take));
                lineDao.updateById(l);
                addOsPool(l.getWarehouseCode(), l.getItemCode(), l.getBatchNo(), take,
                        "红冲恢复 RET:" + returnNo);
                budgets.put(l.getItemCode(), budget.subtract(take));
                any = true;
            }
        }
        log.info("外协甲供红冲恢复 po={} ret={} qty={} applied={}", poNo, returnNo, finishedQty, any);
        return any;
    }

    // ---------- 5.2 池核算与台账助手 ----------

    /**
     * 外协在途池扣减（试产平移先例：行锁 + 非负护栏；QTY 总量同步减——物料在供应商侧消耗）。
     */
    private void deductOsPool(String wh, String item, String batch, BigDecimal qty, String action) {
        InvStock row = stockDao.selectOne(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, wh)
                .eq(InvStock::getItemCode, item)
                .eq(InvStock::getBatchNo, batch == null ? "" : batch)
                .last("FOR UPDATE"));
        if (row == null) {
            throw new ServiceException(422, action + "失败：库存不存在该批次行（"
                    + wh + "/" + item + "/" + batch + "）");
        }
        BigDecimal os = nvl(row.getOsQty());
        if (os.compareTo(qty) < 0) {
            throw new ServiceException(422, action + "失败：外协在途不足（需 " + qty.stripTrailingZeros().toPlainString()
                    + "，当前 " + os.stripTrailingZeros().toPlainString() + "）：" + item + " 批次 " + batch);
        }
        stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                .eq(InvStock::getId, row.getId())
                .ge(InvStock::getOsQty, qty)
                .setSql("OS_QTY = OS_QTY - " + qty.toPlainString())
                .setSql("QTY = QTY - " + qty.toPlainString()));
    }

    /**
     * 外协在途池增补（发料出库入池 / 红冲恢复）：OS_QTY 增 + QTY 增。
     * 发料出库场景引擎 OUT 已减 QTY/AVAILABLE，入池后总量守恒（可用转在途）。
     */
    private void addOsPool(String wh, String item, String batch, BigDecimal qty, String action) {
        InvStock row = stockDao.selectOne(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, wh)
                .eq(InvStock::getItemCode, item)
                .eq(InvStock::getBatchNo, batch == null ? "" : batch)
                .last("FOR UPDATE"));
        if (row == null) {
            throw new ServiceException(422, action + "失败：库存不存在该批次行（"
                    + wh + "/" + item + "/" + batch + "）");
        }
        stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                .eq(InvStock::getId, row.getId())
                .setSql("OS_QTY = OS_QTY + " + qty.toPlainString())
                .setSql("QTY = QTY + " + qty.toPlainString()));
    }

    /** BOM 快照定额单耗 = Σ 行单耗 ×(1+损耗)（展平多层行自然累加；无 BOM 行 → 0） */
    private BigDecimal bomUnitQty(String moId, String itemCode) {
        if (!hasText(moId)) {
            return BigDecimal.ZERO;
        }
        List<MrpMoBom> rows = moBomDao.selectList(new LambdaQueryWrapper<MrpMoBom>()
                .eq(MrpMoBom::getMoId, moId)
                .eq(MrpMoBom::getItemCode, itemCode));
        BigDecimal sum = BigDecimal.ZERO;
        for (MrpMoBom b : rows) {
            BigDecimal unit = nvl(b.getUnitQty());
            BigDecimal loss = nvl(b.getLossRate());
            sum = sum.add(unit.multiply(BigDecimal.ONE.add(loss)));
        }
        return sum;
    }

    private OsConsume ledger(String type, String anchor, OsMaterial h, OsMaterialLine l, BigDecimal qty) {
        OsConsume c = new OsConsume();
        c.setDispatchId(h.getDispatchId());
        c.setOsmId(h.getId());
        c.setOsmLineId(l.getId());
        c.setConsumeType(type);
        c.setAnchorNo(anchor);
        c.setItemCode(l.getItemCode());
        c.setItemName(l.getItemName());
        c.setBatchNo(l.getBatchNo());
        c.setWarehouseCode(l.getWarehouseCode());
        c.setQty(qty);
        c.setCreateBy(SecurityUtils.getCurrentUserId());
        return c;
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // ---------- helpers ----------

    private List<OsMaterialLine> linesOf(String osmId) {
        return lineDao.selectList(new LambdaQueryWrapper<OsMaterialLine>()
                .eq(OsMaterialLine::getOsmId, osmId)
                .orderByAsc(OsMaterialLine::getLineNo));
    }

    private OsMaterial require(String id) {
        OsMaterial h = osmDao.selectById(id);
        if (h == null) {
            throw new ServiceException(404, "外协发料单不存在：" + id);
        }
        return h;
    }

    private MrpDispatch requireDispatch(String id) {
        if (!hasText(id)) {
            throw new ServiceException(422, "外协派工单必填");
        }
        MrpDispatch d = dispatchDao.selectById(id);
        if (d == null) {
            throw new ServiceException(404, "外协派工单不存在：" + id);
        }
        return d;
    }

    /** 该 (仓库, 物料, 批次) 当前可用量（并发复核口径，与引擎扣减一致） */
    private BigDecimal availableOf(String warehouseCode, String itemCode, String batchNo) {
        List<InvStock> rows = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, warehouseCode)
                .eq(InvStock::getItemCode, itemCode)
                .eq(InvStock::getBatchNo, batchNo == null ? "" : batchNo));
        BigDecimal sum = BigDecimal.ZERO;
        for (InvStock s : rows) {
            if (s.getAvailableQty() != null) {
                sum = sum.add(s.getAvailableQty());
            }
        }
        return sum;
    }

    private String nextOsmNo() {
        String prefix = "OSM" + LocalDateTime.now().format(DAY);
        Integer max = osmDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object v) {
        if (!(v instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "发料明细行必填");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map)) {
                throw new ServiceException(422, "明细行格式非法");
            }
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    private static String requireText(Object v, String msg) {
        String s = str(v);
        if (s == null || s.isEmpty()) {
            throw new ServiceException(422, msg);
        }
        return s;
    }

    private static BigDecimal positive(Object v, String msg) {
        BigDecimal d;
        try {
            d = new BigDecimal(str(v) == null ? "" : str(v));
        } catch (NumberFormatException e) {
            throw new ServiceException(422, msg + "（格式非法）");
        }
        if (d.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException(422, msg + "（须大于 0）");
        }
        return d;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** 写操作权限：PLANNER/PLAN_MGR/PURCHASER/ADMIN（spec 菜单权限 5.10.3） */
    private void requireRole(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> userRoles.add(a.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : new String[]{"ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_PURCHASER"}) {
            for (String r : userRoles) {
                if (r.equalsIgnoreCase(want)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "当前角色无权" + action);
    }

    /** 读操作：同写权限线（5.10.3 叶子可见角色） */
    private void requireRead() {
        requireRole("查看外协发料单");
    }
}
