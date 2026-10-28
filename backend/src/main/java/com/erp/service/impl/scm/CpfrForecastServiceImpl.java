package com.erp.service.impl.scm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.scm.CpfrAgreementDao;
import com.erp.dao.scm.CpfrFeedbackDao;
import com.erp.dao.scm.CpfrForecastHeadDao;
import com.erp.dao.scm.CpfrForecastLineDao;
import com.erp.entity.scm.CpfrAgreement;
import com.erp.entity.scm.CpfrFeedback;
import com.erp.entity.scm.CpfrForecastHead;
import com.erp.entity.scm.CpfrForecastLine;
import com.erp.service.scm.CpfrClockService;
import com.erp.service.scm.CpfrForecastService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CPFR 预测版本/数据包（spec cpfr-collaboration R2）：
 * 预测量手工维护（丙决策）；生成数据包时自动快照库存/在途 PO/安全库存；
 * 推送 = 按协议生成反馈行 + 幂等键 + LAST_PERIOD_TAG（未启用协议 422）。
 */
@Service
public class CpfrForecastServiceImpl implements CpfrForecastService {

    private final CpfrForecastHeadDao headDao;
    private final CpfrForecastLineDao lineDao;
    private final CpfrAgreementDao agreementDao;
    private final CpfrFeedbackDao feedbackDao;
    private final JdbcTemplate jdbcTemplate;
    private final CpfrClockService clockService;

    public CpfrForecastServiceImpl(CpfrForecastHeadDao headDao,
                                   CpfrForecastLineDao lineDao,
                                   CpfrAgreementDao agreementDao,
                                   CpfrFeedbackDao feedbackDao,
                                   JdbcTemplate jdbcTemplate,
                                   CpfrClockService clockService) {
        this.headDao = headDao;
        this.lineDao = lineDao;
        this.agreementDao = agreementDao;
        this.feedbackDao = feedbackDao;
        this.jdbcTemplate = jdbcTemplate;
        this.clockService = clockService;
    }

