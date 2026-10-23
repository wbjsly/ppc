package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamAssetCategoryDao;
import com.erp.dao.eam.EamCountDiffDao;
import com.erp.dao.eam.EamCountLineDao;
import com.erp.dao.eam.EamCountPlanDao;
import com.erp.dao.eam.EamCountTeamMemberDao;
import com.erp.dao.eam.EamTransferOrderDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamAssetCategory;
import com.erp.entity.eam.EamCountLine;
import com.erp.entity.eam.EamCountPlan;
import com.erp.entity.eam.EamCountTeamMember;
import com.erp.service.eam.EamCountService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产盘点实现（add-asset-count，spec asset-count，design D3/D4）。
 * 清单下发=基准日物理快照（分批 500）；抽样=分类 RISK_LEVEL 比例类内随机；
 * 实盘=扫码命中/待确认/盘盈/盘亏 + CSV 导入导出；结案=覆盖率 L1 卡控 + 报告单签归档。
 */
@Slf4j
@Service
public class EamCountServiceImpl implements EamCountService {

    /** 已盘实态（覆盖率分子；UNKNOWN 待确认不计） */
    private static final List<String> COUNTED_STATUSES =
            List.of("IN_USE", "IDLE", "DAMAGED", "PENDING_SCRAP", "MISSING", "SURPLUS");
    /** 快照行标记：基准日后新增行不计入覆盖率（BR-29 单独列示） */
    private static final String POST_BASE_MARKER = "POST_BASE";

    private final EamCountPlanDao planDao;
    private final EamCountLineDao lineDao;
    private final EamCountDiffDao diffDao;
    private final EamCountTeamMemberDao memberDao;
    private final EamAssetCardDao cardDao;
    private final EamAssetCategoryDao categoryDao;
    private final EamTransferOrderDao transferDao;
    private final com.erp.service.SysParamService sysParamService;
    private final com.erp.service.approval.ApprovalEngine approvalEngine;

    public EamCountServiceImpl(EamCountPlanDao planDao,
                               EamCountLineDao lineDao,
                               EamCountDiffDao diffDao,
                               EamCountTeamMemberDao memberDao,
                               EamAssetCardDao cardDao,
                               EamAssetCategoryDao categoryDao,
                               EamTransferOrderDao transferDao,
                               com.erp.service.SysParamService sysParamService,
                               com.erp.service.approval.ApprovalEngine approvalEngine) {
        this.planDao = planDao;
        this.lineDao = lineDao;
        this.diffDao = diffDao;
        this.memberDao = memberDao;
        this.cardDao = cardDao;
        this.categoryDao = categoryDao;
        this.transferDao = transferDao;
        this.sysParamService = sysParamService;
        this.approvalEngine = approvalEngine;
    }

    // ================= 分页 / 详情 / CRUD =================

