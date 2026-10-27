package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmMatIssueDao;
import com.erp.dao.pm.PmMatIssueLineDao;
import com.erp.dao.pm.PmSampleLedgerDao;
import com.erp.dao.pm.PmTrialConsumptionDao;
import com.erp.dao.pm.PmTrialConsumptionLineDao;
import com.erp.entity.pm.PmMatIssue;
import com.erp.entity.pm.PmMatIssueLine;
import com.erp.entity.pm.PmSampleLedger;
import com.erp.entity.pm.PmTrialConsumption;
import com.erp.entity.pm.PmTrialConsumptionLine;
import com.erp.service.pm.PmTrialConsumptionService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 试制消耗确认实现（add-rnd-material，design D3）。
 * 一领料单一份确认（服务层防重 409）；差异>10% 无说明 l4Warning（不阻断）；
 * 样品台账 BATCH_NO 从领料行带出（BR-4.8-39 追溯）；CONFIRMED 后禁改（BR-4.8-26 惯例）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmTrialConsumptionServiceImpl implements PmTrialConsumptionService {

    /** 差异率阈值（%），超则 L4 提示研发负责人核实 */
    private static final BigDecimal DEVIATION_THRESHOLD = BigDecimal.TEN;

    private final PmTrialConsumptionDao consumptionDao;
    private final PmTrialConsumptionLineDao lineDao;
    private final PmSampleLedgerDao sampleDao;
    private final PmMatIssueDao issueDao;
    private final PmMatIssueLineDao issueLineDao;

    @Override
    @Transactional
    public Map<String, Object> confirm(Map<String, Object> payload) {
        String issueId = str(payload.get("issueId"));
        PmMatIssue issue = requireIssue(issueId);
        // 仅试制领料单可做消耗确认
        if (!"1".equals(issue.getTrialFlag())) {
            throw new ServiceException(422, "仅研发试制领料单可做消耗确认");
        }
        if (!PmMatIssue.ST_APPROVED.equals(issue.getStatus())) {
            throw new ServiceException(422, "仅已发料（APPROVED）的试制领料单可做消耗确认");
        }
        // 一领料单一份确认（防重 409）
        Long dup = consumptionDao.selectCount(new LambdaQueryWrapper<PmTrialConsumption>()
                .eq(PmTrialConsumption::getIssueId, issueId)
                .eq(PmTrialConsumption::getDelFlag, "0"));
        if (dup != null && dup > 0) {
            throw new ServiceException(409, "该领料单已存在消耗确认");
        }
        Object rawLines = payload.get("lines");
        if (!(rawLines instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "消耗明细行不能为空");
        }
        String me = SecurityUtils.getCurrentUserId();

        PmTrialConsumption c = new PmTrialConsumption();
        c.setId(uuid());
        c.setIssueId(issueId);
        c.setProjectId(issue.getProjectId());
        c.setReportAttachId(str(payload.get("reportAttachId")));
        c.setRemark(str(payload.get("remark")));
        c.setStatus(PmTrialConsumption.ST_CONFIRMED);
        c.setConfirmBy(me);
        c.setConfirmDate(LocalDateTime.now());
        c.setCreateBy(me);
        c.setCreateDate(LocalDateTime.now());
        c.setUpdateBy(me);
        c.setUpdateDate(LocalDateTime.now());

        BigDecimal totalIssued = BigDecimal.ZERO;
        BigDecimal totalActual = BigDecimal.ZERO;
        StringBuilder l4 = new StringBuilder();
        boolean deviationUnexplained = false;

        // 领料行快照：itemId → (qty, unitPrice, batchNo)
        Map<String, PmMatIssueLine> lineByItem = new LinkedHashMap<>();
        for (PmMatIssueLine il : issueLineDao.selectList(new LambdaQueryWrapper<PmMatIssueLine>()
                .eq(PmMatIssueLine::getIssueId, issueId)
                .eq(PmMatIssueLine::getDelFlag, "0"))) {
            lineByItem.put(il.getItemId(), il);
        }

        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> m)) {
                continue;
            }
            String itemId = str(m.get("itemId"));
            PmMatIssueLine src = lineByItem.get(itemId);
            if (src == null) {
                throw new ServiceException(422, "物料 " + itemId + " 不在该领料单中");
            }
            BigDecimal issued = src.getQty() == null ? BigDecimal.ZERO : src.getQty();
            BigDecimal actual = dec(m.get("actualQty"));
            if (actual == null || actual.signum() < 0) {
                throw new ServiceException(422, "物料 " + itemId + " 实耗数非法");
            }
            if (actual.compareTo(issued) > 0) {
                throw new ServiceException(422, "物料 " + itemId + " 实耗数不得超过领用数");
            }
            BigDecimal surplus = issued.subtract(actual);
            // 差异率（以领用数为基数）
            String note = str(m.get("deviationNote"));
            if (issued.signum() > 0) {
                BigDecimal devRate = actual.subtract(issued).abs()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(issued, 2, RoundingMode.HALF_UP);
                if (devRate.compareTo(DEVIATION_THRESHOLD) > 0) {
                    if (note == null || note.isBlank()) {
                        deviationUnexplained = true;
                        l4.append("物料 ").append(itemId).append(" 实耗与领用差异 ")
                           .append(devRate).append("% >10% 未说明，请研发负责人核实；");
                    }
                }
            }
            PmTrialConsumptionLine line = new PmTrialConsumptionLine();
            line.setId(uuid());
            line.setConsumptionId(c.getId());
            line.setIssueLineId(src.getId());
            line.setItemId(itemId);
            line.setItemName(src.getItemName());
            line.setIssuedQty(issued);
            line.setActualQty(actual);
            line.setSurplusQty(surplus);
            line.setUnitPrice(src.getUnitPrice());
            line.setDeviationNote(note);
            line.setCreateBy(me);
            line.setCreateDate(LocalDateTime.now());
            lineDao.insert(line);

            totalIssued = totalIssued.add(issued);
            totalActual = totalActual.add(actual);
        }
        c.setTotalIssued(totalIssued);
        c.setTotalActual(totalActual);
        c.setTotalSurplus(totalIssued.subtract(totalActual));
        c.setDeviationFlag(deviationUnexplained ? "1" : "0");
        consumptionDao.insert(c);

        // 样品台账随确认落库（BR-4.8-39：BATCH_NO 从领料行带出）
        Object rawSamples = payload.get("samples");
        if (rawSamples instanceof List<?> slist) {
            int seq = 1;
            for (Object raw : slist) {
                if (!(raw instanceof Map<?, ?> m)) {
                    continue;
                }
                String itemId = str(m.get("itemId"));
                PmMatIssueLine src = lineByItem.get(itemId);
                PmSampleLedger s = new PmSampleLedger();
                s.setId(uuid());
                s.setConsumptionId(c.getId());
                s.setIssueId(issueId);
                s.setSampleNo(str(m.get("sampleNo")));
                if (s.getSampleNo() == null || s.getSampleNo().isBlank()) {
                    s.setSampleNo("SMP-" + c.getId().substring(0, 8) + "-" + String.format("%02d", seq));
                }
                s.setName(str(m.get("name")));
                if (s.getName() == null || s.getName().isBlank()) {
                    throw new ServiceException(422, "样品名称必填");
                }
                s.setBatchNo(src == null ? str(m.get("batchNo")) : src.getBatchNo());
                s.setItemId(itemId);
                s.setQty(dec(m.get("qty")));
                String dest = str(m.get("destination"));
                if (!PmSampleLedger.DEST_TEST.equals(dest) && !PmSampleLedger.DEST_CUSTOMER.equals(dest)
                        && !PmSampleLedger.DEST_RETAIN.equals(dest) && !PmSampleLedger.DEST_SCRAP.equals(dest)) {
                    throw new ServiceException(422, "样品去向必须为测试/送客户/留样/报废四选一");
                }
                s.setDestination(dest);
                s.setStatus(PmSampleLedger.DEST_SCRAP.equals(dest) ? "SCRAPPED" : "AVAILABLE");
                s.setRegistrer(me);
                s.setRemark(str(m.get("remark")));
                s.setCreateBy(me);
                s.setCreateDate(LocalDateTime.now());
                sampleDao.insert(s);
                seq++;
            }
        }

        Map<String, Object> out = toMap(c);
        out.put("l4Warning", deviationUnexplained ? l4.toString() : null);
        return out;
    }

    @Override
    public Map<String, Object> list(Map<String, Object> query) {
        LambdaQueryWrapper<PmTrialConsumption> w = new LambdaQueryWrapper<PmTrialConsumption>()
                .eq(PmTrialConsumption::getDelFlag, "0")
                .orderByDesc(PmTrialConsumption::getCreateDate);
        String projectId = str(query.get("projectId"));
        String issueId = str(query.get("issueId"));
        if (hasText(projectId)) {
            w.eq(PmTrialConsumption::getProjectId, projectId);
        }
        if (hasText(issueId)) {
            w.eq(PmTrialConsumption::getIssueId, issueId);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (PmTrialConsumption c : consumptionDao.selectList(w)) {
            items.add(toMap(c));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        PmTrialConsumption c = requireConsumption(id);
        Map<String, Object> out = toMap(c);
        out.put("lines", lineDao.selectList(new LambdaQueryWrapper<PmTrialConsumptionLine>()
                .eq(PmTrialConsumptionLine::getConsumptionId, id)
                .eq(PmTrialConsumptionLine::getDelFlag, "0")));
        out.put("samples", sampleDao.selectList(new LambdaQueryWrapper<PmSampleLedger>()
                .eq(PmSampleLedger::getConsumptionId, id)
                .eq(PmSampleLedger::getDelFlag, "0")));
        return out;
    }

    @Override
    public Map<String, Object> sampleLedger(Map<String, Object> query) {
        LambdaQueryWrapper<PmSampleLedger> w = new LambdaQueryWrapper<PmSampleLedger>()
                .eq(PmSampleLedger::getDelFlag, "0")
                .orderByDesc(PmSampleLedger::getCreateDate);
        String issueId = str(query.get("issueId"));
        String batchNo = str(query.get("batchNo"));
        String destination = str(query.get("destination"));
        if (hasText(issueId)) {
            w.eq(PmSampleLedger::getIssueId, issueId);
        }
        if (hasText(batchNo)) {
            w.eq(PmSampleLedger::getBatchNo, batchNo);
        }
        if (hasText(destination)) {
            w.eq(PmSampleLedger::getDestination, destination);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (PmSampleLedger s : sampleDao.selectList(w)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("sampleNo", s.getSampleNo());
            m.put("name", s.getName());
            m.put("batchNo", s.getBatchNo());
            m.put("itemId", s.getItemId());
            m.put("qty", s.getQty());
            m.put("destination", s.getDestination());
            m.put("status", s.getStatus());
            m.put("issueId", s.getIssueId());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }

    // ================================================================
    // 工具
    // ================================================================

    private PmMatIssue requireIssue(String id) {
        PmMatIssue issue = issueDao.selectById(id);
        if (issue == null || "1".equals(issue.getDelFlag())) {
            throw new ServiceException(404, "领料单不存在");
        }
        return issue;
    }

    private PmTrialConsumption requireConsumption(String id) {
        PmTrialConsumption c = consumptionDao.selectById(id);
        if (c == null || "1".equals(c.getDelFlag())) {
            throw new ServiceException(404, "消耗确认单不存在");
        }
        return c;
    }

    private Map<String, Object> toMap(PmTrialConsumption c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("issueId", c.getIssueId());
        m.put("projectId", c.getProjectId());
        m.put("reportAttachId", c.getReportAttachId());
        m.put("totalIssued", c.getTotalIssued());
        m.put("totalActual", c.getTotalActual());
        m.put("totalSurplus", c.getTotalSurplus());
        m.put("deviationFlag", c.getDeviationFlag());
        m.put("remark", c.getRemark());
        m.put("status", c.getStatus());
        m.put("confirmBy", c.getConfirmBy());
        m.put("confirmDate", c.getConfirmDate() == null ? null : c.getConfirmDate().toString());
        return m;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "数值格式错误：" + o);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
