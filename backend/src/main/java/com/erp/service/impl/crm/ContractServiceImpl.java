package com.erp.service.impl.crm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.crm.ContractDao;
import com.erp.dao.crm.ContractPlanDao;
import com.erp.dao.crm.ContractVersionDao;
import com.erp.dao.crm.OpportunityDao;
import com.erp.dao.sd.SoDao;
import com.erp.entity.crm.Contract;
import com.erp.entity.crm.ContractPlan;
import com.erp.entity.crm.ContractVersion;
import com.erp.entity.crm.Opportunity;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.entity.sd.So;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.crm.ContractService;
import com.erp.service.fin.ArService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 销售合同实现（tasks 14.2~14.6，spec sales-contract，D10/D11）。
 */
@Slf4j
@Service("crmContractServiceImpl")
public class ContractServiceImpl implements ContractService {

    /** 商机金额差异阈值：|diff| > 10% → L2（FR-4.8-1-7） */
    private static final BigDecimal DIFF_THRESHOLD = new BigDecimal("0.10");

    private final ContractDao contractDao;
    private final ContractPlanDao planDao;
    private final ContractVersionDao versionDao;
    private final OpportunityDao oppDao;
    private final MdmCustomerGroupDao customerDao;
    private final SoDao soDao;
    private final ArService arService;
    private final ApprovalEngine approvalEngine;
    private final ObjectMapper mapper = new ObjectMapper();

    public ContractServiceImpl(ContractDao contractDao,
                               ContractPlanDao planDao,
                               ContractVersionDao versionDao,
                               OpportunityDao oppDao,
                               MdmCustomerGroupDao customerDao,
                               SoDao soDao,
                               ArService arService,
                               ApprovalEngine approvalEngine) {
        this.contractDao = contractDao;
        this.planDao = planDao;
        this.versionDao = versionDao;
        this.oppDao = oppDao;
        this.customerDao = customerDao;
        this.soDao = soDao;
        this.arService = arService;
        this.approvalEngine = approvalEngine;
    }

    private static final String[] ROLES = {"ROLE_SALES", "ROLE_SALES_MGR",
            "ROLE_FINANCE_MGR", "ROLE_ADMIN"};

    // ==================== 列表与详情 ====================

    @Override
    public Page<Contract> page(long current, long size, String keyword, String status,
                               String customerId) {
        requireAny("查询合同");
        LambdaQueryWrapper<Contract> qw = new LambdaQueryWrapper<Contract>()
                .eq(isNotBlank(status), Contract::getStatus, status)
                .eq(isNotBlank(customerId), Contract::getCustomerId, customerId)
                .and(isNotBlank(keyword), w -> w.like(Contract::getContractNo, keyword)
                        .or().like(Contract::getCustomerName, keyword)
                        .or().like(Contract::getTitle, keyword))
                .orderByDesc(Contract::getCreateDate);
        return contractDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String id) {
        requireAny("查询合同详情");
        Contract c = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", c);
        out.put("sos", sosOf(c));
        out.put("planProgress", arService.planProgress(id));
        out.put("arSummary", arService.contractArSummary(id));
        out.put("versions", versions(id));
        out.put("planAdjust", planAdjust(c));
        // 审批日志：签订/变更共用 approvalId，按活动版本归属 bizType
        if (isNotBlank(c.getApprovalId())) {
            ContractVersion pending = versionDao.selectOne(new LambdaQueryWrapper<ContractVersion>()
                    .eq(ContractVersion::getContractId, id)
                    .eq(ContractVersion::getStatus, "APPROVING")
                    .orderByDesc(ContractVersion::getVersionNo).last("LIMIT 1"));
            if (pending != null) {
                out.put("approvalLogs", approvalEngine.logs("ContractChange", pending.getId()));
            } else {
                out.put("approvalLogs", approvalEngine.logs("Contract", id));
            }
        } else {
            out.put("approvalLogs", approvalEngine.logs("Contract", id));
        }
        return out;
    }

