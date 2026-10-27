package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmAchievementDao;
import com.erp.dao.pm.PmAchievementMemberDao;
import com.erp.dao.pm.PmIpAgreementDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.entity.pm.PmAchievement;
import com.erp.entity.pm.PmAchievementMember;
import com.erp.entity.pm.PmIpAgreement;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmWbs;
import com.erp.service.pm.PmIpArbitrationService;
import com.erp.service.pm.PmAchievementService;
import com.erp.service.pm.PmIpAssetService;
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
 * 研发成果登记 + 权属判定 + 确认状态机实现（add-ip-management，design D2/D3/D4）。
 * 判定引擎四路径：协议 INDEP/COOP/ENTRUST + 独立缺协议例外（L4 放行判公司 100%）。
 * CONFIRMED 后禁改禁删（BR-4.8-26）；确认同事务自动建档。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmAchievementServiceImpl implements PmAchievementService {

    private final PmAchievementDao achievementDao;
    private final PmAchievementMemberDao memberDao;
    private final PmIpAgreementDao agreementDao;
    private final PmProjectDao projectDao;
    private final PmWbsDao wbsDao;
    private final PmIpAssetService assetService;
    private final PmIpArbitrationService arbitrationService;
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public Map<String, Object> save(Map<String, Object> payload) {
        String id = str(payload.get("id"));
        boolean isNew = id == null || id.isBlank();
        PmAchievement ach;
        if (isNew) {
            ach = new PmAchievement();
            ach.setId(uuid());
            ach.setAchieveNo(nextNo());
            ach.setStatus(PmAchievement.ST_DRAFT);
            ach.setArbitratingFlag("0");
        } else {
            ach = requireAchievement(id);
            // BR-4.8-26：已确认成果禁改
            requireNotConfirmed(ach, "修改");
        }

        String projectId = str(payload.get("projectId"));
        String wbsId = str(payload.get("wbsId"));
        String type = str(payload.get("type"));
        String name = str(payload.get("name"));
        if (projectId == null || projectId.isBlank()) {
            throw new ServiceException(422, "成果必须关联项目");
        }
        // C-4.8-07：未关联 WBS 任务级节点 → L1 硬阻断
        if (wbsId == null || wbsId.isBlank()) {
            throw new ServiceException(422, "研发成果必须关联 WBS 任务（C-4.8-07 硬阻断）");
        }
        PmWbs wbs = wbsDao.selectById(wbsId);
        if (wbs == null || "1".equals(wbs.getDelFlag())
                || (wbs.getLevel() != null && wbs.getLevel() != PmWbs.LV_TASK)) {
            throw new ServiceException(422, "关联 WBS 必须为任务级节点");
        }
        if (!type.equals(PmAchievement.TYPE_PATENT) && !type.equals(PmAchievement.TYPE_SOFT_CP)
                && !type.equals(PmAchievement.TYPE_TRADE_SECRET) && !type.equals(PmAchievement.TYPE_TECH_DOC)) {
            throw new ServiceException(422, "成果类型必须为专利/软著/技术秘密/技术文档四选一");
        }
        if (name == null || name.isBlank()) {
            throw new ServiceException(422, "成果名称必填");
        }
        // 同任务同类型防重（服务层，409）
        Long dup = achievementDao.selectCount(new LambdaQueryWrapper<PmAchievement>()
                .eq(PmAchievement::getWbsId, wbsId)
                .eq(PmAchievement::getType, type)
                .eq(PmAchievement::getDelFlag, "0")
                .ne(isNew ? PmAchievement::getId : PmAchievement::getId, isNew ? "" : ach.getId()));
        if (dup != null && dup > 0) {
            throw new ServiceException(409, "该 WBS 任务已存在同类型成果");
        }
        // 描述 <20 字 L4 提示（不阻断，返回体带 l4Warning）
        String description = str(payload.get("description"));

        ach.setProjectId(projectId);
        ach.setWbsId(wbsId);
        ach.setType(type);
        ach.setName(name);
        ach.setDescription(description);
        LocalDate completeDate = date(payload.get("completeDate"));
        ach.setCompleteDate(completeDate);

        // 判定引擎（D2 四路径）
        Map<String, Object> judge = judge(projectId, type);
        if (judge.get("_blocked") != null) {
            throw new ServiceException(422, String.valueOf(judge.get("_blocked")));
        }
        ach.setOwnershipResult(str(judge.get("ownershipJson")));

        String me = SecurityUtils.getCurrentUserId();
        ach.setUpdateBy(me);
        ach.setUpdateDate(LocalDateTime.now());
        if (isNew) {
            ach.setCreateBy(me);
            ach.setCreateDate(LocalDateTime.now());
            achievementDao.insert(ach);
        } else {
            achievementDao.updateById(ach);
        }

        // 参与人员快照（一次性覆盖写）
        memberDao.delete(new LambdaQueryWrapper<PmAchievementMember>()
                .eq(PmAchievementMember::getAchieveId, ach.getId()));
        Object membersRaw = payload.get("members");
        if (membersRaw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                PmAchievementMember mem = new PmAchievementMember();
                mem.setId(uuid());
                mem.setAchieveId(ach.getId());
                mem.setEmpId(str(m.get("empId")));
                mem.setEmpName(str(m.get("empName")));
                mem.setHours(dec(m.get("hours")));
                mem.setCreateBy(me);
                mem.setCreateDate(LocalDateTime.now());
                memberDao.insert(mem);
            }
        }

        // 提交确认：DRAFT → PENDING_CONFIRM
        String action = str(payload.get("action"));
        if ("SUBMIT".equalsIgnoreCase(action)) {
            ach.setStatus(PmAchievement.ST_PENDING_CONFIRM);
            achievementDao.updateById(ach);
        }

        Map<String, Object> out = toMap(ach);
        out.put("l4Warning", judge.get("l4Warning"));
        if (description != null && description.length() < 20) {
            out.put("l4Warning", "成果描述过短（<20 字），建议补充技术细节");
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> extractMembers(String wbsId) {
        // design D3：工时归集聚合（状态 >= APPROVED 口径）
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ts.EMP_ID AS empId, COALESCE(ts.EMP_NAME,'') AS empName, "
                        + "SUM(l.HOURS) AS hours "
                        + "FROM erp_pm_timesheet_line l "
                        + "JOIN erp_pm_timesheet ts ON ts.ID = l.TIMESHEET_ID AND ts.DEL_FLAG='0' "
                        + "WHERE l.WBS_ID = ? AND l.DEL_FLAG='0' "
                        + "AND ts.STATUS IN ('APPROVED','COLLECTED') "
                        + "GROUP BY ts.EMP_ID, ts.EMP_NAME ORDER BY hours DESC", wbsId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("empId", r.get("empId"));
            m.put("empName", r.get("empName"));
            m.put("hours", r.get("hours"));
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> confirm(String id, Map<String, Object> payload) {
        PmAchievement ach = requireAchievement(id);
        if (!PmAchievement.ST_PENDING_CONFIRM.equals(ach.getStatus())
                && !PmAchievement.ST_LEGAL_REVIEW.equals(ach.getStatus())) {
            throw new ServiceException(422, "仅待确认/法务复核态成果可确认（当前 " + ach.getStatus() + ")");
        }
        requireRole("RD_LEAD", "ADMIN");
        String me = SecurityUtils.getCurrentUserId();
        ach.setStatus(PmAchievement.ST_CONFIRMED);
        ach.setConfirmBy(me);
        ach.setConfirmDate(LocalDateTime.now());
        ach.setUpdateBy(me);
        ach.setUpdateDate(LocalDateTime.now());
        achievementDao.updateById(ach);
        // 同事务自动建档（FR-4.8-6-5）
        assetService.generateFromAchievement(ach);
        return toMap(ach);
    }

    @Override
    @Transactional
    public Map<String, Object> object(String id, Map<String, Object> payload) {
        PmAchievement ach = requireAchievement(id);
        if (!PmAchievement.ST_PENDING_CONFIRM.equals(ach.getStatus())) {
            throw new ServiceException(422, "仅待确认成果可提异议（当前 " + ach.getStatus() + ")");
        }
        requireRole("RD_LEAD", "ADMIN");
        String objectionType = str(payload.get("objectionType"));
        String reason = str(payload.get("reason"));
        if (reason == null || reason.isBlank()) {
            throw new ServiceException(422, "异议说明必填");
        }
        String me = SecurityUtils.getCurrentUserId();
        ach.setStatus(PmAchievement.ST_LEGAL_REVIEW);
        ach.setObjectionType(objectionType);
        ach.setRejectReason(reason);
        ach.setUpdateBy(me);
        ach.setUpdateDate(LocalDateTime.now());
        // 多方主张 → 自动创建仲裁单（主入口，C-4.8-04）
        if (PmAchievement.OBJ_MULTI.equals(objectionType)) {
            ach.setArbitratingFlag("1");
            achievementDao.updateById(ach);
            Map<String, Object> arb = arbitrationService.createFromObjection(ach);
            Map<String, Object> out = toMap(ach);
            out.put("arbitration", arb);
            return out;
        }
        achievementDao.updateById(ach);
        return toMap(ach);
    }

    @Override
    @Transactional
    public Map<String, Object> legalReview(String id, Map<String, Object> payload) {
        PmAchievement ach = requireAchievement(id);
        if (!PmAchievement.ST_LEGAL_REVIEW.equals(ach.getStatus())) {
            throw new ServiceException(422, "非法务复核态成果（当前 " + ach.getStatus() + ")");
        }
        requireRole("LEGAL", "ADMIN");
        String action = str(payload.get("action"));
        String me = SecurityUtils.getCurrentUserId();
        if ("OVERRIDE".equalsIgnoreCase(action)) {
            // 改判权属（回写判定结果）后回待确认
            String ownershipJson = str(payload.get("ownershipResult"));
            if (ownershipJson == null || ownershipJson.isBlank()) {
                throw new ServiceException(422, "改判须提供权属结果");
            }
            ach.setOwnershipResult(ownershipJson);
            ach.setStatus(PmAchievement.ST_PENDING_CONFIRM);
        } else {
            // 复核无异议 → 回待确认
            ach.setStatus(PmAchievement.ST_PENDING_CONFIRM);
        }
        ach.setUpdateBy(me);
        ach.setUpdateDate(LocalDateTime.now());
        achievementDao.updateById(ach);
        return toMap(ach);
    }

    @Override
    public Map<String, Object> list(Map<String, Object> query) {
        LambdaQueryWrapper<PmAchievement> w = new LambdaQueryWrapper<PmAchievement>()
                .eq(PmAchievement::getDelFlag, "0")
                .orderByDesc(PmAchievement::getCreateDate);
        String projectId = str(query.get("projectId"));
        String status = str(query.get("status"));
        if (projectId != null && !projectId.isBlank()) {
            w.eq(PmAchievement::getProjectId, projectId);
        }
        if (status != null && !status.isBlank()) {
            w.eq(PmAchievement::getStatus, status);
        }
        List<PmAchievement> rows = achievementDao.selectList(w);
        List<Map<String, Object>> items = new ArrayList<>();
        for (PmAchievement a : rows) {
            items.add(toMap(a));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        PmAchievement ach = requireAchievement(id);
        Map<String, Object> out = toMap(ach);
        List<PmAchievementMember> members = memberDao.selectList(new LambdaQueryWrapper<PmAchievementMember>()
                .eq(PmAchievementMember::getAchieveId, id)
                .eq(PmAchievementMember::getDelFlag, "0"));
        List<Map<String, Object>> mems = new ArrayList<>();
        for (PmAchievementMember m : members) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("empId", m.getEmpId());
            mm.put("empName", m.getEmpName());
            mm.put("hours", m.getHours());
            mems.add(mm);
        }
        out.put("members", mems);
        return out;
    }

    // ================================================================
    // 判定引擎（design D2）
    // ================================================================

    /** 判定引擎：协议三型 + 独立缺协议例外。返回 {ownershipJson, l4Warning?, _blocked?} */
    private Map<String, Object> judge(String projectId, String type) {
        Map<String, Object> out = new LinkedHashMap<>();
        PmIpAgreement a = agreementDao.selectOne(new LambdaQueryWrapper<PmIpAgreement>()
                .eq(PmIpAgreement::getProjectId, projectId)
                .eq(PmIpAgreement::getEffective, "1")
                .eq(PmIpAgreement::getDelFlag, "0")
                .last("LIMIT 1"));
        PmProject project = projectDao.selectById(projectId);
        if (a == null) {
            // 协议缺失：独立研发 L4 放行判公司 100%；合作/委托 L1 阻断
            boolean indep = project != null && "研发".equals(project.getProjType())
                    && (project.getContractId() == null || project.getContractId().isBlank());
            if (indep) {
                out.put("ownershipJson", "{\"company\":100}");
                out.put("l4Warning", "IP 归属协议缺失，建议法务补签（当前按独立研发判定公司 100%）");
                return out;
            }
            out.put("_blocked", "IP 归属协议缺失，暂停成果登记，请法务补充协议（FR-4.8-6-2 / C-4.8-08）");
            return out;
        }
        switch (a.getCoopMode()) {
            case "COOP" -> out.put("ownershipJson",
                    "{\"company\":" + a.getCompanyRate().stripTrailingZeros().toPlainString()
                            + ",\"partner\":" + a.getPartnerRate().stripTrailingZeros().toPlainString()
                            + ",\"partnerName\":\"" + esc(a.getPartnerName()) + "\"}");
            case "ENTRUST" -> out.put("ownershipJson",
                    "{\"entrustee\":\"" + esc(a.getEntrusteeName()) + "\"}");
            default -> out.put("ownershipJson", "{\"company\":100}");
        }
        return out;
    }

    // ================================================================
    // 工具
    // ================================================================

    private void requireNotConfirmed(PmAchievement ach, String action) {
        if (PmAchievement.ST_CONFIRMED.equals(ach.getStatus())) {
            throw new ServiceException(422, "已确认成果永久保留，不可" + action + "（BR-4.8-26）");
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

    private PmAchievement requireAchievement(String id) {
        PmAchievement a = achievementDao.selectById(id);
        if (a == null || "1".equals(a.getDelFlag())) {
            throw new ServiceException(404, "成果不存在");
        }
        return a;
    }

    private String nextNo() {
        String prefix = "IPR" + LocalDate.now().toString().replace("-", "");
        Integer seq = jdbc.queryForObject(
                "SELECT COUNT(*) + 1 FROM erp_pm_achievement WHERE ACHIEVE_NO LIKE ?",
                Integer.class, prefix + "%");
        return prefix + "-" + String.format("%03d", seq == null ? 1 : seq);
    }

    private Map<String, Object> toMap(PmAchievement a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("achieveNo", a.getAchieveNo());
        m.put("projectId", a.getProjectId());
        m.put("wbsId", a.getWbsId());
        m.put("type", a.getType());
        m.put("name", a.getName());
        m.put("description", a.getDescription());
        m.put("completeDate", a.getCompleteDate() == null ? null : a.getCompleteDate().toString());
        m.put("ownershipResult", a.getOwnershipResult());
        m.put("status", a.getStatus());
        m.put("arbitratingFlag", a.getArbitratingFlag());
        m.put("confirmBy", a.getConfirmBy());
        m.put("confirmDate", a.getConfirmDate() == null ? null : a.getConfirmDate().toString());
        m.put("objectionType", a.getObjectionType());
        m.put("rejectReason", a.getRejectReason());
        return m;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
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
