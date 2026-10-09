package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmCostCenterDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.proc.ProcPrApprovalDao;
import com.erp.dao.proc.ProcPrDeliveryLineDao;
import com.erp.dao.proc.ProcPrLineDao;
import com.erp.dao.proc.ProcRequisitionDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.proc.ProcPrApproval;
import com.erp.entity.proc.ProcPrDeliveryLine;
import com.erp.entity.proc.ProcPrLine;
import com.erp.entity.proc.ProcRequisition;
import com.erp.procurement.RequisitionStateMachine;
import com.erp.service.proc.ProcRequisitionService;
import com.erp.service.proc.ProcRequisitionSupport;
import com.erp.util.SecurityUtils;
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

/**
 * 请购单实现（design add-purchase-requisition D2/D5/D6）。
 * batch/confirm 等多行方法不加外层事务语义按行独立（confirm 行校验失败在头迁移前整体拒绝——
 * 80% 为整单确认前置校验，非行级部分提交）。
 */
@Slf4j
@Service
public class ProcRequisitionServiceImpl implements ProcRequisitionService {

    private final ProcRequisitionDao prDao;
    private final ProcPrLineDao lineDao;
    private final ProcPrDeliveryLineDao deliveryDao;
    private final ProcPrApprovalDao approvalDao;
    private final MdmItemDao itemDao;
    private final MdmSupplierDao supplierDao;
    private final MdmCostCenterDao costCenterDao;
    private final ProcRequisitionSupport support;

    public ProcRequisitionServiceImpl(ProcRequisitionDao prDao,
                                      ProcPrLineDao lineDao,
                                      ProcPrDeliveryLineDao deliveryDao,
                                      ProcPrApprovalDao approvalDao,
                                      MdmItemDao itemDao,
                                      MdmSupplierDao supplierDao,
                                      MdmCostCenterDao costCenterDao,
                                      ProcRequisitionSupport support) {
        this.prDao = prDao;
        this.lineDao = lineDao;
        this.deliveryDao = deliveryDao;
        this.approvalDao = approvalDao;
        this.itemDao = itemDao;
        this.supplierDao = supplierDao;
        this.costCenterDao = costCenterDao;
        this.support = support;
    }

    // ---------- 分页（懒 sweep 先行） ----------

    @Override
    public Page<Map<String, Object>> page(long current, long size, String status,
                                          String sourceType, String keyword) {
        sweep();
        LambdaQueryWrapper<ProcRequisition> qw = new LambdaQueryWrapper<ProcRequisition>()
                .eq(isNotBlank(status), ProcRequisition::getStatus, status)
                .eq(isNotBlank(sourceType), ProcRequisition::getSourceType, sourceType)
                .like(isNotBlank(keyword), ProcRequisition::getPrNo, isNotBlank(keyword) ? keyword.trim() : null)
                .orderByDesc(ProcRequisition::getCreateDate);
        Page<ProcRequisition> raw = prDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> records = new ArrayList<>();
        for (ProcRequisition pr : raw.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", pr.getId());
            row.put("prNo", pr.getPrNo());
            row.put("sourceType", pr.getSourceType());
            row.put("status", pr.getStatus());
            row.put("reqReason", pr.getReqReason());
            row.put("approvalAmount", pr.getApprovalAmount());
            row.put("remindFlag", pr.getRemindFlag());
            row.put("escalateFlag", pr.getEscalateFlag());
            row.put("budgetSubject", pr.getBudgetSubject());
            row.put("budgetCostCenterId", pr.getBudgetCostCenterId());
            row.put("budgetInternalOrderNo", pr.getBudgetInternalOrderNo());
            row.put("lineCount", lineDao.selectCount(new LambdaQueryWrapper<ProcPrLine>()
                    .eq(ProcPrLine::getPrId, pr.getId())));
            row.put("createBy", pr.getCreateBy());
            row.put("createDate", pr.getCreateDate());
            row.put("confirmDate", pr.getConfirmDate());
            records.add(row);
        }
        result.setRecords(records);
        return result;
    }

