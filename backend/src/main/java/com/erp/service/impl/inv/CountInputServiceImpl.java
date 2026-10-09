package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvCheckDiffDao;
import com.erp.dao.inv.InvCountLineDao;
import com.erp.dao.inv.InvCountTaskDao;
import com.erp.entity.inv.ExpiryEval;
import com.erp.entity.inv.InvCheckDiff;
import com.erp.entity.inv.InvCountLine;
import com.erp.entity.inv.InvCountTask;
import com.erp.entity.mdm.MdmItem;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.inv.CountAdjustService;
import com.erp.service.inv.CountInputService;
import com.erp.service.inv.CountTaskService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实盘录入与差异分流实现（4.11，spec count-management 录入遮蔽/待复盘阻断/容差分流；design D4/D5）。
 * 逐行提交逐行分流：>10% 阻断标 RECOUNT；≤容差当场自动调整（共享调整通道）；
 * 超容差生成 DIFF_TYPE=COUNT 差异单挂 CountDiff 单节点审批（仓库主管），任务推进 ADJUSTING。
 */
@Slf4j
@Service
public class CountInputServiceImpl implements CountInputService {

    /** CountDiff 审批 BIZ_TYPE（spec count-management 审批与自动调整执行） */
    public static final String BIZ_COUNT_DIFF = "CountDiff";
    /** 容差复盘线（BR-4.4-39：>10% 标待复盘阻断） */
    private static final BigDecimal RECOUNT_RATE = new BigDecimal("0.10");

    private final InvCountLineDao lineDao;
    private final InvCountTaskDao taskDao;
    private final InvCheckDiffDao diffDao;
    private final MdmItemDao itemDao;
    private final SysParamService sysParamService;
    private final CountAdjustService adjustService;
    private final CountTaskService taskService;
    private final ApprovalEngine approvalEngine;
    private final com.erp.service.inv.CountDiffService diffService;
    private final com.erp.service.system.NoticeService noticeService;
    /** RECOUNT 标记独立提交（422 回滚不丢标记） */
    private final org.springframework.transaction.support.TransactionTemplate REQUIRES_NEW;

    public CountInputServiceImpl(InvCountLineDao lineDao, InvCountTaskDao taskDao,
                                 InvCheckDiffDao diffDao, MdmItemDao itemDao,
                                 SysParamService sysParamService,
                                 CountAdjustService adjustService,
                                 CountTaskService taskService, ApprovalEngine approvalEngine,
                                 com.erp.service.inv.CountDiffService diffService,
                                 com.erp.service.system.NoticeService noticeService,
                                 org.springframework.transaction.PlatformTransactionManager txMgr) {
        this.REQUIRES_NEW = new org.springframework.transaction.support.TransactionTemplate(txMgr);
        this.REQUIRES_NEW.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lineDao = lineDao;
        this.taskDao = taskDao;
        this.diffDao = diffDao;
        this.itemDao = itemDao;
        this.sysParamService = sysParamService;
        this.adjustService = adjustService;
        this.taskService = taskService;
        this.approvalEngine = approvalEngine;
        this.diffService = diffService;
        this.noticeService = noticeService;
    }

