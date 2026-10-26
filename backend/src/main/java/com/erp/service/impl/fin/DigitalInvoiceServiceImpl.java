package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.DecodedRequestDao;
import com.erp.dao.fin.DigitalQuotaDao;
import com.erp.dao.fin.InvoiceDeliveryDao;
import com.erp.dao.fin.SalesInvoiceDao;
import com.erp.entity.fin.DecodedRequest;
import com.erp.entity.fin.DigitalQuota;
import com.erp.entity.fin.InvoiceDelivery;
import com.erp.entity.fin.SalesInvoice;
import com.erp.service.SysParamService;
import com.erp.service.fin.DigitalInvoiceService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数电发票管理实现（M9-2-3，D5 离线模拟：额度+队列+补开拆分+勾稽）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigitalInvoiceServiceImpl implements DigitalInvoiceService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final DigitalQuotaDao quotaDao;
    private final DecodedRequestDao requestDao;
    private final InvoiceDeliveryDao deliveryDao;
    private final SalesInvoiceDao salesInvoiceDao;
    private final SysParamService sysParamService;

    // ---------- 额度 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DigitalQuota saveQuota(Map<String, Object> payload) {
        String period = str(payload.get("period"));
        BigDecimal total = dec(payload.get("totalLimit"), null);
        if (!hasText(period) || !period.trim().matches("\\d{6}") || total == null || total.signum() <= 0) {
            throw new ServiceException(422, "额度期间（YYYYMM）与总额度必填且为正");
        }
        DigitalQuota q = quotaDao.selectByPeriod(period.trim());
        if (q == null) {
            q = new DigitalQuota();
            q.setPeriod(period.trim());
            q.setUsedAmt(BigDecimal.ZERO);
            q.setCreateBy(SecurityUtils.getCurrentUserId());
            q.setCreateDate(LocalDateTime.now());
            q.setDelFlag("0");
            q.setVerNo(0);
        }
        q.setTotalLimit(total);
        q.setRemark(str(payload.get("remark")));
        q.setUpdateBy(SecurityUtils.getCurrentUserId());
        q.setUpdateDate(LocalDateTime.now());
        if (q.getId() == null) {
            quotaDao.insert(q);
        } else {
            quotaDao.updateById(q);
        }
        return q;
    }

    @Override
    public Map<String, Object> quota(String period) {
        DigitalQuota q = quotaDao.selectByPeriod(hasText(period) ? period.trim()
                : LocalDate.now().format(PERIOD_FMT));
        Map<String, Object> out = new LinkedHashMap<>();
        if (q == null) {
            out.put("exists", false);
            out.put("period", hasText(period) ? period.trim() : LocalDate.now().format(PERIOD_FMT));
            out.put("totalLimit", BigDecimal.ZERO);
            out.put("usedAmt", BigDecimal.ZERO);
            out.put("available", BigDecimal.ZERO);
            return out;
        }
        out.put("exists", true);
        out.put("id", q.getId());
        out.put("period", q.getPeriod());
        out.put("totalLimit", q.getTotalLimit());
        out.put("usedAmt", q.getUsedAmt());
        out.put("available", q.getTotalLimit().subtract(q.getUsedAmt()));
        return out;
    }

    // ---------- 请求队列 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DecodedRequest submitRequest(Map<String, Object> payload) {
        String clientName = str(payload.get("clientName"));
        BigDecimal amount = dec(payload.get("amount"), null);
        String soNo = str(payload.get("soNo"));
        String shipNo = str(payload.get("shipNo"));
        String lineNo = str(payload.get("lineNo"));
        if (!hasText(clientName) || amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "客户名称与请求金额必填");
        }
        // 幂等键（BR-4.14-37）：SO+出库+行
        String idem = (hasText(soNo) ? soNo.trim() : "-") + "#"
                + (hasText(shipNo) ? shipNo.trim() : "-") + "#"
                + (hasText(lineNo) ? lineNo.trim() : "0");
        if (requestDao.countIssuedByKey(idem) > 0) {
            throw new ServiceException(422, "幂等键已开具（" + idem + "，BR-4.14-37 重复开票阻断）");
        }
        // 额度校验（BR-4.14-36 L1）
        String period = LocalDate.now().format(PERIOD_FMT);
        DigitalQuota q = quotaDao.selectByPeriod(period);
        BigDecimal available = q == null ? BigDecimal.ZERO
                : q.getTotalLimit().subtract(q.getUsedAmt());
        if (available.compareTo(amount) < 0) {
            throw new ServiceException(422, "授信额度不足（BR-4.14-36）：本期可用 " + available
                    + " < 请求 " + amount + "，请税务专员申请调整额度");
        }
        DecodedRequest r = new DecodedRequest();
        r.setReqNo("DSR" + LocalDate.now().format(DAY)
                + String.format("%03d", requestDao.selectCount(new LambdaQueryWrapper<DecodedRequest>()
                        .likeRight(DecodedRequest::getReqNo, "DSR" + LocalDate.now().format(DAY))) + 1));
        r.setClientName(clientName.trim());
        r.setAmount(amount);
        r.setSoNo(hasText(soNo) ? soNo.trim() : null);
        r.setShipNo(hasText(shipNo) ? shipNo.trim() : null);
        r.setIdempotentKey(idem);
        r.setStatus(DecodedRequest.STATUS_PENDING);
        r.setSplitCount(1);
        r.setRemark(str(payload.get("remark")));
        r.setCreateBy(SecurityUtils.getCurrentUserId());
        r.setCreateDate(LocalDateTime.now());
        r.setVerNo(0);
        r.setDelFlag("0");
        requestDao.insert(r);
        return r;
    }

    @Override
    public List<DecodedRequest> listRequests(String status) {
        LambdaQueryWrapper<DecodedRequest> q = new LambdaQueryWrapper<DecodedRequest>()
                .eq(DecodedRequest::getDelFlag, "0")
                .orderByAsc(DecodedRequest::getCreateDate); // 按提交时序（BR-4.14-36 按序补开）
        if (hasText(status)) {
            q.eq(DecodedRequest::getStatus, status.trim().toUpperCase());
        }
        return requestDao.selectList(q);
    }

    // ---------- 补开（D5：拆分+扣额度+交付联动） ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> issue(String requestId) {
        DecodedRequest r = requestDao.selectById(requestId);
        if (r == null || "1".equals(r.getDelFlag())) {
            throw new ServiceException(422, "请求不存在：" + requestId);
        }
        if (DecodedRequest.STATUS_ISSUED.equals(r.getStatus())) {
            throw new ServiceException(422, "幂等键已开具（" + r.getIdempotentKey()
                    + "，BR-4.14-37 重复补开阻断）");
        }
        // 额度扣减（BR-4.14-36 二次校验——提交与补开间可能被他单占用）
        String period = LocalDate.now().format(PERIOD_FMT);
        DigitalQuota q = quotaDao.selectByPeriod(period);
        if (q == null) {
            throw new ServiceException(422, "本期（" + period + "）未登记授信额度");
        }
        BigDecimal available = q.getTotalLimit().subtract(q.getUsedAmt());
        if (available.compareTo(r.getAmount()) < 0) {
            throw new ServiceException(422, "授信额度不足（补开时可用 " + available
                    + " < " + r.getAmount() + "，BR-4.14-36）");
        }
        // 超限拆分（BR-4.14-15）：按金额降序二分——每张 ≤ 限额
        BigDecimal limit = sysParamService.getAmount("INVOICE_MAX_AMOUNT", new BigDecimal("100000"));
        List<String> invoiceNos = new ArrayList<>();
        int split = 1;
        if (r.getAmount().compareTo(limit) > 0) {
            split = r.getAmount().divide(limit, 0, RoundingMode.CEILING).intValue();
        }
        for (int i = 0; i < split; i++) {
            invoiceNos.add(nextInvoiceNo());
        }
        r.setStatus(DecodedRequest.STATUS_ISSUED);
        r.setInvoiceNos(String.join(",", invoiceNos));
        r.setSplitCount(split);
        r.setIssueAt(LocalDateTime.now());
        r.setUpdateBy(SecurityUtils.getCurrentUserId());
        r.setUpdateDate(LocalDateTime.now());
        requestDao.updateById(r);
        q.setUsedAmt(q.getUsedAmt().add(r.getAmount()));
        q.setUpdateBy(SecurityUtils.getCurrentUserId());
        q.setUpdateDate(LocalDateTime.now());
        quotaDao.updateById(q);

        // 自动建交付 PENDING 行（D6 联动，方式待选）
        List<String> deliveryIds = new ArrayList<>();
        for (String no : invoiceNos) {
            InvoiceDelivery d = new InvoiceDelivery();
            d.setInvoiceNo(no);
            d.setSource(InvoiceDelivery.SRC_DIGITAL);
            d.setSourceId(r.getId());
            d.setStatus(InvoiceDelivery.STATUS_PENDING);
            d.setRetryCount(0);
            d.setCreateBy(SecurityUtils.getCurrentUserId());
            d.setCreateDate(LocalDateTime.now());
            d.setVerNo(0);
            d.setDelFlag("0");
            deliveryDao.insert(d);
            deliveryIds.add(d.getId());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", r.getId());
        out.put("reqNo", r.getReqNo());
        out.put("status", r.getStatus());
        out.put("invoiceNos", r.getInvoiceNos());
        out.put("splitCount", split);
        out.put("deliveryIds", deliveryIds);
        out.put("quotaAvailable", q.getTotalLimit().subtract(q.getUsedAmt()));
        return out;
    }

    // ---------- 逐票勾稽（FR-4.14-6-4） ----------

    @Override
    public Map<String, Object> reconcile(String period) {
        String p = hasText(period) ? period.trim() : LocalDate.now().format(PERIOD_FMT);
        List<Map<String, Object>> issues = new ArrayList<>();

        // 重开嫌疑：同幂等键多条 ISSUED（防御性——唯一索引在正常流下已挡，此处兜底核查）
        List<DecodedRequest> issued = requestDao.selectList(new LambdaQueryWrapper<DecodedRequest>()
                .eq(DecodedRequest::getDelFlag, "0")
                .eq(DecodedRequest::getStatus, DecodedRequest.STATUS_ISSUED));
        Map<String, List<DecodedRequest>> byKey = new LinkedHashMap<>();
        for (DecodedRequest r : issued) {
            byKey.computeIfAbsent(r.getIdempotentKey(), k -> new ArrayList<>()).add(r);
        }
        byKey.forEach((key, rows) -> {
            if (rows.size() > 1) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "重开嫌疑");
                m.put("detail", "幂等键 " + key + " 存在 " + rows.size() + " 条 ISSUED："
                        + rows.stream().map(DecodedRequest::getInvoiceNos).toList());
                issues.add(m);
            }
        });

        // 漏开：期间内销项发票的 SO 在数电无对应 ISSUED 请求
        List<SalesInvoice> sales = salesInvoiceDao.selectList(new LambdaQueryWrapper<SalesInvoice>()
                .eq(SalesInvoice::getDelFlag, "0")
                .isNotNull(SalesInvoice::getSoNo)
                .apply("DATE_FORMAT(INVOICE_DATE, '%Y%m') = {0}", p));
        for (SalesInvoice si : sales) {
            boolean covered = issued.stream().anyMatch(r ->
                    si.getSoNo().equals(r.getSoNo()) && si.getInvoiceNo() != null
                    && r.getInvoiceNos() != null && r.getInvoiceNos().contains(si.getInvoiceNo()));
            if (!covered) {
                // 销项有票但数电无对应 ISSUED 关联——仅当该 SO 曾提交过数电请求才算漏开
                boolean requested = requestDao.selectCount(new LambdaQueryWrapper<DecodedRequest>()
                        .eq(DecodedRequest::getSoNo, si.getSoNo())
                        .eq(DecodedRequest::getDelFlag, "0")) > 0;
                if (requested) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", "漏开嫌疑");
                    m.put("detail", "SO " + si.getSoNo() + " 销项票 " + si.getInvoiceNo()
                            + " 在数电台账无对应 ISSUED 记录");
                    issues.add(m);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", p);
        out.put("issues", issues);
        out.put("issueCount", issues.size());
        out.put("allGreen", issues.isEmpty());
        return out;
    }

    // ---------- helpers ----------

    private String nextInvoiceNo() {
        String prefix = "DSI" + LocalDate.now().format(DAY);
        Long n = requestDao.selectCount(new LambdaQueryWrapper<DecodedRequest>()
                .isNotNull(DecodedRequest::getInvoiceNos)
                .likeRight(DecodedRequest::getInvoiceNos, prefix));
        return prefix + String.format("%03d", (n == null ? 0 : n) + 1);
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o, BigDecimal dflt) {
        if (o == null || (o instanceof String s && s.isBlank())) {
            return dflt;
        }
        return o instanceof BigDecimal bd ? bd : new BigDecimal(String.valueOf(o));
    }
}
