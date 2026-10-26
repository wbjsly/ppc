package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.InvoiceDeliveryDao;
import com.erp.dao.fin.InvoiceVoidApplyDao;
import com.erp.dao.fin.SalesInvoiceDao;
import com.erp.dao.sd.InvoiceApplyDao;
import com.erp.entity.fin.InvoiceDelivery;
import com.erp.entity.fin.InvoiceVoidApply;
import com.erp.entity.fin.SalesInvoice;
import com.erp.entity.sd.InvoiceApply;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 发票作废审批回调（BIZ_TYPE = RedInvoiceVoid，spec red-invoice-void）。
 * onApproved：执行作废（发票 VOID + 关联申请置作废终态 + 三条件并发复核）；
 * onRejected：置 REJECTED 留痕。当月未入账场景不生成凭证（原开票链路未产生凭证锚，
 * 作废幂等无副作用——design D6）。
 * executeVoid 内聚于本回调（仅依赖 DAO——若注入 RedVoidServiceImpl 会形成
 * ApprovalServiceImpl → Callback → Service → ApprovalEngine 循环）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedVoidApprovalCallback implements ApprovalCallback {

    private final InvoiceVoidApplyDao voidDao;
    private final SalesInvoiceDao invoiceDao;
    private final InvoiceDeliveryDao deliveryDao;
    private final InvoiceApplyDao invoiceApplyDao;

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(RedVoidServiceImpl.BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        InvoiceVoidApply a = voidDao.selectById(instance.getBizId());
        if (a == null) {
            log.warn("void approved but missing: {}", instance.getBizId());
            return;
        }
        if (InvoiceVoidApply.ST_VOIDED.equals(a.getStatus())) {
            return; // 幂等
        }
        executeVoid(a);
    }

    /** 执行作废：锁票 + 三条件并发复核（BR-4.14-24）→ VOID + 关联申请作废终态 */
    private void executeVoid(InvoiceVoidApply a) {
        SalesInvoice inv = invoiceDao.selectForUpdate(a.getInvoiceId());
        if (inv == null) {
            log.warn("void apply {} invoice missing: {}", a.getApplyNo(), a.getInvoiceId());
            return;
        }
        if (SalesInvoice.ST_VOID.equals(inv.getStatus())) {
            a.setStatus(InvoiceVoidApply.ST_VOIDED);
            voidDao.updateById(a);
            return;
        }
        long sent = deliveryDao.selectCount(new LambdaQueryWrapper<InvoiceDelivery>()
                .eq(InvoiceDelivery::getInvoiceNo, inv.getInvoiceNo())
                .eq(InvoiceDelivery::getStatus, InvoiceDelivery.STATUS_SENT)
                .eq(InvoiceDelivery::getDelFlag, "0"));
        if (sent > 0 || SalesInvoice.ST_CONFIRMED.equals(inv.getStatus())) {
            throw new ServiceException(422,
                    "审批期间发票已交付/已确认，作废条件失效，请驳回申请改走红冲（BR-4.14-24）");
        }
        inv.setStatus(SalesInvoice.ST_VOID);
        inv.setUpdateBy("approval");
        inv.setUpdateDate(LocalDateTime.now());
        invoiceDao.updateById(inv);
        var applies = invoiceApplyDao.selectList(new LambdaQueryWrapper<InvoiceApply>()
                .eq(InvoiceApply::getInvoiceId, inv.getId()));
        for (InvoiceApply ap : applies) {
            ap.setStatus(InvoiceApply.ST_CANCELLED);
            invoiceApplyDao.updateById(ap);
        }
        a.setStatus(InvoiceVoidApply.ST_VOIDED);
        a.setUpdateBy("approval");
        a.setUpdateDate(LocalDateTime.now());
        voidDao.updateById(a);
        log.info("void apply {} executed: invoice {} → VOID", a.getApplyNo(), inv.getInvoiceNo());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        InvoiceVoidApply a = voidDao.selectById(instance.getBizId());
        if (a == null || InvoiceVoidApply.ST_VOIDED.equals(a.getStatus())) {
            return;
        }
        a.setStatus(InvoiceVoidApply.ST_REJECTED);
        a.setUpdateBy("approval");
        a.setUpdateDate(LocalDateTime.now());
        voidDao.updateById(a);
        log.info("void apply {} rejected (invoice untouched)", a.getApplyNo());
    }
}
