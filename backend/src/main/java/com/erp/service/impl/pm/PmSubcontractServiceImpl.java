package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmSubPaymentDao;
import com.erp.dao.pm.PmSubcontractDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmSubPayment;
import com.erp.entity.pm.PmSubcontract;
import com.erp.entity.pm.PmWbs;
import com.erp.service.pm.PmCostCollectService;
import com.erp.service.pm.PmSubcontractService;
import com.erp.service.system.NoticeService;
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
 * 外协合同台账与付款实现（add-cost-collection-freeze，FR-4.8-5-2）。
 * 超合同剩余余额 L1 硬阻断；合同未挂 WBS → NO_WBS_FLAG=1 + L4 提示（付款归集落项目级）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmSubcontractServiceImpl implements PmSubcontractService {

    private final PmSubcontractDao contractDao;
    private final PmSubPaymentDao paymentDao;
    private final PmProjectDao projectDao;
    private final PmWbsDao wbsDao;
    private final PmCostCollectService costCollectService;
    private final PmChangeInterceptor changeInterceptor;
    private final NoticeService noticeService;

    // ---- 合同 ----

    @Override
    public List<Map<String, Object>> listContracts(String projectId) {
        LambdaQueryWrapper<PmSubcontract> qw = new LambdaQueryWrapper<PmSubcontract>()
                .eq(PmSubcontract::getDelFlag, "0")
                .orderByDesc(PmSubcontract::getCreateDate);
        if (hasText(projectId)) {
            qw.eq(PmSubcontract::getProjectId, projectId);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmSubcontract c : contractDao.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contract", c);
            row.put("remain", c.getTotalAmount().subtract(c.getPaidAmount() == null ? BigDecimal.ZERO : c.getPaidAmount()));
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> saveContract(Map<String, Object> payload) {
        String projectId = str(payload.get("projectId"));
        PmProject project = hasText(projectId) ? projectDao.selectById(projectId) : null;
        if (project == null) {
            throw new ServiceException(422, "项目不存在");
        }
        BigDecimal total = dec(payload.get("totalAmount"));
        if (total == null || total.signum() <= 0) {
            throw new ServiceException(422, "合同含税总额须为正");
        }
        boolean isNew = true;
        PmSubcontract c;
        String id = str(payload.get("id"));
        if (hasText(id)) {
            c = contractDao.selectById(id);
            if (c == null || "1".equals(c.getDelFlag())) {
                throw new ServiceException(422, "合同不存在");
            }
            if (c.getPaidAmount() != null && c.getPaidAmount().signum() > 0) {
                throw new ServiceException(422, "已有付款的合同不可修改台账（付款明细见付款单）");
            }
            isNew = false;
        } else {
            c = new PmSubcontract();
            c.setId(uuid());
            c.setContractNo(nextNo());
            c.setPaidAmount(BigDecimal.ZERO);
            c.setStatus("ACTIVE");
            c.setCreateBy(currentUser());
            c.setCreateDate(LocalDateTime.now());
        }
        c.setProjectId(projectId);
        c.setWbsId(str(payload.get("wbsId")));
        c.setSupplierId(str(payload.get("supplierId")));
        c.setSupplierName(str(payload.get("supplierName")));
        c.setTotalAmount(total);
        // 未挂 WBS → L4 提示标记（FR-4.8-5-2 异常处理）
        if (!hasText(c.getWbsId())) {
            c.setNoWbsFlag("1");
            noticeService.push("ROLE_PMO", null,
                    "外协合同未关联 WBS：" + c.getContractNo(),
                    "合同 " + c.getContractNo() + "（项目：" + project.getName()
                            + "）未关联 WBS 任务，付款成本将归集至项目级，请补充关联",
                    "PmSubcontractNoWbs", c.getId());
        } else {
            PmWbs wbs = wbsDao.selectById(c.getWbsId());
            if (wbs == null) {
                throw new ServiceException(422, "WBS 不存在");
            }
            c.setNoWbsFlag("0");
        }
        if (isNew) {
            contractDao.insert(c);
        } else {
            c.setUpdateBy(currentUser());
            c.setUpdateDate(LocalDateTime.now());
            contractDao.updateById(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.getId());
        out.put("contractNo", c.getContractNo());
        out.put("noWbsFlag", c.getNoWbsFlag());
        return out;
    }

    // ---- 付款 ----

    @Override
    public List<Map<String, Object>> listPayments(String projectId, String contractId, String status) {
        LambdaQueryWrapper<PmSubPayment> qw = new LambdaQueryWrapper<PmSubPayment>()
                .eq(PmSubPayment::getDelFlag, "0")
                .orderByDesc(PmSubPayment::getCreateDate);
        if (hasText(projectId)) {
            qw.eq(PmSubPayment::getProjectId, projectId);
        }
        if (hasText(contractId)) {
            qw.eq(PmSubPayment::getContractId, contractId);
        }
        if (hasText(status)) {
            qw.eq(PmSubPayment::getStatus, status);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmSubPayment p : paymentDao.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("payment", p);
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> savePayment(Map<String, Object> payload, boolean submit) {
        String contractId = str(payload.get("contractId"));
        PmSubcontract contract = hasText(contractId) ? contractDao.selectById(contractId) : null;
        if (contract == null) {
            throw new ServiceException(422, "外协合同不存在");
        }
        PmProject project = projectDao.selectById(contract.getProjectId());
        if (project != null && PmProject.FREEZE_YES.equals(project.getFreezeFlag())) {
            throw new ServiceException(422, "项目已冻结（" + nvl(project.getFreezeReason())
                    + "），不可新增外协付款，请先走超预算审批解冻");
        }
        // 变更拦截（C-4.8-09，add-project-change）：提交时按合同 WBS 精准拦
        if (submit) {
            changeInterceptor.assertNoPendingChange(contract.getProjectId(), contract.getWbsId());
        }
        BigDecimal amount = dec(payload.get("amount"));
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "付款金额须为正");
        }
        BigDecimal paid = contract.getPaidAmount() == null ? BigDecimal.ZERO : contract.getPaidAmount();
        BigDecimal remain = contract.getTotalAmount().subtract(paid);
        // 在途（已提交未审）付款合计也要占余额
        BigDecimal pending = pendingOf(contractId);
        if (amount.compareTo(remain.subtract(pending)) > 0) {
            throw new ServiceException(422, "超出合同剩余余额：合同总额 " + contract.getTotalAmount()
                    + "，已付 " + paid + "，在途 " + pending + "，剩余可用 "
                    + remain.subtract(pending) + "，本次 " + amount);
        }
        boolean isNew = true;
        PmSubPayment p;
        String id = str(payload.get("id"));
        if (hasText(id)) {
            p = paymentDao.selectById(id);
            if (p == null || "1".equals(p.getDelFlag())) {
                throw new ServiceException(422, "付款单不存在");
            }
            if (!PmSubPayment.ST_DRAFT.equals(p.getStatus()) && !PmSubPayment.ST_REJECTED.equals(p.getStatus())) {
                throw new ServiceException(422, "仅草稿/已驳回付款单可修改");
            }
            isNew = false;
        } else {
            p = new PmSubPayment();
            p.setId(uuid());
            p.setPayNo(nextPayNo());
            p.setCreateBy(currentUser());
            p.setCreateDate(LocalDateTime.now());
        }
        p.setContractId(contractId);
        p.setProjectId(contract.getProjectId());
        p.setWbsId(contract.getWbsId());
        p.setAmount(amount);
        p.setPayDate(LocalDate.parse(str(payload.get("payDate"))));
        p.setStatus(PmSubPayment.ST_DRAFT);
        if (isNew) {
            paymentDao.insert(p);
        } else {
            p.setUpdateBy(currentUser());
            p.setUpdateDate(LocalDateTime.now());
            paymentDao.updateById(p);
        }
        if (submit) {
            p.setStatus(PmSubPayment.ST_SUBMITTED);
            p.setUpdateDate(LocalDateTime.now());
            paymentDao.updateById(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.getId());
        out.put("payNo", p.getPayNo());
        out.put("status", p.getStatus());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> reviewPayment(String id, Map<String, Object> payload) {
        PmSubPayment p = paymentDao.selectById(id);
        if (p == null || "1".equals(p.getDelFlag())) {
            throw new ServiceException(422, "付款单不存在");
        }
        if (!PmSubPayment.ST_SUBMITTED.equals(p.getStatus())) {
            throw new ServiceException(422, "仅已提交付款单可审核");
        }
        boolean approved = Boolean.parseBoolean(str(payload.get("approved")));
        String opinion = str(payload.get("opinion"));
        if (!approved && !hasText(opinion)) {
            throw new ServiceException(422, "驳回必须填写原因");
        }
        if (approved) {
            PmSubcontract contract = contractDao.selectById(p.getContractId());
            // 归集外协成本（WBS 空则项目级）
            costCollectService.collect(PmCostCollectService.TYPE_SUBCONTRACT,
                    PmCostCollectService.SRC_SUB_PAYMENT, p.getId(),
                    p.getProjectId(), p.getWbsId(), p.getAmount(), LocalDate.now());
            // 回写合同已付累计
            if (contract != null) {
                contract.setPaidAmount((contract.getPaidAmount() == null ? BigDecimal.ZERO : contract.getPaidAmount())
                        .add(p.getAmount()));
                if (contract.getPaidAmount().compareTo(contract.getTotalAmount()) >= 0) {
                    contract.setStatus("CLOSED");
                }
                contract.setUpdateDate(LocalDateTime.now());
                contractDao.updateById(contract);
            }
            p.setStatus(PmSubPayment.ST_APPROVED);
        } else {
            p.setStatus(PmSubPayment.ST_REJECTED);
            p.setRejectReason(opinion);
        }
        p.setReviewBy(currentUser());
        p.setReviewDate(LocalDateTime.now());
        p.setUpdateDate(LocalDateTime.now());
        paymentDao.updateById(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.getId());
        out.put("status", p.getStatus());
        return out;
    }

    // ---- helpers ----

    /** 在途（SUBMITTED 未审）付款合计 */
    private BigDecimal pendingOf(String contractId) {
        List<PmSubPayment> pendings = paymentDao.selectList(new LambdaQueryWrapper<PmSubPayment>()
                .eq(PmSubPayment::getContractId, contractId)
                .eq(PmSubPayment::getStatus, PmSubPayment.ST_SUBMITTED)
                .eq(PmSubPayment::getDelFlag, "0"));
        BigDecimal sum = BigDecimal.ZERO;
        for (PmSubPayment x : pendings) {
            sum = sum.add(x.getAmount());
        }
        return sum;
    }

    private String nextNo() {
        String prefix = "PSC" + LocalDate.now().toString().replace("-", "");
        Long n = contractDao.selectCount(new LambdaQueryWrapper<PmSubcontract>()
                .likeRight(PmSubcontract::getContractNo, prefix));
        return prefix + String.format("%03d", (n == null ? 0 : n) + 1);
    }

    private String nextPayNo() {
        String prefix = "SPP" + LocalDate.now().toString().replace("-", "");
        Long n = paymentDao.selectCount(new LambdaQueryWrapper<PmSubPayment>()
                .likeRight(PmSubPayment::getPayNo, prefix));
        return prefix + String.format("%03d", (n == null ? 0 : n) + 1);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
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

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
