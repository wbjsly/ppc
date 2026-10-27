package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmCostCollectDao;
import com.erp.dao.pm.PmMatIssueDao;
import com.erp.dao.pm.PmMatIssueLineDao;
import com.erp.dao.pm.PmTrialConsumptionDao;
import com.erp.dao.pm.PmTrialConsumptionLineDao;
import com.erp.dao.pm.PmTrialReturnDao;
import com.erp.dao.pm.PmTrialReturnLineDao;
import com.erp.entity.pm.PmCostCollect;
import com.erp.entity.pm.PmMatIssue;
import com.erp.entity.pm.PmMatIssueLine;
import com.erp.entity.pm.PmTrialConsumption;
import com.erp.entity.pm.PmTrialConsumptionLine;
import com.erp.entity.pm.PmTrialReturn;
import com.erp.entity.pm.PmTrialReturnLine;
import com.erp.service.inv.StockPostingEngine;
import com.erp.service.pm.PmCostCollectService;
import com.erp.service.pm.PmTrialReturnService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 试制结余退库与成本冲减实现（add-rnd-material，design D4）。
 * REUSABLE → 入库（StockPostingEngine TRIAL_RETURN_IN）+ 负向成本归集冲减；
 * SCRAP → 不入库、成本保留；跨项目挪用 L1（批次须属原领料单）；结余校验；RETURNED_FLAG 防重复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmTrialReturnServiceImpl implements PmTrialReturnService {

    private final PmTrialReturnDao returnDao;
    private final PmTrialReturnLineDao returnLineDao;
    private final PmMatIssueDao issueDao;
    private final PmMatIssueLineDao issueLineDao;
    private final PmTrialConsumptionDao consumptionDao;
    private final PmTrialConsumptionLineDao consumptionLineDao;
    private final PmCostCollectService costCollectService;
    private final StockPostingEngine postingEngine;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    @Transactional
    public Map<String, Object> save(Map<String, Object> payload, boolean submit) {
        String id = str(payload.get("id"));
        boolean isNew = !hasText(id);
        PmTrialReturn ret;
        if (isNew) {
            ret = new PmTrialReturn();
            ret.setId(uuid());
            ret.setReturnNo(nextNo());
            ret.setStatus(PmTrialReturn.ST_DRAFT);
        } else {
            ret = requireReturn(id);
            if (!PmTrialReturn.ST_DRAFT.equals(ret.getStatus())
                    && !PmTrialReturn.ST_REJECTED.equals(ret.getStatus())) {
                throw new ServiceException(422, "仅草稿/已驳回退料单可修改");
            }
        }
        String issueId = str(payload.get("issueId"));
        PmMatIssue issue = requireIssue(issueId);
        if (!"1".equals(issue.getTrialFlag())) {
            throw new ServiceException(422, "仅研发试制领料单可退料");
        }
        ret.setIssueId(issueId);
        ret.setProjectId(issue.getProjectId());
        String me = SecurityUtils.getCurrentUserId();

        Object rawLines = payload.get("lines");
        if (!(rawLines instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "退库明细行不能为空");
        }
        // 删除旧行（替换式保存）
        for (PmTrialReturnLine old : returnLineDao.selectList(new LambdaQueryWrapper<PmTrialReturnLine>()
                .eq(PmTrialReturnLine::getReturnId, ret.getId()))) {
            returnLineDao.deleteById(old.getId());
        }
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> m)) {
                continue;
            }
            String itemId = str(m.get("itemId"));
            String batchNo = str(m.get("batchNo"));
            BigDecimal qty = dec(m.get("returnQty"));
            String qc = str(m.get("qcResult"));
            if (!hasText(itemId)) {
                throw new ServiceException(422, "退库行物料必填");
            }
            if (qty == null || qty.signum() <= 0) {
                throw new ServiceException(422, "物料 " + itemId + " 退库数须为正");
            }
            if (!PmTrialReturnLine.QC_REUSABLE.equals(qc) && !PmTrialReturnLine.QC_SCRAP.equals(qc)) {
                throw new ServiceException(422, "物料 " + itemId + " 质检结论须为可再用/报废");
            }
            // 跨项目挪用 L1（BR-4.8-04）：批次须属原领料单（不在原领料行中即视为外来/跨项目）
            PmMatIssueLine src = findIssueLine(issueId, itemId, batchNo);
            if (src == null) {
                throw new ServiceException(422, "物料 " + itemId + " 批次 " + batchNo
                        + " 不属于该试制领料单，禁止跨项目挪用（BR-4.8-04）");
            }
            // 结余校验：退库数 ≤ 该物料结余（消耗确认结余；无确认则领用数-已退数）
            BigDecimal surplus = surplusOf(issueId, itemId, ret.getId());
            if (qty.compareTo(surplus) > 0) {
                throw new ServiceException(422, "物料 " + itemId + " 退库数 " + qty
                        + " 超过可用结余 " + surplus);
            }
            BigDecimal price = src.getUnitPrice() == null ? BigDecimal.ZERO : src.getUnitPrice();
            PmTrialReturnLine line = new PmTrialReturnLine();
            line.setId(uuid());
            line.setReturnId(ret.getId());
            line.setItemId(itemId);
            line.setItemName(src.getItemName());
            line.setBatchNo(hasText(batchNo) ? batchNo : src.getBatchNo());
            line.setReturnQty(qty);
            line.setUnitPrice(price);
            line.setAmount(qty.multiply(price));
            line.setQcResult(qc);
            line.setReturnedFlag("0");
            line.setCreateBy(me);
            line.setCreateDate(LocalDateTime.now());
            returnLineDao.insert(line);
        }
        ret.setUpdateBy(me);
        ret.setUpdateDate(LocalDateTime.now());
        if (isNew) {
            ret.setCreateBy(me);
            ret.setCreateDate(LocalDateTime.now());
            returnDao.insert(ret);
        } else {
            returnDao.updateById(ret);
        }
        if (submit) {
            ret.setStatus(PmTrialReturn.ST_SUBMITTED);
            returnDao.updateById(ret);
        }
        return toMap(ret);
    }

    @Override
    @Transactional
    public Map<String, Object> review(String id, Map<String, Object> payload) {
        PmTrialReturn ret = requireReturn(id);
        if (!PmTrialReturn.ST_SUBMITTED.equals(ret.getStatus())) {
            throw new ServiceException(422, "仅已提交退料单可审核");
        }
        boolean approved = Boolean.parseBoolean(str(payload.get("approved")));
        String me = SecurityUtils.getCurrentUserId();
        if (!approved) {
            ret.setStatus(PmTrialReturn.ST_REJECTED);
            ret.setRejectReason(str(payload.get("opinion")));
            ret.setUpdateBy(me);
            ret.setUpdateDate(LocalDateTime.now());
            returnDao.updateById(ret);
            return toMap(ret);
        }
        // 审核通过：REUSABLE 入库+成本冲减；SCRAP 保留
        List<StockPostingEngine.Line> inLines = new ArrayList<>();
        for (PmTrialReturnLine line : returnLineDao.selectList(new LambdaQueryWrapper<PmTrialReturnLine>()
                .eq(PmTrialReturnLine::getReturnId, id)
                .eq(PmTrialReturnLine::getDelFlag, "0"))) {
            if ("1".equals(line.getReturnedFlag())) {
                continue; // 防重复冲减
            }
            if (PmTrialReturnLine.QC_REUSABLE.equals(line.getQcResult())) {
                // 入库（复用原领料行的仓库/批次）
                PmMatIssueLine src = findIssueLine(ret.getIssueId(), line.getItemId(), line.getBatchNo());
                StockPostingEngine.Line pl = new StockPostingEngine.Line();
                pl.warehouseCode = src == null ? "WH-02" : src.getWarehouseCode();
                pl.itemCode = line.getItemId();
                pl.itemName = line.getItemName();
                pl.batchNo = line.getBatchNo();
                pl.qty = line.getReturnQty();
                inLines.add(pl);
                // 负向成本归集冲减（WBS 取原领料行）
                costCollectService.collect(PmCostCollectService.TYPE_MATERIAL,
                        "TRIAL_RETURN", line.getId(), ret.getProjectId(),
                        src == null ? null : src.getWbsId(), line.getAmount().negate(), LocalDate.now());
            }
            // SCRAP：不入库、不冲减（成本保留在项目）
            line.setReturnedFlag("1");
            line.setUpdateBy(me);
            line.setUpdateDate(LocalDateTime.now());
            returnLineDao.updateById(line);
        }
        if (!inLines.isEmpty()) {
            postingEngine.post(StockPostingEngine.Request.of(
                    "TRIAL_RETURN_IN", "PM_TRIAL_RTN", ret.getReturnNo(), inLines));
        }
        ret.setStatus(PmTrialReturn.ST_APPROVED);
        ret.setReviewBy(me);
        ret.setReviewDate(LocalDateTime.now());
        ret.setUpdateBy(me);
        ret.setUpdateDate(LocalDateTime.now());
        returnDao.updateById(ret);
        return toMap(ret);
    }

    @Override
    public Map<String, Object> list(Map<String, Object> query) {
        LambdaQueryWrapper<PmTrialReturn> w = new LambdaQueryWrapper<PmTrialReturn>()
                .eq(PmTrialReturn::getDelFlag, "0")
                .orderByDesc(PmTrialReturn::getCreateDate);
        String projectId = str(query.get("projectId"));
        String issueId = str(query.get("issueId"));
        if (hasText(projectId)) {
            w.eq(PmTrialReturn::getProjectId, projectId);
        }
        if (hasText(issueId)) {
            w.eq(PmTrialReturn::getIssueId, issueId);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (PmTrialReturn r : returnDao.selectList(w)) {
            items.add(toMap(r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        PmTrialReturn ret = requireReturn(id);
        Map<String, Object> out = toMap(ret);
        out.put("lines", returnLineDao.selectList(new LambdaQueryWrapper<PmTrialReturnLine>()
                .eq(PmTrialReturnLine::getReturnId, id)
                .eq(PmTrialReturnLine::getDelFlag, "0")));
        return out;
    }

    // ================================================================
    // 工具
    // ================================================================

    /** 在原领料行中找物料+批次（batchNo 空则按物料匹配）；找不到即跨项目/外来 → L1 */
    private PmMatIssueLine findIssueLine(String issueId, String itemId, String batchNo) {
        LambdaQueryWrapper<PmMatIssueLine> w = new LambdaQueryWrapper<PmMatIssueLine>()
                .eq(PmMatIssueLine::getIssueId, issueId)
                .eq(PmMatIssueLine::getItemId, itemId)
                .eq(PmMatIssueLine::getDelFlag, "0");
        if (hasText(batchNo)) {
            w.eq(PmMatIssueLine::getBatchNo, batchNo);
        }
        return issueLineDao.selectOne(w.last("LIMIT 1"));
    }

    /** 该物料可用结余 = 消耗确认结余（或领用数）− 已退未冲减数 */
    private BigDecimal surplusOf(String issueId, String itemId, String excludeReturnId) {
        // 领用数
        PmMatIssueLine src = findIssueLine(issueId, itemId, null);
        BigDecimal issued = src == null || src.getQty() == null ? BigDecimal.ZERO : src.getQty();
        // 实耗数（从消耗确认取，无确认则 0）
        BigDecimal actual = BigDecimal.ZERO;
        PmTrialConsumption c = consumptionDao.selectOne(new LambdaQueryWrapper<PmTrialConsumption>()
                .eq(PmTrialConsumption::getIssueId, issueId)
                .eq(PmTrialConsumption::getDelFlag, "0")
                .last("LIMIT 1"));
        if (c != null) {
            PmTrialConsumptionLine cl = consumptionLineDao.selectOne(new LambdaQueryWrapper<PmTrialConsumptionLine>()
                    .eq(PmTrialConsumptionLine::getConsumptionId, c.getId())
                    .eq(PmTrialConsumptionLine::getItemId, itemId)
                    .eq(PmTrialConsumptionLine::getDelFlag, "0")
                    .last("LIMIT 1"));
            if (cl != null && cl.getActualQty() != null) {
                actual = cl.getActualQty();
            }
        }
        BigDecimal surplus = issued.subtract(actual);
        // 已退数（累加历史退料行，排除当前单）
        BigDecimal returned = BigDecimal.ZERO;
        for (PmTrialReturnLine rl : returnLineDao.selectList(new LambdaQueryWrapper<PmTrialReturnLine>()
                .eq(PmTrialReturnLine::getItemId, itemId)
                .eq(PmTrialReturnLine::getDelFlag, "0"))) {
            if (rl.getReturnId().equals(excludeReturnId)) {
                continue;
            }
            PmTrialReturn r = returnDao.selectById(rl.getReturnId());
            if (r != null && issueId.equals(r.getIssueId())
                    && !PmTrialReturn.ST_REJECTED.equals(r.getStatus())
                    && rl.getReturnQty() != null) {
                returned = returned.add(rl.getReturnQty());
            }
        }
        return surplus.subtract(returned);
    }

    private PmMatIssue requireIssue(String id) {
        PmMatIssue issue = issueDao.selectById(id);
        if (issue == null || "1".equals(issue.getDelFlag())) {
            throw new ServiceException(404, "领料单不存在");
        }
        return issue;
    }

    private PmTrialReturn requireReturn(String id) {
        PmTrialReturn r = returnDao.selectById(id);
        if (r == null || "1".equals(r.getDelFlag())) {
            throw new ServiceException(404, "退料单不存在");
        }
        return r;
    }

    private String nextNo() {
        String prefix = "RTR" + LocalDate.now().toString().replace("-", "");
        Integer seq = jdbc.queryForObject(
                "SELECT COUNT(*) + 1 FROM erp_pm_trial_return WHERE RETURN_NO LIKE ?",
                Integer.class, prefix + "%");
        return prefix + "-" + String.format("%03d", seq == null ? 1 : seq);
    }

    private Map<String, Object> toMap(PmTrialReturn r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("returnNo", r.getReturnNo());
        m.put("issueId", r.getIssueId());
        m.put("projectId", r.getProjectId());
        m.put("status", r.getStatus());
        m.put("reviewBy", r.getReviewBy());
        m.put("rejectReason", r.getRejectReason());
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