    // ==================== 14.2 签订 ====================

    @Override
    @Transactional
    public Contract createDraft(Map<String, Object> req) {
        requireAny("创建合同草稿");
        Contract c = new Contract();
        c.setId(uuid());
        c.setContractNo(nextNo("CT", contractDao::selectNosByPrefix));
        String oppId = str(req.get("oppId"));
        if (isNotBlank(oppId)) {
            // 商机带出（FR-4.8-1-7）：名称、客户、预期金额（差异基准）
            Opportunity opp = oppDao.selectById(oppId);
            if (opp == null) {
                throw new ServiceException(404, "商机不存在：" + oppId);
            }
            c.setOppId(opp.getId());
            c.setOppName(opp.getOppName());
            c.setCustomerId(opp.getCustomerId());
            c.setCustomerCode(opp.getCustomerCode());
            c.setCustomerName(opp.getCustomerName());
            c.setOppAmount(opp.getExpectAmount());
        } else {
            String customerId = str(req.get("customerId"));
            if (isBlank(customerId)) {
                throw new ServiceException(422, "客户必填（或从商机生成）");
            }
            MdmCustomerGroup cust = customerDao.selectById(customerId);
            if (cust == null) {
                throw new ServiceException(404, "客户不存在：" + customerId);
            }
            c.setCustomerId(cust.getId());
            c.setCustomerCode(cust.getCustomerCode());
            c.setCustomerName(cust.getCustomerName());
        }
        if (isBlank(str(req.get("title")))) {
            throw new ServiceException(422, "合同名称必填");
        }
        c.setTitle(str(req.get("title")));
        c.setAmount(num(req.get("amount")));
        if (c.getAmount().signum() <= 0) {
            throw new ServiceException(422, "合同金额必须大于 0");
        }
        c.setStartDate(req.get("startDate") == null ? null
                : LocalDate.parse(str(req.get("startDate"))));
        c.setEndDate(req.get("endDate") == null ? null
                : LocalDate.parse(str(req.get("endDate"))));
        c.setRemark(str(req.get("remark")));
        c.setStatus(Contract.ST_DRAFT);
        c.setVersionNo(1);
        c.setChangeCount(0);
        c.setSoCount(0);
        recalcDiff(c);
        c.setCreateBy(currentUser());
        contractDao.insert(c);
        // 首版快照（历史只读）
        writeVersion(c, "CREATE", "合同创建", null, "APPROVED");
        log.info("contract {} created (opp={}, amount={}, diff={})", c.getContractNo(),
                c.getOppId(), strip(c.getAmount()), strip(c.getDiffRate()));
        return c;
    }

    @Override
    @Transactional
    public Contract update(String id, Map<String, Object> req) {
        requireAny("修改合同");
        Contract c = require(id);
        if (!Contract.ST_DRAFT.equals(c.getStatus())
                && !Contract.ST_REJECTED.equals(c.getStatus())) {
            throw new ServiceException(422, "仅草稿/被驳回可修改（当前：" + c.getStatus() + "）");
        }
        if (isNotBlank(str(req.get("contractNo")))
                && !c.getContractNo().equals(str(req.get("contractNo")))) {
            throw new ServiceException(422, "合同编号生成后不可修改（spec 编码锁定）");
        }
        if (req.get("title") != null) {
            c.setTitle(str(req.get("title")));
        }
        if (req.get("amount") != null) {
            c.setAmount(num(req.get("amount")));
            if (c.getAmount().signum() <= 0) {
                throw new ServiceException(422, "合同金额必须大于 0");
            }
        }
        if (req.get("startDate") != null) {
            c.setStartDate(LocalDate.parse(str(req.get("startDate"))));
        }
        if (req.get("endDate") != null) {
            c.setEndDate(LocalDate.parse(str(req.get("endDate"))));
        }
        if (req.get("remark") != null) {
            c.setRemark(str(req.get("remark")));
        }
        recalcDiff(c);
        contractDao.updateById(c);
        return c;
    }

