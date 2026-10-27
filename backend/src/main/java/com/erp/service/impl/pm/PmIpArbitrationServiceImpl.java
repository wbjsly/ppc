package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmAchievementDao;
import com.erp.dao.pm.PmIpArbitrationDao;
import com.erp.dao.pm.PmIpClaimDao;
import com.erp.entity.pm.PmAchievement;
import com.erp.entity.pm.PmIpArbitration;
import com.erp.entity.pm.PmIpClaim;
import com.erp.service.pm.PmIpArbitrationService;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * IP 权属冲突仲裁实现（add-ip-management，design D4 仲裁状态机）。
 * 裁决回写成果 OWNERSHIP_RESULT 覆盖原判定 + 成果回 PENDING_CONFIRM 重确认。
 * 意见不被接受 → ESCALATED_EXTERNAL（终态，人工跟进）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmIpArbitrationServiceImpl implements PmIpArbitrationService {

    private final PmIpArbitrationDao arbitrationDao;
    private final PmIpClaimDao claimDao;
    private final PmAchievementDao achievementDao;
    private final NoticeService noticeService;
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public Map<String, Object> createFromObjection(PmAchievement achievement) {
        // 防重：该成果已有在途仲裁单
        requireNoPending(achievement.getId());
        String me = SecurityUtils.getCurrentUserId();
        PmIpArbitration arb = newArb(achievement.getId(), PmIpArbitration.SRC_CONFIRM_OBJECTION, me);
        arbitrationDao.insert(arb);
        noticeService.push("ROLE_LEGAL", null,
                "IP 权属冲突仲裁待处理：" + achievement.getName(),
                "成果 " + achievement.getAchieveNo() + " 研发负责人提多方权属主张异议，请法务组织仲裁",
                "PmIpArbitration", arb.getId());
        return toMap(arb);
    }

    @Override
    @Transactional
    public Map<String, Object> createManual(Map<String, Object> payload) {
        requireRole("LEGAL", "ADMIN");
        String achieveId = str(payload.get("achieveId"));
        PmAchievement ach = requireAchievement(achieveId);
        requireNoPending(achieveId);
        String me = SecurityUtils.getCurrentUserId();
        PmIpArbitration arb = newArb(achieveId, PmIpArbitration.SRC_MANUAL, me);
        arbitrationDao.insert(arb);
        // 标记成果仲裁在途
        ach.setArbitratingFlag("1");
        ach.setUpdateBy(me);
        ach.setUpdateDate(LocalDateTime.now());
        achievementDao.updateById(ach);
        return toMap(arb);
    }

    @Override
    @Transactional
    public Map<String, Object> addClaim(String arbId, Map<String, Object> payload) {
        PmIpArbitration arb = requireArb(arbId);
        if (!PmIpArbitration.ST_CLAIMS_SUBMITTED.equals(arb.getStatus())
                && !PmIpArbitration.ST_LEGAL_REVIEWING.equals(arb.getStatus())) {
            throw new ServiceException(422, "仅主张提交/仲裁中阶段可补充主张（当前 " + arb.getStatus() + ")");
        }
        String claimant = str(payload.get("claimant"));
        if (!PmIpClaim.PARTY_A.equals(claimant) && !PmIpClaim.PARTY_B.equals(claimant)) {
            throw new ServiceException(422, "主张方必须为 PARTY_A 或 PARTY_B");
        }
        String me = SecurityUtils.getCurrentUserId();
        PmIpClaim claim = new PmIpClaim();
        claim.setId(uuid());
        claim.setArbId(arbId);
        claim.setClaimant(claimant);
        claim.setClaimantName(str(payload.get("claimantName")));
        claim.setClaimRate(dec(payload.get("claimRate")));
        claim.setEvidence(str(payload.get("evidence")));
        claim.setRemark(str(payload.get("remark")));
        claim.setCreateBy(me);
        claim.setCreateDate(LocalDateTime.now());
        claimDao.insert(claim);
        // 首条主张提交 → 进入法务仲裁中
        if (PmIpArbitration.ST_CLAIMS_SUBMITTED.equals(arb.getStatus())) {
            arb.setStatus(PmIpArbitration.ST_LEGAL_REVIEWING);
            arb.setUpdateBy(me);
            arb.setUpdateDate(LocalDateTime.now());
            arbitrationDao.updateById(arb);
        }
        Map<String, Object> out = toMap(arb);
        out.put("claimId", claim.getId());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> issueOpinion(String arbId, Map<String, Object> payload) {
        PmIpArbitration arb = requireArb(arbId);
        if (!PmIpArbitration.ST_LEGAL_REVIEWING.equals(arb.getStatus())
                && !PmIpArbitration.ST_CLAIMS_SUBMITTED.equals(arb.getStatus())) {
            throw new ServiceException(422, "仅仲裁中阶段可出具意见（当前 " + arb.getStatus() + ")");
        }
        requireRole("LEGAL", "ADMIN");
        String opinion = str(payload.get("opinion"));
        if (opinion == null || opinion.isBlank()) {
            throw new ServiceException(422, "仲裁意见必填");
        }
        String me = SecurityUtils.getCurrentUserId();
        arb.setOpinion(opinion);
        arb.setOpinionBy(me);
        arb.setOpinionDate(LocalDateTime.now());
        arb.setStatus(PmIpArbitration.ST_OPINION_ISSUED);
        arb.setUpdateBy(me);
        arb.setUpdateDate(LocalDateTime.now());
        arbitrationDao.updateById(arb);
        noticeService.push("ROLE_RD_LEAD", null,
                "IP 仲裁意见待确认：" + arb.getArbNo(),
                "法务已出具仲裁意见，请研发负责人代公司确认（接受/拒绝）",
                "PmIpArbitration", arb.getId());
        return toMap(arb);
    }

    @Override
    @Transactional
    public Map<String, Object> confirmOpinion(String arbId, Map<String, Object> payload) {
        PmIpArbitration arb = requireArb(arbId);
        if (!PmIpArbitration.ST_OPINION_ISSUED.equals(arb.getStatus())) {
            throw new ServiceException(422, "仅意见出具后可确认（当前 " + arb.getStatus() + ")");
        }
        requireRole("RD_LEAD", "ADMIN");
        String action = str(payload.get("action"));
        String me = SecurityUtils.getCurrentUserId();
        if ("REJECT".equalsIgnoreCase(action)) {
            // 意见不被接受 → 升级外部（终态，人工跟进）
            arb.setStatus(PmIpArbitration.ST_ESCALATED_EXTERNAL);
            arb.setEscalateReason(str(payload.get("reason")));
        } else {
            arb.setStatus(PmIpArbitration.ST_PARTY_CONFIRMED);
            arb.setConfirmBy(me);
            arb.setConfirmDate(LocalDateTime.now());
        }
        arb.setUpdateBy(me);
        arb.setUpdateDate(LocalDateTime.now());
        arbitrationDao.updateById(arb);
        return toMap(arb);
    }

    @Override
    @Transactional
    public Map<String, Object> adjudicate(String arbId, Map<String, Object> payload) {
        PmIpArbitration arb = requireArb(arbId);
        if (!PmIpArbitration.ST_PARTY_CONFIRMED.equals(arb.getStatus())) {
            throw new ServiceException(422, "仅双方确认后可裁决回写（当前 " + arb.getStatus() + ")");
        }
        requireRole("LEGAL", "ADMIN");
        String result = str(payload.get("adjudicateResult"));
        if (result == null || result.isBlank()) {
            throw new ServiceException(422, "裁决权属结果必填");
        }
        String me = SecurityUtils.getCurrentUserId();
        arb.setAdjudicateResult(result);
        arb.setStatus(PmIpArbitration.ST_ADJUDICATED);
        arb.setUpdateBy(me);
        arb.setUpdateDate(LocalDateTime.now());
        arbitrationDao.updateById(arb);
        // 回写成果：覆盖原判定 + 回待确认重确认 + 解除仲裁在途
        PmAchievement ach = requireAchievement(arb.getAchieveId());
        ach.setOwnershipResult(result);
        ach.setStatus(PmAchievement.ST_PENDING_CONFIRM);
        ach.setArbitratingFlag("0");
        ach.setUpdateBy(me);
        ach.setUpdateDate(LocalDateTime.now());
        achievementDao.updateById(ach);
        return toMap(arb);
    }

    @Override
    public Map<String, Object> list(Map<String, Object> query) {
        LambdaQueryWrapper<PmIpArbitration> w = new LambdaQueryWrapper<PmIpArbitration>()
                .eq(PmIpArbitration::getDelFlag, "0")
                .orderByDesc(PmIpArbitration::getCreateDate);
        String achieveId = str(query.get("achieveId"));
        String status = str(query.get("status"));
        if (achieveId != null && !achieveId.isBlank()) {
            w.eq(PmIpArbitration::getAchieveId, achieveId);
        }
        if (status != null && !status.isBlank()) {
            w.eq(PmIpArbitration::getStatus, status);
        }
        List<PmIpArbitration> rows = arbitrationDao.selectList(w);
        List<Map<String, Object>> items = new ArrayList<>();
        for (PmIpArbitration a : rows) {
            Map<String, Object> m = toMap(a);
            List<PmIpClaim> claims = claimDao.selectList(new LambdaQueryWrapper<PmIpClaim>()
                    .eq(PmIpClaim::getArbId, a.getId())
                    .eq(PmIpClaim::getDelFlag, "0"));
            List<Map<String, Object>> cs = new ArrayList<>();
            for (PmIpClaim c : claims) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("claimant", c.getClaimant());
                cm.put("claimantName", c.getClaimantName());
                cm.put("claimRate", c.getClaimRate());
                cm.put("evidence", c.getEvidence());
                cs.add(cm);
            }
            m.put("claims", cs);
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }

    // ================================================================
    // 工具
    // ================================================================

    private PmIpArbitration newArb(String achieveId, String source, String me) {
        PmIpArbitration arb = new PmIpArbitration();
        arb.setId(uuid());
        arb.setArbNo(nextNo());
        arb.setAchieveId(achieveId);
        arb.setSource(source);
        arb.setStatus(PmIpArbitration.ST_CLAIMS_SUBMITTED);
        arb.setCreateBy(me);
        arb.setCreateDate(LocalDateTime.now());
        arb.setUpdateBy(me);
        arb.setUpdateDate(LocalDateTime.now());
        return arb;
    }

    /** 在途防重：同一成果仅一个未终态仲裁单（409） */
    private void requireNoPending(String achieveId) {
        Long pending = arbitrationDao.selectCount(new LambdaQueryWrapper<PmIpArbitration>()
                .eq(PmIpArbitration::getAchieveId, achieveId)
                .eq(PmIpArbitration::getDelFlag, "0")
                .notIn(PmIpArbitration::getStatus,
                        PmIpArbitration.ST_ADJUDICATED, PmIpArbitration.ST_ESCALATED_EXTERNAL));
        if (pending != null && pending > 0) {
            throw new ServiceException(409, "该成果已存在在途仲裁单");
        }
    }

    private void requireRole(String... wanted) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(403, "未认证");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(r -> roles.add(r.getAuthority()));
        for (String w : wanted) {
            for (String r : roles) {
                if (("ROLE_" + w).equalsIgnoreCase(r)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "当前用户无权执行该操作");
    }

    private PmIpArbitration requireArb(String id) {
        PmIpArbitration a = arbitrationDao.selectById(id);
        if (a == null || "1".equals(a.getDelFlag())) {
            throw new ServiceException(404, "仲裁单不存在");
        }
        return a;
    }

    private PmAchievement requireAchievement(String id) {
        PmAchievement a = achievementDao.selectById(id);
        if (a == null || "1".equals(a.getDelFlag())) {
            throw new ServiceException(404, "成果不存在");
        }
        return a;
    }

    private String nextNo() {
        String prefix = "IAR" + LocalDate.now().toString().replace("-", "");
        Integer seq = jdbc.queryForObject(
                "SELECT COUNT(*) + 1 FROM erp_pm_ip_arbitration WHERE ARB_NO LIKE ?",
                Integer.class, prefix + "%");
        return prefix + "-" + String.format("%03d", seq == null ? 1 : seq);
    }

    private Map<String, Object> toMap(PmIpArbitration a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("arbNo", a.getArbNo());
        m.put("achieveId", a.getAchieveId());
        m.put("source", a.getSource());
        m.put("status", a.getStatus());
        m.put("opinion", a.getOpinion());
        m.put("opinionBy", a.getOpinionBy());
        m.put("adjudicateResult", a.getAdjudicateResult());
        m.put("escalateReason", a.getEscalateReason());
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
            return null;
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
