package com.erp.service.impl.mdm;

import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.dao.mdm.MdmCustomerViewDao;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.mdm.MdmCustomerView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 信用额度口径共享组件（design D1/D3）：
 * 求和校验（BR-4.1-31）与有效额度口径供准入 Service 与信用额度 Service 共用，避免双份实现漂移。
 *
 * 双口径（D3）：
 *  - 求和/占用率 = CREDIT_LIMIT 常规列（与 BR-4.1-31/32 约束口径一致，临时/压缩不参与）
 *  - 试算有效额度 = COALESCE(COMPRESSED_LIMIT, CREDIT_LIMIT) + 未过期临时额度
 */
@Component
public class CreditLimitSupport {

    private final MdmCustomerGroupDao groupDao;
    private final MdmCustomerViewDao viewDao;

    @Value("${app.mdm.group-credit-limit-ratio:1.0}")
    private BigDecimal groupCreditLimitRatio;

    public CreditLimitSupport(MdmCustomerGroupDao groupDao, MdmCustomerViewDao viewDao) {
        this.groupDao = groupDao;
        this.viewDao = viewDao;
    }

    public BigDecimal getRatio() {
        return groupCreditLimitRatio;
    }

    /**
     * BR-4.1-31：Σ本集团法人常规额度 × ratio ≤ 集团总额度；总额度空 → 放行（未配置口径）。
     * incoming 为待保存行（可带 id 表示更新，求和时剔除自身旧值）。
     */
    public void requireCreditSumWithinGroup(MdmCustomerView incoming) {
        if (incoming.getCreditLimit() == null) {
            return; // 本条无常规额度变更，求和不变
        }
        MdmCustomerGroup group = groupDao.selectById(incoming.getGroupId());
        if (group == null) {
            throw new ServiceException(404, "客户集团视图不存在");
        }
        if (group.getCreditLimitTotal() == null) {
            return; // 未配置集团总额度 → 放行（spec：未配置选宽松侧）
        }
        BigDecimal existingSum = viewDao.sumCreditLimit(incoming.getGroupId());
        if (incoming.getId() != null) {
            MdmCustomerView stored = viewDao.selectById(incoming.getId());
            if (stored != null && stored.getCreditLimit() != null) {
                existingSum = existingSum.subtract(stored.getCreditLimit());
            }
        }
        BigDecimal projected = existingSum.add(incoming.getCreditLimit())
                .multiply(groupCreditLimitRatio);
        if (projected.compareTo(group.getCreditLimitTotal()) > 0) {
            BigDecimal over = projected.subtract(group.getCreditLimitTotal()).setScale(2, RoundingMode.HALF_UP);
            throw new ServiceException(422, "各法人信用额度之和超出集团总额度（超出 " + over
                    + "），请调减本法人额度或申请集团额度上调（BR-4.1-31）");
        }
    }

    /** 试算口径有效额度（D3）：压缩值优先于常规值，临时额度须在有效期内（过期口径按 0，由懒校验负责真正回滚） */
    public BigDecimal effectiveLimit(MdmCustomerView view) {
        BigDecimal base = view.getCompressedLimit() != null ? view.getCompressedLimit()
                : (view.getCreditLimit() != null ? view.getCreditLimit() : BigDecimal.ZERO);
        BigDecimal temp = BigDecimal.ZERO;
        if (view.getTempCreditLimit() != null && view.getTempExpireDate() != null
                && view.getTempExpireDate().isAfter(LocalDate.now())) {
            temp = view.getTempCreditLimit();
        }
        return base.add(temp);
    }

    /**
     * 复审超期判定（C-4.3-13 台账，design D5）：
     * 有复审日期 → 距今 >12 个月超期；无复审日期 → 以建档日（视图 create_date）起算满 12 个月才算超期（宽限）。
     */
    public boolean reviewOverdue(MdmCustomerView view) {
        LocalDate now = LocalDate.now();
        if (view.getLastReviewDate() != null) {
            return view.getLastReviewDate().plusMonths(12).isBefore(now);
        }
        if (view.getCreateDate() != null) {
            return view.getCreateDate().toLocalDate().plusMonths(12).isBefore(now);
        }
        return false;
    }
}