    @Override
    public Page<EamCountPlan> page(long current, long size, String status, String countType) {
        LambdaQueryWrapper<EamCountPlan> qw = new LambdaQueryWrapper<EamCountPlan>()
                .eq(hasText(status), EamCountPlan::getStatus, status)
                .eq(hasText(countType), EamCountPlan::getCountType, countType)
                .orderByDesc(EamCountPlan::getCreateDate);
        return planDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String id) {
        EamCountPlan plan = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", plan);
        out.put("team", memberDao.selectList(new LambdaQueryWrapper<EamCountTeamMember>()
                .eq(EamCountTeamMember::getPlanId, id)));
        out.put("diffSummary", diffDao.sumByType(id));
        out.put("coverage", coverageOf(id));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> create(EamCountPlan req) {
        String type = req.getCountType() == null ? "" : req.getCountType().trim().toUpperCase();
        if (!"FULL".equals(type) && !"SAMPLE".equals(type)) {
            throw new ServiceException(422, "盘点范围取值域：FULL 全面 / SAMPLE 抽样");
        }
        if (req.getBaseDate() == null) {
            throw new ServiceException(422, "盘点基准日必填（BR-29 快照锚）");
        }
        String method = req.getMethod() == null ? "SCAN" : req.getMethod().trim().toUpperCase();
        if (!"SCAN".equals(method) && !"MANUAL".equals(method)) {
            throw new ServiceException(422, "盘点方式取值域：SCAN 标签扫描 / MANUAL 手工对账");
        }
        EamCountPlan plan = new EamCountPlan();
        plan.setCntNo(nextCntNo());
        plan.setCountType(type);
        plan.setBaseDate(req.getBaseDate());
        plan.setMethod(method);
        plan.setPlanDesc(req.getPlanDesc());
        plan.setStartDate(req.getStartDate());
        plan.setEndDate(req.getEndDate());
        plan.setStatus("DRAFT");
        plan.setTotalQty(0);
        plan.setCountedQty(0);
        plan.setReportStatus("NONE");
        plan.setRemark(req.getRemark());
        plan.setCreateBy(SecurityUtils.getCurrentUserId());
        planDao.insert(plan);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", plan);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> update(EamCountPlan req) {
        EamCountPlan old = require(req.getId());
        if (!"DRAFT".equals(old.getStatus())) {
            throw new ServiceException(422, "仅草稿可编辑（当前 " + old.getStatus() + "）");
        }
        EamCountPlan upd = new EamCountPlan();
        upd.setId(old.getId());
        upd.setVerNo(old.getVerNo());
        upd.setBaseDate(req.getBaseDate() == null ? old.getBaseDate() : req.getBaseDate());
        upd.setMethod(req.getMethod() == null ? old.getMethod() : req.getMethod());
        upd.setPlanDesc(req.getPlanDesc());
        upd.setStartDate(req.getStartDate());
        upd.setEndDate(req.getEndDate());
        upd.setRemark(req.getRemark());
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        planDao.updateById(upd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", planDao.selectById(old.getId()));
        return out;
    }

    // ================= 人员分组 =================

    @Override
    @Transactional
    public Map<String, Object> maintainTeam(String planId, List<EamCountTeamMember> members) {
        EamCountPlan plan = require(planId);
        if ("CLOSED".equals(plan.getStatus())) {
            throw new ServiceException(422, "已结案盘点单不可维护人员");
        }
        // 整体覆盖式维护
        memberDao.delete(new LambdaQueryWrapper<EamCountTeamMember>()
                .eq(EamCountTeamMember::getPlanId, planId));
        int n = 0;
        if (members != null) {
            for (EamCountTeamMember m : members) {
                if (!hasText(m.getMemberName())) {
                    continue;
                }
                EamCountTeamMember row = new EamCountTeamMember();
                row.setPlanId(planId);
                row.setCntNo(plan.getCntNo());
                row.setMemberName(m.getMemberName());
                row.setGroupName(m.getGroupName());
                row.setIndependentFlag(m.getIndependentFlag() == null ? "1" : m.getIndependentFlag());
                row.setRemark(m.getRemark());
                row.setCreateBy(SecurityUtils.getCurrentUserId());
                memberDao.insert(row);
                n++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", n);
        return out;
    }

    @Override
    public List<EamCountTeamMember> team(String planId) {
        return memberDao.selectList(new LambdaQueryWrapper<EamCountTeamMember>()
                .eq(EamCountTeamMember::getPlanId, planId));
    }

    // ================= 抽样（BR-26）=================

    @Override
    public Map<String, Object> samplePreview() {
        Map<String, Integer> rates = parseSampleRates();
        List<EamAssetCategory> categories = categoryDao.selectList(
                new LambdaQueryWrapper<EamAssetCategory>().eq(EamAssetCategory::getDelFlag, "0"));
        List<Map<String, Object>> rows = new ArrayList<>();
        long totalCards = cardDao.selectCount(new LambdaQueryWrapper<EamAssetCard>()
                .eq(EamAssetCard::getStatus, "IN_USE"));
        long totalSample = 0;
        for (EamAssetCategory cat : categories) {
            String risk = hasText(cat.getRiskLevel()) ? cat.getRiskLevel().trim().toUpperCase() : "MID";
            int pct = rates.getOrDefault(risk, rates.getOrDefault("MID", 30));
            long cnt = cardDao.selectCount(new LambdaQueryWrapper<EamAssetCard>()
                    .eq(EamAssetCard::getStatus, "IN_USE")
                    .eq(EamAssetCard::getCategoryCode, cat.getCategoryCode()));
            long sample = Math.max(1, Math.round(cnt * pct / 100.0));
            if (cnt == 0) {
                sample = 0;
            }
            totalSample += sample;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("categoryCode", cat.getCategoryCode());
            row.put("categoryName", cat.getName());
            row.put("riskLevel", risk);
            row.put("sampleRate", pct);
            row.put("cardCount", cnt);
            row.put("sampleCount", sample);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rates", rates);
        out.put("categories", rows);
        out.put("totalCards", totalCards);
        out.put("totalSample", totalSample);
        return out;
    }

    /** 解析参数 ASSET_COUNT_SAMPLE_RATE（形如 HIGH=50,MID=30,LOW=10） */
    private Map<String, Integer> parseSampleRates() {
        Map<String, Integer> rates = new HashMap<>();
        rates.put("HIGH", 50);
        rates.put("MID", 30);
        rates.put("LOW", 10);
        String v = sysParamService.getValue("ASSET_COUNT_SAMPLE_RATE");
        if (!hasText(v)) {
            return rates;
        }
        for (String part : v.split(",")) {
            String[] kv = part.trim().split("=");
            if (kv.length == 2) {
                try {
                    rates.put(kv[0].trim().toUpperCase(), Integer.parseInt(kv[1].trim()));
                } catch (NumberFormatException ignore) {
                    // 非法条目跳过，保底默认
                }
            }
        }
        return rates;
    }

    // ================= 清单下发（BR-29 快照）=================

    @Override
    @Transactional
    public Map<String, Object> issue(String planId) {
        EamCountPlan plan = require(planId);
        if (!"DRAFT".equals(plan.getStatus())) {
            throw new ServiceException(422, "仅草稿计划可下发清单（当前 " + plan.getStatus() + "）");
        }
        long existing = lineDao.selectCount(new LambdaQueryWrapper<EamCountLine>()
                .eq(EamCountLine::getPlanId, planId));
        if (existing > 0) {
            throw new ServiceException(409, "清单已下发（明细 " + existing + " 行），不可重复下发");
        }
        LocalDate base = plan.getBaseDate();
        List<EamAssetCard> cards;
        if ("SAMPLE".equals(plan.getCountType())) {
            cards = sampleCards(base);
        } else {
            // FULL：基准日前启用且当前在册
            cards = cardDao.selectList(new LambdaQueryWrapper<EamAssetCard>()
                    .eq(EamAssetCard::getStatus, "IN_USE")
                    .le(EamAssetCard::getEffectiveDate, base));
        }
        if (cards.isEmpty()) {
            throw new ServiceException(422, "基准日无在册资产，清单为空（基准日 " + base + "）");
        }
        // 快照插入（分批 500，design D3）
        int inserted = 0;
        List<EamCountLine> batch = new ArrayList<>(500);
        for (EamAssetCard card : cards) {
            batch.add(snapshotLine(plan, card));
            if (batch.size() >= 500) {
                inserted += insertBatch(plan, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            inserted += insertBatch(plan, batch);
        }
        EamCountPlan upd = new EamCountPlan();
        upd.setId(plan.getId());
        upd.setVerNo(plan.getVerNo());
        upd.setStatus("ISSUED");
        upd.setTotalQty(inserted);
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        planDao.updateById(upd);
        log.info("盘点单 {} 清单下发：快照 {} 行（基准日 {}，类型 {}）",
                plan.getCntNo(), inserted, base, plan.getCountType());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", planId);
        out.put("snapshotRows", inserted);
        out.put("countType", plan.getCountType());
        return out;
    }

    /** 抽样选卡：按分类 RISK_LEVEL 比例 → 类内随机 → 类内至少 1 张兜底 → 全类别覆盖 */
    private List<EamAssetCard> sampleCards(LocalDate base) {
        Map<String, Integer> rates = parseSampleRates();
        List<EamAssetCategory> categories = categoryDao.selectList(
                new LambdaQueryWrapper<EamAssetCategory>().eq(EamAssetCategory::getDelFlag, "0"));
        List<EamAssetCard> picked = new ArrayList<>();
        for (EamAssetCategory cat : categories) {
            String risk = hasText(cat.getRiskLevel()) ? cat.getRiskLevel().trim().toUpperCase() : "MID";
            int pct = rates.getOrDefault(risk, rates.getOrDefault("MID", 30));
            List<EamAssetCard> pool = cardDao.selectList(new LambdaQueryWrapper<EamAssetCard>()
                    .eq(EamAssetCard::getStatus, "IN_USE")
                    .eq(EamAssetCard::getCategoryCode, cat.getCategoryCode())
                    .le(EamAssetCard::getEffectiveDate, base));
            if (pool.isEmpty()) {
                continue;   // 该分类基准日无在册，全类别覆盖以有卡分类为准
            }
            int quota = (int) Math.max(1, Math.round(pool.size() * pct / 100.0));
            // 类内随机：列表已全量取出，Java 侧 shuffle 取配额（万级卡可接受）
            List<EamAssetCard> shuffled = new ArrayList<>(pool);
            java.util.Collections.shuffle(shuffled, new java.util.Random());
            for (int i = 0; i < Math.min(quota, shuffled.size()); i++) {
                picked.add(shuffled.get(i));
            }
        }
        return picked;
    }

    private EamCountLine snapshotLine(EamCountPlan plan, EamAssetCard card) {
        EamCountLine line = new EamCountLine();
        line.setPlanId(plan.getId());
        line.setCntNo(plan.getCntNo());
        line.setCardId(card.getId());
        line.setCardNo(card.getCardNo());
        line.setSnapName(card.getAssetName());
        line.setSnapDept(card.getDeptName());
        line.setSnapCc(card.getCostCenterCode());
        line.setSnapLocation(card.getStorageLocation());
        line.setSnapCategory(card.getCategoryCode());
        line.setSnapCost(card.getAssetCost());
        line.setSnapAccum(card.getAccumDepr());
        line.setSnapNbv(nz(card.getAssetCost()).subtract(nz(card.getAccumDepr()))
                .subtract(nz(card.getImpairment())).setScale(2, RoundingMode.HALF_UP));
        line.setSnapStatus(card.getStatus());
        line.setDiffFlag("0");
        line.setRefreshLastUsedFlag("0");
        line.setCreateBy(SecurityUtils.getCurrentUserId());
        return line;
    }

    private int insertBatch(EamCountPlan plan, List<EamCountLine> batch) {
        for (EamCountLine line : batch) {
            lineDao.insert(line);
        }
        return batch.size();
    }

    // ================= 明细分页 =================

    @Override
    public Map<String, Object> linePage(String planId, long current, long size,
                                        String actualStatus, String diffType, String cardNo) {
        require(planId);
        LambdaQueryWrapper<EamCountLine> qw = new LambdaQueryWrapper<EamCountLine>()
                .eq(EamCountLine::getPlanId, planId)
                .eq(hasText(actualStatus), EamCountLine::getActualStatus, actualStatus)
                .eq(hasText(diffType), EamCountLine::getDiffType, diffType)
                .like(hasText(cardNo), EamCountLine::getCardNo, cardNo)
                .orderByAsc(EamCountLine::getCardNo);
        Page<EamCountLine> p = lineDao.selectPage(new Page<>(current, size), qw);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", p);
        return out;
    }

    // ================= 实盘录入 =================

    @Override
    @Transactional
    public Map<String, Object> countEntry(String planId, String cardNo, String actualStatus,
                                          String actualNote, boolean refreshLastUsed) {
        return countEntry(planId, cardNo, actualStatus, actualNote, refreshLastUsed, null);
    }

    @Override
    @Transactional
    public Map<String, Object> countEntry(String planId, String cardNo, String actualStatus,
                                          String actualNote, boolean refreshLastUsed,
                                          String categoryCode) {
        EamCountPlan plan = require(planId);
        requireCountable(plan);
        if (!hasText(cardNo) && !hasText(actualNote)) {
            throw new ServiceException(422, "卡号或实盘备注必填（无标签资产手工记录用备注描述）");
        }
        String status = actualStatus == null ? "" : actualStatus.trim().toUpperCase();
        if (!List.of("IN_USE", "IDLE", "DAMAGED", "PENDING_SCRAP", "MISSING", "UNKNOWN", "SURPLUS")
                .contains(status)) {
            throw new ServiceException(422, "实盘状态取值域：IN_USE/IDLE/DAMAGED/PENDING_SCRAP/MISSING/UNKNOWN/SURPLUS");
        }
        EamCountLine line = null;
        if (hasText(cardNo)) {
            line = findLine(planId, cardNo.trim());
        }
        if (line == null) {
            // 快照未命中：无标签待确认 / 盘盈（实物有账无）/ 基准日后新增
            return entryMiss(plan, cardNo, status, actualNote, refreshLastUsed, categoryCode);
        }
        if (hasText(line.getActualStatus())) {
            throw new ServiceException(409, "该行已实盘（" + line.getActualStatus() + "），如需修正请联系管理员");
        }
        EamCountLine upd = new EamCountLine();
        upd.setId(line.getId());
        upd.setVerNo(line.getVerNo());
        upd.setActualStatus(status);
        upd.setActualNote(actualNote);
        upd.setCountedBy(SecurityUtils.getCurrentUserId());
        upd.setCountedAt(LocalDateTime.now());
        upd.setCountMethod(hasText(cardNo) ? "SCAN" : "MANUAL");
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        // BR-29 基准日后变动单独列示：快照行正常实盘；若扫描的卡在基准日后才启用 → 标记
        EamAssetCard card = hasText(line.getCardId()) ? cardDao.selectById(line.getCardId()) : null;
        if (card != null && card.getEffectiveDate() != null
                && card.getEffectiveDate().isAfter(plan.getBaseDate())
                && !"MISSING".equals(status)) {
            upd.setActualNote(appendNote(actualNote, "基准日后新增资产，不计入覆盖率"));
            upd.setRemark(POST_BASE_MARKER);
        }
        // STATUS_CONFLICT：快照内卡当前已处置但实物在场
        if (card != null && "DISPOSED".equals(card.getStatus()) && !"MISSING".equals(status)) {
            upd.setDiffType("STATUS_CONFLICT");
            upd.setDiffFlag("1");
        }
        // 闲置锚刷新（S-4.13-13）
        boolean refreshed = false;
        if (refreshLastUsed && "IN_USE".equals(status) && card != null) {
            refreshed = refreshLastUsedAt(card);
        }
        lineDao.updateById(upd);
        markCounting(plan);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lineId", line.getId());
        out.put("cardNo", line.getCardNo());
        out.put("actualStatus", status);
        out.put("lastUsedRefreshed", refreshed);
        return out;
    }

    /** 快照未命中分支：无标签待确认 / 盘盈 / 基准日后新增 */
    private Map<String, Object> entryMiss(EamCountPlan plan, String cardNo, String status,
                                          String actualNote, boolean refreshLastUsed,
                                          String categoryCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!hasText(cardNo)) {
            // 无标签 + SURPLUS：盘盈（实物有账无、无标签，靠备注描述 + 盘点员判定分类）
            if ("SURPLUS".equals(status)) {
                EamCountLine line = newLine(plan, "SURPLUS-M" + System.nanoTime(), null);
                line.setSnapName(actualNote == null ? "未登记资产" : actualNote);
                line.setSnapCategory(hasText(categoryCode) ? categoryCode : null);
                line.setActualStatus("SURPLUS");
                line.setActualNote(actualNote);
                line.setCountedBy(SecurityUtils.getCurrentUserId());
                line.setCountedAt(LocalDateTime.now());
                line.setCountMethod("MANUAL");
                line.setDiffType("OVER");
                line.setDiffFlag("1");
                lineDao.insert(line);
                markCounting(plan);
                out.put("lineId", line.getId());
                out.put("diffType", "OVER");
                return out;
            }
            // 无标签：手工记录 → 待确认
            EamCountLine line = newLine(plan, "PEND-" + System.nanoTime(), null);
            line.setActualStatus("UNKNOWN");
            line.setActualNote(actualNote);
            line.setCountedBy(SecurityUtils.getCurrentUserId());
            line.setCountedAt(LocalDateTime.now());
            line.setCountMethod("MANUAL");
            line.setDiffType("PENDING_CONFIRM");
            line.setDiffFlag("1");
            lineDao.insert(line);
            markCounting(plan);
            out.put("lineId", line.getId());
            out.put("diffType", "PENDING_CONFIRM");
            return out;
        }
        EamAssetCard card = cardDao.selectOne(new LambdaQueryWrapper<EamAssetCard>()
                .eq(EamAssetCard::getCardNo, cardNo.trim())
                .last("LIMIT 1"));
        if (card == null) {
            // 实物有账无 → 盘盈待确认（合成号 SURPLUS-）
            EamCountLine line = newLine(plan, "SURPLUS-" + cardNo.trim(), null);
            line.setSnapName(actualNote == null ? cardNo.trim() : actualNote);
            line.setSnapCategory(hasText(categoryCode) ? categoryCode : null);
            line.setActualStatus("SURPLUS");
            line.setActualNote(actualNote);
            line.setCountedBy(SecurityUtils.getCurrentUserId());
            line.setCountedAt(LocalDateTime.now());
            line.setCountMethod("MANUAL");
            line.setDiffType("OVER");
            line.setDiffFlag("1");
            lineDao.insert(line);
            markCounting(plan);
            out.put("lineId", line.getId());
            out.put("diffType", "OVER");
            return out;
        }
        // 卡存在但不在快照：基准日后新增（单独列示不计覆盖率）
        EamCountLine line = newLine(plan, card.getCardNo(), card.getId());
        line.setSnapName(card.getAssetName());
        line.setSnapDept(card.getDeptName());
        line.setSnapCc(card.getCostCenterCode());
        line.setSnapLocation(card.getStorageLocation());
        line.setSnapCategory(card.getCategoryCode());
        line.setSnapCost(card.getAssetCost());
        line.setSnapAccum(card.getAccumDepr());
        line.setSnapNbv(nz(card.getAssetCost()).subtract(nz(card.getAccumDepr()))
                .subtract(nz(card.getImpairment())).setScale(2, RoundingMode.HALF_UP));
        line.setSnapStatus(card.getStatus());
        line.setActualStatus(status);
        line.setActualNote(appendNote(actualNote, "基准日后新增资产，不计入覆盖率"));
        line.setCountedBy(SecurityUtils.getCurrentUserId());
        line.setCountedAt(LocalDateTime.now());
        line.setCountMethod("SCAN");
        line.setRemark(POST_BASE_MARKER);
        if ("DISPOSED".equals(card.getStatus())) {
            line.setDiffType("STATUS_CONFLICT");
            line.setDiffFlag("1");
        }
        lineDao.insert(line);
        markCounting(plan);
        boolean refreshed = refreshLastUsed && "IN_USE".equals(status) && refreshLastUsedAt(card);
        out.put("lineId", line.getId());
        out.put("cardNo", card.getCardNo());
        out.put("actualStatus", status);
        out.put("lastUsedRefreshed", refreshed);
        out.put("postBase", true);
        return out;
    }

    // ================= CSV 导入 / 导出 =================

    @Override
    @Transactional
    public Map<String, Object> batchImport(String planId, String csvContent) {
        EamCountPlan plan = require(planId);
        requireCountable(plan);
        if (!hasText(csvContent)) {
            throw new ServiceException(422, "CSV 内容为空");
        }
        // 解析抽纯函数 CountCsvParser（BOM/表头/行号/列拆分可纯单测）
        List<com.erp.eam.CountCsvParser.Row> rows;
        try {
            rows = com.erp.eam.CountCsvParser.parse(csvContent);
        } catch (IllegalArgumentException ex) {
            throw new ServiceException(422, ex.getMessage());
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        int ok = 0;
        for (com.erp.eam.CountCsvParser.Row r : rows) {
            if (r.broken()) {
                errors.add(errRow(r.rowNo(), r.raw(), r.parseError()));
                continue;
            }
            try {
                countEntry(planId, r.cardNo(), r.status(), r.note(), r.refresh());
                ok++;
            } catch (ServiceException ex) {
                errors.add(errRow(r.rowNo(), r.raw(), ex.getMessage()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", ok + errors.size());
        out.put("success", ok);
        out.put("failed", errors.size());
        out.put("errors", errors);
        return out;
    }

    private Map<String, Object> errRow(int rowNo, String raw, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowNo", rowNo);
        m.put("content", raw.length() > 80 ? raw.substring(0, 80) : raw);
        m.put("error", msg);
        return m;
    }

    @Override
    public String exportCsv(String planId) {
        require(planId);
        StringBuilder sb = new StringBuilder("﻿卡号,资产名称,使用部门,成本中心,存放地点,分类,原值,NBV,快照状态,实盘状态,盘点人\n");
        int offset = 0;
        while (true) {
            Page<EamCountLine> p = lineDao.selectPage(new Page<>(offset / 1000 + 1, 1000),
                    new LambdaQueryWrapper<EamCountLine>()
                            .eq(EamCountLine::getPlanId, planId)
                            .orderByAsc(EamCountLine::getCardNo));
            for (EamCountLine line : p.getRecords()) {
                sb.append(csv(line.getCardNo())).append(',')
                        .append(csv(line.getSnapName())).append(',')
                        .append(csv(line.getSnapDept())).append(',')
                        .append(csv(line.getSnapCc())).append(',')
                        .append(csv(line.getSnapLocation())).append(',')
                        .append(csv(line.getSnapCategory())).append(',')
                        .append(line.getSnapCost() == null ? "" : line.getSnapCost().toPlainString()).append(',')
                        .append(line.getSnapNbv() == null ? "" : line.getSnapNbv().toPlainString()).append(',')
                        .append(csv(line.getSnapStatus())).append(',')
                        .append(csv(line.getActualStatus())).append(',')
                        .append(csv(line.getCountedBy()))
                        .append('\n');
            }
            offset += p.getRecords().size();
            if (offset >= p.getTotal() || p.getRecords().isEmpty()) {
                break;
            }
        }
        return sb.toString();
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v.replace("\"", "\"\"");
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s + "\"" : s;
    }

    // ================= 覆盖率 =================

    @Override
    @Transactional
    public Map<String, Object> refreshCoverage(String planId) {
        EamCountPlan plan = require(planId);
        Map<String, Object> cov = coverageOf(planId);
        EamCountPlan upd = new EamCountPlan();
        upd.setId(plan.getId());
        upd.setVerNo(plan.getVerNo());
        // 分子分母同口径：totalQty 随刷新回写（盘盈合成行计入分母，否则应盘≠已盘基数）
        upd.setTotalQty((Integer) cov.get("totalQty"));
        upd.setCountedQty((Integer) cov.get("countedQty"));
        upd.setCoverageRate((BigDecimal) cov.get("coverageRate"));
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        planDao.updateById(upd);
        return cov;
    }

    private Map<String, Object> coverageOf(String planId) {
        // 已盘 = 快照行（非 POST_BASE）且实态 ∈ COUNTED_STATUSES；UNKNOWN 待确认不计
        List<EamCountLine> lines = lineDao.selectList(new LambdaQueryWrapper<EamCountLine>()
                .eq(EamCountLine::getPlanId, planId));
        int total = 0;
        int counted = 0;
        List<String> unCounted = new ArrayList<>();
        for (EamCountLine line : lines) {
            if (POST_BASE_MARKER.equals(line.getRemark())) {
                continue;   // 基准日后新增不计覆盖率（BR-29）
            }
            total++;
            if (hasText(line.getActualStatus()) && COUNTED_STATUSES.contains(line.getActualStatus())) {
                counted++;
            } else if (unCounted.size() < 50) {
                unCounted.add(line.getCardNo());
            }
        }
        BigDecimal rate = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(counted).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalQty", total);
        out.put("countedQty", counted);
        out.put("coverageRate", rate);
        out.put("unCountedSample", unCounted);
        return out;
    }

    // ================= 基准日后变动（BR-29）=================

    @Override
    public Map<String, Object> postBaseChanges(String planId) {
        EamCountPlan plan = require(planId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cards", lineDao.selectCardsAfterBaseDate(plan.getBaseDate(), 200));
        out.put("transfers", transferDao.selectList(new LambdaQueryWrapper<com.erp.entity.eam.EamTransferOrder>()
                .eq(com.erp.entity.eam.EamTransferOrder::getStatus, "COMPLETED")
                .gt(com.erp.entity.eam.EamTransferOrder::getCompletedAt,
                        plan.getBaseDate().atStartOfDay())
                .orderByDesc(com.erp.entity.eam.EamTransferOrder::getCompletedAt)));
        return out;
    }

    // ================= 结案与报告（BR-26）=================

    @Override
    @Transactional
    public Map<String, Object> submitReport(String planId) {
        EamCountPlan plan = require(planId);
        if (!"DIFF_REVIEW".equals(plan.getStatus()) && !"ADJUSTING".equals(plan.getStatus())
                && !"COUNTING".equals(plan.getStatus())) {
            throw new ServiceException(422, "当前状态不可结案（" + plan.getStatus() + "）");
        }
        // 前置：全部差异 CLOSED
        int unsettled = diffDao.countUnsettled(planId);
        if (unsettled > 0) {
            throw new ServiceException(422, "存在 " + unsettled + " 条未闭环差异单，不可结案");
        }
        Map<String, Object> cov = refreshCoverage(planId);
        BigDecimal rate = (BigDecimal) cov.get("coverageRate");
        int total = (Integer) cov.get("totalQty");
        int counted = (Integer) cov.get("countedQty");
        @SuppressWarnings("unchecked")
        List<String> unCounted = (List<String>) cov.get("unCountedSample");
        // BR-26 L1：FULL 覆盖率 <100% 阻断结案并列未盘清单
        if ("FULL".equals(plan.getCountType()) && counted < total) {
            throw new ServiceException(422, "BR-4.13-26 覆盖率不足阻断结案：已盘 " + counted + "/" + total
                    + "（" + rate.multiply(BigDecimal.valueOf(100)).setScale(2) + "%），未盘卡片："
                    + String.join("、", unCounted));
        }
        // SAMPLE：实际抽样比例与全类别校验
        if ("SAMPLE".equals(plan.getCountType())) {
            validateSampleCoverage(plan, total);
        }
        // 差异汇总 → 报告
        List<Map<String, Object>> diffSummary = diffDao.sumByType(planId);
        BigDecimal diffAmount = BigDecimal.ZERO;
        StringBuilder diffDesc = new StringBuilder();
        for (Map<String, Object> d : diffSummary) {
            diffDesc.append(d.get("diffType")).append("×").append(d.get("cnt")).append("（")
                    .append(d.get("amount")).append("）；");
            diffAmount = diffAmount.add((BigDecimal) d.get("amount"));
        }
        String summary = "覆盖率 " + rate.multiply(BigDecimal.valueOf(100)).setScale(2) + "%（"
                + counted + "/" + total + "）；差异：" + (diffDesc.length() == 0 ? "无" : diffDesc)
                + " 改进建议：" + (diffAmount.compareTo(BigDecimal.ZERO) > 0
                ? "差异金额 " + diffAmount + "，建议缩短下次盘点周期并复核资产保管责任" : "账实相符，维持现有盘点周期");
        // 财务资产会计单签归档
        var inst = approvalEngine.submit("AssetCountReport", plan.getId(),
                "盘点报告归档审批：" + plan.getCntNo(), null,
                List.of(List.of(com.erp.service.approval.ApprovalNodeSpec
                        .sign("ROLE_FIN_ASSET", "财务资产会计审核"))));
        EamCountPlan upd = new EamCountPlan();
        upd.setId(plan.getId());
        upd.setVerNo(planDao.selectById(plan.getId()).getVerNo());
        upd.setStatus("ADJUSTING");
        upd.setReportStatus("PENDING");
        upd.setReportApprId(inst.getId());
        upd.setReportSummary(summary.length() > 1900 ? summary.substring(0, 1900) : summary);
        upd.setCoverageRate(rate);
        upd.setCountedQty(counted);
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        planDao.updateById(upd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", planId);
        out.put("apprId", inst.getId());
        out.put("reportSummary", summary);
        return out;
    }

    /** SAMPLE 结案校验：抽样比例不低于参数、覆盖全部有卡分类 */
    private void validateSampleCoverage(EamCountPlan plan, int total) {
        Map<String, Integer> rates = parseSampleRates();
        List<EamAssetCategory> categories = categoryDao.selectList(
                new LambdaQueryWrapper<EamAssetCategory>().eq(EamAssetCategory::getDelFlag, "0"));
        List<String> violated = new ArrayList<>();
        Map<String, Integer> snapCatCount = new HashMap<>();
        for (EamCountLine line : lineDao.selectList(new LambdaQueryWrapper<EamCountLine>()
                .eq(EamCountLine::getPlanId, plan.getId()))) {
            if (hasText(line.getSnapCategory())) {
                snapCatCount.merge(line.getSnapCategory(), 1, Integer::sum);
            }
        }
        for (EamAssetCategory cat : categories) {
            long cnt = cardDao.selectCount(new LambdaQueryWrapper<EamAssetCard>()
                    .eq(EamAssetCard::getStatus, "IN_USE")
                    .eq(EamAssetCard::getCategoryCode, cat.getCategoryCode())
                    .le(EamAssetCard::getEffectiveDate, plan.getBaseDate()));
            if (cnt == 0) {
                continue;
            }
            String risk = hasText(cat.getRiskLevel()) ? cat.getRiskLevel().trim().toUpperCase() : "MID";
            int pct = rates.getOrDefault(risk, rates.getOrDefault("MID", 30));
            int expected = (int) Math.max(1, Math.round(cnt * pct / 100.0));
            int actual = snapCatCount.getOrDefault(cat.getCategoryCode(), 0);
            if (actual < expected) {
                violated.add(cat.getCategoryCode() + "（抽 " + actual + "/" + expected
                        + "，风险 " + risk + " " + pct + "%）");
            }
        }
        if (!violated.isEmpty()) {
            throw new ServiceException(422, "BR-4.13-26 抽样比例不足或未覆盖全部类别，阻断结案："
                    + String.join("；", violated));
        }
    }

    @Override
    @Transactional
    public Map<String, Object> onReportApproved(String planId) {
        EamCountPlan plan = require(planId);
        EamCountPlan upd = new EamCountPlan();
        upd.setId(plan.getId());
        upd.setVerNo(plan.getVerNo());
        upd.setStatus("CLOSED");
        upd.setReportStatus("APPROVED");
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        planDao.updateById(upd);
        log.info("盘点单 {} 报告归档通过 → 已结案", plan.getCntNo());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", planId);
        out.put("status", "CLOSED");
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> onReportRejected(String planId, String reason) {
        EamCountPlan plan = require(planId);
        EamCountPlan upd = new EamCountPlan();
        upd.setId(plan.getId());
        upd.setVerNo(plan.getVerNo());
        upd.setStatus("DIFF_REVIEW");
        upd.setReportStatus("NONE");
        upd.setRejectReason(hasText(reason) ? reason : "报告审批驳回");
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        planDao.updateById(upd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", planId);
        out.put("status", "DIFF_REVIEW");
        return out;
    }

    // ================= 内部 =================

    private EamCountLine newLine(EamCountPlan plan, String cardNo, String cardId) {
        EamCountLine line = new EamCountLine();
        line.setPlanId(plan.getId());
        line.setCntNo(plan.getCntNo());
        line.setCardId(cardId);
        line.setCardNo(cardNo);
        line.setDiffFlag("0");
        line.setRefreshLastUsedFlag("0");
        line.setCreateBy(SecurityUtils.getCurrentUserId());
        return line;
    }

    private EamCountLine findLine(String planId, String cardNo) {
        return lineDao.selectOne(new LambdaQueryWrapper<EamCountLine>()
                .eq(EamCountLine::getPlanId, planId)
                .eq(EamCountLine::getCardNo, cardNo)
                .last("LIMIT 1"));
    }

    private boolean refreshLastUsedAt(EamAssetCard card) {
        EamAssetCard upd = new EamAssetCard();
        upd.setId(card.getId());
        upd.setVerNo(card.getVerNo());
        upd.setLastUsedAt(LocalDate.now());
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        return cardDao.updateById(upd) > 0;
    }

    private void markCounting(EamCountPlan plan) {
        if ("ISSUED".equals(plan.getStatus())) {
            EamCountPlan upd = new EamCountPlan();
            upd.setId(plan.getId());
            upd.setVerNo(plan.getVerNo());
            upd.setStatus("COUNTING");
            upd.setUpdateBy(SecurityUtils.getCurrentUserId());
            planDao.updateById(upd);
        }
    }

    private void requireCountable(EamCountPlan plan) {
        if (!List.of("ISSUED", "COUNTING").contains(plan.getStatus())) {
            throw new ServiceException(422, "当前状态不可实盘录入（" + plan.getStatus() + "，须已下发清单）");
        }
    }

    private EamCountPlan require(String id) {
        EamCountPlan plan = id == null ? null : planDao.selectById(id);
        if (plan == null) {
            throw new ServiceException(404, "盘点单不存在：" + id);
        }
        return plan;
    }

    private String nextCntNo() {
        String prefix = "CNT" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = planDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    private static String appendNote(String note, String suffix) {
        if (!hasText(note)) {
            return suffix;
        }
        return note.contains(suffix) ? note : note + "；" + suffix;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
