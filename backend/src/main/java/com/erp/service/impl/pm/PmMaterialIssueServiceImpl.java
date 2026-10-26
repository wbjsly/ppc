package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmCostCollectDao;
import com.erp.dao.pm.PmMatIssueDao;
import com.erp.dao.pm.PmMatIssueLineDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.entity.pm.PmCostCollect;
import com.erp.entity.pm.PmMatIssue;
import com.erp.entity.pm.PmMatIssueLine;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmWbs;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.inv.StockPostingEngine;
import com.erp.service.pm.PmCostCollectService;
import com.erp.service.pm.PmMaterialIssueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 项目领料单实现（add-cost-collection-freeze，FR-4.8-5-1 / BR-4.8-20）。
 * 提交即真实扣库存（StockPostingEngine，类型 MATERIAL_OUT）；审核通过归集材料成本 + 凭证。
 * 单价快照由录入携带（库存无成本列，无移动加权数据源——design D2 偏差声明）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmMaterialIssueServiceImpl implements PmMaterialIssueService {

    private final PmMatIssueDao issueDao;
    private final PmMatIssueLineDao lineDao;
    private final PmProjectDao projectDao;
    private final PmWbsDao wbsDao;
    private final PmCostCollectService costCollectService;
    private final StockPostingEngine postingEngine;
    private final PmChangeInterceptor changeInterceptor;

    @Override
    public List<Map<String, Object>> list(String projectId, String status) {
        LambdaQueryWrapper<PmMatIssue> qw = new LambdaQueryWrapper<PmMatIssue>()
                .eq(PmMatIssue::getDelFlag, "0")
                .orderByDesc(PmMatIssue::getCreateDate);
        if (hasText(projectId)) {
            qw.eq(PmMatIssue::getProjectId, projectId);
        }
        if (hasText(status)) {
            qw.eq(PmMatIssue::getStatus, status);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmMatIssue i : issueDao.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("issue", i);
            row.put("lineCount", lineDao.selectCount(new LambdaQueryWrapper<PmMatIssueLine>()
                    .eq(PmMatIssueLine::getIssueId, i.getId())
                    .eq(PmMatIssueLine::getDelFlag, "0")));
            out.add(row);
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        PmMatIssue issue = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issue", issue);
        out.put("lines", lineDao.selectList(new LambdaQueryWrapper<PmMatIssueLine>()
                .eq(PmMatIssueLine::getIssueId, id)
                .eq(PmMatIssueLine::getDelFlag, "0")));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> save(Map<String, Object> payload, boolean submit) {
        String projectId = str(payload.get("projectId"));
        PmProject project = requireProject(projectId);
        // 冻结拦截（BR-4.8-21）
        if (PmProject.FREEZE_YES.equals(project.getFreezeFlag())) {
            throw new ServiceException(422, "项目已冻结（" + safe(project.getFreezeReason())
                    + "），不可新增领料，请先走超预算审批解冻");
        }
        Object rawLines = payload.get("lines");
        if (!(rawLines instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "明细行不能为空");
        }
        boolean isNew = true;
        PmMatIssue issue;
        String id = str(payload.get("id"));
        List<PmMatIssueLine> lines = new ArrayList<>();
        if (hasText(id)) {
            issue = issueDao.selectById(id);
            if (issue == null || "1".equals(issue.getDelFlag())) {
                throw new ServiceException(422, "领料单不存在");
            }
            if (!PmMatIssue.ST_DRAFT.equals(issue.getStatus()) && !PmMatIssue.ST_REJECTED.equals(issue.getStatus())) {
                throw new ServiceException(422, "仅草稿/已驳回领料单可修改");
            }
            isNew = false;
            // 替换式保存：旧明细删除（重新提交扣账）
            for (PmMatIssueLine old : lineDao.selectList(new LambdaQueryWrapper<PmMatIssueLine>()
                    .eq(PmMatIssueLine::getIssueId, id))) {
                lineDao.deleteById(old.getId());
            }
        } else {
            issue = new PmMatIssue();
            issue.setId(uuid());
            issue.setIssueNo(nextNo());
            issue.setCreateBy(currentUser());
            issue.setCreateDate(LocalDateTime.now());
        }
        issue.setProjectId(projectId);
        issue.setIssueDate(LocalDate.parse(str(payload.get("issueDate"))));
        issue.setRemark(str(payload.get("remark")));
        BigDecimal total = BigDecimal.ZERO;
        int i = 0;
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> m)) {
                continue;
            }
            i++;
            String wbsId = str(m.get("wbsId"));
            if (!hasText(wbsId)) {
                throw new ServiceException(422, "第 " + i + " 行未关联 WBS 任务（BR-4.8-20：领料必须关联任务，不可归集至未分配）");
            }
            PmWbs wbs = wbsDao.selectById(wbsId);
            if (wbs == null || "1".equals(wbs.getDelFlag())
                    || (wbs.getLevel() != null && wbs.getLevel() != PmWbs.LV_TASK)) {
                throw new ServiceException(422, "第 " + i + " 行 WBS 必须为任务级节点");
            }
            // 变更拦截（C-4.8-09，add-project-change）：提交时按 WBS 精准拦，草稿不拦
            if (submit) {
                changeInterceptor.assertNoPendingChange(projectId, wbsId);
            }
            String itemId = str(m.get("itemId"));
            if (!hasText(itemId)) {
                throw new ServiceException(422, "第 " + i + " 行物料必填");
            }
            BigDecimal qty = dec(m.get("qty"));
            BigDecimal price = dec(m.get("unitPrice"));
            if (qty == null || qty.signum() <= 0) {
                throw new ServiceException(422, "第 " + i + " 行数量须为正");
            }
            if (price == null || price.signum() < 0) {
                throw new ServiceException(422, "第 " + i + " 行单价非法（库存无成本列，须录入单价快照）");
            }
            PmMatIssueLine line = new PmMatIssueLine();
            line.setId(uuid());
            line.setIssueId(issue.getId());
            line.setWbsId(wbsId);
            line.setItemId(itemId);
            line.setItemName(str(m.get("itemName")));
            line.setBatchNo(str(m.get("batchNo")));
            line.setWarehouseCode(str(m.get("warehouseCode")));
            if (!hasText(line.getWarehouseCode())) {
                throw new ServiceException(422, "第 " + i + " 行仓库必填（扣库存维度）");
            }
            line.setQty(qty);
            line.setUnitPrice(price);
            line.setAmount(qty.multiply(price).setScale(2, RoundingMode.HALF_UP));
            line.setCreateBy(currentUser());
            line.setCreateDate(LocalDateTime.now());
            lines.add(line);
            total = total.add(line.getAmount());
        }
        if (lines.isEmpty()) {
            throw new ServiceException(422, "明细行不能为空");
        }
        issue.setStatus(PmMatIssue.ST_DRAFT);
        if (isNew) {
            issueDao.insert(issue);
        } else {
            issue.setUpdateBy(currentUser());
            issue.setUpdateDate(LocalDateTime.now());
            issueDao.updateById(issue);
        }
        for (PmMatIssueLine line : lines) {
            lineDao.insert(line);
        }
        if (submit) {
            // 提交：真实扣库存（引擎是 erp_inv_stock 唯一变更通道）
            List<StockPostingEngine.Line> postLines = new ArrayList<>();
            for (PmMatIssueLine line : lines) {
                StockPostingEngine.Line pl = new StockPostingEngine.Line();
                pl.warehouseCode = line.getWarehouseCode();
                pl.itemCode = line.getItemId();
                pl.itemName = line.getItemName();
                pl.batchNo = line.getBatchNo();
                pl.qty = line.getQty();
                postLines.add(pl);
            }
            postingEngine.post(StockPostingEngine.Request.of(
                    "MATERIAL_OUT", "PM_MI", issue.getIssueNo(), postLines));
            issue.setStatus(PmMatIssue.ST_SUBMITTED);
            issue.setUpdateDate(LocalDateTime.now());
            issueDao.updateById(issue);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", issue.getId());
        out.put("issueNo", issue.getIssueNo());
        out.put("status", issue.getStatus());
        out.put("totalAmount", total);
        out.put("lineCount", lines.size());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> review(String id, Map<String, Object> payload) {
        PmMatIssue issue = require(id);
        if (!PmMatIssue.ST_SUBMITTED.equals(issue.getStatus())) {
            throw new ServiceException(422, "仅已提交领料单可审核");
        }
        boolean approved = Boolean.parseBoolean(str(payload.get("approved")));
        String opinion = str(payload.get("opinion"));
        if (!approved && !hasText(opinion)) {
            throw new ServiceException(422, "驳回必须填写原因");
        }
        if (approved) {
            BigDecimal total = BigDecimal.ZERO;
            String firstWbs = null;
            for (PmMatIssueLine line : lineDao.selectList(new LambdaQueryWrapper<PmMatIssueLine>()
                    .eq(PmMatIssueLine::getIssueId, id)
                    .eq(PmMatIssueLine::getDelFlag, "0"))) {
                total = total.add(line.getAmount());
                if (firstWbs == null) {
                    firstWbs = line.getWbsId();
                }
                // 按明细行逐行归集（成本可追至任务）
                costCollectService.collect(PmCostCollectService.TYPE_MATERIAL,
                        PmCostCollectService.SRC_MAT_ISSUE, line.getId(),
                        issue.getProjectId(), line.getWbsId(), line.getAmount(), LocalDate.now());
            }
            if (total.signum() <= 0) {
                throw new ServiceException(422, "归集金额异常");
            }
            issue.setStatus(PmMatIssue.ST_APPROVED);
        } else {
            issue.setStatus(PmMatIssue.ST_REJECTED);
            issue.setRejectReason(opinion);
        }
        issue.setReviewBy(currentUser());
        issue.setReviewDate(LocalDateTime.now());
        issue.setUpdateDate(LocalDateTime.now());
        issueDao.updateById(issue);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", issue.getId());
        out.put("status", issue.getStatus());
        return out;
    }

    // ---- helpers ----

    private PmMatIssue require(String id) {
        PmMatIssue issue = issueDao.selectById(id);
        if (issue == null || "1".equals(issue.getDelFlag())) {
            throw new ServiceException(422, "领料单不存在");
        }
        return issue;
    }

    private PmProject requireProject(String projectId) {
        PmProject p = hasText(projectId) ? projectDao.selectById(projectId) : null;
        if (p == null) {
            throw new ServiceException(422, "项目不存在");
        }
        return p;
    }

    private String nextNo() {
        String prefix = "PMI" + LocalDate.now().toString().replace("-", "");
        Long n = issueDao.selectCount(new LambdaQueryWrapper<PmMatIssue>()
                .likeRight(PmMatIssue::getIssueNo, prefix));
        return prefix + String.format("%03d", (n == null ? 0 : n) + 1);
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
        return new BigDecimal(String.valueOf(o));
    }

    private static String currentUser() {
        try {
            return com.erp.util.SecurityUtils.getCurrentUserId();
        } catch (Exception e) {
            return "system";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