    @Override
    public Map<String, Object> detail(String id) {
        sweep();
        ProcRequisition pr = support.requirePr(id);
        List<ProcPrLine> lines = linesOf(id);
        List<Map<String, Object>> lineRows = new ArrayList<>();
        for (ProcPrLine l : lines) {
            Map<String, Object> row = toLineRow(l);
            List<Map<String, Object>> dels = new ArrayList<>();
            for (ProcPrDeliveryLine d : deliveryDao.selectList(
                    new LambdaQueryWrapper<ProcPrDeliveryLine>()
                            .eq(ProcPrDeliveryLine::getPrLineId, l.getId())
                            .orderByAsc(ProcPrDeliveryLine::getDeliveryDate))) {
                Map<String, Object> dm = new LinkedHashMap<>();
                dm.put("id", d.getId());
                dm.put("deliveryDate", d.getDeliveryDate());
                dm.put("qty", d.getQty());
                dels.add(dm);
            }
            row.put("deliveryLines", dels);
            if (l.getSuggestedSupplierId() != null) {
                MdmSupplier sup = supplierDao.selectById(l.getSuggestedSupplierId());
                row.put("supplierName", sup == null ? null : sup.getSupplierName());
            }
            lineRows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pr", pr);
        result.put("lines", lineRows);
        return result;
    }

    // ---------- 手工创建/编辑/删除（2.1.2） ----------

    @Override
    @Transactional
    public Map<String, Object> createManual(Map<String, Object> payload) {
        String reason = str(payload.get("reqReason"));
        if (reason == null || reason.trim().length() < 2) {
            throw new ServiceException(422, "需求理由说明必填（至少 2 字）");
        }
        String project = str(payload.get("reqProjectNo"));
        String ccId = str(payload.get("reqCostCenterId"));
        String orderNo = str(payload.get("reqInternalOrderNo"));
        if (!isNotBlank(project) && !isNotBlank(ccId) && !isNotBlank(orderNo)) {
            throw new ServiceException(422, "需求来源三选一必填（项目编号/成本中心/内部订单号）");
        }
        if (isNotBlank(ccId) && costCenterDao.selectById(ccId) == null) {
            throw new ServiceException(422, "成本中心不存在，请从列表选择");
        }
        List<Map<String, Object>> lines = mapList(payload.get("lines"));
        if (lines.isEmpty()) {
            throw new ServiceException(422, "至少一行请购行");
        }
        String budgetSubject = str(payload.get("budgetSubject"));
        String budgetCc = str(payload.get("budgetCostCenterId"));
        String budgetOrder = str(payload.get("budgetInternalOrderNo"));
        boolean hasBudget = isNotBlank(budgetSubject) || isNotBlank(budgetCc) || isNotBlank(budgetOrder);
        if (isNotBlank(budgetCc) && costCenterDao.selectById(budgetCc) == null) {
            throw new ServiceException(422, "预算成本中心不存在");
        }
        // 行校验（Active L1 等）
        List<ProcPrLine> built = buildLines(lines, null, "MANUAL");

        ProcRequisition pr = new ProcRequisition();
        pr.setPrNo(support.nextPrNo());
        pr.setSourceType("MANUAL");
        // BR-4.2-09：预算三空 → 待预算确认
        pr.setStatus(hasBudget ? RequisitionStateMachine.PENDING_APPROVAL
                : RequisitionStateMachine.PENDING_BUDGET);
        pr.setReqProjectNo(isNotBlank(project) ? project.trim() : null);
        pr.setReqCostCenterId(isNotBlank(ccId) ? ccId : null);
        pr.setReqInternalOrderNo(isNotBlank(orderNo) ? orderNo.trim() : null);
        pr.setBudgetSubject(isNotBlank(budgetSubject) ? budgetSubject.trim() : null);
        pr.setBudgetCostCenterId(isNotBlank(budgetCc) ? budgetCc : null);
        pr.setBudgetInternalOrderNo(isNotBlank(budgetOrder) ? budgetOrder.trim() : null);
        pr.setReqReason(reason.trim());
        pr.setRemindFlag("0");
        pr.setEscalateFlag("0");
        support.insertWithRetry(pr);
        insertLines(pr, built);
        support.publishHead(pr, "PROC.PR.CREATED",
                "手工请购 " + pr.getPrNo() + " " + built.size() + " 行，状态 " + pr.getStatus());
        log.info("PR manual created {} status={} budget={}", pr.getPrNo(), pr.getStatus(), hasBudget);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pr", pr);
        result.put("lineCount", built.size());
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> updateManual(String id, Map<String, Object> payload) {
        ProcRequisition pr = support.requirePr(id);
        if (!"MANUAL".equals(pr.getSourceType())) {
            throw new ServiceException(422, "MRP 请购单请使用行编辑接口修改（数量/日期/复核）");
        }
        requireEditable(pr);
        String reason = str(payload.get("reqReason"));
        if (reason == null || reason.trim().length() < 2) {
            throw new ServiceException(422, "需求理由说明必填（至少 2 字）");
        }
        String ccId = str(payload.get("reqCostCenterId"));
        if (isNotBlank(ccId) && costCenterDao.selectById(ccId) == null) {
            throw new ServiceException(422, "成本中心不存在");
        }
        String budgetSubject = str(payload.get("budgetSubject"));
        String budgetCc = str(payload.get("budgetCostCenterId"));
        String budgetOrder = str(payload.get("budgetInternalOrderNo"));
        boolean hasBudget = isNotBlank(budgetSubject) || isNotBlank(budgetCc) || isNotBlank(budgetOrder);
        if (isNotBlank(budgetCc) && costCenterDao.selectById(budgetCc) == null) {
            throw new ServiceException(422, "预算成本中心不存在");
        }
        List<Map<String, Object>> lines = mapList(payload.get("lines"));
        if (lines.isEmpty()) {
            throw new ServiceException(422, "至少一行请购行");
        }
        List<ProcPrLine> built = buildLines(lines, null, "MANUAL");

        pr.setReqReason(reason.trim());
        pr.setReqProjectNo(str(payload.get("reqProjectNo")));
        pr.setReqCostCenterId(ccId);
        pr.setReqInternalOrderNo(str(payload.get("reqInternalOrderNo")));
        pr.setBudgetSubject(isNotBlank(budgetSubject) ? budgetSubject.trim() : null);
        pr.setBudgetCostCenterId(isNotBlank(budgetCc) ? budgetCc : null);
        pr.setBudgetInternalOrderNo(isNotBlank(budgetOrder) ? budgetOrder.trim() : null);
        // 预算清空回落（BR-4.2-09 对称）：可改态内
        if (!hasBudget && RequisitionStateMachine.PENDING_APPROVAL.equals(pr.getStatus())) {
            support.transition(pr, RequisitionStateMachine.PENDING_BUDGET, "预算来源清空回落");
        } else if (hasBudget && RequisitionStateMachine.PENDING_BUDGET.equals(pr.getStatus())) {
            support.transition(pr, RequisitionStateMachine.PENDING_APPROVAL, "预算来源补录");
        }
        // 全量替换行（手工行无 MRP 基线）
        lineDao.delete(new LambdaQueryWrapper<ProcPrLine>().eq(ProcPrLine::getPrId, pr.getId()));
        insertLines(pr, built);
        support.persist(pr);
        log.info("PR manual updated {} lines={} status={}", pr.getPrNo(), built.size(), pr.getStatus());
        return detail(pr.getId());
    }

    @Override
    @Transactional
    public void deleteManual(String id) {
        ProcRequisition pr = support.requirePr(id);
        if (!"MANUAL".equals(pr.getSourceType())) {
            throw new ServiceException(422, "仅手工请购单可删除");
        }
        requireEditable(pr);
        prDao.deleteById(id); // @TableLogic 软删
        log.info("PR manual deleted {} status={}", pr.getPrNo(), pr.getStatus());
    }

    // ---------- MRP 行编辑 ----------

    @Override
    @Transactional
    public void updateLines(String prId, List<Map<String, Object>> lines) {
        ProcRequisition pr = support.requirePr(prId);
        String st = pr.getStatus();
        if (!RequisitionStateMachine.PENDING_CONFIRM.equals(st)
                && !RequisitionStateMachine.PENDING_MODIFY.equals(st)) {
            throw new ServiceException(422, "当前状态 " + st + " 不可编辑行（仅 待确认/已驳回 可改）");
        }
        if (lines == null || lines.isEmpty()) {
            throw new ServiceException(422, "行列表不能为空");
        }
        for (Map<String, Object> row : lines) {
            String lineId = str(row.get("lineId"));
            ProcPrLine line = lineDao.selectById(lineId);
            if (line == null || !prId.equals(line.getPrId())) {
                throw new ServiceException(404, "请购行不存在：" + lineId);
            }
            BigDecimal qty = decimal(row.get("qty"));
            if (qty == null || qty.signum() <= 0) {
                throw new ServiceException(422, "行数量须大于 0（行 " + line.getLineNo() + "）");
            }
            LocalDate reqDate = date(str(row.get("reqDate")));
            if (reqDate == null) {
                throw new ServiceException(422, "需求日期必填（行 " + line.getLineNo() + "）");
            }
            line.setQty(qty);
            line.setReqDate(reqDate);
            line.setReduceReason(str(row.get("reduceReason")));
            line.setReviewer(str(row.get("reviewer")));
            lineDao.updateById(line);
            log.info("PR {} line {} edited qty={} reqDate={} reviewer={}",
                    pr.getPrNo(), line.getLineNo(), qty, reqDate, line.getReviewer());
        }
    }

    // ---------- 确认（80% 卡控） ----------

    @Override
    @Transactional
    public Map<String, Object> confirm(String prId) {
        ProcRequisition pr = support.requirePr(prId);
        if (!RequisitionStateMachine.PENDING_CONFIRM.equals(pr.getStatus())) {
            throw new ServiceException(422, "仅「待确认」的 PR 可确认，当前 " + pr.getStatus());
        }
        List<ProcPrLine> lines = linesOf(prId);
        if (lines.isEmpty()) {
            throw new ServiceException(422, "无请购行不可确认");
        }
        // BR-4.2-08：逐行 80%（MRP 基线行），不足须 调减理由+复核人
        for (ProcPrLine l : lines) {
            if (l.getMrpSuggestedQty() == null) {
                continue;
            }
            BigDecimal floor = l.getMrpSuggestedQty()
                    .multiply(new BigDecimal("0.8")).setScale(4, RoundingMode.HALF_UP);
            if (l.getQty().compareTo(floor) < 0) {
                boolean reviewed = isNotBlank(l.getReduceReason()) && l.getReduceReason().trim().length() >= 2
                        && isNotBlank(l.getReviewer());
                if (!reviewed) {
                    throw new ServiceException(422, "行 " + l.getLineNo() + "（" + l.getItemCode()
                            + "）确认量 " + l.getQty() + " 低于建议量 " + l.getMrpSuggestedQty()
                            + " 的 80%（下限 " + floor + "），须填写调减理由并经计划员复核（BR-4.2-08）");
                }
            }
        }
        pr.setConfirmBy(SecurityUtils.getCurrentUserId());
        pr.setConfirmDate(LocalDateTime.now());
        support.persist(pr);
        support.transition(pr, RequisitionStateMachine.CONFIRMED, "MRP 采购员确认");
        support.publishHead(pr, "PROC.PR.CONFIRMED",
                "确认 " + lines.size() + " 行，确认人 " + pr.getConfirmBy());
        return detail(prId);
    }

    // ---------- 供应商与流转 ----------

    @Override
    @Transactional
    public void setLineSupplier(String lineId, String supplierId) {
        ProcPrLine line = lineDao.selectById(lineId);
        if (line == null) {
            throw new ServiceException(404, "请购行不存在");
        }
        ProcRequisition pr = support.requirePr(line.getPrId());
        String st = pr.getStatus();
        if (!RequisitionStateMachine.CONFIRMED.equals(st)
                && !RequisitionStateMachine.APPROVED.equals(st)
                && !RequisitionStateMachine.PENDING_RFQ.equals(st)) {
            throw new ServiceException(422, "当前状态 " + st + " 不可维护建议供应商（已确认/已批准/待询价）");
        }
        if (isNotBlank(supplierId) && supplierDao.selectById(supplierId) == null) {
            throw new ServiceException(422, "供应商不存在");
        }
        line.setSuggestedSupplierId(isNotBlank(supplierId) ? supplierId : null);
        lineDao.updateById(line);
        log.info("PR {} line {} supplier set {}", pr.getPrNo(), line.getLineNo(), supplierId);
    }

    @Override
    @Transactional
    public Map<String, Object> routeToRfq(String prId) {
        ProcRequisition pr = support.requirePr(prId);
        if (!RequisitionStateMachine.APPROVED.equals(pr.getStatus())) {
            throw new ServiceException(422, "仅「已批准」的 PR 可流转询价（须先经审批），当前 "
                    + pr.getStatus());
        }
        List<ProcPrLine> lines = linesOf(prId);
        boolean anySupplier = lines.stream().anyMatch(l -> isNotBlank(l.getSuggestedSupplierId()));
        if (!anySupplier) {
            throw new ServiceException(422, "至少一行须补充建议供应商后方可流转询价");
        }
        support.transition(pr, RequisitionStateMachine.PENDING_RFQ, "流转询价");
        support.publishHead(pr, "PROC.PR.ROUTED", pr.getPrNo() + " 流转至询价（2.2 待接收）");
        return detail(prId);
    }

    // ---------- 关闭（手工 / 90 天 sweep / 达量） ----------

    @Override
    @Transactional
    public void closePr(String id, String reason) {
        ProcRequisition pr = support.requirePr(id);
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "关闭原因必填（至少 2 字）");
        }
        pr.setCloseReason(reason.trim());
        pr.setCloseBy(SecurityUtils.getCurrentUserId());
        pr.setCloseDate(LocalDateTime.now());
        support.persist(pr);
        support.transition(pr, RequisitionStateMachine.CLOSED, reason.trim());
        support.publishHead(pr, "PROC.PR.CLOSED", "手工关闭：" + reason.trim());
    }

