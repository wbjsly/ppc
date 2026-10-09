package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.sd.SdQuoteDao;
import com.erp.dao.sd.QuoteLineDao;
import com.erp.dao.sd.QuoteVersionDao;
import com.erp.entity.sd.SdQuote;
import com.erp.entity.sd.QuoteLine;
import com.erp.entity.sd.QuoteVersion;
import com.erp.service.SysParamService;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报价发布/驳回落库组件：ApprovalCallback 只能注 DAO（ApprovalServiceImpl 构造注入
 * List&lt;ApprovalCallback&gt;，回调注入 QuoteService 会形成审批引擎构造循环），
 * 故把发布逻辑独立成无审批依赖的组件，由 QuoteService（自动审批）与
 * QuoteApprovalCallback（人工审批）共用。
 */
@Slf4j
@Component
public class QuotePublisher {

    private static final int DEFAULT_VALID_DAYS = 30;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SdQuoteDao quoteDao;
    private final QuoteLineDao lineDao;
    private final QuoteVersionDao versionDao;
    private final SysParamService paramService;

    public QuotePublisher(SdQuoteDao quoteDao, QuoteLineDao lineDao,
                          QuoteVersionDao versionDao, SysParamService paramService) {
        this.quoteDao = quoteDao;
        this.lineDao = lineDao;
        this.versionDao = versionDao;
        this.paramService = paramService;
    }

    /**
     * 发布：生成正式报价编号与有效期（默认 30 天参数化），置 PUBLISHED。幂等。
     * 可从 DRAFT（自动审批）或 PENDING（人工审批通过）进入。
     */
    @Transactional
    public SdQuote publish(String quoteId, String operator) {
        SdQuote q = quoteDao.selectById(quoteId);
        if (q == null) {
            throw new ServiceException(422, "报价不存在：" + quoteId);
        }
        if (SdQuote.ST_PUBLISHED.equals(q.getStatus()) && q.getQuoteNo() != null) {
            return q; // 幂等
        }
        if (!SdQuote.ST_PENDING.equals(q.getStatus()) && !SdQuote.ST_DRAFT.equals(q.getStatus())
                && !SdQuote.ST_REJECTED.equals(q.getStatus())) {
            throw new ServiceException(422, "报价状态不可发布：" + q.getStatus());
        }
        int validDays = paramService.getInt("QUOTE_VALID_DAYS", DEFAULT_VALID_DAYS);
        LocalDate today = LocalDate.now();
        q.setQuoteNo(nextNo());
        q.setValidFrom(today);
        q.setValidTo(today.plusDays(validDays));
        q.setStatus(SdQuote.ST_PUBLISHED);
        q.setPublishBy(operator);
        q.setPublishAt(LocalDateTime.now());
        quoteDao.updateById(q);
        // MP updateById 忽略 null：显式清除在途审批
        quoteDao.update(null, new LambdaUpdateWrapper<SdQuote>()
                .eq(SdQuote::getId, q.getId())
                .set(SdQuote::getApprovalId, null));
        saveVersion(q, QuoteVersion.OP_PUBLISH, operator, "发布，有效期至 " + q.getValidTo());
        log.info("quote published: {} → {} valid to {}", q.getDraftNo(), q.getQuoteNo(), q.getValidTo());
        return q;
    }

    /** 审批驳回：回 REJECTED 可改重提、清除在途审批。幂等。 */
    @Transactional
    public SdQuote reject(String quoteId, String operator, String opinion) {
        SdQuote q = quoteDao.selectById(quoteId);
        if (q == null) {
            return null;
        }
        if (SdQuote.ST_REJECTED.equals(q.getStatus())) {
            return q; // 幂等
        }
        if (!SdQuote.ST_PENDING.equals(q.getStatus())) {
            throw new ServiceException(422, "报价状态不可驳回：" + q.getStatus());
        }
        q.setStatus(SdQuote.ST_REJECTED);
        quoteDao.updateById(q);
        quoteDao.update(null, new LambdaUpdateWrapper<SdQuote>()
                .eq(SdQuote::getId, quoteId)
                .set(SdQuote::getApprovalId, null));
        saveVersion(q, QuoteVersion.OP_REJECT, operator, opinion);
        log.info("quote rejected: {} opinion={}", q.getDraftNo(), opinion);
        return q;
    }

    private void saveVersion(SdQuote q, String opType, String operator, String remark) {
        QuoteVersion v = new QuoteVersion();
        v.setQuoteId(q.getId());
        v.setVersionNo(q.getVersionNo() == null ? 1 : q.getVersionNo());
        v.setOpType(opType);
        v.setOperatorId(operator);
        v.setOperateAt(LocalDateTime.now());
        v.setRemark(remark);
        try {
            List<QuoteLine> lines = lineDao.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuoteLine>()
                            .eq(QuoteLine::getQuoteId, q.getId())
                            .orderByAsc(QuoteLine::getLineNo));
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("header", q);
            snap.put("lines", lines);
            v.setSnapshotJson(mapper.writeValueAsString(snap));
        } catch (Exception e) {
            v.setSnapshotJson("{}");
        }
        versionDao.insert(v);
    }

    private String nextNo() {
        String prefix = "QT" + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        for (String no : quoteDao.selectQuoteNosByPrefix(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 非规范编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    static String currentUser() {
        String id = SecurityUtils.getCurrentUserId();
        return id == null ? "system" : id;
    }
}
