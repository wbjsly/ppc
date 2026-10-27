package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmIpAgreementDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.entity.pm.PmIpAgreement;
import com.erp.entity.pm.PmProject;
import com.erp.service.pm.PmIpAgreementService;
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
 * IP 归属协议实现（add-ip-management，design D1/D2）。
 * BR-4.8-25：合作研发公司+合作方比例合计必须=100%（不可模糊）；
 * 每项目一份生效协议（后签覆盖前签，旧协议 EFFECTIVE=0 留痕可查）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmIpAgreementServiceImpl implements PmIpAgreementService {

    /** 合作模式 */
    public static final String MODE_INDEP = "INDEP";
    public static final String MODE_COOP = "COOP";
    public static final String MODE_ENTRUST = "ENTRUST";

    private final PmIpAgreementDao agreementDao;
    private final PmProjectDao projectDao;

    @Override
    @Transactional
    public Map<String, Object> save(Map<String, Object> payload) {
        // 维护写操作限法务/管理员（服务层单据级校验，铁律⑥）
        requireLegal();
        String projectId = str(payload.get("projectId"));
        String coopMode = str(payload.get("coopMode"));
        if (projectId == null || projectId.isBlank()) {
            throw new ServiceException(422, "协议必须关联项目");
        }
        if (!MODE_INDEP.equals(coopMode) && !MODE_COOP.equals(coopMode) && !MODE_ENTRUST.equals(coopMode)) {
            throw new ServiceException(422, "合作模式必须为独立研发/合作研发/委托研发三选一");
        }
        PmProject project = projectDao.selectById(projectId);
        if (project == null || "1".equals(project.getDelFlag())) {
            throw new ServiceException(422, "项目不存在或已删除");
        }

        BigDecimal companyRate = dec(payload.get("companyRate"));
        String partnerName = str(payload.get("partnerName"));
        BigDecimal partnerRate = dec(payload.get("partnerRate"));
        String entrusteeName = str(payload.get("entrusteeName"));
        LocalDate signDate = date(payload.get("signDate"));

        if (MODE_COOP.equals(coopMode)) {
            // BR-4.8-25：合作研发比例须明确且合计=100%
            if (companyRate == null || partnerRate == null) {
                throw new ServiceException(422, "合作研发须明确公司与合作方权属比例（不可模糊表述）");
            }
            BigDecimal sum = companyRate.add(partnerRate);
            if (sum.compareTo(BigDecimal.valueOf(100)) != 0) {
                throw new ServiceException(422, "权属比例合计必须为 100%（当前 " + sum + "%）");
            }
            if (partnerName == null || partnerName.isBlank()) {
                throw new ServiceException(422, "合作研发须填写合作方名称");
            }
        } else if (MODE_INDEP.equals(coopMode)) {
            companyRate = BigDecimal.valueOf(100);
            partnerRate = null;
        } else { // ENTRUST
            if (entrusteeName == null || entrusteeName.isBlank()) {
                throw new ServiceException(422, "委托研发须填写委托方名称");
            }
            if (companyRate == null) {
                companyRate = BigDecimal.ZERO;
            }
        }
        if (signDate == null) {
            signDate = LocalDate.now();
        }

        // 后签覆盖前签：旧协议置 EFFECTIVE=0 留痕
        List<PmIpAgreement> olds = agreementDao.selectList(new LambdaQueryWrapper<PmIpAgreement>()
                .eq(PmIpAgreement::getProjectId, projectId)
                .eq(PmIpAgreement::getEffective, "1")
                .eq(PmIpAgreement::getDelFlag, "0"));
        String me = SecurityUtils.getCurrentUserId();
        for (PmIpAgreement old : olds) {
            old.setEffective("0");
            old.setUpdateBy(me);
            old.setUpdateDate(LocalDateTime.now());
            agreementDao.updateById(old);
        }

        PmIpAgreement a = new PmIpAgreement();
        a.setId(uuid());
        a.setProjectId(projectId);
        a.setCoopMode(coopMode);
        a.setCompanyRate(companyRate);
        a.setPartnerName(partnerName);
        a.setPartnerRate(partnerRate);
        a.setEntrusteeName(entrusteeName);
        a.setSignDate(signDate);
        a.setEffective("1");
        a.setRemark(str(payload.get("remark")));
        a.setCreateBy(me);
        a.setCreateDate(LocalDateTime.now());
        a.setUpdateBy(me);
        a.setUpdateDate(LocalDateTime.now());
        agreementDao.insert(a);
        return toMap(a);
    }

    @Override
    public Map<String, Object> effectiveOf(String projectId) {
        PmIpAgreement a = agreementDao.selectOne(new LambdaQueryWrapper<PmIpAgreement>()
                .eq(PmIpAgreement::getProjectId, projectId)
                .eq(PmIpAgreement::getEffective, "1")
                .eq(PmIpAgreement::getDelFlag, "0")
                .last("LIMIT 1"));
        return a == null ? null : toMap(a);
    }

    @Override
    public Map<String, Object> list(Map<String, Object> query) {
        LambdaQueryWrapper<PmIpAgreement> w = new LambdaQueryWrapper<PmIpAgreement>()
                .eq(PmIpAgreement::getDelFlag, "0")
                .orderByDesc(PmIpAgreement::getCreateDate);
        String projectId = str(query.get("projectId"));
        if (projectId != null && !projectId.isBlank()) {
            w.eq(PmIpAgreement::getProjectId, projectId);
        }
        List<PmIpAgreement> rows = agreementDao.selectList(w);
        List<Map<String, Object>> items = new ArrayList<>();
        for (PmIpAgreement a : rows) {
            items.add(toMap(a));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }

    // ================================================================
    // 共用工具（判定引擎/立项卡控经 effectiveOf 复用）
    // ================================================================

    private void requireLegal() {
        if (!hasAnyRole("LEGAL", "ADMIN")) {
            throw new ServiceException(403, "仅法务或管理员可维护 IP 归属协议");
        }
    }

    private boolean hasAnyRole(String... wanted) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        for (String w : wanted) {
            for (String r : roles) {
                if (("ROLE_" + w).equalsIgnoreCase(r)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, Object> toMap(PmIpAgreement a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("projectId", a.getProjectId());
        m.put("coopMode", a.getCoopMode());
        m.put("companyRate", a.getCompanyRate());
        m.put("partnerName", a.getPartnerName());
        m.put("partnerRate", a.getPartnerRate());
        m.put("entrusteeName", a.getEntrusteeName());
        m.put("signDate", a.getSignDate() == null ? null : a.getSignDate().toString());
        m.put("effective", a.getEffective());
        m.put("remark", a.getRemark());
        return m;
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

    private static LocalDate date(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        return LocalDate.parse(String.valueOf(o));
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
