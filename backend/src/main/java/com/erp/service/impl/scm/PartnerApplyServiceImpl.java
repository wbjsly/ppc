package com.erp.service.impl.scm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.scm.CriticalGroupDao;
import com.erp.dao.scm.PartnerApplyCertDao;
import com.erp.dao.scm.PartnerApplyDao;
import com.erp.dao.system.ApprovalInstanceDao;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.scm.CriticalGroup;
import com.erp.entity.scm.PartnerApply;
import com.erp.entity.scm.PartnerApplyCert;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.scm.PartnerApplyService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 伙伴引入申请单实现（add-partner-onboarding，design D1/D2）。
 * 风险判定：EST_ANNUAL_AMOUNT > 100 万 OR 主营品类物料组 ∈ 关键物料组白名单 → HIGH（三级）。
 * 判定结果在提交时落值（RISK_LEVEL/KEY_MATERIAL_FLAG 快照），审核期间不随主数据漂移。
 */
@Slf4j
@Service
public class PartnerApplyServiceImpl implements PartnerApplyService {

    /** BR-4.9-10 高风险金额阈值：> 100 万/年 */
    static final BigDecimal HIGH_RISK_AMOUNT = new BigDecimal("1000000");
    /** 信用代码 18 位（数字+大写字母，排除易混 I/O/S/V 规则本期不细化，仅字符集校验） */
    private static final Pattern TAX_NO_PATTERN = Pattern.compile("^[0-9A-HJ-NPQRTUWXY]{18}$");
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PartnerApplyDao applyDao;
    private final PartnerApplyCertDao certDao;
    private final CriticalGroupDao criticalGroupDao;
    private final MdmSupplierDao supplierDao;
    private final ApprovalInstanceDao approvalInstanceDao;
    private final ApprovalEngine approvalEngine;
    private final JdbcTemplate jdbcTemplate;

