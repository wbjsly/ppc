package com.erp.service.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mrp.MrpBomDao;
import com.erp.dao.mrp.MrpBomItemDao;
import com.erp.dao.mrp.MrpBomSubstituteDao;
import com.erp.dao.mrp.MrpRoutingDao;
import com.erp.dao.mrp.MrpRoutingOpDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.dao.sd.SoDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.entity.inv.InvStock;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
import com.erp.entity.mrp.MrpBomSubstitute;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.entity.proc.PurchaseOrderLine;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoLine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MRP 数据源内聚类（change add-mrp-demand-planning，design D2：四类源只读直查，口径变更只改此处）。
 *
 * 字段口径核实结论（任务 1.3，design Open Question 1 已关闭）：
 * 1) SO 需求：头 {@code erp_sd_so.status ∈ (CONFIRMED, PARTIAL_SHIPPED)} + 行 lineStatus='OPEN'
 *    （LS_CANCELLED 排除）；未交量 = 行 qty − shippedQty（负值截 0）；需求日期 = 行 expectDeliveryDate
 *    最早者（FR-4.5-2-1「销售订单确认量」口径）。
 * 2) PO 在途：头 status='APPROVED' 行的 QTY − RECEIVED_QTY——收货过账实时回写
 *    （GoodsReceiptServiceImpl 对 RECEIVED_QTY 原子累加），无需状态近似回退。
 * 3) OnHand：erp_inv_stock 按物料 ΣAVAILABLE_QTY（冻结/待检不计入，可作 MRP 可用）。
 * 4) 在制 InProcess：恒 0 桩（proposal D4，5.4 工单落地后在此接入读入口）。
 * 5) ROP：erp_mdm_item.ROP_QTY（119 新列），0/NULL 不启用补货策略。
 * 6) BOM：仅 PUBLISHED 版本行 + 行级替代（优先级+比例）；路线：仅 PUBLISHED 版本 ΣLEAD_TIME。
 */
@Slf4j
@Component
public class MrpDataSource {

    private final MdmItemDao mdmItemDao;
    private final SoDao soDao;
    private final SoLineDao soLineDao;
    private final InvStockDao invStockDao;
    private final PurchaseOrderDao purchaseOrderDao;
    private final PurchaseOrderLineDao purchaseOrderLineDao;
    private final MrpBomDao bomDao;
    private final MrpBomItemDao bomItemDao;
    private final MrpBomSubstituteDao bomSubstituteDao;
    private final MrpRoutingDao routingDao;
    private final MrpRoutingOpDao routingOpDao;

    public MrpDataSource(MdmItemDao mdmItemDao, SoDao soDao, SoLineDao soLineDao,
                         InvStockDao invStockDao, PurchaseOrderDao purchaseOrderDao,
                         PurchaseOrderLineDao purchaseOrderLineDao, MrpBomDao bomDao,
                         MrpBomItemDao bomItemDao, MrpBomSubstituteDao bomSubstituteDao,
                         MrpRoutingDao routingDao, MrpRoutingOpDao routingOpDao) {
        this.mdmItemDao = mdmItemDao;
        this.soDao = soDao;
        this.soLineDao = soLineDao;
        this.invStockDao = invStockDao;
        this.purchaseOrderDao = purchaseOrderDao;
        this.purchaseOrderLineDao = purchaseOrderLineDao;
        this.bomDao = bomDao;
        this.bomItemDao = bomItemDao;
        this.bomSubstituteDao = bomSubstituteDao;
        this.routingDao = routingDao;
        this.routingOpDao = routingOpDao;
    }

    /** SO 需求（已确认口径）：数量合计 + 最早未交期 */
    public static class SoDemand {
        public BigDecimal qty = BigDecimal.ZERO;
        public LocalDate earliestDate;
    }

