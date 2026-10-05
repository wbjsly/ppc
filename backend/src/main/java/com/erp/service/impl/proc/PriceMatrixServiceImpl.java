package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemCategoryDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.proc.AnalysisSnapshotDao;
import com.erp.dao.proc.QuoteDao;
import com.erp.dao.proc.RfqDao;
import com.erp.dao.proc.RfqLineDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mdm.MdmItemCategory;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.proc.AnalysisSnapshot;
import com.erp.entity.proc.Quote;
import com.erp.entity.proc.Rfq;
import com.erp.entity.proc.RfqLine;
import com.erp.procurement.RfqStateMachine;
import com.erp.service.proc.PriceMatrixService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 比价矩阵工作台读服务实现（change add-price-comparison-matrix design D3/D4）。
 * 实时聚合：item(品类) -> rfq_line -> rfq -> quote；不建物化表。
 * <p>偏差 D4 —— 报价为 RFQ 级（Quote 无 lineId），透视按 RFQ 行物料品类归组；
 * 跨品类 RFQ 计入其全部品类并标注 crossCategory，不做报价行级拆分（不动 2.2.1 报价模型）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceMatrixServiceImpl implements PriceMatrixService {

    private final MdmItemDao itemDao;
    private final MdmItemCategoryDao itemCategoryDao;
    private final MdmSupplierDao mdmSupplierDao;
    private final RfqLineDao lineDao;
    private final RfqDao rfqDao;
    private final QuoteDao quoteDao;
    private final AnalysisSnapshotDao analysisSnapshotDao;
    private final com.fasterxml.jackson.databind.ObjectMapper jsonMapper;

    // ---------- 品类选项 ----------

    @Override
    public List<Map<String, Object>> categories() {
        // 有询价行为的品类：rfq_line 物料 -> 品类（distinct），附 RFQ 数
        List<RfqLine> lines = lineDao.selectList(new LambdaQueryWrapper<RfqLine>()
                .select(RfqLine::getRfqId, RfqLine::getItemCode));
        if (lines.isEmpty()) {
            return List.of();
        }
        Set<String> itemCodes = new TreeSet<>();
        lines.forEach(l -> itemCodes.add(l.getItemCode()));
        Map<String, String> itemCat = new LinkedHashMap<>();
        for (MdmItem item : itemDao.selectList(new LambdaQueryWrapper<MdmItem>()
                .in(MdmItem::getItemCode, itemCodes))) {
            itemCat.put(item.getItemCode(), item.getCategoryCode());
        }
        Map<String, Set<String>> catRfqs = new LinkedHashMap<>();
        for (RfqLine l : lines) {
            String cat = itemCat.get(l.getItemCode());
            if (cat != null) {
                catRfqs.computeIfAbsent(cat, k -> new LinkedHashSet<>()).add(l.getRfqId());
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : catRfqs.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("categoryCode", e.getKey());
            m.put("categoryName", categoryName(e.getKey()));
            m.put("rfqCount", e.getValue().size());
            result.add(m);
        }
        result.sort(Comparator.comparing(m -> String.valueOf(m.get("categoryCode"))));
        return result;
    }

    // ---------- 品类视角 ----------

    @Override
    public Map<String, Object> categoryView(String categoryCode) {
        if (categoryCode == null || categoryCode.trim().isEmpty()) {
            throw new ServiceException(422, "categoryCode 必填");
        }
        String code = categoryCode.trim();
        AggCtx ctx = loadCtx(code);
        Map<String, List<QuoteRow>> bySupplier = new LinkedHashMap<>();
        for (QuoteRow qr : ctx.rows) {
            if ("1".equals(qr.quote.getExcluded())) {
                continue;   // 剔除报价不参与透视（与矩阵口径一致）
            }
            bySupplier.computeIfAbsent(qr.quote.getSupplierId(), k -> new ArrayList<>()).add(qr);
        }
        List<Map<String, Object>> suppliers = new ArrayList<>();
        for (Map.Entry<String, List<QuoteRow>> e : bySupplier.entrySet()) {
            List<QuoteRow> rows = e.getValue();
            rows.sort(Comparator.comparing(q -> q.quote.getCreateDate() == null
                    ? LocalDateTime.MIN : q.quote.getCreateDate()));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("supplierId", e.getKey());
            s.put("supplierName", supplierName(e.getKey()));
            // 最新有效报价 = 最近一条（谈判后价优先）
            QuoteRow last = rows.get(rows.size() - 1);
            s.put("latestPrice", eff(last.quote));
            s.put("latestDate", last.quote.getCreateDate());
            // 历史报价序列（时间序）
            List<Map<String, Object>> history = new ArrayList<>();
            for (QuoteRow qr : rows) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("rfqNo", qr.rfq.getRfqNo());
                h.put("date", qr.quote.getCreateDate());
                h.put("unitPrice", qr.quote.getUnitPrice());
                h.put("negotiatedPrice", qr.quote.getNegotiatedPrice());
                h.put("price", eff(qr.quote));
                h.put("crossCategory", qr.crossCategory);
                history.add(h);
            }
            s.put("history", history);
            s.put("rfqCount", rows.stream().map(q -> q.quote.getRfqId()).distinct().count());
            // 中标次数：该供应商在本品类 RFQ 集合内的中选数
            s.put("awardCount", ctx.rfqs.values().stream()
                    .filter(r -> e.getKey().equals(r.getAwardSupplierId())
                            && RfqStateMachine.AWARDED.equals(r.getStatus()))
                    .count());
            // 平均谈判让价 = mean((原价-谈判价)/原价)，仅算有谈判价的
            List<BigDecimal> discounts = new ArrayList<>();
            for (QuoteRow qr : rows) {
                Quote q = qr.quote;
                if (q.getNegotiatedPrice() != null && q.getUnitPrice() != null
                        && q.getUnitPrice().signum() > 0
                        && q.getNegotiatedPrice().compareTo(q.getUnitPrice()) < 0) {
                    discounts.add(q.getUnitPrice().subtract(q.getNegotiatedPrice())
                            .divide(q.getUnitPrice(), 4, RoundingMode.HALF_UP));
                }
            }
            s.put("avgNegotiateDiscount", discounts.isEmpty() ? null
                    : discounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(discounts.size()), 4, RoundingMode.HALF_UP));
            suppliers.add(s);
        }
        // 按最新报价升序（低价在前）
        suppliers.sort(Comparator.comparing(
                m -> m.get("latestPrice") == null ? BigDecimal.valueOf(Long.MAX_VALUE)
                        : (BigDecimal) m.get("latestPrice")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categoryCode", code);
        result.put("categoryName", categoryName(code));
        result.put("suppliers", suppliers);
        result.put("crossCategoryRfqNos", ctx.crossCategoryRfqNos());
        return result;
    }

    // ---------- 供应商视角 ----------

    @Override
    public Map<String, Object> supplierView(String categoryCode, String supplierId) {
        if (categoryCode == null || categoryCode.trim().isEmpty()) {
            throw new ServiceException(422, "categoryCode 必填");
        }
        if (supplierId == null || supplierId.trim().isEmpty()) {
            throw new ServiceException(422, "supplierId 必填");
        }
        String code = categoryCode.trim();
        AggCtx ctx = loadCtx(code);
        List<QuoteRow> rows = new ArrayList<>();
        for (QuoteRow qr : ctx.rows) {
            if (supplierId.equals(qr.quote.getSupplierId())) {
                rows.add(qr);
            }
        }
        rows.sort(Comparator.comparing(q -> q.quote.getCreateDate() == null
                ? LocalDateTime.MIN : q.quote.getCreateDate()));
        List<Map<String, Object>> detail = new ArrayList<>();
        int awardCount = 0;
        List<BigDecimal> discounts = new ArrayList<>();
        for (QuoteRow qr : rows) {
            Quote q = qr.quote;
            Rfq r = qr.rfq;
            boolean awarded = r.getAwardSupplierId() != null
                    && r.getAwardSupplierId().equals(supplierId)
                    && RfqStateMachine.AWARDED.equals(r.getStatus());
            if (awarded) {
                awardCount++;
            }
            if (q.getNegotiatedPrice() != null && q.getUnitPrice() != null
                    && q.getUnitPrice().signum() > 0
                    && q.getNegotiatedPrice().compareTo(q.getUnitPrice()) < 0) {
                discounts.add(q.getUnitPrice().subtract(q.getNegotiatedPrice())
                        .divide(q.getUnitPrice(), 4, RoundingMode.HALF_UP));
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("rfqNo", r.getRfqNo());
            d.put("quoteDate", q.getCreateDate());
            d.put("unitPrice", q.getUnitPrice());
            d.put("negotiatedPrice", q.getNegotiatedPrice());
            d.put("price", eff(q));
            d.put("excluded", "1".equals(q.getExcluded()));
            d.put("excludedReason", q.getExcludedReason());
            d.put("crossCategory", qr.crossCategory);
            d.put("awarded", awarded);
            detail.add(d);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("quoteCount", rows.size());
        summary.put("awardCount", awardCount);
        summary.put("trend", rows.stream().map(qr -> eff(qr.quote)).toList());
        summary.put("avgNegotiateDiscount", discounts.isEmpty() ? null
                : discounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(discounts.size()), 4, RoundingMode.HALF_UP));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categoryCode", code);
        result.put("categoryName", categoryName(code));
        result.put("supplierId", supplierId);
        result.put("supplierName", supplierName(supplierId));
        result.put("rows", detail);
        result.put("summary", summary);
        return result;
    }

    // ---------- 比价记录（快照） ----------

    @Override
    public List<Map<String, Object>> snapshots() {
        List<AnalysisSnapshot> list = analysisSnapshotDao.selectList(
                new LambdaQueryWrapper<AnalysisSnapshot>()
                        .orderByDesc(AnalysisSnapshot::getUpdateDate));
        List<Map<String, Object>> result = new ArrayList<>();
        for (AnalysisSnapshot s : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("rfqId", s.getRfqId());
            m.put("rfqNo", s.getRfqNo());
            m.put("analysisNo", s.getAnalysisNo());
            m.put("awardSupplierId", s.getAwardSupplierId());
            m.put("awardSupplierName", supplierName(s.getAwardSupplierId()));
            m.put("awardPrice", s.getAwardPrice());
            m.put("createDate", s.getCreateDate());
            m.put("updateDate", s.getUpdateDate());
            result.add(m);
        }
        return result;
    }

    @Override
    public Map<String, Object> snapshot(String rfqId) {
        if (rfqId == null || rfqId.trim().isEmpty()) {
            throw new ServiceException(422, "rfqId 必填");
        }
        AnalysisSnapshot s = analysisSnapshotDao.selectOne(
                new LambdaQueryWrapper<AnalysisSnapshot>()
                        .eq(AnalysisSnapshot::getRfqId, rfqId.trim()));
        if (s == null) {
            throw new ServiceException(404, "该 RFQ 无比价结果快照（未定标）：" + rfqId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rfqId", s.getRfqId());
        result.put("rfqNo", s.getRfqNo());
        result.put("analysisNo", s.getAnalysisNo());
        result.put("awardSupplierId", s.getAwardSupplierId());
        result.put("awardPrice", s.getAwardPrice());
        result.put("updateDate", s.getUpdateDate());
        try {
            result.put("snapshot", jsonMapper.readValue(s.getSnapshotJson(), Map.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(500, "快照 JSON 解析失败：" + e.getMessage());
        }
        return result;
    }

    // ---------- 私有 ----------

    /** 单条报价透视行（报价 + 所属 RFQ + 是否跨品类） */
    private static final class QuoteRow {
        final Quote quote;
        final Rfq rfq;
        final boolean crossCategory;
        QuoteRow(Quote quote, Rfq rfq, boolean crossCategory) {
            this.quote = quote;
            this.rfq = rfq;
            this.crossCategory = crossCategory;
        }
    }

    /** 聚合上下文：一次加载品类 -> 行 -> RFQ -> 报价 */
    private final class AggCtx {
        final Map<String, Rfq> rfqs = new LinkedHashMap<>();
        final List<QuoteRow> rows = new ArrayList<>();
        private final Set<String> crossNos = new LinkedHashSet<>();

        Set<String> crossCategoryRfqNos() {
            return crossNos;
        }
    }

    private AggCtx loadCtx(String categoryCode) {
        AggCtx ctx = new AggCtx();
        // 1) 品类 -> 物料编码
        Set<String> itemCodes = new TreeSet<>();
        for (MdmItem item : itemDao.selectList(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getCategoryCode, categoryCode))) {
            itemCodes.add(item.getItemCode());
        }
        if (itemCodes.isEmpty()) {
            return ctx;   // 空品类：空结果（不 404，前端提示无数据）
        }
        // 2) 该品类物料所在 RFQ（命中行）
        List<RfqLine> hitLines = lineDao.selectList(new LambdaQueryWrapper<RfqLine>()
                .in(RfqLine::getItemCode, itemCodes));
        Set<String> rfqIds = new LinkedHashSet<>();
        hitLines.forEach(l -> rfqIds.add(l.getRfqId()));
        if (rfqIds.isEmpty()) {
            return ctx;
        }
        // 3) 这些 RFQ 的全部行（判定跨品类）
        List<RfqLine> allLines = lineDao.selectList(new LambdaQueryWrapper<RfqLine>()
                .in(RfqLine::getRfqId, rfqIds));
        Map<String, Set<String>> rfqCats = new LinkedHashMap<>();
        Map<String, String> itemCat = new LinkedHashMap<>();
        for (MdmItem item : itemDao.selectList(new LambdaQueryWrapper<MdmItem>()
                .in(MdmItem::getItemCode, itemCodes))) {
            itemCat.put(item.getItemCode(), item.getCategoryCode());
        }
        // 物料编码不在本品类集合的行，其品类需另查（跨品类判定）
        Set<String> unknown = new LinkedHashSet<>();
        for (RfqLine l : allLines) {
            if (!itemCat.containsKey(l.getItemCode())) {
                unknown.add(l.getItemCode());
            }
        }
        if (!unknown.isEmpty()) {
            for (MdmItem item : itemDao.selectList(new LambdaQueryWrapper<MdmItem>()
                    .in(MdmItem::getItemCode, unknown))) {
                itemCat.put(item.getItemCode(), item.getCategoryCode());
            }
        }
        for (RfqLine l : allLines) {
            String cat = itemCat.get(l.getItemCode());
            if (cat != null) {
                rfqCats.computeIfAbsent(l.getRfqId(), k -> new LinkedHashSet<>()).add(cat);
            }
        }
        // 4) RFQ 头 + 报价
        for (Rfq r : rfqDao.selectBatchIds(rfqIds)) {
            ctx.rfqs.put(r.getId(), r);
        }
        for (Quote q : quoteDao.selectList(new LambdaQueryWrapper<Quote>()
                .in(Quote::getRfqId, rfqIds))) {
            Rfq r = ctx.rfqs.get(q.getRfqId());
            if (r == null) {
                continue;
            }
            boolean cross = rfqCats.getOrDefault(q.getRfqId(), Set.of()).size() > 1;
            if (cross) {
                ctx.crossNos.add(r.getRfqNo());
            }
            ctx.rows.add(new QuoteRow(q, r, cross));
        }
        return ctx;
    }

    private String supplierName(String supplierId) {
        if (supplierId == null) {
            return null;
        }
        MdmSupplier s = mdmSupplierDao.selectById(supplierId);
        return s == null ? supplierId : s.getSupplierName();
    }

    private String categoryName(String categoryCode) {
        // MdmItemCategory 主键为 UUID id，categoryCode 是业务码（design D3）
        MdmItemCategory c = itemCategoryDao.selectOne(
                new LambdaQueryWrapper<MdmItemCategory>()
                        .eq(MdmItemCategory::getCategoryCode, categoryCode)
                        .last("LIMIT 1"));
        return c == null ? categoryCode : c.getCategoryName();
    }

    /** 有效价：谈判后优先（与矩阵 effPrice 同口径，剔除行为 null） */
    private static BigDecimal eff(Quote q) {
        if ("1".equals(q.getExcluded())) {
            return null;
        }
        return q.getNegotiatedPrice() != null ? q.getNegotiatedPrice() : q.getUnitPrice();
    }
}