    public PartnerApplyServiceImpl(PartnerApplyDao applyDao,
                                   PartnerApplyCertDao certDao,
                                   CriticalGroupDao criticalGroupDao,
                                   MdmSupplierDao supplierDao,
                                   ApprovalInstanceDao approvalInstanceDao,
                                   ApprovalEngine approvalEngine,
                                   JdbcTemplate jdbcTemplate) {
        this.applyDao = applyDao;
        this.certDao = certDao;
        this.criticalGroupDao = criticalGroupDao;
        this.supplierDao = supplierDao;
        this.approvalInstanceDao = approvalInstanceDao;
        this.approvalEngine = approvalEngine;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ================================================================
    // 保存草稿（BR-4.9-08/09 校验前移）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> save(Map<String, Object> payload) {
        requireRole("维护伙伴引入申请", "PURCHASER");
        String id = str(payload.get("id"));
        PartnerApply a;
        if (StringUtils.hasText(id)) {
            a = require(id);
            if (!PartnerApply.ST_DRAFT.equals(a.getStatus())) {
                throw new ServiceException(422, "仅草稿态可编辑，当前：" + a.getStatus());
            }
        } else {
            a = new PartnerApply();
            a.setId(uuid());
            a.setApplyNo(nextApplyNo());
            a.setPartnerType("SUPPLIER");
            a.setStatus(PartnerApply.ST_DRAFT);
            a.setRejectCount(0);
            a.setCreateBy(SecurityUtils.getCurrentUserId());
            a.setCreateDate(LocalDateTime.now());
        }
        a.setSupplierName(str(payload.get("supplierName")));
        a.setTaxNo(str(payload.get("taxNo")));
        a.setContactName(str(payload.get("contactName")));
        a.setContactPhone(str(payload.get("contactPhone")));
        a.setContactEmail(str(payload.get("contactEmail")));
        a.setMainCategory(str(payload.get("mainCategory")));
        a.setBankName(str(payload.get("bankName")));
        a.setPaymentTerms(str(payload.get("paymentTerms")));
        a.setRemark(str(payload.get("remark")));
        Object amt = payload.get("estAnnualAmount");
        a.setEstAnnualAmount(amt == null || !StringUtils.hasText(String.valueOf(amt))
                ? null : new BigDecimal(String.valueOf(amt).trim()));

        validateRequired(a);
        validateTaxNo(a.getTaxNo());
        dedupTaxNo(a.getTaxNo(), id);

        a.setUpdateBy(SecurityUtils.getCurrentUserId());
        a.setUpdateDate(LocalDateTime.now());
        if (!StringUtils.hasText(id)) {
            applyDao.insert(a);
        } else {
            applyDao.updateById(a);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apply", a);
        return out;
    }

    // ================================================================
    // 提交审批（风险分级动态组链）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> submit(String id) {
        requireRole("提交伙伴引入申请", "PURCHASER");
        PartnerApply a = require(id);
        if (!PartnerApply.ST_DRAFT.equals(a.getStatus())) {
            throw new ServiceException(422, "仅草稿态可提交，当前：" + a.getStatus());
        }
        // 证照卡点（提交时点，BR-4.9-09）；附件数从附件表实时重算（证照行保存后才上传附件）
        List<PartnerApplyCert> certs = certsOf(id);
        if (certs.isEmpty()) {
            throw new ServiceException(422, "至少上传一条资质证照（含附件）");
        }
        LocalDate today = LocalDate.now();
        for (PartnerApplyCert c : certs) {
            syncCount(c); // 附件上传在证照行保存之后，落库计数已过时
            if (c.getAttachCount() == null || c.getAttachCount() < 1) {
                throw new ServiceException(422, "「" + certTypeName(c.getCertType())
                        + "」未上传扫描件附件");
            }
            if (c.getExpireDate() != null && c.getExpireDate().isBefore(today)) {
                throw new ServiceException(422, "「" + certTypeName(c.getCertType())
                        + "」资质已过期，请更新后重新提交（BR-4.9-09）");
            }
        }
        // 风险判定并落值（快照防漂移）
        assessRisk(a);

        // 动态组链：HIGH 三级 / NORMAL 两级
        List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_PURCHASER", "采购初审")));
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_QUALITY_MGR", "质量复审")));
        if (PartnerApply.RISK_HIGH.equals(a.getRiskLevel())) {
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_COMPLIANCE", "合规终审")));
        }
        ApprovalInstance inst = approvalEngine.submit("PARTNER_ADMISSION", a.getId(),
                "伙伴引入：" + a.getSupplierName() + "（" + a.getApplyNo() + "）",
                "ROLE_PROCUREMENT", chain);

        a.setStatus(PartnerApply.ST_APPROVING);
        a.setApprovalId(inst.getId());
        a.setUpdateBy(SecurityUtils.getCurrentUserId());
        a.setUpdateDate(LocalDateTime.now());
        applyDao.updateById(a);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apply", a);
        out.put("approvalId", inst.getId());
        out.put("nodeCount", chain.size());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> withdraw(String id) {
        requireRole("撤回伙伴引入申请", "PURCHASER");
        PartnerApply a = require(id);
        if (!PartnerApply.ST_APPROVING.equals(a.getStatus())) {
            throw new ServiceException(422, "仅审批中可撤回");
        }
        if (StringUtils.hasText(a.getApprovalId())) {
            ApprovalInstance inst = approvalInstanceDao.selectById(a.getApprovalId());
            if (inst != null && "PENDING".equals(inst.getStatus())) {
                inst.setStatus("CANCELLED");
                inst.setUpdateBy(SecurityUtils.getCurrentUserId());
                inst.setUpdateDate(LocalDateTime.now());
                approvalInstanceDao.updateById(inst);
            }
        }
        a.setStatus(PartnerApply.ST_DRAFT);
        a.setApprovalId(null);
        a.setUpdateBy(SecurityUtils.getCurrentUserId());
        a.setUpdateDate(LocalDateTime.now());
        applyDao.updateById(a);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apply", a);
        return out;
    }

    // ================================================================
    // 查询
    // ================================================================

    @Override
    public Map<String, Object> page(long current, long size, String status, String keyword) {
        requireRole("查询伙伴引入申请", "PURCHASER");
        LambdaQueryWrapper<PartnerApply> w = new LambdaQueryWrapper<PartnerApply>()
                .eq(PartnerApply::getDelFlag, "0")
                .eq(StringUtils.hasText(status), PartnerApply::getStatus, status)
                .and(StringUtils.hasText(keyword), q -> q
                        .like(PartnerApply::getSupplierName, keyword.trim())
                        .or().like(PartnerApply::getApplyNo, keyword.trim())
                        .or().like(PartnerApply::getTaxNo, keyword.trim()))
                .orderByDesc(PartnerApply::getCreateDate);
        Page<PartnerApply> p = applyDao.selectPage(new Page<>(current, size), w);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", p.getTotal());
        out.put("records", p.getRecords());
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        requireRole("查看伙伴引入申请", "PURCHASER");
        PartnerApply a = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apply", a);
        out.put("certs", certsOf(id));
        // 激活链接状态（账号回写后可查）
        if (StringUtils.hasText(a.getAccountId())) {
            out.put("activateTokens", jdbcTemplate.queryForList(
                    "SELECT ID, TOKEN, EXPIRE_AT, USED_AT, INVALID_FLAG, CREATE_DATE "
                            + "FROM erp_scm_partner_activate_token WHERE ACCOUNT_ID = ? "
                            + "AND DEL_FLAG = '0' ORDER BY CREATE_DATE DESC",
                    a.getAccountId()));
        }
        // 审批日志摘要
        if (StringUtils.hasText(a.getApprovalId())) {
            out.put("approval", approvalEngine.logs("PARTNER_ADMISSION", id));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> listByStatus(String status) {
        LambdaQueryWrapper<PartnerApply> w = new LambdaQueryWrapper<PartnerApply>()
                .eq(PartnerApply::getDelFlag, "0")
                .eq(StringUtils.hasText(status), PartnerApply::getStatus, status)
                .orderByDesc(PartnerApply::getCreateDate);
        return new ArrayList<>(applyDao.selectList(w).stream().map(this::row).toList());
    }

    // ================================================================
    // 证照行（FR-4.9-1-2）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> saveCert(Map<String, Object> payload) {
        requireRole("维护申请证照", "PURCHASER");
        String applyId = str(payload.get("applyId"));
        PartnerApply a = require(applyId);
        if (!PartnerApply.ST_DRAFT.equals(a.getStatus())) {
            throw new ServiceException(422, "仅草稿态可维护证照");
        }
        String id = str(payload.get("id"));
        PartnerApplyCert c;
        if (StringUtils.hasText(id)) {
            c = certDao.selectById(id);
            if (c == null || !"0".equals(c.getDelFlag())) {
                throw new ServiceException(404, "证照行不存在");
            }
            if (!applyId.equals(c.getApplyId())) {
                throw new ServiceException(422, "证照行不属于该申请单");
            }
        } else {
            c = new PartnerApplyCert();
            c.setId(uuid());
            c.setApplyId(applyId);
            c.setCreateBy(SecurityUtils.getCurrentUserId());
            c.setCreateDate(LocalDateTime.now());
        }
        String type = str(payload.get("certType"));
        Set<String> types = Set.of("LICENSE", "QMS", "ENV", "PERMIT", "OTHER");
        if (type == null || !types.contains(type)) {
            throw new ServiceException(422, "证照类型非法：" + type);
        }
        c.setCertType(type);
        c.setCertNo(str(payload.get("certNo")));
        c.setIssueDate(parseDate(str(payload.get("issueDate"))));
        LocalDate expire = parseDate(str(payload.get("expireDate")));
        if (expire == null) {
            throw new ServiceException(422, "有效期必填");
        }
        c.setExpireDate(expire);
        // ATTACH_COUNT 以附件表实际计数为准（防直改库绕过）
        syncCount(c);
        c.setUpdateBy(SecurityUtils.getCurrentUserId());
        c.setUpdateDate(LocalDateTime.now());
        if (!StringUtils.hasText(id)) {
            certDao.insert(c);
        } else {
            certDao.updateById(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cert", c);
        return out;
    }

    @Override
    @Transactional
    public void deleteCert(String certId) {
        requireRole("删除申请证照", "PURCHASER");
        PartnerApplyCert c = certDao.selectById(certId);
        if (c == null || "1".equals(c.getDelFlag())) {
            throw new ServiceException(404, "证照行不存在");
        }
        PartnerApply a = require(c.getApplyId());
        if (!PartnerApply.ST_DRAFT.equals(a.getStatus())) {
            throw new ServiceException(422, "仅草稿态可删除证照");
        }
        c.setDelFlag("1");
        c.setUpdateBy(SecurityUtils.getCurrentUserId());
        c.setUpdateDate(LocalDateTime.now());
        certDao.updateById(c);
    }

    @Override
    public void syncCertAttachCount(String certId) {
        PartnerApplyCert c = certDao.selectById(certId);
        if (c == null) {
            return;
        }
        syncCount(c);
        certDao.updateById(c);
    }

    /** 附件计数以 erp_sys_attachment 实际行数为准（BIZ_TYPE=PARTNER_APPLY_CERT，BIZ_ID=证照行 ID） */
    private void syncCount(PartnerApplyCert c) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_attachment WHERE BIZ_TYPE = 'PARTNER_APPLY_CERT' "
                        + "AND BIZ_ID = ? AND DEL_FLAG = '0'", Long.class, c.getId());
        c.setAttachCount(n == null ? 0 : n.intValue());
    }

    private static LocalDate parseDate(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        return LocalDate.parse(s.trim());
    }

    // ================================================================
    // 内部工具
    // ================================================================

    /** 风险判定：金额 > 100 万 OR 主营品类物料组 ∈ 关键物料组白名单 → HIGH（落值快照） */
    private void assessRisk(PartnerApply a) {
        boolean amountHigh = a.getEstAnnualAmount() != null
                && a.getEstAnnualAmount().compareTo(HIGH_RISK_AMOUNT) > 0;
        boolean materialHigh = false;
        if (StringUtils.hasText(a.getMainCategory())) {
            Set<String> groups = criticalGroupDao.selectList(
                            new LambdaQueryWrapper<CriticalGroup>()
                                    .eq(CriticalGroup::getDelFlag, "0"))
                    .stream().map(CriticalGroup::getMaterialGroup)
                    .collect(java.util.stream.Collectors.toSet());
            // 主营品类可为逗号分隔多组
            for (String g : a.getMainCategory().split("[,，]")) {
                if (groups.contains(g.trim())) {
                    materialHigh = true;
                    break;
                }
            }
        }
        a.setKeyMaterialFlag(materialHigh ? "1" : "0");
        a.setRiskLevel((amountHigh || materialHigh)
                ? PartnerApply.RISK_HIGH : PartnerApply.RISK_NORMAL);
    }

    private void validateRequired(PartnerApply a) {
        if (!StringUtils.hasText(a.getSupplierName())) {
            throw new ServiceException(422, "企业名称必填");
        }
        if (!StringUtils.hasText(a.getTaxNo())) {
            throw new ServiceException(422, "统一社会信用代码必填");
        }
        if (!StringUtils.hasText(a.getContactName())
                || !StringUtils.hasText(a.getContactPhone())) {
            throw new ServiceException(422, "联系人与联系方式必填");
        }
        if (!StringUtils.hasText(a.getContactEmail()) || !a.getContactEmail().contains("@")) {
            throw new ServiceException(422, "联系人邮箱必填（激活链接送达）");
        }
        // FR-4.9-1-1 明文银行账户必填；建档 requireFields 同口径（开户行+付款条件），缺则审核通过后建档必败
        if (!StringUtils.hasText(a.getBankName())) {
            throw new ServiceException(422, "银行账户必填");
        }
        if (!StringUtils.hasText(a.getPaymentTerms())) {
            throw new ServiceException(422, "付款条件必填");
        }
    }

    /** BR-4.9-08：18 位格式 L1 阻断 */
    private void validateTaxNo(String taxNo) {
        if (taxNo == null || !TAX_NO_PATTERN.matcher(taxNo).matches()) {
            throw new ServiceException(422, "统一社会信用代码须为 18 位（数字+大写字母）");
        }
    }

    /** BR-4.9-08：与既有档案 TAX_NO 或在途申请精确重复 → L1 阻断（无 force 绕过） */
    private void dedupTaxNo(String taxNo, String selfId) {
        Long inSupplier = supplierDao.selectCount(new LambdaQueryWrapper<MdmSupplier>()
                .eq(MdmSupplier::getTaxNo, taxNo));
        if (inSupplier != null && inSupplier > 0) {
            throw new ServiceException(422, "该企业已存在（信用代码命中供应商档案）");
        }
        Long inFlight = applyDao.selectCount(new LambdaQueryWrapper<PartnerApply>()
                .eq(PartnerApply::getTaxNo, taxNo)
                .eq(PartnerApply::getDelFlag, "0")
                .in(PartnerApply::getStatus, PartnerApply.ST_DRAFT, PartnerApply.ST_APPROVING)
                .ne(StringUtils.hasText(selfId), PartnerApply::getId, selfId));
        if (inFlight != null && inFlight > 0) {
            throw new ServiceException(422, "该企业已存在（在途引入申请重复）");
        }
    }

    private List<PartnerApplyCert> certsOf(String applyId) {
        return certDao.selectList(new LambdaQueryWrapper<PartnerApplyCert>()
                .eq(PartnerApplyCert::getApplyId, applyId)
                .eq(PartnerApplyCert::getDelFlag, "0"));
    }

    private String nextApplyNo() {
        String prefix = "PA" + LocalDate.now().format(NO_FMT);
        Long max = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM erp_scm_partner_apply WHERE APPLY_NO LIKE ?",
                Long.class, prefix + "%");
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    private PartnerApply require(String id) {
        PartnerApply a = applyDao.selectById(id);
        if (a == null || "1".equals(a.getDelFlag())) {
            throw new ServiceException(404, "申请单不存在：" + id);
        }
        return a;
    }

    private Map<String, Object> row(PartnerApply a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("applyNo", a.getApplyNo());
        m.put("supplierName", a.getSupplierName());
        m.put("taxNo", a.getTaxNo());
        m.put("riskLevel", a.getRiskLevel());
        m.put("status", a.getStatus());
        m.put("rejectCount", a.getRejectCount());
        m.put("supplierId", a.getSupplierId());
        m.put("accountId", a.getAccountId());
        m.put("createDate", a.getCreateDate());
        return m;
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(g -> userRoles.add(g.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String role : allowed) {
            if (userRoles.stream().anyMatch(r -> ("ROLE_" + role).equalsIgnoreCase(r))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限" + action);
    }

    private static String certTypeName(String type) {
        if (type == null) {
            return "证照";
        }
        return switch (type) {
            case "LICENSE" -> "营业执照";
            case "QMS" -> "质量体系认证";
            case "ENV" -> "环保资质";
            case "PERMIT" -> "行业许可证";
            default -> "证照";
        };
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