    /** BOM 爆炸行：主料 + 按优先级排序的替代序列 */
    public static class BomLine {
        public String mainItem;
        public BigDecimal usage;
        public BigDecimal lossRate;
        /** 替代序列 [主料, sub1, sub2...]（仅启用替代；元素 = {itemCode, ratio} 语义上 ratio 主料=1） */
        public List<String[]> candidates = new ArrayList<>();
    }

    /** 范围物料集（proposal D5：FULL/CATEGORY/GROUP；产品族以分类代理） */
    public List<MdmItem> scopeItems(String scopeType, String scopeValue) {
        LambdaQueryWrapper<MdmItem> w = new LambdaQueryWrapper<>();
        if ("CATEGORY".equals(scopeType)) {
            w.eq(MdmItem::getCategoryCode, scopeValue);
        } else if ("GROUP".equals(scopeType)) {
            w.eq(MdmItem::getMaterialGroup, scopeValue);
        }
        // FULL = 全部（含非启用：非启用物料仍可能有 SO 需求要暴露，转正时由 PR 链路硬阻断）
        return mdmItemDao.selectList(w);
    }

    /** 物料主数据索引（状态/类型/LT/MOQ/ROP 等） */
    public Map<String, MdmItem> itemIndex(List<MdmItem> items) {
        Map<String, MdmItem> out = new LinkedHashMap<>();
        for (MdmItem it : items) {
            out.put(it.getItemCode(), it);
        }
        return out;
    }

    /** SO 需求：头 CONFIRMED/PARTIAL_SHIPPED、行 OPEN，未交 = qty − shippedQty */
    public Map<String, SoDemand> soDemands(Set<String> itemCodes) {
        Map<String, SoDemand> out = new HashMap<>();
        if (itemCodes.isEmpty()) {
            return out;
        }
        List<So> confirmed = soDao.selectList(new LambdaQueryWrapper<So>()
                .in(So::getStatus, So.ST_CONFIRMED, So.ST_PARTIAL_SHIPPED));
        if (confirmed.isEmpty()) {
            return out;
        }
        Set<String> soIds = new HashSet<>();
        for (So so : confirmed) {
            soIds.add(so.getId());
        }
        List<SoLine> lines = soLineDao.selectList(new LambdaQueryWrapper<SoLine>()
                .in(SoLine::getSoId, soIds)
                .eq(SoLine::getLineStatus, SoLine.LS_OPEN)
                .in(SoLine::getItemCode, itemCodes));
        for (SoLine line : lines) {
            if (line.getQty() == null) {
                continue;
            }
            BigDecimal shipped = line.getShippedQty() == null ? BigDecimal.ZERO : line.getShippedQty();
            BigDecimal outstanding = line.getQty().subtract(shipped);
            if (outstanding.signum() <= 0) {
                continue;
            }
            SoDemand d = out.computeIfAbsent(line.getItemCode(), k -> new SoDemand());
            d.qty = d.qty.add(outstanding);
            if (line.getExpectDeliveryDate() != null
                    && (d.earliestDate == null || line.getExpectDeliveryDate().isBefore(d.earliestDate))) {
                d.earliestDate = line.getExpectDeliveryDate();
            }
        }
        return out;
    }