    @Override
    @Transactional
    public Contract submit(String id) {
        requireAny("提交合同");
        Contract c = require(id);
        if (!Contract.ST_DRAFT.equals(c.getStatus())
                && !Contract.ST_REJECTED.equals(c.getStatus())) {
            throw new ServiceException(422, "仅草稿/被驳回可提交（当前：" + c.getStatus() + "）");
        }
        if (c.getAmount() == null || c.getAmount().signum() <= 0) {
            throw new ServiceException(422, "合同金额必须大于 0");
        }
        boolean need = needApproval(c);
        if (need) {
            // FR-4.8-1-7：差异 >10% → L2（销售经理 → 销售总监）
            var inst = approvalEngine.submit("Contract", c.getId(),
                    "合同签订审批（金额差异 " + pct(c.getDiffRate()) + "）："
                            + c.getContractNo() + " / " + c.getCustomerName()
                            + " / ¥" + strip(c.getAmount()), null,
                    List.of(List.of(ApprovalNodeSpec.sign("ROLE_SALES_MGR", "销售经理审批")),
                            List.of(ApprovalNodeSpec.sign("ROLE_SALES_DIRECTOR", "销售总监审批"))));
            c.setApprovalId(inst.getId());
            c.setPrevStatus(Contract.ST_DRAFT);
            c.setStatus(Contract.ST_APPROVING);
            contractDao.updateById(c);
            log.info("contract {} submit L2 (diff={})", c.getContractNo(),
                    pct(c.getDiffRate()));
        } else {
            // 差异 ≤10% → 直接进法务审核占位
            c.setPrevStatus(Contract.ST_DRAFT);
            c.setStatus(Contract.ST_LEGAL_REVIEW);
            contractDao.updateById(c);
        }
        return c;
    }

    @Override
    @Transactional
    public Contract legalReview(String id, boolean pass, String reviewer, String opinion) {
        requireAny("法务审核");
        Contract c = require(id);
        if (!Contract.ST_LEGAL_REVIEW.equals(c.getStatus())) {
            throw new ServiceException(422, "当前状态非法务审核（" + c.getStatus() + "）");
        }
        if (isBlank(reviewer)) {
            throw new ServiceException(422, "法务审核人必填（占位登记 spec 14.2）");
        }
        if (isBlank(opinion) || opinion.trim().length() < 2) {
            throw new ServiceException(422, "审核意见必填（≥2 字，占位登记 spec 14.2）");
        }
        c.setLegalReviewBy(reviewer.trim());
        c.setLegalReviewOpinion(opinion.trim());
        c.setLegalReviewAt(LocalDateTime.now());
        if (pass) {
            c.setStatus(Contract.ST_SIGNED);
            c.setSignDate(LocalDate.now());
            c.setSignedBy(currentUser());
            c.setSignedAt(LocalDateTime.now());
        } else {
            // 驳回 → 退回修改并注明原因（prevStatus 记录来源）
            c.setPrevStatus(Contract.ST_LEGAL_REVIEW);
            c.setStatus(Contract.ST_DRAFT);
        }
        contractDao.updateById(c);
        log.info("contract {} legal review pass={} by {}", c.getContractNo(), pass, reviewer);
        return c;
    }

    // ==================== 14.4 收款计划 ====================

