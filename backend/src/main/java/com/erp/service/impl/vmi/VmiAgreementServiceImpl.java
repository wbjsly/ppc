package com.erp.service.impl.vmi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.vmi.VmiAgreementDao;
import com.erp.dao.vmi.VmiAgreementLineDao;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.vmi.VmiAgreement;
import com.erp.entity.vmi.VmiAgreementLine;
import com.erp.service.vmi.VmiAgreementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * VMI 寄售协议实现（spec vmi-consignment）。
 * 命中口径（design D9）：同供应商、状态 EFFECTIVE、今日在 [effectiveDate, expireDate] 内。
 */
@Slf4j
@Service
public class VmiAgreementServiceImpl implements VmiAgreementService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final VmiAgreementDao agreeDao;
    private final VmiAgreementLineDao lineDao;
    private final MdmSupplierDao supplierDao;

    public VmiAgreementServiceImpl(VmiAgreementDao agreeDao,
                                   VmiAgreementLineDao lineDao,
                                   MdmSupplierDao supplierDao) {
        this.agreeDao = agreeDao;
        this.lineDao = lineDao;
        this.supplierDao = supplierDao;
    }

    // ---------- CRUD ----------

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        VmiAgreement a = fromPayload(payload, new VmiAgreement());
        a.setAgreeNo(nextAgreeNo());
        a.setStatus(VmiAgreement.ST_DRAFT);
        agreeDao.insert(a);
        List<VmiAgreementLine> lines = linesFromPayload(payload, a.getId());
        for (VmiAgreementLine l : lines) {
            lineDao.insert(l);
        }
        log.info("VMI 协议 {} created supplier={} lines={}", a.getAgreeNo(),
                a.getSupplierName(), lines.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agreement", a);
        out.put("lineCount", lines.size());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> payload) {
        VmiAgreement a = require(id);
        if (!VmiAgreement.ST_DRAFT.equals(a.getStatus())) {
            throw new ServiceException(422, "仅草稿状态可修改协议，当前 " + a.getStatus()
                    + "（如需调整请停用后新建）");
        }
        fromPayload(payload, a);
        agreeDao.updateById(a);
        lineDao.delete(new LambdaQueryWrapper<VmiAgreementLine>()
                .eq(VmiAgreementLine::getAgreeId, a.getId()));
        List<VmiAgreementLine> lines = linesFromPayload(payload, a.getId());
        for (VmiAgreementLine l : lines) {
            lineDao.insert(l);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agreement", a);
        out.put("lineCount", lines.size());
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        VmiAgreement a = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agreement", a);
        out.put("lines", lineDao.selectList(new LambdaQueryWrapper<VmiAgreementLine>()
                .eq(VmiAgreementLine::getAgreeId, a.getId())
                .orderByAsc(VmiAgreementLine::getItemCode)));
        return out;
    }

    @Override
    public Page<Map<String, Object>> page(long current, long size,
                                          String supplierId, String status) {
        LambdaQueryWrapper<VmiAgreement> qw = new LambdaQueryWrapper<VmiAgreement>()
                .eq(isNotBlank(supplierId), VmiAgreement::getSupplierId, supplierId)
                .eq(isNotBlank(status), VmiAgreement::getStatus, status)
                .orderByDesc(VmiAgreement::getCreateDate);
        Page<VmiAgreement> p = agreeDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VmiAgreement a : p.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agreementNo", a.getAgreeNo());
            row.put("id", a.getId());
            row.put("supplierId", a.getSupplierId());
            row.put("supplierName", a.getSupplierName());
            row.put("settleCycle", a.getSettleCycle());
            row.put("transferTime", a.getTransferTime());
            row.put("effectiveDate", a.getEffectiveDate());
            row.put("expireDate", a.getExpireDate());
            row.put("status", a.getStatus());
            row.put("remark", a.getRemark());
            row.put("createDate", a.getCreateDate());
            row.put("lineCount", lineDao.selectCount(new LambdaQueryWrapper<VmiAgreementLine>()
                    .eq(VmiAgreementLine::getAgreeId, a.getId())));
            rows.add(row);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    @Transactional
    public void effective(String id) {
        VmiAgreement a = require(id);
        if (!VmiAgreement.ST_DRAFT.equals(a.getStatus())) {
            throw new ServiceException(422, "仅草稿协议可生效，当前 " + a.getStatus());
        }
        Long cnt = lineDao.selectCount(new LambdaQueryWrapper<VmiAgreementLine>()
                .eq(VmiAgreementLine::getAgreeId, a.getId()));
        if (cnt == null || cnt == 0) {
            throw new ServiceException(422, "协议须至少一条寄售物料行方可生效");
        }
        if (a.getExpireDate() != null && a.getExpireDate().isBefore(LocalDate.now())) {
            throw new ServiceException(422, "协议失效日期早于今天，不可生效");
        }
        a.setStatus(VmiAgreement.ST_EFFECTIVE);
        agreeDao.updateById(a);
        log.info("VMI 协议 {} effective", a.getAgreeNo());
    }

    @Override
    @Transactional
    public void disable(String id) {
        VmiAgreement a = require(id);
        if (VmiAgreement.ST_DISABLED.equals(a.getStatus())) {
            throw new ServiceException(422, "协议已停用");
        }
        a.setStatus(VmiAgreement.ST_DISABLED);
        agreeDao.updateById(a);
        log.info("VMI 协议 {} disabled", a.getAgreeNo());
    }

    // ---------- 判定 / 卡控 / 计价 ----------

    @Override
    public Set<String> hitItems(String supplierId, Collection<String> itemCodes) {
        Set<String> out = new HashSet<>();
        if (!isNotBlank(supplierId) || itemCodes == null || itemCodes.isEmpty()) {
            return out;
        }
        LocalDate today = LocalDate.now();
        List<VmiAgreement> actives = effectiveNow(supplierId, today);
        if (actives.isEmpty()) {
            return out;
        }
        List<String> agreeIds = actives.stream().map(VmiAgreement::getId).toList();
        List<VmiAgreementLine> lines = lineDao.selectList(new LambdaQueryWrapper<VmiAgreementLine>()
                .in(VmiAgreementLine::getAgreeId, agreeIds));
        for (String item : itemCodes) {
            if (item == null) {
                continue;
            }
            for (VmiAgreementLine l : lines) {
                if (item.equals(l.getItemCode())) {
                    out.add(item);
                    break;
                }
            }
        }
        return out;
    }

    @Override
    public VmiAgreementLine lineFor(String supplierId, String itemCode) {
        if (!isNotBlank(supplierId) || !isNotBlank(itemCode)) {
            return null;
        }
        for (VmiAgreement a : statusEffective(supplierId)) {
            VmiAgreementLine hit = lineDao.selectOne(new LambdaQueryWrapper<VmiAgreementLine>()
                    .eq(VmiAgreementLine::getAgreeId, a.getId())
                    .eq(VmiAgreementLine::getItemCode, itemCode)
                    .last("LIMIT 1"));
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    @Override
    public VmiAgreementLine priceLineOn(String supplierId, String itemCode, LocalDate onDate) {
        if (!isNotBlank(supplierId) || !isNotBlank(itemCode) || onDate == null) {
            return null;
        }
        for (VmiAgreement a : effectiveNow(supplierId, onDate)) {
            List<VmiAgreementLine> lines = lineDao.selectList(
                    new LambdaQueryWrapper<VmiAgreementLine>()
                            .eq(VmiAgreementLine::getAgreeId, a.getId())
                            .eq(VmiAgreementLine::getItemCode, itemCode));
            for (VmiAgreementLine l : lines) {
                if (l.getPriceStart() != null && !onDate.isBefore(l.getPriceStart())
                        && l.getPriceEnd() != null && !onDate.isAfter(l.getPriceEnd())) {
                    return l;
                }
            }
        }
        return null;
    }

    @Override
    public void requireIssueable(String supplierId, LocalDate deliveryDate) {
        if (!isNotBlank(supplierId)) {
            throw new ServiceException(422, "供应商必填");
        }
        LocalDate today = LocalDate.now();
        LocalDate bound = deliveryDate == null ? today : deliveryDate;
        List<VmiAgreement> actives = statusEffective(supplierId);
        boolean ok = actives.stream().anyMatch(a ->
                (a.getEffectiveDate() == null || !today.isBefore(a.getEffectiveDate()))
                        && a.getExpireDate() != null && !a.getExpireDate().isBefore(bound));
        if (!ok) {
            throw new ServiceException(422, "VMI 协议有效期早于 PO 交期或协议未生效，请续签协议"
                    + "（BR-4.2-35）");
        }
    }

    // ---------- 私有 ----------

    private List<VmiAgreement> statusEffective(String supplierId) {
        return agreeDao.selectList(new LambdaQueryWrapper<VmiAgreement>()
                .eq(VmiAgreement::getSupplierId, supplierId)
                .eq(VmiAgreement::getStatus, VmiAgreement.ST_EFFECTIVE)
                .orderByDesc(VmiAgreement::getCreateDate)
                .last("LIMIT 100"));
    }

    private List<VmiAgreement> effectiveNow(String supplierId, LocalDate onDate) {
        List<VmiAgreement> out = new ArrayList<>();
        for (VmiAgreement a : statusEffective(supplierId)) {
            if ((a.getEffectiveDate() == null || !onDate.isBefore(a.getEffectiveDate()))
                    && (a.getExpireDate() == null || !onDate.isAfter(a.getExpireDate()))) {
                out.add(a);
            }
        }
        return out;
    }

    private VmiAgreement require(String id) {
        VmiAgreement a = agreeDao.selectById(id);
        if (a == null) {
            throw new ServiceException(404, "VMI 协议不存在：" + id);
        }
        return a;
    }

    private VmiAgreement fromPayload(Map<String, Object> payload, VmiAgreement a) {
        String supplierId = str(payload.get("supplierId"));
        if (!isNotBlank(supplierId)) {
            throw new ServiceException(422, "supplierId 必填");
        }
        MdmSupplier s = supplierDao.selectById(supplierId.trim());
        if (s == null) {
            throw new ServiceException(404, "供应商不存在：" + supplierId);
        }
        a.setSupplierId(supplierId.trim());
        a.setSupplierName(s.getSupplierName());
        String cycle = str(payload.get("settleCycle"));
        cycle = isNotBlank(cycle) ? cycle : VmiAgreement.CYCLE_MONTH;
        if (!VmiAgreement.CYCLE_WEEK.equals(cycle) && !VmiAgreement.CYCLE_MONTH.equals(cycle)) {
            throw new ServiceException(422, "settleCycle 须为 WEEK 或 MONTH");
        }
        a.setSettleCycle(cycle);
        String transfer = str(payload.get("transferTime"));
        a.setTransferTime(isNotBlank(transfer) ? transfer : "ISSUE");
        a.setEffectiveDate(parseDate(payload.get("effectiveDate"), "effectiveDate"));
        a.setExpireDate(parseDate(payload.get("expireDate"), "expireDate"));
        if (a.getEffectiveDate().isAfter(a.getExpireDate())) {
            throw new ServiceException(422, "生效日期不得晚于失效日期");
        }
        a.setRemark(str(payload.get("remark")));
        return a;
    }

    private List<VmiAgreementLine> linesFromPayload(Map<String, Object> payload, String agreeId) {
        Object raw = payload.get("lines");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "寄售物料清单行必填");
        }
        List<VmiAgreementLine> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new ServiceException(422, "物料行格式非法");
            }
            String itemCode = str(m.get("itemCode"));
            if (!isNotBlank(itemCode)) {
                throw new ServiceException(422, "物料编码必填");
            }
            if (!seen.add(itemCode)) {
                throw new ServiceException(422, "物料重复：" + itemCode);
            }
            VmiAgreementLine l = new VmiAgreementLine();
            l.setAgreeId(agreeId);
            l.setItemCode(itemCode.trim());
            l.setItemName(str(m.get("itemName")));
            l.setUnit(str(m.get("unit")));
            BigDecimal min = dec(m.get("minQty"));
            BigDecimal max = dec(m.get("maxQty"));
            l.setMinQty(min == null ? BigDecimal.ZERO : min);
            l.setMaxQty(max == null ? BigDecimal.ZERO : max);
            if (l.getMinQty().signum() < 0 || l.getMaxQty().signum() < 0) {
                throw new ServiceException(422, itemCode + " 水位不得为负数");
            }
            BigDecimal price = dec(m.get("unitPrice"));
            if (price == null || price.signum() <= 0) {
                throw new ServiceException(422, itemCode + " 协议单价须大于 0");
            }
            l.setUnitPrice(price);
            l.setPriceStart(parseDate(m.get("priceStart"), itemCode + " priceStart"));
            l.setPriceEnd(parseDate(m.get("priceEnd"), itemCode + " priceEnd"));
            if (l.getPriceStart().isAfter(l.getPriceEnd())) {
                throw new ServiceException(422, itemCode + " 价格条款起日不得晚于止日");
            }
            out.add(l);
        }
        return out;
    }

    private String nextAgreeNo() {
        String prefix = "VM" + LocalDateTime.now().format(MONTH) + "-";
        Integer max = agreeDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private static LocalDate parseDate(Object o, String field) {
        if (o == null || !isNotBlank(String.valueOf(o))) {
            throw new ServiceException(422, field + " 必填");
        }
        try {
            return LocalDate.parse(String.valueOf(o).trim());
        } catch (RuntimeException e) {
            throw new ServiceException(422, field + " 日期格式非法（yyyy-MM-dd）：" + o);
        }
    }

    private static BigDecimal dec(Object o) {
        if (o == null || !isNotBlank(String.valueOf(o))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "数值格式错误：" + o);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
