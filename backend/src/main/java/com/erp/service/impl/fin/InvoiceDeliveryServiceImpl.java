package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.InvoiceDeliveryDao;
import com.erp.dao.fin.SalesInvoiceDao;
import com.erp.entity.fin.InvoiceDelivery;
import com.erp.entity.fin.SalesInvoice;
import com.erp.service.SysParamService;
import com.erp.service.fin.InvoiceDeliveryService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发票交付实现（M9-2-4，D6）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceDeliveryServiceImpl implements InvoiceDeliveryService {

    private final InvoiceDeliveryDao deliveryDao;
    private final SalesInvoiceDao salesInvoiceDao;
    private final SysParamService sysParamService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvoiceDelivery register(Map<String, Object> payload) {
        String invoiceNo = str(payload.get("invoiceNo"));
        String method = str(payload.get("method"));
        if (!hasText(invoiceNo)) {
            throw new ServiceException(422, "发票号必填");
        }
        if (!hasText(method)) {
            throw new ServiceException(422, "交付方式必填（EMAIL/SMS/LINK/MAIL/PICKUP）");
        }
        method = method.trim().toUpperCase();
        if (!List.of("EMAIL", "SMS", "LINK", "MAIL", "PICKUP").contains(method)) {
            throw new ServiceException(422, "交付方式非法：" + method);
        }
        // 销项票号须存在（SOURCE=SALES 手工登记）
        Long si = salesInvoiceDao.selectCount(new LambdaQueryWrapper<SalesInvoice>()
                .eq(SalesInvoice::getInvoiceNo, invoiceNo.trim())
                .eq(SalesInvoice::getDelFlag, "0"));
        if (si == null || si == 0) {
            throw new ServiceException(422, "销项发票不存在：" + invoiceNo);
        }
        InvoiceDelivery d = new InvoiceDelivery();
        d.setInvoiceNo(invoiceNo.trim());
        d.setSource(InvoiceDelivery.SRC_SALES);
        d.setSourceId(str(payload.get("sourceId")));
        d.setMethod(method);
        d.setContact(str(payload.get("contact")));
        d.setStatus(InvoiceDelivery.STATUS_PENDING);
        d.setRetryCount(0);
        d.setRemark(str(payload.get("remark")));
        d.setCreateBy(SecurityUtils.getCurrentUserId());
        d.setCreateDate(LocalDateTime.now());
        d.setVerNo(0);
        d.setDelFlag("0");
        deliveryDao.insert(d);
        return d;
    }

    @Override
    public List<Map<String, Object>> list(String status, String source) {
        LambdaQueryWrapper<InvoiceDelivery> q = new LambdaQueryWrapper<InvoiceDelivery>()
                .eq(InvoiceDelivery::getDelFlag, "0")
                .orderByDesc(InvoiceDelivery::getCreateDate);
        if (hasText(status)) {
            q.eq(InvoiceDelivery::getStatus, status.trim().toUpperCase());
        }
        if (hasText(source)) {
            q.eq(InvoiceDelivery::getSource, source.trim().toUpperCase());
        }
        int overdueDays = sysParamService.getInt("DELIVERY_OVERDUE_DAYS", 7);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(overdueDays);
        List<Map<String, Object>> out = new ArrayList<>();
        for (InvoiceDelivery d : deliveryDao.selectList(q)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("invoiceNo", d.getInvoiceNo());
            m.put("source", d.getSource());
            m.put("method", d.getMethod());
            m.put("contact", d.getContact());
            m.put("status", d.getStatus());
            m.put("sentAt", d.getSentAt());
            m.put("retryCount", d.getRetryCount());
            m.put("createDate", d.getCreateDate());
            // 超期黄标 L4（PENDING/FAILED 超阈值天数，视图语义不阻断）
            boolean overdue = !InvoiceDelivery.STATUS_SENT.equals(d.getStatus())
                    && d.getCreateDate() != null && d.getCreateDate().isBefore(cutoff);
            m.put("overdue", overdue);
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvoiceDelivery markSent(String id) {
        InvoiceDelivery d = require(id);
        d.setStatus(InvoiceDelivery.STATUS_SENT);
        d.setSentAt(LocalDateTime.now());
        d.setUpdateBy(SecurityUtils.getCurrentUserId());
        d.setUpdateDate(LocalDateTime.now());
        deliveryDao.updateById(d);
        return d;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvoiceDelivery resend(String id) {
        InvoiceDelivery d = require(id);
        int maxRetry = sysParamService.getInt("DELIVERY_MAX_RETRY", 2);
        int next = (d.getRetryCount() == null ? 0 : d.getRetryCount()) + 1;
        if (next > maxRetry) {
            throw new ServiceException(422, "重发次数已达上限（" + maxRetry + "）");
        }
        d.setRetryCount(next);
        d.setStatus(InvoiceDelivery.STATUS_FAILED);
        d.setUpdateBy(SecurityUtils.getCurrentUserId());
        d.setUpdateDate(LocalDateTime.now());
        deliveryDao.updateById(d);
        return d;
    }

    // ---------- helpers ----------

    private InvoiceDelivery require(String id) {
        InvoiceDelivery d = deliveryDao.selectById(id);
        if (d == null || "1".equals(d.getDelFlag())) {
            throw new ServiceException(422, "交付记录不存在：" + id);
        }
        return d;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