    @Override
    @Transactional
    public void closeLine(String lineId, String reason) {
        ProcPrLine line = requireLine(lineId);
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "关闭原因必填（至少 2 字）");
        }
        closeLineInternal(line, reason.trim());
        closeHeadIfAllLinesClosed(support.requirePr(line.getPrId()), "行全部关闭");
    }

    @Override
    public List<Map<String, Object>> saveDeliveryLines(String lineId, List<Map<String, Object>> rows) {
        ProcPrLine line = requireLine(lineId);
        ProcRequisition pr = support.requirePr(line.getPrId());
        if (RequisitionStateMachine.CLOSED.equals(pr.getStatus()) || !"OPEN".equals(line.getLineStatus())) {
            throw new ServiceException(422, "已关闭的 PR/行不可维护交付计划行");
        }
        List<Map<String, Object>> normalized = rows == null ? new ArrayList<>() : rows;
        BigDecimal sum = BigDecimal.ZERO;
        List<ProcPrDeliveryLine> built = new ArrayList<>();
        int i = 1;
        for (Map<String, Object> row : normalized) {
            BigDecimal qty = decimal(row.get("qty"));
            LocalDate d = date(str(row.get("deliveryDate")));
            if (qty == null || qty.signum() <= 0 || d == null) {
                throw new ServiceException(422, "交付行 " + i + "：日期与数量必填且数量>0");
            }
            sum = sum.add(qty);
            ProcPrDeliveryLine dl = new ProcPrDeliveryLine();
            dl.setPrLineId(lineId);
            dl.setDeliveryDate(d);
            dl.setQty(qty);
            built.add(dl);
            i++;
        }
        if (sum.compareTo(line.getQty()) > 0) {
            throw new ServiceException(422, "交付计划合计 " + sum + " 超过行需求量 " + line.getQty()
                    + "（FR-4.2-1-1 分批交付约束）");
        }
        // 整单替换（无逻辑删除列 → 物理替换）+ 留痕
        List<ProcPrDeliveryLine> old = deliveryDao.selectList(
                new LambdaQueryWrapper<ProcPrDeliveryLine>().eq(ProcPrDeliveryLine::getPrLineId, lineId));
        log.info("PR {} line {} delivery replace old={} new={}",
                pr.getPrNo(), line.getLineNo(), old.size(), built.size());
        for (ProcPrDeliveryLine o : old) {
            deliveryDao.deleteById(o.getId());
        }
        for (ProcPrDeliveryLine dl : built) {
            deliveryDao.insert(dl);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProcPrDeliveryLine dl : built) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", dl.getId());
            m.put("deliveryDate", dl.getDeliveryDate());
            m.put("qty", dl.getQty());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> receivePoAllocation(String lineId, BigDecimal qty) {
        ProcPrLine line = requireLine(lineId);
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "下达数量须大于 0");
        }
        BigDecimal total = line.getAllocQty() == null ? BigDecimal.ZERO : line.getAllocQty();
        BigDecimal after = total.add(qty);
        if (after.compareTo(line.getQty()) > 0) {
            throw new ServiceException(422, "超量下达阻断（BR-4.2-51）：行需求 " + line.getQty()
                    + "，已下达 " + total + "，剩余可下达 " + line.getQty().subtract(total));
        }
        line.setAllocQty(after);
        if (after.compareTo(line.getQty()) == 0) {
            RequisitionStateMachine.requireLine(line.getLineStatus(), "CLOSED");
            line.setLineStatus("CLOSED");
            line.setCloseReason("下达完成（PO 反写）");
            line.setCloseDate(LocalDateTime.now());
        }
        lineDao.updateById(line);
        ProcRequisition pr = support.requirePr(line.getPrId());
        if ("CLOSED".equals(line.getLineStatus())) {
            closeHeadIfAllLinesClosed(pr, "全部行下达完成");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lineId", line.getId());
        result.put("allocQty", line.getAllocQty());
        result.put("lineStatus", line.getLineStatus());
        result.put("prStatus", pr.getStatus());
        log.info("PR {} line {} allocation {} -> {}", pr.getPrNo(), line.getLineNo(), qty, after);
        return result;
    }

    // ---------- MRP 生成（design D4） ----------

    @Override
    @Transactional
    public Map<String, Object> createFromMrp(List<Map<String, Object>> acceptedRows) {
        ProcRequisition pr = new ProcRequisition();
        pr.setPrNo(support.nextPrNo());
        pr.setSourceType("MRP");
        pr.setStatus(RequisitionStateMachine.PENDING_CONFIRM);
        pr.setReqReason("MRP 模拟净算自动生成（NetReq = 需求−库存−在制−在途）");
        pr.setRemindFlag("0");
        pr.setEscalateFlag("0");
        support.insertWithRetry(pr);
        int no = 1;
        for (Map<String, Object> a : acceptedRows) {
            String itemCode = String.valueOf(a.get("itemCode"));
            MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                    .eq(MdmItem::getItemCode, itemCode.trim().toUpperCase()).last("LIMIT 1"));
            // 二次校验（预检后到生成间状态可能变化，BR-4.2-07 双保险）
            if (item == null || !"1".equals(item.getStatus())) {
                throw new ServiceException(422, "行 " + no + " 物料 " + itemCode
                        + " 状态非启用（BR-4.2-07），生成终止");
            }
            ProcPrLine line = new ProcPrLine();
            line.setPrId(pr.getId());
            line.setLineNo(no);
            line.setItemCode(item.getItemCode());
            line.setQty(new java.math.BigDecimal(String.valueOf(a.get("netReq"))));
            line.setReqDate(LocalDate.parse(String.valueOf(a.get("reqDate"))));
            BigDecimal price = isNotBlank(str(a.get("estUnitPrice")))
                    ? new BigDecimal(str(a.get("estUnitPrice")).trim()) : BigDecimal.ZERO;
            line.setEstUnitPrice(price);
            line.setSourceEnum(str(a.get("sourceEnum")));
            line.setSourceDocNo(str(a.get("sourceDocNo")));
            line.setSuggestedSupplierId(str(a.get("supplierId")));
            line.setMrpSuggestedQty(line.getQty()); // 建议量基线（80% 卡控）
            line.setOverdueFlag(LocalDate.parse(String.valueOf(a.get("reqDate")))
                    .isBefore(LocalDate.now()) ? "1" : "0");
            line.setLineStatus("OPEN");
            line.setAllocQty(BigDecimal.ZERO);
            lineDao.insert(line);
            support.publishLine(pr, no, "PROC.PR.CREATED",
                    line.getItemCode() + " qty=" + line.getQty() + " @" + line.getReqDate()
                            + "（建议 " + line.getMrpSuggestedQty() + "）"
                            + ("1".equals(line.getOverdueFlag()) ? " 逾期" : ""));
            no++;
        }
        support.publishHead(pr, "PROC.PR.CREATED",
                "MRP 生成 " + pr.getPrNo() + " " + (no - 1) + " 行，状态 PENDING_CONFIRM");
        log.info("MRP PR created {} lines={}", pr.getPrNo(), no - 1);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prId", pr.getId());
        result.put("prNo", pr.getPrNo());
        return result;
    }

    // ---------- 懒 sweep：催办/升级 + 90 天关闭（BR-4.2-50 / C-4.2-11） ----------

    private void sweep() {
        LocalDateTime now = LocalDateTime.now();
        // 催办/升级：待确认 PR 按创建日起算工作日
        List<ProcRequisition> pending = prDao.selectList(new LambdaQueryWrapper<ProcRequisition>()
                .eq(ProcRequisition::getStatus, RequisitionStateMachine.PENDING_CONFIRM)
                .eq(ProcRequisition::getRemindFlag, "0"));
        for (ProcRequisition pr : pending) {
            long wd = RequisitionStateMachine.businessDaysBetween(pr.getCreateDate().toLocalDate(), now);
            if (wd >= 3) {
                pr.setRemindFlag("1");
                pr.setEscalateFlag("1");
                pr.setScanDate(now);
                support.persist(pr);
                log.info("[TODO-NOTIFY] PR {} 超3工作日未确认，升级采购经理（BR-4.2-50 桩）", pr.getPrNo());
            } else if (wd >= 1) {
                pr.setRemindFlag("1");
                pr.setScanDate(now);
                support.persist(pr);
                log.info("[TODO-NOTIFY] PR {} 超1工作日未确认，催办（BR-4.2-50 桩）", pr.getPrNo());
            }
        }
        // 90 天自动关闭（C-4.2-11）
        LocalDate limit = LocalDate.now().minusDays(90);
        List<ProcPrLine> stale = lineDao.selectList(new LambdaQueryWrapper<ProcPrLine>()
                .eq(ProcPrLine::getLineStatus, "OPEN")
                .eq(ProcPrLine::getAllocQty, BigDecimal.ZERO)
                .lt(ProcPrLine::getReqDate, limit));
        for (ProcPrLine line : stale) {
            log.info("[TODO-NOTIFY] PR行 {} 需求日 {} 超90天，自动关闭（C-4.2-11）",
                    line.getLineNo(), line.getReqDate());
            closeLineInternal(line, "C-4.2-11 超需求日期 90 天未下达，系统自动关闭");
            try {
                closeHeadIfAllLinesClosed(support.requirePr(line.getPrId()), "C-4.2-11 全行自动关闭");
            } catch (ServiceException e) {
                log.warn("90天关闭头联动失败 line={} : {}", line.getId(), e.getMessage());
            }
        }
    }

    // ---------- 私有 ----------

    private void closeLineInternal(ProcPrLine line, String reason) {
        RequisitionStateMachine.requireLine(line.getLineStatus(), "CLOSED");
        line.setLineStatus("CLOSED");
        line.setCloseReason(reason);
        line.setCloseDate(LocalDateTime.now());
        lineDao.updateById(line);
        log.info("PR line closed id={} reason={}", line.getId(), reason);
    }

    private void closeHeadIfAllLinesClosed(ProcRequisition pr, String why) {
        if (RequisitionStateMachine.CLOSED.equals(pr.getStatus())) {
            return;
        }
        Long open = lineDao.selectCount(new LambdaQueryWrapper<ProcPrLine>()
                .eq(ProcPrLine::getPrId, pr.getId())
                .eq(ProcPrLine::getLineStatus, "OPEN"));
        if (open != null && open == 0) {
            pr.setCloseReason(why);
            pr.setCloseBy(SecurityUtils.getCurrentUserId());
            pr.setCloseDate(LocalDateTime.now());
            support.persist(pr);
            support.transition(pr, RequisitionStateMachine.CLOSED, why);
            support.publishHead(pr, "PROC.PR.CLOSED", why);
        }
    }

    private void requireEditable(ProcRequisition pr) {
        String st = pr.getStatus();
        if (!RequisitionStateMachine.PENDING_BUDGET.equals(st)
                && !RequisitionStateMachine.PENDING_APPROVAL.equals(st)
                && !RequisitionStateMachine.PENDING_MODIFY.equals(st)) {
            throw new ServiceException(422, "当前状态 " + st + " 不可编辑（仅 待预算确认/待审批/已驳回）");
        }
    }

    /** 行组装：物料 Active L1（BR-4.2-07）+ 字段校验 */
    private List<ProcPrLine> buildLines(List<Map<String, Object>> rows, BigDecimal mrpBase,
                                        String defaultSourceEnum) {
        List<ProcPrLine> out = new ArrayList<>();
        int i = 1;
        for (Map<String, Object> row : rows) {
            String itemCode = str(row.get("itemCode"));
            if (!isNotBlank(itemCode)) {
                throw new ServiceException(422, "行 " + i + " 物料编码必填");
            }
            MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                    .eq(MdmItem::getItemCode, itemCode.trim().toUpperCase()).last("LIMIT 1"));
            if (item == null) {
                throw new ServiceException(422, "行 " + i + " 物料不存在：" + itemCode);
            }
            if (!"1".equals(item.getStatus())) {
                // BR-4.2-07 L1：非 Active 拒绝并提示状态
                throw new ServiceException(422, "行 " + i + " 物料 " + itemCode + " 当前状态为 "
                        + itemStatusName(item.getStatus()) + "，禁止创建 PR（BR-4.2-07，已发布启用方可请购）");
            }
            BigDecimal qty = decimal(row.get("qty"));
            if (qty == null || qty.signum() <= 0) {
                throw new ServiceException(422, "行 " + i + " 数量须大于 0");
            }
            LocalDate reqDate = date(str(row.get("reqDate")));
            if (reqDate == null) {
                throw new ServiceException(422, "行 " + i + " 需求日期必填");
            }
            BigDecimal price = decimal(row.get("estUnitPrice"));
            if (price == null || price.signum() < 0) {
                throw new ServiceException(422, "行 " + i + " 预估单价必填（≥0，审批判级基数）");
            }
            ProcPrLine line = new ProcPrLine();
            line.setItemCode(item.getItemCode());
            line.setQty(qty);
            line.setReqDate(reqDate);
            line.setEstUnitPrice(price);
            String sourceEnum = str(row.get("sourceEnum"));
            line.setSourceEnum(isNotBlank(sourceEnum) ? sourceEnum.trim().toUpperCase() : defaultSourceEnum);
            line.setSourceDocNo(str(row.get("sourceDocNo")));
            line.setMrpSuggestedQty(mrpBase);
            line.setReduceReason(str(row.get("reduceReason")));
            line.setReviewer(str(row.get("reviewer")));
            // 逾期/异常标记（FR-4.2-1-1）
            line.setOverdueFlag(reqDate.isBefore(LocalDate.now()) ? "1" : "0");
            line.setLineStatus("OPEN");
            line.setAllocQty(BigDecimal.ZERO);
            out.add(line);
            i++;
        }
        return out;
    }

    private void insertLines(ProcRequisition pr, List<ProcPrLine> lines) {
        int no = 1;
        for (ProcPrLine line : lines) {
            line.setPrId(pr.getId());
            line.setLineNo(no);
            lineDao.insert(line);
            String diff = line.getItemCode() + " qty=" + line.getQty() + " @" + line.getReqDate()
                    + (line.getMrpSuggestedQty() != null ? "（建议 " + line.getMrpSuggestedQty() + "）" : "");
            support.publishLine(pr, no, "PROC.PR.CREATED", diff);
            no++;
        }
    }

    private List<ProcPrLine> linesOf(String prId) {
        return lineDao.selectList(new LambdaQueryWrapper<ProcPrLine>()
                .eq(ProcPrLine::getPrId, prId).orderByAsc(ProcPrLine::getLineNo));
    }

    private ProcPrLine requireLine(String lineId) {
        ProcPrLine line = lineDao.selectById(lineId);
        if (line == null) {
            throw new ServiceException(404, "请购行不存在");
        }
        return line;
    }

    private Map<String, Object> toLineRow(ProcPrLine l) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", l.getId());
        row.put("lineNo", l.getLineNo());
        row.put("itemCode", l.getItemCode());
        row.put("qty", l.getQty());
        row.put("reqDate", l.getReqDate());
        row.put("estUnitPrice", l.getEstUnitPrice());
        row.put("sourceEnum", l.getSourceEnum());
        row.put("sourceDocNo", l.getSourceDocNo());
        row.put("suggestedSupplierId", l.getSuggestedSupplierId());
        row.put("mrpSuggestedQty", l.getMrpSuggestedQty());
        row.put("reduceReason", l.getReduceReason());
        row.put("reviewer", l.getReviewer());
        row.put("overdueFlag", l.getOverdueFlag());
        row.put("lineStatus", l.getLineStatus());
        row.put("closeReason", l.getCloseReason());
        row.put("allocQty", l.getAllocQty());
        return row;
    }

    private String itemStatusName(String status) {
        if ("1".equals(status)) return "启用";
        if ("0".equals(status)) return "停用";
        if ("2".equals(status)) return "已归档";
        return "未发布(" + status + ")";
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private BigDecimal decimal(Object o) {
        String s = str(o);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "数值格式非法：" + s);
        }
    }

    private LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ServiceException(422, "日期格式须为 yyyy-MM-dd：" + s);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object o) {
        if (!(o instanceof List)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object x : (List<Object>) o) {
            if (x instanceof Map) {
                out.add((Map<String, Object>) x);
            }
        }
        return out;
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