    /** OnHand：按物料 ΣAVAILABLE_QTY */
    public Map<String, BigDecimal> onHand(Set<String> itemCodes) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (itemCodes.isEmpty()) {
            return out;
        }
        List<InvStock> rows = invStockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .in(InvStock::getItemCode, itemCodes));
        for (InvStock s : rows) {
            BigDecimal avail = s.getAvailableQty() == null ? BigDecimal.ZERO : s.getAvailableQty();
            out.merge(s.getItemCode(), avail, BigDecimal::add);
        }
        return out;
    }

    /** Incoming 在途：APPROVED PO 行 QTY − RECEIVED_QTY（负值截 0） */
    public Map<String, BigDecimal> inTransit(Set<String> itemCodes) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (itemCodes.isEmpty()) {
            return out;
        }
        List<PurchaseOrder> approved = purchaseOrderDao.selectList(new LambdaQueryWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getStatus, "APPROVED"));
        if (approved.isEmpty()) {
            return out;
        }
        Set<String> poIds = new HashSet<>();
        for (PurchaseOrder po : approved) {
            poIds.add(po.getId());
        }
        List<PurchaseOrderLine> lines = purchaseOrderLineDao.selectList(new LambdaQueryWrapper<PurchaseOrderLine>()
                .in(PurchaseOrderLine::getPoId, poIds)
                .in(PurchaseOrderLine::getItemCode, itemCodes));
        for (PurchaseOrderLine line : lines) {
            if (line.getQty() == null) {
                continue;
            }
            BigDecimal received = line.getReceivedQty() == null ? BigDecimal.ZERO : line.getReceivedQty();
            BigDecimal open = line.getQty().subtract(received);
            if (open.signum() <= 0) {
                continue;
            }
            out.merge(line.getItemCode(), open, BigDecimal::add);
        }
        return out;
    }

    /** 已发布 BOM 行（含启用替代序列；返回 null = 该父件无已发布 BOM） */
    public List<BomLine> publishedBomLines(String parentItemCode) {
        MrpBom bom = bomDao.selectOne(new LambdaQueryWrapper<MrpBom>()
                .eq(MrpBom::getParentItemCode, parentItemCode)
                .eq(MrpBom::getStatus, MrpBom.ST_PUBLISHED)
                .orderByDesc(MrpBom::getVersionMajor)
                .orderByDesc(MrpBom::getVersionMinor)
                .last("LIMIT 1"));
        if (bom == null) {
            return null;
        }
        List<MrpBomItem> rows = bomItemDao.selectList(new LambdaQueryWrapper<MrpBomItem>()
                .eq(MrpBomItem::getBomId, bom.getId())
                .orderByAsc(MrpBomItem::getLineNo));
        List<BomLine> out = new ArrayList<>();
        for (MrpBomItem row : rows) {
            BomLine line = new BomLine();
            line.mainItem = row.getItemCode();
            line.usage = row.getQty();
            line.lossRate = row.getLossRate() == null ? BigDecimal.ZERO : row.getLossRate();
            line.candidates.add(new String[]{row.getItemCode(), "1"});
            List<MrpBomSubstitute> subs = bomSubstituteDao.selectList(new LambdaQueryWrapper<MrpBomSubstitute>()
                    .eq(MrpBomSubstitute::getBomItemId, row.getId())
                    .orderByAsc(MrpBomSubstitute::getPriority));
            for (MrpBomSubstitute sub : subs) {
                MdmItem subItem = mdmItemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                        .eq(MdmItem::getItemCode, sub.getSubstituteItemCode()));
                if (subItem != null && "1".equals(subItem.getStatus())) {
                    line.candidates.add(new String[]{sub.getSubstituteItemCode(), sub.getRatio().toPlainString()});
                }
            }
            out.add(line);
        }
        return out;
    }

    /** 已发布路线 ΣLEAD_TIME（天；无已发布路线返回 null → 调用方兜底 leadTimeDays） */
    public BigDecimal publishedRouteLeadDays(String itemCode) {
        MrpRouting routing = routingDao.selectOne(new LambdaQueryWrapper<MrpRouting>()
                .eq(MrpRouting::getItemCode, itemCode)
                .eq(MrpRouting::getStatus, MrpRouting.ST_PUBLISHED)
                .orderByDesc(MrpRouting::getVersionMajor)
                .orderByDesc(MrpRouting::getVersionMinor)
                .last("LIMIT 1"));
        if (routing == null) {
            return null;
        }
        List<MrpRoutingOp> ops = routingOpDao.selectList(new LambdaQueryWrapper<MrpRoutingOp>()
                .eq(MrpRoutingOp::getRoutingId, routing.getId()));
        BigDecimal sum = null;
        for (MrpRoutingOp op : ops) {
            if (op.getLeadTime() != null) {
                sum = (sum == null ? BigDecimal.ZERO : sum).add(op.getLeadTime());
            }
        }
        return sum;
    }
}
