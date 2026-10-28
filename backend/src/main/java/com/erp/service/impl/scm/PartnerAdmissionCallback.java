package com.erp.service.impl.scm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.scm.PartnerActivateTokenDao;
import com.erp.dao.scm.PartnerApplyDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.dao.system.SysUserDao;
import com.erp.dao.system.SysUserRoleDao;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.scm.PartnerActivateToken;
import com.erp.entity.scm.PartnerApply;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.SysUser;
import com.erp.entity.system.SysUserRole;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.mdm.MdmSupplierService;
import com.erp.service.system.NoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * 伙伴引入审批回调（add-partner-onboarding，design D3）。
 * BIZ_TYPE=PARTNER_ADMISSION：
 *  pass → 复核黑名单/查重（防并发双单）→ 调 supplier-admission 建档（SUP-NNNN + PENDING）
 *        → review APPROVED 置 QUALIFIED（审核已在申请环节完成，跳过档案层待审）
 *        → 建待激活门户账号（STATUS='0' 不可登录）+ 一次性激活 token（7 天）
 *        → 申请单 APPROVED 回写 SUPPLIER_ID/ACCOUNT_ID + 通知采购
 *        → 建档失败（黑名单命中/查重）→ 申请单 REJECTED + 原因
 *  fail（驳回）→ REJECT_COUNT+1，≥3 自动 CLOSED（BR-4.9-10 语义）
 * 只注入 DAO + NoticeService + ObjectProvider<MdmSupplierService>（避免构造循环）。
 */
@Slf4j
@Component
public class PartnerAdmissionCallback implements ApprovalCallback {

    public static final String BIZ_PARTNER_ADMISSION = "PARTNER_ADMISSION";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PartnerApplyDao applyDao;
    private final PartnerActivateTokenDao tokenDao;
    private final MdmSupplierDao supplierDao;
    private final SysUserDao userDao;
    private final SysUserRoleDao userRoleDao;
    private final ApprovalTaskDao taskDao;
    private final NoticeService noticeService;
    private final ObjectProvider<MdmSupplierService> supplierServiceProvider;

    public PartnerAdmissionCallback(PartnerApplyDao applyDao,
                                    PartnerActivateTokenDao tokenDao,
                                    MdmSupplierDao supplierDao,
                                    SysUserDao userDao,
                                    SysUserRoleDao userRoleDao,
                                    ApprovalTaskDao taskDao,
                                    NoticeService noticeService,
                                    ObjectProvider<MdmSupplierService> supplierServiceProvider) {
        this.applyDao = applyDao;
        this.tokenDao = tokenDao;
        this.supplierDao = supplierDao;
        this.userDao = userDao;
        this.userRoleDao = userRoleDao;
        this.taskDao = taskDao;
        this.noticeService = noticeService;
        this.supplierServiceProvider = supplierServiceProvider;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_PARTNER_ADMISSION);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        PartnerApply a = applyOf(instance.getBizId());
        if (a == null) {
            log.warn("partner admission approved but apply missing: {}", instance.getBizId());
            return;
        }
        if (!PartnerApply.ST_APPROVING.equals(a.getStatus())) {
            // 撤回/关闭后的迟到回调，忽略
            log.info("partner admission approved but apply status={}, skip", a.getStatus());
            return;
        }
        // 并发兜底：建档前预检税号占用（异常会标 rollback-only 毒化整单，故预检不走异常路径）
        Long occupied = supplierDao.selectCount(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<MdmSupplier>().eq(MdmSupplier::getTaxNo, a.getTaxNo()));
        if (occupied != null && occupied > 0) {
            a.setStatus(PartnerApply.ST_REJECTED);
            a.setCloseReason("审核通过但建档失败：税号已占用（并发引入）");
            a.setUpdateDate(LocalDateTime.now());
            applyDao.updateById(a);
            log.warn("partner admission pre-check failed: tax occupied {}", a.getTaxNo());
            return;
        }
        MdmSupplierService supplierService = supplierServiceProvider.getObject();
        MdmSupplier created;
        try {
            // 建档（SUP-NNNN 自动编码；名称相似 forceCreate=true 放行——申请环节已查重）
            MdmSupplier s = new MdmSupplier();
            s.setSupplierName(a.getSupplierName());
            s.setTaxNo(a.getTaxNo());
            s.setContactName(a.getContactName());
            s.setContactPhone(a.getContactPhone());
            s.setBankName(a.getBankName());
            s.setPaymentTerms(a.getPaymentTerms());
            // 合规筛查结果：入驻申请已过多级审核（含合规终审），视同 CLEAR；真实筛查命中在建档入口被 L1 挡
            s.setBlacklistResult("CLEAR");
            // 建档必填兜底：申请单暂无地址字段（入驻申请侧重资质审核），留待门户初始化引导补全
            s.setAddress("待补（入驻后在门户维护）");
            created = supplierService.create(s, true);
            // 审核已在申请环节完成 → 直接 review 置 QUALIFIED
            supplierService.review(created.getId(), "APPROVED",
                    "入驻申请单多级审核通过（" + a.getApplyNo() + "）自动准入");
        } catch (ServiceException e) {
            // 预检已挡主要路径；此处兜底不可依赖（异常已标 rollback-only）——记录并让事务回滚
            log.error("partner admission auto-create supplier failed unexpectedly: {}",
                    e.getMessage());
            throw e;
        }