    @Override
    public Page<Map<String, Object>> pageHeads(long current, long size, String periodTag) {
        Page<CpfrForecastHead> page = headDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<CpfrForecastHead>()
                        .eq(StringUtils.hasText(periodTag), CpfrForecastHead::getPeriodTag, periodTag)
                        .orderByDesc(CpfrForecastHead::getCreateDate));
        Page<Map<String, Object>> out = new Page<>(page.getCurrent(), page.getTotal(), page.getSize());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CpfrForecastHead h : page.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.getId());
            m.put("periodTag", h.getPeriodTag());
            m.put("status", h.getStatus());
            m.put("pushIdempotentKey", h.getPushIdempotentKey());
            m.put("pushedAt", h.getPushedAt());
            m.put("remark", h.getRemark());
            m.put("createDate", h.getCreateDate());
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> createHead(Map<String, Object> payload) {
        String periodTag = CpfrAgreementServiceImpl.str(payload.get("periodTag"));
        if (!StringUtils.hasText(periodTag) || periodTag.length() > 16) {
            throw new ServiceException(422, "周期标识必填且 ≤16 字符（如 2026W44 / 202610）");
        }
        CpfrForecastHead dup = headDao.selectOne(new LambdaQueryWrapper<CpfrForecastHead>()
                .eq(CpfrForecastHead::getPeriodTag, periodTag));
        if (dup != null) {
            throw new ServiceException(422, "该周期预测版本已存在：" + periodTag);
        }
        CpfrForecastHead h = new CpfrForecastHead();
        h.setPeriodTag(periodTag);
        h.setStatus(CpfrForecastHead.ST_DRAFT);
        h.setRemark(CpfrAgreementServiceImpl.str(payload.get("remark")));
        headDao.insert(h);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.getId());
        m.put("periodTag", h.getPeriodTag());
        m.put("status", h.getStatus());
        return m;
    }

    @Override
    @Transactional
    public Map<String, Object> saveLine(Map<String, Object> payload) {
        String headId = CpfrAgreementServiceImpl.str(payload.get("headId"));
        String agreementId = CpfrAgreementServiceImpl.str(payload.get("agreementId"));
        String itemCode = CpfrAgreementServiceImpl.str(payload.get("itemCode"));
        Integer weekOffset = CpfrAgreementServiceImpl.inte(payload.get("weekOffset"));
        BigDecimal qty = CpfrAgreementServiceImpl.dec(payload.get("forecastQty"));
        if (!StringUtils.hasText(headId) || !StringUtils.hasText(agreementId)
                || !StringUtils.hasText(itemCode) || weekOffset == null || qty == null) {
            throw new ServiceException(422, "headId/agreementId/itemCode/weekOffset/forecastQty 均必填");
        }
        CpfrForecastHead head = headDao.selectById(headId);
        if (head == null) {
            throw new ServiceException(404, "预测版本不存在");
        }
        if (!CpfrForecastHead.ST_DRAFT.equals(head.getStatus())) {
            throw new ServiceException(422, "仅 DRAFT 版本可维护预测行");
        }
        CpfrAgreement agr = agreementDao.selectById(agreementId);
        if (agr == null) {
            throw new ServiceException(404, "CPFR 协议不存在");
        }
        if (agr.getHorizonWeeks() != null && weekOffset > agr.getHorizonWeeks()) {
            throw new ServiceException(422, "周偏移超出展望期 " + agr.getHorizonWeeks() + " 周");
        }
        CpfrForecastLine line = lineDao.selectOne(new LambdaQueryWrapper<CpfrForecastLine>()
                .eq(CpfrForecastLine::getHeadId, headId)
                .eq(CpfrForecastLine::getAgreementId, agreementId)
                .eq(CpfrForecastLine::getItemCode, itemCode)
                .eq(CpfrForecastLine::getWeekOffset, weekOffset));
        if (line == null) {
            line = new CpfrForecastLine();
            line.setHeadId(headId);
            line.setAgreementId(agreementId);
            line.setItemCode(itemCode);
            line.setItemName(CpfrAgreementServiceImpl.str(payload.get("itemName")));
            line.setWeekOffset(weekOffset);
        }
        line.setForecastQty(qty);
        if (line.getId() == null) {
            lineDao.insert(line);
        } else {
            lineDao.updateById(line);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", line.getId());
        m.put("headId", headId);
        m.put("agreementId", agreementId);
        m.put("itemCode", itemCode);
        m.put("weekOffset", weekOffset);
        m.put("forecastQty", qty);
        return m;
    }

    @Override
    public List<Map<String, Object>> lines(String headId, String agreementId) {
        List<CpfrForecastLine> list = lineDao.selectList(new LambdaQueryWrapper<CpfrForecastLine>()
                .eq(CpfrForecastLine::getHeadId, headId)
                .eq(StringUtils.hasText(agreementId), CpfrForecastLine::getAgreementId, agreementId)
                .orderByAsc(CpfrForecastLine::getAgreementId)
                .orderByAsc(CpfrForecastLine::getWeekOffset));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CpfrForecastLine l : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("agreementId", l.getAgreementId());
            m.put("itemCode", l.getItemCode());
            m.put("itemName", l.getItemName());
            m.put("weekOffset", l.getWeekOffset());
            m.put("forecastQty", l.getForecastQty());
            m.put("snapshotStock", l.getSnapshotStock());
            m.put("snapshotOnhandPo", l.getSnapshotOnhandPo());
            m.put("safetyStock", l.getSafetyStock());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> generatePackage(String headId) {
        CpfrForecastHead head = headDao.selectById(headId);
        if (head == null) {
            throw new ServiceException(404, "预测版本不存在");
        }
        List<CpfrForecastLine> list = lineDao.selectList(new LambdaQueryWrapper<CpfrForecastLine>()
                .eq(CpfrForecastLine::getHeadId, headId));
        if (list.isEmpty()) {
            throw new ServiceException(422, "预测版本无行，无法生成数据包");
        }
        for (CpfrForecastLine l : list) {
            l.setSnapshotStock(queryStock(l.getItemCode()));
            l.setSnapshotOnhandPo(queryOnhandPo(l.getItemCode()));
            l.setSafetyStock(querySafetyStock(l.getItemCode()));
            lineDao.updateById(l);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("headId", headId);
        m.put("lineCount", list.size());
        m.put("generatedAt", clockService.now());
        return m;
    }

    @Override
    @Transactional
    public Map<String, Object> pushCycle(String headId) {
        CpfrForecastHead head = headDao.selectById(headId);
        if (head == null) {
            throw new ServiceException(404, "预测版本不存在");
        }
        if (!CpfrForecastHead.ST_DRAFT.equals(head.getStatus())
                && !CpfrForecastHead.ST_PUSHED.equals(head.getStatus())) {
            throw new ServiceException(422, "版本状态不可推送：" + head.getStatus());
        }
        List<CpfrForecastLine> list = lineDao.selectList(new LambdaQueryWrapper<CpfrForecastLine>()
                .eq(CpfrForecastLine::getHeadId, headId));
        if (list.isEmpty()) {
            throw new ServiceException(422, "预测版本无行，无法推送");
        }
        // 协议启用校验（未启用/暂停 → 422，BR-4.9-34 联动）
        List<String> agrIds = list.stream().map(CpfrForecastLine::getAgreementId).distinct().toList();
        LocalDateTime now = clockService.now();
        int created = 0;
        for (String agrId : agrIds) {
            CpfrAgreement agr = agreementDao.selectById(agrId);
            if (agr == null) {
                throw new ServiceException(422, "协议不存在：" + agrId);
            }
            CpfrAgreementServiceImpl.evaluateExpiryStatic(agr, clockService);
            if (!CpfrAgreement.ST_ACTIVE.equals(agr.getStatus())) {
                throw new ServiceException(422, "协议未启用或已暂停（" + agr.getSupplierName()
                        + "，" + agr.getStatus() + "），禁止推送预测数据包");
            }
        }
        // 幂等键：periodTag:v1（同版本重复推送复用键）
        String idemKey = head.getPeriodTag() + ":v1";
        for (CpfrForecastLine l : list) {
            CpfrFeedback existing = feedbackDao.selectOne(new LambdaQueryWrapper<CpfrFeedback>()
                    .eq(CpfrFeedback::getAgreementId, l.getAgreementId())
                    .eq(CpfrFeedback::getPeriodTag, head.getPeriodTag())
                    .eq(CpfrFeedback::getWeekOffset, l.getWeekOffset())
                    .eq(CpfrFeedback::getItemCode, l.getItemCode()));
            if (existing != null) {
                continue; // 幂等：已生成反馈行
            }
            CpfrFeedback fb = new CpfrFeedback();
            fb.setAgreementId(l.getAgreementId());
            fb.setPeriodTag(head.getPeriodTag());
            fb.setWeekOffset(l.getWeekOffset());
            fb.setItemCode(l.getItemCode());
            fb.setDemandQty(l.getForecastQty());
            fb.setStatus(CpfrFeedback.ST_PENDING);
            fb.setPeriodStartAt(now);
            feedbackDao.insert(fb);
            created++;
        }
        head.setStatus(CpfrForecastHead.ST_PUSHED);
        head.setPushIdempotentKey(idemKey);
        head.setPushedAt(now);
        headDao.updateById(head);
        for (String agrId : agrIds) {
            CpfrAgreement agr = agreementDao.selectById(agrId);
            agr.setLastPeriodTag(head.getPeriodTag());
            agreementDao.updateById(agr);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("headId", headId);
        m.put("periodTag", head.getPeriodTag());
        m.put("idempotentKey", idemKey);
        m.put("feedbackRowsCreated", created);
        m.put("pushedAt", now);
        return m;
    }

    /** 当前库存快照（可用量汇总，erp_inv_stock AVAILABLE_QTY） */
    BigDecimal queryStock(String itemCode) {
        BigDecimal v = jdbcTemplate.query(
                "SELECT COALESCE(SUM(AVAILABLE_QTY),0) FROM erp_inv_stock WHERE ITEM_CODE = ? AND DEL_FLAG = '0'",
                rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO, itemCode);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 在途 PO 快照（已审批未关闭 PO 行量汇总；erp_proc_po_line 无 DEL_FLAG，经 P 过滤） */
    BigDecimal queryOnhandPo(String itemCode) {
        BigDecimal v = jdbcTemplate.query(
                "SELECT COALESCE(SUM(L.QTY),0) FROM erp_proc_po_line L " +
                        "JOIN erp_proc_po P ON P.ID = L.PO_ID " +
                        "WHERE L.ITEM_CODE = ? AND P.STATUS IN ('APPROVED','CONFIRMED') " +
                        "AND P.DEL_FLAG = '0'",
                rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO, itemCode);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 安全库存（物料主数据 SAFETY_STOCK） */
    BigDecimal querySafetyStock(String itemCode) {
        BigDecimal v = jdbcTemplate.query(
                "SELECT COALESCE(SAFETY_STOCK,0) FROM erp_mdm_item WHERE ITEM_CODE = ? AND DEL_FLAG = '0' LIMIT 1",
                rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO, itemCode);
        return v == null ? BigDecimal.ZERO : v;
    }
}
