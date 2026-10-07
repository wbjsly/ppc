package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.dao.sd.SoChangeDao;
import com.erp.dao.sd.SoDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoChange;
import com.erp.entity.sd.SoLine;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 行级价格变更审批回调（BIZ_TYPE = PriceChange，BR-4.3-26）。
 * 通过 → 解锁行（priceLocked=0，显式 UPDATE）并重算头金额与毛利；
 * 驳回 → 恢复协议原价 + 解锁（改价不生效），留痕到 SO 变更台账。
 * 只注入 DAO（审批引擎同事务回调，禁注 Service）。
 */
@Slf4j
@Component
public class PriceChangeCallback implements ApprovalCallback {

    public static final String BIZ_TYPE = "PriceChange";

    private final SoLineDao lineDao;
    private final SoDao soDao;
    private final SoChangeDao changeDao;
    private final ApprovalTaskDao taskDao;
    private final MdmItemDao itemDao;

    public PriceChangeCallback(SoLineDao lineDao,
                               SoDao soDao,
                               SoChangeDao changeDao,
                               ApprovalTaskDao taskDao,
                               MdmItemDao itemDao) {
        this.lineDao = lineDao;
        this.soDao = soDao;
        this.changeDao = changeDao;
        this.taskDao = taskDao;
        this.itemDao = itemDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        SoLine line = lineDao.selectById(instance.getBizId());
        if (line == null) {
            log.warn("price change approved but line missing: {}", instance.getBizId());
            return;
        }
        String signer = signerOf(instance.getId(), "PASSED");
        // 解锁行（显式 UPDATE；MP updateById 忽略 null）
        lineDao.update(null, new LambdaUpdateWrapper<SoLine>()
                .eq(SoLine::getId, line.getId())
                .set(SoLine::getPriceLocked, "0")
                .set(SoLine::getPriceApprovalId, null));
        recalcHeader(line.getSoId());
        record(line, "PRICE_APPROVED", String.valueOf(line.getOriginalUnitPrice()),
                String.valueOf(line.getUnitPrice()), null,
                "价格变更审批通过（签署 " + signer + "）");
        log.info("SO line {} price change approved by {}", line.getId(), signer);
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        SoLine line = lineDao.selectById(instance.getBizId());
        if (line == null) {
            log.warn("price change rejected but line missing: {}", instance.getBizId());
            return;
        }
        String opinion = opinionOf(instance.getId());
        // 驳回 → 恢复协议原价 + 解锁（改价不生效）
        java.math.BigDecimal restored = line.getOriginalUnitPrice() != null
                ? line.getOriginalUnitPrice() : line.getUnitPrice();
        lineDao.update(null, new LambdaUpdateWrapper<SoLine>()
                .eq(SoLine::getId, line.getId())
                .set(SoLine::getUnitPrice, restored)
                .set(SoLine::getPriceLocked, "0")
                .set(SoLine::getPriceApprovalId, null)
                .set(SoLine::getAmount, restored.multiply(line.getQty())
                        .setScale(2, java.math.RoundingMode.HALF_UP)));
        recalcHeader(line.getSoId());
        record(line, "PRICE_REJECTED", String.valueOf(line.getUnitPrice()),
                String.valueOf(restored), opinion,
                "价格变更审批驳回，已恢复协议原价：" + opinion);
        log.info("SO line {} price change rejected: {}", line.getId(), opinion);
    }

    /** 头金额与毛利重算（改价生效/回退后同步，口径与 SoServiceImpl.recalc 一致） */
    private void recalcHeader(String soId) {
        So so = soDao.selectById(soId);
        if (so == null) {
            return;
        }
        List<SoLine> lines = lineDao.selectList(new LambdaQueryWrapper<SoLine>()
                .eq(SoLine::getSoId, soId));
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        java.math.BigDecimal cost = java.math.BigDecimal.ZERO;
        for (SoLine l : lines) {
            total = total.add(l.getAmount() == null ? java.math.BigDecimal.ZERO : l.getAmount());
            MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                    .eq(MdmItem::getItemCode, l.getItemCode()).last("LIMIT 1"));
            java.math.BigDecimal unitCost = item == null || item.getStandardCost() == null
                    ? java.math.BigDecimal.ZERO : item.getStandardCost();
            cost = cost.add(unitCost.multiply(l.getQty() == null
                    ? java.math.BigDecimal.ZERO : l.getQty())
                    .setScale(2, java.math.RoundingMode.HALF_UP));
        }
        so.setTotalAmount(total);
        so.setMarginRate(total.signum() > 0
                ? total.subtract(cost).divide(total, 4, java.math.RoundingMode.HALF_UP)
                : java.math.BigDecimal.ZERO);
        soDao.updateById(so);
    }

    private void record(SoLine line, String field, String oldV, String newV,
                        String opinion, String reason) {
        SoChange c = new SoChange();
        c.setId(uuid());
        c.setSoId(line.getSoId());
        c.setLineNo(line.getLineNo());
        c.setFieldName(field);
        c.setOldValue(oldV);
        c.setNewValue(newV);
        c.setReason(reason + (opinion == null ? "" : "（意见：" + opinion + "）"));
        c.setOperatorId("approval");
        c.setOperateAt(LocalDateTime.now());
        changeDao.insert(c);
    }

    private String signerOf(String instanceId, String status) {
        ApprovalTask t = task(instanceId, status);
        return t == null || t.getSigner() == null ? "system" : t.getSigner();
    }

    private String opinionOf(String instanceId) {
        ApprovalTask t = task(instanceId, "REJECTED");
        return t == null ? null : t.getOpinion();
    }

    private ApprovalTask task(String instanceId, String status) {
        List<ApprovalTask> list = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instanceId)
                .eq(ApprovalTask::getStatus, status));
        return list.isEmpty() ? null : list.get(0);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