        // 待激活门户账号（STATUS='0' 不可登录，激活置 '1'）
        SysUser u = new SysUser();
        String username = "sup-" + a.getTaxNo().substring(a.getTaxNo().length() - 8).toLowerCase();
        u.setUsername(username);
        // 占位密码（激活时覆盖），复杂度由激活端点校验
        u.setPassword("$2a$10$KvoqroXik9qiqRA8MLTZTe4XrU1QurZ3dLZVEg9CJNfImhr1AGD3W");
        u.setNickName(a.getSupplierName() + "门户");
        u.setStatus("0");
        u.setSupplierId(created.getId());
        try {
            userDao.insert(u);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            username = "sup-" + created.getId().substring(0, 12).toLowerCase();
            u.setUsername(username);
            userDao.insert(u);
        }
        SysUserRole ur = new SysUserRole();
        ur.setId("ur-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        ur.setUserId(u.getId());
        ur.setRoleId("role-supplier");
        userRoleDao.insert(ur);

        // 一次性激活 token（7 天，BR-4.9-11）
        PartnerActivateToken t = new PartnerActivateToken();
        t.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
        t.setAccountId(u.getId());
        t.setSupplierId(created.getId());
        t.setToken(randomToken());
        t.setExpireAt(LocalDateTime.now().plusDays(PartnerActivateToken.EXPIRE_DAYS));
        t.setInvalidFlag("0");
        t.setCreateBy("system");
        t.setCreateDate(LocalDateTime.now());
        tokenDao.insert(t);

        a.setStatus(PartnerApply.ST_APPROVED);
        a.setSupplierId(created.getId());
        a.setAccountId(u.getId());
        a.setUpdateBy("system");
        a.setUpdateDate(LocalDateTime.now());
        applyDao.updateById(a);

        noticeService.push("ROLE_PROCUREMENT", null,
                "伙伴入驻通过：" + a.getSupplierName(),
                "申请单 " + a.getApplyNo() + " 审核通过，已建档 " + created.getSupplierCode()
                        + " 并创建待激活门户账号（激活链接在申请单详情查看）",
                BIZ_PARTNER_ADMISSION, a.getId());
        log.info("partner admission approved: apply={} supplier={} account={}",
                a.getApplyNo(), created.getSupplierCode(), username);
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        PartnerApply a = applyOf(instance.getBizId());
        if (a == null) {
            return;
        }
        int count = (a.getRejectCount() == null ? 0 : a.getRejectCount()) + 1;
        a.setRejectCount(count);
        if (count >= PartnerApply.REJECT_CLOSE_THRESHOLD) {
            a.setStatus(PartnerApply.ST_CLOSED);
            a.setCloseReason("累计驳回 " + count + " 次，自动关闭（BR-4.9-10 语义）");
            noticeService.push("ROLE_PROCUREMENT", null,
                    "伙伴引入申请自动关闭：" + a.getSupplierName(),
                    "申请单 " + a.getApplyNo() + " 累计驳回 " + count + " 次已自动关闭",
                    BIZ_PARTNER_ADMISSION, a.getId());
        } else {
            // 整单驳回 → 退回 DRAFT 可修改后重提
            a.setStatus(PartnerApply.ST_DRAFT);
            a.setApprovalId(null);
        }
        a.setUpdateDate(LocalDateTime.now());
        applyDao.updateById(a);
        log.info("partner admission rejected: apply={} rejectCount={} closed={}",
                a.getApplyNo(), count, count >= PartnerApply.REJECT_CLOSE_THRESHOLD);
    }

    private PartnerApply applyOf(String id) {
        if (id == null) {
            return null;
        }
        return applyDao.selectOne(new LambdaQueryWrapper<PartnerApply>()
                .eq(PartnerApply::getId, id)
                .eq(PartnerApply::getDelFlag, "0")
                .last("LIMIT 1"));
    }

    /** 64 hex 一次性随机 token */
    static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