    /** BR-4.4-41 首期口径：该仓位累计 ≥2 张超容差差异单 → 通知仓库主管（幂等 BIZ_ID=仓位+日） */
    private void notifyWatchIfNeeded(String binCode) {
        try {
            boolean watched = diffService.watchList().stream()
                    .anyMatch(m -> str(m.get("binCode")).equals(str(binCode)));
            if (!watched) {
                return;
            }
            noticeService.push("ROLE_WAREHOUSE", null,
                    "盘点仓位进入重点监控：" + binCode,
                    "仓位 " + binCode + " 已累计 ≥2 次盘点差异超容差，纳入重点监控清单"
                            + "（频次自动上调待 ABC 排程落地，本期请人工加强复核）。",
                    "COUNT_WATCH", binCode + ":" + java.time.LocalDate.now());
        } catch (Exception e) {
            log.warn("count watch notify failed: {}", e.getMessage());   // 通知失败不阻断审批
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @Override
    @Transactional
    public Map<String, Object> submitCount(String lineId, BigDecimal actualQty,
                                           String abnormalFlag, String remark) {
        requireWarehouse("提交实盘");
        if (isBlank(lineId)) {
            throw new ServiceException(422, "盘点行必填");
        }
        if (actualQty == null || actualQty.signum() < 0) {
            throw new ServiceException(422, "实盘数量必填且不可为负");
        }
        InvCountLine l = lineDao.selectById(lineId);
        if (l == null) {
            throw new ServiceException(422, "盘点行不存在");
        }
        InvCountTask task = taskDao.selectById(l.getTaskId());
        if (task == null || !InvCountTask.ST_COUNTING.equals(task.getStatus())
                && !InvCountTask.ST_ADJUSTING.equals(task.getStatus())) {
            throw new ServiceException(422, "任务不在可录入状态（"
                    + (task == null ? "不存在" : task.getStatus()) + "）");
        }

        // 差异三列计算（spec 差异分析：DIFF_RATE 账面 0 实盘 >0 按 100%）
        BigDecimal book = nvl(l.getBookQty());
        BigDecimal diffQty = actualQty.subtract(book);
        BigDecimal diffRate;
        if (book.signum() == 0) {
            diffRate = diffQty.signum() == 0 ? BigDecimal.ZERO : BigDecimal.ONE;
        } else {
            diffRate = diffQty.abs().divide(book, 6, RoundingMode.HALF_UP);
        }
        BigDecimal unitCost = standardCost(l.getItemCode());
        BigDecimal diffAmount = diffQty.abs().multiply(unitCost).setScale(2, RoundingMode.HALF_UP);

        l.setActualQty(actualQty);
        l.setDiffQty(diffQty);
        l.setDiffRate(diffRate);
        l.setDiffAmount(diffAmount);
        l.setAbnormalFlag(abnormalFlag);
        l.setRemark(remark);
        l.setCountedBy(SecurityUtils.getCurrentUserId());
        l.setCountedAt(LocalDateTime.now());

        // >10% → 阻断标待复盘（BR-4.4-39 L1 保留）。
        // 标记须在 422 回滚后仍落库 → REQUIRES_NEW 独立提交（spec 要求阻断且标记）。
        if (diffRate.compareTo(RECOUNT_RATE) > 0) {
            l.setCountStatus(InvCountLine.ST_RECOUNT);
            l.setActualQty(actualQty);
            l.setDiffQty(diffQty);
            l.setDiffRate(diffRate);
            l.setDiffAmount(diffAmount);
            l.setCountedBy(SecurityUtils.getCurrentUserId());
            l.setCountedAt(LocalDateTime.now());
            REQUIRES_NEW.executeWithoutResult(st -> lineDao.updateById(l));
            throw new ServiceException(422, "差异率 " + pct(diffRate)
                    + " 超 10%，该行已标记待复盘，请两人复核后重新录入（BR-4.4-39）");
        }

        BigDecimal tolerance = sysParamService.getRate("TOLERANCE_DEFAULT",
                new BigDecimal("0.005"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (diffQty.signum() == 0 || diffRate.compareTo(tolerance) <= 0) {
            // 容差内：当场自动调整（BR-4.4-37，记调整原因无需审批）
            if (diffQty.signum() != 0) {
                l.setAutoAdjust("1");
                l.setCountStatus(InvCountLine.ST_COUNTED);
                lineDao.updateById(l);
                adjustService.executeAdjustment(task, List.of(l),
                        "容差内自动调整（差异率 " + pct(diffRate) + " ≤ "
                                + tolerance.multiply(new BigDecimal("100")).stripTrailingZeros()
                                        .toPlainString() + "%）");
            } else {
                l.setAutoAdjust("1");
                l.setCountStatus(InvCountLine.ST_ADJUSTED);
                lineDao.updateById(l);
            }
            taskService.tryComplete(task.getId());   // 全行闭环 → DONE + 报告（spec 任务状态机）
            out.put("outcome", "AUTO_ADJUSTED");
        } else {
            // 超容差：COUNT 差异单 + CountDiff 审批（BR-4.4-38 / C-4.4-04）。
            // 幂等：复盘重录复用既有 PENDING 差异单（不重复建单），仅重新挂审批。
            l.setAutoAdjust("0");
            l.setCountStatus(InvCountLine.ST_COUNTED);
            InvCheckDiff d = isBlank(l.getDiffId()) ? null
                    : diffDao.selectById(l.getDiffId());
            if (d != null && InvCheckDiff.ST_RESOLVED.equals(d.getStatus())) {
                throw new ServiceException(422, "该行差异单已闭环，不可重复提交");
            }
            if (d == null) {
            d = new InvCheckDiff();
            d.setDiffType(InvCheckDiff.T_COUNT);
            d.setSrcTaskId(task.getId());
            d.setSrcDocType("COUNT_TASK");
            d.setSrcDocNo(task.getTaskNo());
            d.setItemCode(l.getItemCode());
            d.setWarehouseCode(task.getWarehouseCode());
            d.setBatchNo(l.getBatchNo());
            d.setDiffKind(InvCheckDiff.K_QTY);
            d.setExpectQty(book);
            d.setActualQty(actualQty);
            d.setDeltaQty(diffQty);
            d.setDiffNote("盘点差异率 " + pct(diffRate) + " 超容差（任务 "
                    + task.getTaskNo() + "）" + (isBlank(remark) ? "" : "：" + remark));
            d.setStatus(InvCheckDiff.ST_PENDING);
            d.setCreateBy(SecurityUtils.getCurrentUserId());
            diffDao.insert(d);
            l.setDiffId(d.getId());
            }
            lineDao.updateById(l);

            var inst = approvalEngine.submit(BIZ_COUNT_DIFF, d.getId(),
                    "盘点差异审批：" + task.getTaskNo() + " / " + l.getItemCode(),
                    null, List.of(List.of(
                            ApprovalNodeSpec.sign("ROLE_WAREHOUSE", "仓库主管盘点差异审批"))));
            if (isBlank(d.getDiffNo())) {
                d.setDiffNo("CD-" + inst.getApprNo());
                diffDao.updateById(d);
            }
            taskService.markAdjusting(task.getId());
            notifyWatchIfNeeded(l.getBinCode());
            out.put("outcome", "PENDING_APPROVAL");
            out.put("diffNo", d.getDiffNo());
        }
        // 计数回填
        recountProgress(task.getId());
        // 回显（提交后账面/差异可见——spec 录入遮蔽）
        out.put("lineId", l.getId());
        out.put("bookQty", l.getBookQty());
        out.put("actualQty", actualQty);
        out.put("diffQty", diffQty);
        out.put("diffRate", diffRate);
        out.put("diffAmount", diffAmount);
        out.put("countStatus", l.getCountStatus());
        log.info("count line {} submitted: book={} actual={} rate={} outcome={}",
                l.getId(), book, actualQty, pct(diffRate), out.get("outcome"));
        return out;
    }

    @Override
    public Map<String, Object> result(String lineId) {
        InvCountLine l = isBlank(lineId) ? null : lineDao.selectById(lineId);
        if (l == null) {
            throw new ServiceException(422, "盘点行不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineId", l.getId());
        m.put("binCode", l.getBinCode());
        m.put("itemCode", l.getItemCode());
        m.put("batchNo", l.getBatchNo());
        m.put("countStatus", l.getCountStatus());
        if (l.getActualQty() != null) {
            m.put("bookQty", l.getBookQty());
            m.put("actualQty", l.getActualQty());
            m.put("diffQty", l.getDiffQty());
            m.put("diffRate", l.getDiffRate());
            m.put("diffAmount", l.getDiffAmount());
            m.put("autoAdjust", l.getAutoAdjust());
            m.put("adjustDocNo", l.getAdjustDocNo());
            m.put("diffId", l.getDiffId());
        }
        return m;
    }

    /** 计数回填（countedLines/adjustedLines） */
    private void recountProgress(String taskId) {
        Long counted = lineDao.selectCount(new LambdaQueryWrapper<InvCountLine>()
                .eq(InvCountLine::getTaskId, taskId)
                .ne(InvCountLine::getCountStatus, InvCountLine.ST_PENDING)
                .ne(InvCountLine::getCountStatus, InvCountLine.ST_RECOUNT));
        Long adjusted = lineDao.selectCount(new LambdaQueryWrapper<InvCountLine>()
                .eq(InvCountLine::getTaskId, taskId)
                .eq(InvCountLine::getCountStatus, InvCountLine.ST_ADJUSTED));
        InvCountTask t = taskDao.selectById(taskId);
        if (t != null) {
            t.setCountedLines(counted == null ? 0 : counted.intValue());
            t.setAdjustedLines(adjusted == null ? 0 : adjusted.intValue());
            taskDao.updateById(t);
        }
    }

    private BigDecimal standardCost(String itemCode) {
        MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, itemCode).last("LIMIT 1"));
        if (item == null || item.getStandardCost() == null) {
            return BigDecimal.ZERO;
        }
        return item.getStandardCost();
    }

    private static String pct(BigDecimal rate) {
        return rate.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "%";
    }

    private void requireWarehouse(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new java.util.ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r)
                || "ROLE_WAREHOUSE".equalsIgnoreCase(r))) {
            return;
        }
        throw new ServiceException(403, action + "：需要仓库主管或管理员角色");
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