    @Override
    @Transactional
    public Map<String, Object> savePlans(String contractId, List<Map<String, Object>> plans) {
        requireAny("维护收款计划");
        Contract c = require(contractId);
        if (plans == null || plans.isEmpty()) {
            throw new ServiceException(422, "至少一期收款计划");
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        List<ContractPlan> entities = new ArrayList<>();
        for (Map<String, Object> raw : plans) {
            int periodNo = num(raw.get("periodNo")).intValue();
            BigDecimal amount = num(raw.get("planAmount"));
            if (periodNo <= 0) {
                throw new ServiceException(422, "期数必须从 1 开始");
            }
            if (!seen.add(periodNo)) {
                throw new ServiceException(422, "期数重复：" + periodNo);
            }
            if (amount.signum() <= 0) {
                throw new ServiceException(422, "第 " + periodNo + " 期计划金额必须大于 0");
            }
            if (raw.get("dueDate") == null || isBlank(str(raw.get("dueDate")))) {
                throw new ServiceException(422, "第 " + periodNo + " 期计划到期日必填");
            }
            ContractPlan p = new ContractPlan();
            p.setId(uuid());
            p.setContractId(c.getId());
            p.setContractNo(c.getContractNo());
            p.setCustomerId(c.getCustomerId());
            p.setPeriodNo(periodNo);
            p.setPlanAmount(amount);
            p.setDueDate(LocalDate.parse(str(raw.get("dueDate"))));
            p.setRemark(str(raw.get("remark")));
            p.setCreateBy(currentUser());
            entities.add(p);
        }
        // 全量替换（计划不参与记账，可安全重建）
        planDao.delete(new LambdaQueryWrapper<ContractPlan>()
                .eq(ContractPlan::getContractId, c.getId()));
        entities.sort((a, b) -> a.getPeriodNo().compareTo(b.getPeriodNo()));
        for (ContractPlan p : entities) {
            planDao.insert(p);
        }
        BigDecimal sum = entities.stream().map(ContractPlan::getPlanAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plans", entities);
        out.put("planSum", sum);
        out.put("contractAmount", c.getAmount());
        // 合计与合同额不符 → 提示待调整（spec 变更场景同口径）
        out.put("adjustNeeded", sum.compareTo(nvl(c.getAmount())) != 0);
        out.put("adjustDiff", nvl(c.getAmount()).subtract(sum));
        return out;
    }

    @Override
    @Transactional
    public void removePlan(String planId) {
        requireAny("删除收款期次");
        if (planDao.selectById(planId) == null) {
            throw new ServiceException(404, "收款期次不存在：" + planId);
        }
        planDao.deleteById(planId);
    }

    // ==================== 14.5 计划达成 ====================

    @Override
    public Map<String, Object> progress(String contractId) {
        requireAny("查询计划达成");
        Contract c = require(contractId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", c);
        out.put("planProgress", arService.planProgress(contractId));
        out.put("arSummary", arService.contractArSummary(contractId));
        out.put("sos", sosOf(c));
        out.put("planAdjust", planAdjust(c));
        return out;
    }

    // ==================== 14.6 变更 ====================

    @Override
    @Transactional
    public Contract change(String id, Map<String, Object> payload) {
        requireAny("发起合同变更");
        Contract c = require(id);
        if (!Contract.ST_SIGNED.equals(c.getStatus())) {
            throw new ServiceException(422, "仅已签订合同可变更（当前：" + c.getStatus() + "）");
        }
        if (isNotBlank(c.getApprovalId())) {
            throw new ServiceException(422, "已有审批在进行中，请等待结果");
        }
        String reason = str(payload.get("reason"));
        if (isBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "变更原因必填（≥2 字，14.6 留痕）");
        }
        // 新值（缺省保持原值）
        String newTitle = payload.get("title") == null ? c.getTitle() : str(payload.get("title"));
        BigDecimal newAmount = payload.get("amount") == null ? c.getAmount()
                : num(payload.get("amount"));
        if (newAmount.signum() <= 0) {
            throw new ServiceException(422, "变更后金额必须大于 0");
        }
        LocalDate newStart = payload.get("startDate") == null ? c.getStartDate()
                : LocalDate.parse(str(payload.get("startDate")));
        LocalDate newEnd = payload.get("endDate") == null ? c.getEndDate()
                : LocalDate.parse(str(payload.get("endDate")));

        // 金额差异重校验（14.6：差异扩大 → 重跑 >10% 校验）
        BigDecimal newDiff = diffRate(newAmount, c.getOppAmount());
        boolean need = newDiff != null && newDiff.abs().compareTo(DIFF_THRESHOLD) > 0;

        // 新版本快照（历史只读；号段取历史最大 +1 —— 被驳回版本也占用号段）
        int maxVer = versionDao.selectList(new LambdaQueryWrapper<ContractVersion>()
                        .eq(ContractVersion::getContractId, id))
                .stream().mapToInt(v -> v.getVersionNo() == null ? 0 : v.getVersionNo())
                .max().orElse(0);
        int nextVer = Math.max(maxVer, c.getVersionNo() == null ? 1 : c.getVersionNo()) + 1;
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("title", newTitle);
        snap.put("amount", newAmount);
        snap.put("startDate", str(newStart));
        snap.put("endDate", str(newEnd));
        snap.put("before", Map.of("title", c.getTitle() == null ? "" : c.getTitle(),
                "amount", strip(nvl(c.getAmount())),
                "startDate", str(c.getStartDate()) == null ? "" : str(c.getStartDate()),
                "endDate", str(c.getEndDate()) == null ? "" : str(c.getEndDate())));
        ContractVersion v = new ContractVersion();
        v.setId(uuid());
        v.setContractId(c.getId());
        v.setVersionNo(nextVer);
        v.setSnapshotJson(toJson(snap));
        v.setDiffSummary("金额 " + strip(nvl(c.getAmount())) + " → " + strip(newAmount)
                + "，差异率 " + pct(newDiff));
        v.setOpType("UPDATE");
        v.setChangeReason(reason.trim());
        v.setStatus("APPROVING");
        v.setCreateBy(currentUser());
        versionDao.insert(v);

        // 销售经理审批；金额差异 >10% → 加销售总监（L2）
        List<List<ApprovalNodeSpec>> chain = need
                ? List.of(List.of(ApprovalNodeSpec.sign("ROLE_SALES_MGR", "销售经理审批")),
                List.of(ApprovalNodeSpec.sign("ROLE_SALES_DIRECTOR", "销售总监审批（金额差异）")))
                : List.of(List.of(ApprovalNodeSpec.sign("ROLE_SALES_MGR", "销售经理审批")));
        var inst = approvalEngine.submit("ContractChange", v.getId(),
                "合同变更审批：" + c.getContractNo() + " / " + strip(nvl(c.getAmount()))
                        + "→" + strip(newAmount) + (need ? " / 差异扩大 L2" : ""), null, chain);
        c.setApprovalId(inst.getId());
        v.setApprovalId(inst.getId());
        versionDao.updateById(v);
        c.setPrevStatus(Contract.ST_SIGNED);
        c.setStatus(Contract.ST_APPROVING);
        if (contractDao.updateById(c) == 0) {
            throw new ServiceException(409, "合同状态更新冲突");
        }
        log.info("contract {} change to v{} (needL2={})", c.getContractNo(), nextVer, need);
        return c;
    }

    @Override
    public List<Map<String, Object>> versions(String id) {
        requireAny("查询版本历史");
        require(id);
        List<Map<String, Object>> out = new ArrayList<>();
        List<ContractVersion> list = versionDao.selectList(new LambdaQueryWrapper<ContractVersion>()
                .eq(ContractVersion::getContractId, id)
                .orderByDesc(ContractVersion::getVersionNo));
        for (ContractVersion v : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", v);
            m.put("snapshot", readJson(v.getSnapshotJson()));
            out.add(m);
        }
        return out;
    }

    /** 供 SO 关联（14.3）：校验并回写合同 SO 数（原子 +1） */
    public static void bumpSoCount(ContractDao dao, String contractId) {
        dao.update(null, new LambdaUpdateWrapper<Contract>()
                .eq(Contract::getId, contractId)
                .setSql("SO_COUNT = SO_COUNT + 1"));
    }

    // ==================== 私有工具 ====================

    private void recalcDiff(Contract c) {
        c.setDiffRate(diffRate(c.getAmount(), c.getOppAmount()));
        c.setNeedApproval(needApproval(c));
    }

    private static BigDecimal diffRate(BigDecimal amount, BigDecimal oppAmount) {
        if (amount == null || oppAmount == null || oppAmount.signum() == 0) {
            return null;
        }
        return amount.subtract(oppAmount).divide(oppAmount, 4, RoundingMode.HALF_UP);
    }

    private static boolean needApproval(Contract c) {
        return c.getDiffRate() != null && c.getDiffRate().abs().compareTo(DIFF_THRESHOLD) > 0;
    }

    /** 计划合计与合同额差异（14.6 提示待调整） */
    private Map<String, Object> planAdjust(Contract c) {
        BigDecimal sum = planDao.selectList(new LambdaQueryWrapper<ContractPlan>()
                        .eq(ContractPlan::getContractId, c.getId()))
                .stream().map(ContractPlan::getPlanAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planSum", sum);
        m.put("contractAmount", nvl(c.getAmount()));
        m.put("needed", sum.compareTo(nvl(c.getAmount())) != 0);
        m.put("diff", nvl(c.getAmount()).subtract(sum));
        return m;
    }

    private List<Map<String, Object>> sosOf(Contract c) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<So> sos = soDao.selectList(new LambdaQueryWrapper<So>()
                .eq(So::getContractId, c.getId())
                .orderByDesc(So::getCreateDate));
        for (So so : sos) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", so.getId());
            m.put("soNo", so.getSoNo());
            m.put("status", so.getStatus());
            m.put("totalAmount", so.getTotalAmount());
            out.add(m);
        }
        return out;
    }

    private void writeVersion(Contract c, String opType, String reason,
                              String diffSummary, String status) {
        ContractVersion v = new ContractVersion();
        v.setId(uuid());
        v.setContractId(c.getId());
        v.setVersionNo(c.getVersionNo() == null ? 1 : c.getVersionNo());
        v.setSnapshotJson(toJson(snapshotOf(c)));
        v.setDiffSummary(diffSummary);
        v.setOpType(opType);
        v.setChangeReason(reason);
        v.setStatus(status);
        v.setCreateBy(currentUser());
        versionDao.insert(v);
    }

    private Map<String, Object> snapshotOf(Contract c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contractNo", c.getContractNo());
        m.put("title", c.getTitle());
        m.put("amount", c.getAmount());
        m.put("oppAmount", c.getOppAmount());
        m.put("diffRate", c.getDiffRate());
        m.put("startDate", str(c.getStartDate()));
        m.put("endDate", str(c.getEndDate()));
        m.put("customerName", c.getCustomerName());
        return m;
    }

    private Contract require(String id) {
        Contract c = contractDao.selectById(id);
        if (c == null) {
            throw new ServiceException(404, "合同不存在：" + id);
        }
        return c;
    }

    private String nextNo(String kind, Function<String, List<String>> query) {
        String prefix = kind + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-";
        int max = 0;
        for (String no : query.apply(prefix + "%")) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 跳过非规范编号
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new ServiceException(500, "快照序列化失败：" + e.getMessage());
        }
    }

    private Object readJson(String json) {
        if (isBlank(json)) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void requireAny(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = List.of(ROLES);
        boolean ok = auth.getAuthorities().stream()
                .anyMatch(a -> roles.contains(a.getAuthority()));
        if (!ok) {
            throw new ServiceException(403, "无权限" + action);
        }
    }

    private String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "system" : auth.getName();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal num(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String strip(BigDecimal v) {
        return nvl(v).stripTrailingZeros().toPlainString();
    }

    private static String pct(BigDecimal v) {
        return v == null ? "—" : v.multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
