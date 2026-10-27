package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.SoConfirmTokenDao;
import com.erp.dao.pm.SoImproveTaskDao;
import com.erp.dao.pm.SoOrderDao;
import com.erp.dao.pm.SoProcessRecordDao;
import com.erp.dao.pm.SoSlaGradeDao;
import com.erp.dao.pm.SoSlaLogDao;
import com.erp.dao.pm.SoSurveyDao;
import com.erp.entity.pm.SoConfirmToken;
import com.erp.entity.pm.SoImproveTask;
import com.erp.entity.pm.SoOrder;
import com.erp.entity.pm.SoProcessRecord;
import com.erp.entity.pm.SoSlaGrade;
import com.erp.entity.pm.SoSlaLog;
import com.erp.entity.pm.SoSurvey;
import com.erp.service.pm.PmServiceOrderService;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务工单实现（add-service-order，design D1/D2/D3）。
 * - 定级：类型×影响映射（精确行优先于通配行），人工改理由必填 L5 留痕；
 * - 派单：负载最少推荐（在手 DISPATCHED/PROCESSING 计数），可改派；
 * - SLA：计时起点=登记时间（BR-4.8-03），挂起累计扣减，驳回重计时；
 * - NCR 卡控：质量类/制造·设计缺陷必关联（BR-4.8-36），只读查 qms；
 * - 确认双通道：AGENT 代录 / LINK token（一次性），默认关闭在定时任务；
 * - 处理记录仅追加（BR-4.8-35）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmServiceOrderServiceImpl implements PmServiceOrderService {

    /** 重复投诉查重窗口：前单未关闭或关闭 ≤30 天 */
    private static final int DUP_WINDOW_DAYS = 30;
    /** 客户确认期限（5 工作日 ≈ 7 自然日，token 有效期同口径） */
    private static final int CONFIRM_DAYS = 7;
    /** 改进任务期限 10 工作日 ≈ 14 自然日 */
    private static final int IMPROVE_DAYS = 14;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SoOrderDao orderDao;
    private final SoProcessRecordDao recordDao;
    private final SoConfirmTokenDao tokenDao;
    private final SoSlaGradeDao gradeDao;
    private final SoSlaLogDao slaLogDao;
    private final SoSurveyDao surveyDao;
    private final SoImproveTaskDao improveDao;
    private final NoticeService noticeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ================================================================
    // 登记 / 列表 / 详情
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> register(Map<String, Object> payload) {
        String customerId = str(payload.get("customerId"));
        if (!hasText(customerId)) {
            throw new ServiceException(422, "客户必填（客户主数据不存在请先建立客户档案）");
        }
        requireCustomer(customerId);
        String description = str(payload.get("description"));
        if (!hasText(description)) {
            throw new ServiceException(422, "投诉描述必填");
        }
        String complaintType = str(payload.get("complaintType"));
        if (!isKnownType(complaintType)) {
            throw new ServiceException(422, "投诉类型非法（产品质量/交付延迟/服务态度/功能缺陷/其他）");
        }
        String impact = str(payload.get("impactLevel"));
        if (!SoOrder.IMPACT_PRODUCTION.equals(impact) && !SoOrder.IMPACT_CORE.equals(impact)
                && !SoOrder.IMPACT_NORMAL.equals(impact)) {
            throw new ServiceException(422, "影响程度非法（PRODUCTION/CORE/NORMAL）");
        }
        String me = SecurityUtils.getCurrentUserId();

        SoOrder o = new SoOrder();
        o.setId(uuid());
        o.setOrderNo(nextOrderNo());
        o.setCustomerId(customerId);
        o.setCustomerName(str(payload.get("customerName")));
        o.setContractNo(str(payload.get("contractNo")));
        o.setProjectId(str(payload.get("projectId")));
        o.setComplaintType(complaintType);
        o.setImpactLevel(impact);
        o.setDescription(description);
        o.setExpectDeadline(ts(payload.get("expectDeadline")));
        o.setAttachIds(str(payload.get("attachIds")));
        o.setSkillTag(str(payload.get("skillTag")));
        o.setStatus(SoOrder.ST_REGISTERED);
        o.setSuspendedFlag("0");
        o.setSuspendHours(BigDecimal.ZERO);
        o.setEscalateStage(0);
        o.setRejectCount(0);
        o.setRootCured("0");
        o.setCloseDefaultFlag("0");
        o.setMaterialCost(BigDecimal.ZERO);

        // 自动定级（映射可配，FR-4.8-9-2）：响应/解决截止以登记时间为起点（BR-4.8-03）
        SoSlaGrade grade = resolveGrade(complaintType, impact);
        LocalDateTime now = LocalDateTime.now();
        o.setLevel(grade.getLevel());
        o.setRespDeadline(now.plusHours(grade.getRespHours()));
        o.setResolveDeadline(now.plusHours(grade.getResolveHours()));
        o.setCreateBy(me);
        o.setCreateDate(now);
        o.setUpdateBy(me);
        o.setUpdateDate(now);
        orderDao.insert(o);

        appendRecord(o.getId(), SoProcessRecord.ACT_PROCESS, me,
                "投诉登记，自动定级 L" + o.getLevel() + "（响应 " + grade.getRespHours()
                        + "h / 解决 " + grade.getResolveHours() + "h）", null);

        // 一级投诉自动通知销售经理 + 服务主管（FR-4.8-9-2 / C-4.8-11 前置通知）
        if (o.getLevel() != null && o.getLevel() == 1) {
            noticeService.push("ROLE_SALES_MGR", null,
                    "一级投诉工单：" + o.getOrderNo(),
                    "客户「" + nvl(o.getCustomerName()) + "」一级投诉已登记：" + description,
                    "PmServiceOrder", o.getId());
            noticeService.push("ROLE_SERVICE_MGR", null,
                    "一级投诉工单：" + o.getOrderNo(),
                    "一级投诉已登记，2 小时响应时限计时中，请关注派单",
                    "PmServiceOrderMgr", o.getId());
        }

        Map<String, Object> out = toMap(o);
        // 重复投诉候选（标注不合并，SLA 独立计时）
        out.put("duplicateCandidates", duplicateCandidates(o));
        return out;
    }

    @Override
    public Map<String, Object> page(Map<String, Object> query) {
        LambdaQueryWrapper<SoOrder> w = new LambdaQueryWrapper<SoOrder>()
                .eq(SoOrder::getDelFlag, "0")
                .orderByDesc(SoOrder::getCreateDate);
        eqIf(w, SoOrder::getCustomerId, str(query.get("customerId")));
        eqIf(w, SoOrder::getStatus, str(query.get("status")));
        eqIf(w, SoOrder::getEngineerId, str(query.get("engineerId")));
        String level = str(query.get("level"));
        if (hasText(level)) {
            w.eq(SoOrder::getLevel, Integer.valueOf(level));
        }
        String keyword = str(query.get("keyword"));
        if (hasText(keyword)) {
            String k = "%" + keyword.trim() + "%";
            w.and(x -> x.like(SoOrder::getOrderNo, k).or().like(SoOrder::getDescription, k)
                    .or().like(SoOrder::getCustomerName, k));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (SoOrder o : orderDao.selectList(w)) {
            items.add(toMap(o));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        SoOrder o = require(id);
        Map<String, Object> out = toMap(o);
        List<Map<String, Object>> records = new ArrayList<>();
        for (SoProcessRecord r : recordDao.selectList(new LambdaQueryWrapper<SoProcessRecord>()
                .eq(SoProcessRecord::getOrderId, id)
                .eq(SoProcessRecord::getDelFlag, "0")
                .orderByAsc(SoProcessRecord::getCreateDate))) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", r.getId());
            rm.put("action", r.getAction());
            rm.put("content", r.getContent());
            rm.put("attachIds", r.getAttachIds());
            rm.put("createBy", r.getCreateBy());
            rm.put("createDate", str(r.getCreateDate()));
            records.add(rm);
        }
        out.put("records", records);
        SoSurvey sv = surveyDao.selectOne(new LambdaQueryWrapper<SoSurvey>()
                .eq(SoSurvey::getOrderId, id)
                .eq(SoSurvey::getDelFlag, "0")
                .last("LIMIT 1"));
        out.put("survey", sv == null ? null : surveyMap(sv));
        out.put("confirmTokens", tokenDao.selectList(new LambdaQueryWrapper<SoConfirmToken>()
                .eq(SoConfirmToken::getOrderId, id)
                .eq(SoConfirmToken::getDelFlag, "0")
                .orderByDesc(SoConfirmToken::getCreateDate)));
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (SoImproveTask t : improveDao.selectList(new LambdaQueryWrapper<SoImproveTask>()
                .eq(SoImproveTask::getOrderId, id)
                .eq(SoImproveTask::getDelFlag, "0"))) {
            tasks.add(improveMap(t));
        }
        out.put("improveTasks", tasks);
        return out;
    }

    // ================================================================
    // 重复投诉标注 / 定级调整 / 派单
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> markDuplicate(String id, Map<String, Object> payload) {
        SoOrder o = require(id);
        String targetId = str(payload.get("duplicateOf"));
        if (!hasText(targetId)) {
            throw new ServiceException(422, "须指定被关联的前单");
        }
        SoOrder prev = require(targetId);
        if (prev.getId().equals(o.getId())) {
            throw new ServiceException(422, "不可关联自身");
        }
        o.setDuplicateOf(targetId);
        o.setUpdateBy(SecurityUtils.getCurrentUserId());
        o.setUpdateDate(LocalDateTime.now());
        orderDao.updateById(o);
        appendRecord(o.getId(), SoProcessRecord.ACT_PROCESS, SecurityUtils.getCurrentUserId(),
                "关联为重复投诉，前单 " + prev.getOrderNo() + "（SLA 独立计时）", null);
        return toMap(o);
    }

    @Override
    @Transactional
    public Map<String, Object> adjustLevel(String id, Map<String, Object> payload) {
        SoOrder o = require(id);
        String reason = str(payload.get("reason"));
        if (!hasText(reason)) {
            throw new ServiceException(422, "调整定级须填写理由（L5 审计留痕）");
        }
        Integer newLevel = intOf(payload.get("level"));
        if (newLevel == null || newLevel < 1 || newLevel > 3) {
            throw new ServiceException(422, "等级须为 1/2/3");
        }
        Integer before = o.getLevel();
        o.setLevel(newLevel);
        // 按新等级重算解决截止（响应时限已过不回拨，解决时限从登记时间口径重算）
        SoSlaGrade grade = gradeByLevel(newLevel);
        LocalDateTime base = o.getCreateDate();
        o.setRespDeadline(base.plusHours(grade.getRespHours()));
        o.setResolveDeadline(base.plusHours(grade.getResolveHours()));
        o.setUpdateBy(SecurityUtils.getCurrentUserId());
        o.setUpdateDate(LocalDateTime.now());
        orderDao.updateById(o);
        // L5 留痕：Before/After + 理由
        appendRecord(o.getId(), SoProcessRecord.ACT_PROCESS, SecurityUtils.getCurrentUserId(),
                "定级调整 L" + before + " → L" + newLevel + "，理由：" + reason, null);
        return toMap(o);
    }

    @Override
    @Transactional
    public Map<String, Object> dispatch(String id, Map<String, Object> payload) {
        SoOrder o = require(id);
        if (SoOrder.ST_CLOSED.equals(o.getStatus())) {
            throw new ServiceException(422, "已关闭工单不可派单");
        }
        requireRole("派单/改派", "ROLE_CS", "ROLE_SERVICE_MGR");
        String engineerId = str(payload.get("engineerId"));
        String me = SecurityUtils.getCurrentUserId();
        String before = o.getEngineerId();
        if (!hasText(engineerId)) {
            // 无可派人员 → 待指派并升级服务主管（FR-4.8-9-2 异常路径）
            o.setStatus(SoOrder.ST_DISPATCHED);
            o.setEngineerId(null);
            o.setEngineerName(null);
            noticeService.push("ROLE_SERVICE_MGR", null,
                    "工单待指派：" + o.getOrderNo(),
                    "无可自动派单的服务工程师，请人工指派（" + nvl(o.getCustomerName()) + "）",
                    "PmServiceOrderDispatch", o.getId());
        } else {
            o.setEngineerId(engineerId);
            o.setEngineerName(str(payload.get("engineerName")));
            o.setStatus(SoOrder.ST_DISPATCHED);
        }
        o.setUpdateBy(me);
        o.setUpdateDate(LocalDateTime.now());
        orderDao.updateById(o);
        appendRecord(o.getId(), SoProcessRecord.ACT_PROCESS, me,
                hasText(engineerId)
                        ? ("派单/改派至服务工程师 " + nvl(o.getEngineerName()) + "（原：" + nvl(before) + "）")
                        : "无可派人员，置待指派并升级服务主管", null);
        return toMap(o);
    }

    @Override
    public Map<String, Object> recommendEngineer() {
        List<Map<String, Object>> roster = jdbcTemplate.queryForList(
                "SELECT u.ID AS userId, u.USERNAME AS username, u.NICK_NAME AS nickName "
                        + "FROM erp_admin_user u "
                        + "JOIN erp_admin_user_role ur ON ur.USER_ID = u.ID "
                        + "JOIN erp_admin_role r ON r.ID = ur.ROLE_ID "
                        + "WHERE r.ROLE_CODE = 'ROLE_SERVICE_ENG' AND u.STATUS = '1'");
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Long> load = inHandLoad();
        for (Map<String, Object> u : roster) {
            Map<String, Object> row = new LinkedHashMap<>(u);
            String uid = str(u.get("userId"));
            row.put("inHandCount", load.getOrDefault(uid, 0L));
            items.add(row);
        }
        items.sort(java.util.Comparator.comparingLong(
                m -> ((Number) m.get("inHandCount")).longValue()));
        out.put("recommended", items.isEmpty() ? null : items.get(0));
        out.put("engineers", items);
        return out;
    }

    // ================================================================
    // 处理链路：受理 / 记录 / 根因 / NCR / 已根治 / 挂起恢复
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> accept(String id) {
        SoOrder o = require(id);
        requireRole("受理工单", "ROLE_SERVICE_ENG", "ROLE_SERVICE_MGR");
        if (o.getRespondedAt() == null) {
            o.setRespondedAt(LocalDateTime.now());
        }
        if (SoOrder.ST_DISPATCHED.equals(o.getStatus()) || SoOrder.ST_REGISTERED.equals(o.getStatus())) {
            o.setStatus(SoOrder.ST_PROCESSING);
            if (!hasText(o.getEngineerId())) {
                o.setEngineerId(SecurityUtils.getCurrentUserId());
            }
        }
        o.setUpdateBy(SecurityUtils.getCurrentUserId());
        o.setUpdateDate(LocalDateTime.now());
        orderDao.updateById(o);
        appendRecord(o.getId(), SoProcessRecord.ACT_ACCEPT, SecurityUtils.getCurrentUserId(),
                "工程师受理", null);
        return toMap(o);
    }

    @Override
    @Transactional
    public Map<String, Object> appendProcess(String id, Map<String, Object> payload) {
        SoOrder o = require(id);
        if (SoOrder.ST_CLOSED.equals(o.getStatus())) {
            throw new ServiceException(422, "已关闭工单不可追加处理记录");
        }
        String content = str(payload.get("content"));
        if (!hasText(content)) {
            throw new ServiceException(422, "处理内容必填");
        }
        appendRecord(o.getId(), SoProcessRecord.ACT_PROCESS, SecurityUtils.getCurrentUserId(),
                content, str(payload.get("attachIds")));
        o.setUpdateBy(SecurityUtils.getCurrentUserId());
        o.setUpdateDate(LocalDateTime.now());
        orderDao.updateById(o);
        return toMap(o);
    }

    @Override
    @Transactional
    public Map<String, Object> saveRootCause(String id, Map<String, Object> payload) {
        SoOrder o = require(id);
        String rc = str(payload.get("rootCause"));
        if (!SoOrder.RC_DESIGN.equals(rc) && !SoOrder.RC_MANUFACTURE.equals(rc)
                && !SoOrder.RC_OPERATION.equals(rc) && !SoOrder.RC_EXTERNAL.equals(rc)) {
            throw new ServiceException(422, "根因分类须为设计缺陷/制造缺陷/操作不当/外部原因");
        }
        o.setRootCause(rc);
        o.setUpdateBy(SecurityUtils.getCurrentUserId());
        o.setUpdateDate(LocalDateTime.now());
        orderDao.updateById(o);
        appendRecord(o.getId(), SoProcessRecord.ACT_ROOT_CAUSE, SecurityUtils.getCurrentUserId(),
                "根因分类：" + rootCauseName(rc), null);
        // 设计缺陷自动抄送研发负责人（FR-4.8-9-3）
        if (SoOrder.RC_DESIGN.equals(rc)) {
            noticeService.push("ROLE_RD_LEAD", null,
                    "设计缺陷根因工单：" + o.getOrderNo(),
                    "工单「" + o.getOrderNo() + "」根因判定为设计缺陷：" + o.getDescription(),
                    "PmServiceOrderDesign", o.getId());
        }
        return toMap(o);
    }

    @Override
    @Transactional
    public Map<String, Object> linkNcr(String id, Map<String, Object> payload) {
        SoOrder o = require(id);
        String ncrNo = str(payload.get("ncrNo"));
        if (hasText(ncrNo) && !ncrExists(ncrNo)) {
            throw new ServiceException(422, "NCR 单号不存在：" + ncrNo);
        }
        o.setNcrNo(ncrNo);
        o.setUpdateBy(SecurityUtils.getCurrentUserId());
        o.setUpdateDate(LocalDateTime.now());
        orderDao.updateById(o);
        appendRecord(o.getId(), SoProcessRecord.ACT_PROCESS, SecurityUtils.getCurrentUserId(),
                hasText(ncrNo) ? ("关联质量域 NCR：" + ncrNo) : "解除 NCR 关联", null);
        return toMap(o);
    }

    @Override
    @Transactional
    public Map<String, Object> markCured(String id) {
        SoOrder o = require(id);
        if (isQualityClass(o) && !hasText(o.getNcrNo())) {
            throw new ServiceException(422, "质量类投诉须关联 NCR 后方可标记已根治（BR-4.8-36）");
        }
        o.setRootCured("1");
        o.setUpdateBy(SecurityUtils.getCurrentUserId());
        o.setUpdateDate(LocalDateTime.now());
        orderDao.updateById(o);
        appendRecord(o.getId(), SoProcessRecord.ACT_PROCESS, SecurityUtils.getCurrentUserId(),
                "标记已根治", null);
        return toMap(o);
    }

    @Override
    @Transactional
    public Map<String, Object> suspend(String id, Map<String, Object> payload) {
        SoOrder o = require(id);
        if ("1".equals(o.getSuspendedFlag())) {
            throw new ServiceException(409, "工单已处于挂起状态");
        }
        String reason = str(payload.get("reason"));
        if (!hasText(reason)) {
            reason = "待客户信息";
        }
        o.setSuspendedFlag("1");
        o.setSuspendAt(LocalDateTime.now());
        o.setUpdateBy(SecurityUtils.getCurrentUserId());
        o.setUpdateDate(LocalDateTime.now());
        orderDao.updateById(o);
        appendRecord(o.getId(), SoProcessRecord.ACT_SUSPEND, SecurityUtils.getCurrentUserId(),
                "挂起（" + reason + "），SLA 计时暂停", null);
        writeSlaLog(o.getId(), SoSlaLog.EV_SUSPEND, null, null, reason);
        return toMap(o);
    }

    @Override
    @Transactional
    public Map<String, Object> resume(String id) {
        SoOrder o = require(id);
        if (!"1".equals(o.getSuspendedFlag())) {
            throw new ServiceException(422, "工单未处于挂起状态");
        }
        LocalDateTime suspendedAt = o.getSuspendAt();
        long seconds = suspendedAt == null ? 0
                : java.time.Duration.between(suspendedAt, LocalDateTime.now()).getSeconds();
        BigDecimal hours = BigDecimal.valueOf(seconds / 3600.0);
        o.setSuspendHours(nvlBig(o.getSuspendHours()).add(hours));
        // 顺延解决截止（挂起时长不计 SLA）
        if (o.getResolveDeadline() != null) {
            o.setResolveDeadline(o.getResolveDeadline().plusSeconds(seconds));
        }
        o.setSuspendedFlag("0");
        o.setSuspendAt(null);
        o.setUpdateBy(SecurityUtils.getCurrentUserId());
        o.setUpdateDate(LocalDateTime.now());
        orderDao.updateById(o);
        appendRecord(o.getId(), SoProcessRecord.ACT_RESUME, SecurityUtils.getCurrentUserId(),
                "恢复处理，累计挂起 " + hours.setScale(2, java.math.RoundingMode.HALF_UP) + " 小时不计入 SLA", null);
        writeSlaLog(o.getId(), SoSlaLog.EV_RESUME, null, null, "恢复处理");
        return toMap(o);
    }

    // ================================================================
    // 客户确认双通道 / 关闭
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> submitConfirm(String id) {
        SoOrder o = require(id);
        if (!hasText(o.getRootCause())) {
            throw new ServiceException(422, "请填写根因分类（根因未填不可提交关闭申请）");
        }
        if (!SoOrder.ST_PROCESSING.equals(o.getStatus()) && !SoOrder.ST_DISPATCHED.equals(o.getStatus())) {
            throw new ServiceException(422, "当前状态不可提交客户确认：" + o.getStatus());
        }
        o.setStatus(SoOrder.ST_PENDING_CONFIRM);
        o.setConfirmSubmitAt(LocalDateTime.now());
        o.setUpdateBy(SecurityUtils.getCurrentUserId());
        o.setUpdateDate(LocalDateTime.now());
        orderDao.updateById(o);
        appendRecord(o.getId(), SoProcessRecord.ACT_SUBMIT_CONFIRM, SecurityUtils.getCurrentUserId(),
                "提交客户确认（5 工作日未确认将提醒后默认关闭）", null);
        return toMap(o);
    }

    @Override
    @Transactional
    public Map<String, Object> agentConfirm(String id, Map<String, Object> payload) {
        SoOrder o = require(id);
        if (!SoOrder.ST_PENDING_CONFIRM.equals(o.getStatus())) {
            throw new ServiceException(422, "工单未处于待客户确认状态");
        }
        boolean resolved = "RESOLVED".equalsIgnoreCase(str(payload.get("result")));
        String note = str(payload.get("note"));
        appendRecord(o.getId(), SoProcessRecord.ACT_PROCESS, SecurityUtils.getCurrentUserId(),
                "客户确认代录：" + (resolved ? "已解决" : "驳回") + (hasText(note) ? "（" + note + "）" : ""), null);
        if (resolved) {
            closeOrder(o, SoOrder.VIA_AGENT, "RESOLVED", "0");
        } else {
            rejectAndReprocess(o, SoOrder.VIA_AGENT);
        }
        return toMap(o);
    }

    @Override
    @Transactional
    public Map<String, Object> generateConfirmLink(String id) {
        SoOrder o = require(id);
        SoConfirmToken t = new SoConfirmToken();
        t.setId(uuid());
        t.setOrderId(o.getId());
        t.setToken(randomToken());
        t.setExpireAt(LocalDateTime.now().plusDays(CONFIRM_DAYS));
        t.setUsed("0");
        t.setCreateBy(SecurityUtils.getCurrentUserId());
        t.setCreateDate(LocalDateTime.now());
        tokenDao.insert(t);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", t.getToken());
        out.put("expireAt", t.getExpireAt());
        out.put("url", "/service/confirm/" + t.getToken());
        return out;
    }

    @Override
    public Map<String, Object> tokenView(String token) {
        SoConfirmToken t = requireToken(token);
        SoOrder o = require(t.getOrderId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderNo", o.getOrderNo());
        out.put("customerName", o.getCustomerName());
        out.put("complaintType", o.getComplaintType());
        out.put("description", o.getDescription());
        out.put("status", o.getStatus());
        out.put("expireAt", t.getExpireAt());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> tokenConfirm(String token, Map<String, Object> payload) {
        SoConfirmToken t = requireToken(token);
        SoOrder o = require(t.getOrderId());
        if (!SoOrder.ST_PENDING_CONFIRM.equals(o.getStatus())) {
            throw new ServiceException(422, "工单当前状态不接受确认：" + o.getStatus());
        }
        boolean resolved = "RESOLVED".equalsIgnoreCase(str(payload.get("result")));
        t.setUsed("1");
        t.setUsedAt(LocalDateTime.now());
        tokenDao.updateById(t);
        appendRecord(o.getId(), SoProcessRecord.ACT_PROCESS, "CUSTOMER",
                "客户经确认链接操作：" + (resolved ? "已解决" : "驳回"), null);
        if (resolved) {
            closeOrder(o, SoOrder.VIA_LINK, "RESOLVED", "0");
        } else {
            rejectAndReprocess(o, SoOrder.VIA_LINK);
        }
        return toMap(o);
    }

    // ================================================================
    // 回访 / 改进任务
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> saveSurvey(String id, Map<String, Object> payload) {
        SoOrder o = require(id);
        if (!SoOrder.ST_CLOSED.equals(o.getStatus())) {
            throw new ServiceException(422, "仅已关闭工单可回访");
        }
        SoSurvey s = surveyDao.selectOne(new LambdaQueryWrapper<SoSurvey>()
                .eq(SoSurvey::getOrderId, id).eq(SoSurvey::getDelFlag, "0").last("LIMIT 1"));
        if (s != null) {
            throw new ServiceException(409, "该工单已完成回访");
        }
        boolean unreached = "1".equals(str(payload.get("unreached")));
        s = new SoSurvey();
        s.setId(uuid());
        s.setOrderId(id);
        s.setUnreachedFlag(unreached ? "1" : "0");
        s.setContactTries(intOf(payload.get("contactTries")) == null ? 1 : intOf(payload.get("contactTries")));
        if (!unreached) {
            Integer score = intOf(payload.get("score"));
            if (score == null || score < 1 || score > 5) {
                throw new ServiceException(422, "满意度评分须为 1-5 分");
            }
            s.setScore(score);
            s.setSuggestion(str(payload.get("suggestion")));
            s.setSurveyBy(SecurityUtils.getCurrentUserId());
            s.setSurveyDate(LocalDateTime.now());
        }
        s.setCreateBy(SecurityUtils.getCurrentUserId());
        s.setCreateDate(LocalDateTime.now());
        surveyDao.insert(s);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("survey", surveyMap(s));
        // ≤2 分自动生成改进任务（BR-4.8-06：10 工作日闭环，逾期升级销售经理）
        if (!unreached && s.getScore() != null && s.getScore() <= 2) {
            SoImproveTask task = new SoImproveTask();
            task.setId(uuid());
            task.setOrderId(id);
            task.setContent("低分（" + s.getScore() + "分）改进：" + nvl(s.getSuggestion()));
            task.setOwnerId(o.getEngineerId());
            task.setOwnerName(o.getEngineerName());
            task.setDueDate(LocalDateTime.now().plusDays(IMPROVE_DAYS));
            task.setStatus(SoImproveTask.ST_PENDING);
            task.setEscalatedSales("0");
            task.setCreateBy(SecurityUtils.getCurrentUserId());
            task.setCreateDate(LocalDateTime.now());
            improveDao.insert(task);
            out.put("improveTask", improveMap(task));
            noticeService.push("ROLE_SERVICE_MGR", null,
                    "低分工单改进任务：" + o.getOrderNo(),
                    "满意度 " + s.getScore() + " 分，改进任务已生成，请于 "
                            + task.getDueDate().toLocalDate() + " 前闭环",
                    "PmServiceImprove", task.getId());
        }
        return out;
    }

    @Override
    public Map<String, Object> surveyStats(Map<String, Object> query) {
        String from = str(query.get("from"));
        String to = str(query.get("to"));
        StringBuilder where = new StringBuilder(" WHERE o.DEL_FLAG = '0' AND o.STATUS = 'CLOSED' AND s.DEL_FLAG = '0' ");
        List<Object> args = new ArrayList<>();
        if (hasText(from)) {
            where.append(" AND o.CREATE_DATE >= ? ");
            args.add(from);
        }
        if (hasText(to)) {
            where.append(" AND o.CREATE_DATE <= ? ");
            args.add(to);
        }
        String base = "FROM erp_pm_service_order o "
                + "LEFT JOIN erp_pm_service_survey s ON s.ORDER_ID = o.ID " + where;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byCustomer", jdbcTemplate.queryForList(
                "SELECT o.CUSTOMER_ID AS customerId, o.CUSTOMER_NAME AS customerName, "
                        + "COUNT(*) AS orderCount, ROUND(AVG(s.SCORE),2) AS avgScore, "
                        + "SUM(CASE WHEN s.SCORE <= 2 THEN 1 ELSE 0 END) AS lowScoreCount, "
                        + "SUM(CASE WHEN o.DUPLICATE_OF IS NOT NULL THEN 1 ELSE 0 END) AS duplicateCount "
                        + base + " GROUP BY o.CUSTOMER_ID, o.CUSTOMER_NAME", args.toArray()));
        out.put("byEngineer", jdbcTemplate.queryForList(
                "SELECT o.ENGINEER_ID AS engineerId, o.ENGINEER_NAME AS engineerName, "
                        + "COUNT(*) AS orderCount, ROUND(AVG(s.SCORE),2) AS avgScore, "
                        + "SUM(CASE WHEN s.SCORE <= 2 THEN 1 ELSE 0 END) AS lowScoreCount "
                        + base + " GROUP BY o.ENGINEER_ID, o.ENGINEER_NAME", args.toArray()));
        // 产品线口径以投诉类型代理（无产品线主数据，spec 允许统计口径体现）
        out.put("byType", jdbcTemplate.queryForList(
                "SELECT o.COMPLAINT_TYPE AS complaintType, COUNT(*) AS orderCount, "
                        + "ROUND(AVG(s.SCORE),2) AS avgScore, "
                        + "SUM(CASE WHEN s.UNREACHED_FLAG = '1' THEN 1 ELSE 0 END) AS unreachedCount "
                        + base + " GROUP BY o.COMPLAINT_TYPE", args.toArray()));
        return out;
    }

    @Override
    public Map<String, Object> improveTasks(Map<String, Object> query) {
        LambdaQueryWrapper<SoImproveTask> w = new LambdaQueryWrapper<SoImproveTask>()
                .eq(SoImproveTask::getDelFlag, "0")
                .orderByAsc(SoImproveTask::getDueDate);
        eqIf(w, SoImproveTask::getStatus, str(query.get("status")));
        List<Map<String, Object>> items = new ArrayList<>();
        for (SoImproveTask t : improveDao.selectList(w)) {
            items.add(improveMap(t));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> updateImproveTask(String id, Map<String, Object> payload) {
        SoImproveTask task = improveDao.selectById(id);
        if (task == null || "1".equals(task.getDelFlag())) {
            throw new ServiceException(404, "改进任务不存在");
        }
        String status = str(payload.get("status"));
        if (!SoImproveTask.ST_PENDING.equals(status) && !SoImproveTask.ST_PROCESSING.equals(status)
                && !SoImproveTask.ST_CLOSED.equals(status)) {
            throw new ServiceException(422, "状态非法（PENDING/PROCESSING/CLOSED）");
        }
        task.setStatus(status);
        if (SoImproveTask.ST_CLOSED.equals(status)) {
            task.setCloseBy(SecurityUtils.getCurrentUserId());
            task.setCloseDate(LocalDateTime.now());
        }
        task.setUpdateBy(SecurityUtils.getCurrentUserId());
        task.setUpdateDate(LocalDateTime.now());
        improveDao.updateById(task);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", improveMap(task));
        return out;
    }

    @Override
    @Transactional
    public void addMaterialCost(String serviceOrderNo, BigDecimal amount) {
        if (!hasText(serviceOrderNo) || amount == null || amount.signum() == 0) {
            return;
        }
        SoOrder o = orderDao.selectOne(new LambdaQueryWrapper<SoOrder>()
                .eq(SoOrder::getOrderNo, serviceOrderNo)
                .eq(SoOrder::getDelFlag, "0")
                .last("LIMIT 1"));
        if (o == null) {
            throw new ServiceException(422, "服务工单不存在：" + serviceOrderNo);
        }
        o.setMaterialCost(nvlBig(o.getMaterialCost()).add(amount));
        o.setUpdateDate(LocalDateTime.now());
        orderDao.updateById(o);
    }

    // ================================================================
    // 定时任务支撑（PmServiceOrderDailyTask 调用，幂等）
    // ================================================================

    /** 超时升级（响应超时→主管；解决超时→销售经理）；返回升级条数 */
    public int escalateOverdue(LocalDateTime now) {
        int n = 0;
        List<SoOrder> actives = orderDao.selectList(new LambdaQueryWrapper<SoOrder>()
                .eq(SoOrder::getDelFlag, "0")
                .in(SoOrder::getStatus, SoOrder.ST_REGISTERED, SoOrder.ST_DISPATCHED,
                        SoOrder.ST_PROCESSING, SoOrder.ST_PENDING_CONFIRM)
                .eq(SoOrder::getSuspendedFlag, "0"));
        for (SoOrder o : actives) {
            boolean responded = o.getRespondedAt() != null;
            // 响应超时 → 升级服务主管（BR-4.8-03 链路第一档）
            if (!responded && o.getRespDeadline() != null && now.isAfter(o.getRespDeadline())
                    && nvlInt(o.getEscalateStage()) < 1) {
                if (writeSlaLog(o.getId(), SoSlaLog.EV_ESCALATE, 1, "ROLE_SERVICE_MGR",
                        "响应超时升级服务主管") > 0) {
                    o.setEscalateStage(1);
                    orderDao.updateById(o);
                    appendRecord(o.getId(), SoProcessRecord.ACT_ESCALATE, "SYSTEM",
                            "响应超时自动升级至服务主管（SLA " + o.getLevel() + " 级）", null);
                    noticeService.push("ROLE_SERVICE_MGR", null,
                            "工单响应超时升级：" + o.getOrderNo(),
                            "登记后未在响应时限内受理，已自动升级（" + nvl(o.getCustomerName()) + "）",
                            "PmServiceEscalate", o.getId() + ":1");
                    // 一级投诉同时通知销售经理（C-4.8-11）
                    if (o.getLevel() != null && o.getLevel() == 1) {
                        noticeService.push("ROLE_SALES_MGR", null,
                                "一级投诉响应超时：" + o.getOrderNo(),
                                "一级投诉超响应时限未响应，已升级服务主管",
                                "PmServiceEscalateSales", o.getId() + ":1");
                    }
                    n++;
                }
            }
            // 解决超时 → 升级销售经理（链路第二档）
            if (o.getResolveDeadline() != null && now.isAfter(o.getResolveDeadline())
                    && !SoOrder.ST_CLOSED.equals(o.getStatus())
                    && nvlInt(o.getEscalateStage()) < 2) {
                if (writeSlaLog(o.getId(), SoSlaLog.EV_ESCALATE, 2, "ROLE_SALES_MGR",
                        "解决超时升级销售经理") > 0) {
                    o.setEscalateStage(2);
                    orderDao.updateById(o);
                    appendRecord(o.getId(), SoProcessRecord.ACT_ESCALATE, "SYSTEM",
                            "解决超时自动升级至销售经理", null);
                    noticeService.push("ROLE_SALES_MGR", null,
                            "工单解决超时升级：" + o.getOrderNo(),
                            "解决时限已超（" + nvl(o.getCustomerName()) + "），已升级，请督办",
                            "PmServiceEscalateSales", o.getId() + ":2");
                    n++;
                }
            }
        }
        return n;
    }

    /** 确认超期：5 工作日 → 2 次提醒后默认关闭；返回默认关闭条数 */
    public int defaultCloseExpired(LocalDateTime now) {
        int n = 0;
        List<SoOrder> pendings = orderDao.selectList(new LambdaQueryWrapper<SoOrder>()
                .eq(SoOrder::getDelFlag, "0")
                .eq(SoOrder::getStatus, SoOrder.ST_PENDING_CONFIRM)
                .isNotNull(SoOrder::getConfirmSubmitAt));
        for (SoOrder o : pendings) {
            LocalDateTime remindAt = o.getConfirmSubmitAt().plusDays(CONFIRM_DAYS);
            if (now.isBefore(remindAt)) {
                continue;
            }
            // 提醒 1 / 2（幂等：sla_log 唯一键）
            if (writeSlaLog(o.getId(), SoSlaLog.EV_REMIND_CONFIRM, 1, "ROLE_CS", "确认提醒 1/2") > 0) {
                noticeService.push("ROLE_CS", null,
                        "工单待客户确认提醒：" + o.getOrderNo(),
                        "提交确认已满 5 工作日未确认，请联系客户（第 1 次提醒）",
                        "PmServiceRemind", o.getId() + ":1");
            }
            if (now.isAfter(remindAt.plusDays(1))
                    && writeSlaLog(o.getId(), SoSlaLog.EV_REMIND_CONFIRM, 2, "ROLE_CS", "确认提醒 2/2") > 0) {
                noticeService.push("ROLE_CS", null,
                        "工单待客户确认提醒：" + o.getOrderNo(),
                        "第 2 次提醒：客户仍未确认，将于次日默认关闭",
                        "PmServiceRemind", o.getId() + ":2");
            }
            // 第 2 次提醒后再满 1 天 → 默认关闭（标注默认关闭）
            if (now.isAfter(remindAt.plusDays(2))
                    && writeSlaLog(o.getId(), SoSlaLog.EV_DEFAULT_CLOSE, null, null, "默认关闭") > 0) {
                closeOrder(o, SoOrder.VIA_DEFAULT, "DEFAULT_CLOSED", "1");
                n++;
            }
        }
        return n;
    }

    /** 回访到期提醒（关闭后 3 工作日无回访） */
    public int remindSurveyDue(LocalDateTime now) {
        int n = 0;
        List<SoOrder> closed = orderDao.selectList(new LambdaQueryWrapper<SoOrder>()
                .eq(SoOrder::getDelFlag, "0")
                .eq(SoOrder::getStatus, SoOrder.ST_CLOSED));
        for (SoOrder o : closed) {
            if (o.getUpdateDate() == null || now.isBefore(o.getUpdateDate().plusDays(3))) {
                continue;
            }
            Long surveyed = surveyDao.selectCount(new LambdaQueryWrapper<SoSurvey>()
                    .eq(SoSurvey::getOrderId, o.getId())
                    .eq(SoSurvey::getDelFlag, "0"));
            if (surveyed != null && surveyed > 0) {
                continue;
            }
            if (writeSlaLog(o.getId(), SoSlaLog.EV_SURVEY_DUE, null, "ROLE_CS", "回访到期提醒") > 0) {
                noticeService.push("ROLE_CS", null,
                        "工单回访到期：" + o.getOrderNo(),
                        "工单关闭已满 3 工作日未回访，请尽快录入满意度",
                        "PmServiceSurvey", o.getId());
                n++;
            }
        }
        return n;
    }

    /** 改进任务逾期升级销售经理（BR-4.8-06）；返回升级条数 */
    public int escalateImproveOverdue(LocalDateTime now) {
        int n = 0;
        List<SoImproveTask> open = improveDao.selectList(new LambdaQueryWrapper<SoImproveTask>()
                .eq(SoImproveTask::getDelFlag, "0")
                .ne(SoImproveTask::getStatus, SoImproveTask.ST_CLOSED)
                .eq(SoImproveTask::getEscalatedSales, "0")
                .lt(SoImproveTask::getDueDate, now));
        for (SoImproveTask t : open) {
            t.setEscalatedSales("1");
            t.setUpdateDate(now);
            improveDao.updateById(t);
            appendRecord(t.getOrderId(), SoProcessRecord.ACT_ESCALATE, "SYSTEM",
                    "改进任务逾期自动升级销售经理（BR-4.8-06）", null);
            noticeService.push("ROLE_SALES_MGR", null,
                    "改进任务逾期升级",
                    "工单 " + t.getOrderId() + " 的低分改进任务超 10 工作日未闭环，已升级",
                    "PmServiceImproveOverdue", t.getId());
            n++;
        }
        return n;
    }

    /** 手动触发每日扫描（ADMIN 运维端点；与 PmServiceOrderDailyTask 四段同一逻辑） */
    @Override
    public Map<String, Object> sweep() {
        requireRole("手动扫描", "ROLE_ADMIN");
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("escalated", escalateOverdue(now));
        out.put("defaultClosed", defaultCloseExpired(now));
        out.put("surveyReminded", remindSurveyDue(now));
        out.put("improveEscalated", escalateImproveOverdue(now));
        return out;
    }

    // ================================================================
    // 工具
    // ================================================================

    private void closeOrder(SoOrder o, String via, String result, String closeDefault) {
        o.setStatus(SoOrder.ST_CLOSED);
        o.setConfirmedVia(via);
        o.setConfirmResult(result);
        o.setCloseDefaultFlag(closeDefault);
        o.setUpdateBy(SecurityUtils.getCurrentUserId());
        o.setUpdateDate(LocalDateTime.now());
        orderDao.updateById(o);
        appendRecord(o.getId(), SoProcessRecord.ACT_CLOSE, SecurityUtils.getCurrentUserId(),
                SoOrder.VIA_DEFAULT.equals(via)
                        ? "客户 5 工作日未确认，提醒 2 次后默认关闭（标注默认关闭）"
                        : "客户确认已解决，工单关闭", null);
    }

    private void rejectAndReprocess(SoOrder o, String via) {
        int rejects = nvlInt(o.getRejectCount()) + 1;
        o.setRejectCount(rejects);
        o.setStatus(SoOrder.ST_PROCESSING);
        o.setConfirmedVia(via);
        // 重新计时：解决截止从驳回时刻按当前等级重算
        SoSlaGrade grade = gradeByLevel(o.getLevel() == null ? 3 : o.getLevel());
        o.setResolveDeadline(LocalDateTime.now().plusHours(grade.getResolveHours()));
        o.setUpdateBy(SecurityUtils.getCurrentUserId());
        o.setUpdateDate(LocalDateTime.now());
        orderDao.updateById(o);
        appendRecord(o.getId(), SoProcessRecord.ACT_REJECT, SecurityUtils.getCurrentUserId(),
                "客户驳回（第 " + rejects + " 次），退回处理环节重新计时", null);
        // 驳回 ≥2 次自动升级服务主管（FR-4.8-9-4）
        if (rejects >= 2 && nvlInt(o.getEscalateStage()) < 1) {
            if (writeSlaLog(o.getId(), SoSlaLog.EV_ESCALATE, 1, "ROLE_SERVICE_MGR",
                    "驳回 2 次升级服务主管") > 0) {
                o.setEscalateStage(1);
                orderDao.updateById(o);
                appendRecord(o.getId(), SoProcessRecord.ACT_ESCALATE, "SYSTEM",
                        "驳回 ≥2 次自动升级服务主管介入", null);
                noticeService.push("ROLE_SERVICE_MGR", null,
                        "工单驳回升级：" + o.getOrderNo(),
                        "客户第 " + rejects + " 次驳回，请服务主管介入",
                        "PmServiceReject", o.getId());
            }
        }
    }

    private List<Map<String, Object>> duplicateCandidates(SoOrder o) {
        LambdaQueryWrapper<SoOrder> w = new LambdaQueryWrapper<SoOrder>()
                .eq(SoOrder::getDelFlag, "0")
                .eq(SoOrder::getCustomerId, o.getCustomerId())
                .eq(SoOrder::getComplaintType, o.getComplaintType())
                .ne(SoOrder::getId, o.getId())
                .orderByDesc(SoOrder::getCreateDate)
                .last("LIMIT 5");
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDateTime limit = LocalDateTime.now().minusDays(DUP_WINDOW_DAYS);
        for (SoOrder p : orderDao.selectList(w)) {
            boolean sameScope = hasText(o.getContractNo())
                    ? o.getContractNo().equals(p.getContractNo())
                    : (o.getProjectId() == null ? p.getProjectId() == null
                            : o.getProjectId().equals(p.getProjectId()));
            boolean openOrRecent = !SoOrder.ST_CLOSED.equals(p.getStatus())
                    || (p.getUpdateDate() != null && p.getUpdateDate().isAfter(limit));
            if (sameScope && openOrRecent) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.getId());
                m.put("orderNo", p.getOrderNo());
                m.put("status", p.getStatus());
                m.put("createDate", p.getCreateDate());
                m.put("description", p.getDescription());
                out.add(m);
            }
        }
        return out;
    }

    private SoSlaGrade resolveGrade(String complaintType, String impact) {
        List<SoSlaGrade> grades = gradeDao.selectList(new LambdaQueryWrapper<SoSlaGrade>()
                .eq(SoSlaGrade::getDelFlag, "0")
                .eq(SoSlaGrade::getEnabled, "1")
                .eq(SoSlaGrade::getImpactLevel, impact));
        for (SoSlaGrade g : grades) {
            if (complaintType.equals(g.getComplaintType())) {
                return g;
            }
        }
        for (SoSlaGrade g : grades) {
            if ("*".equals(g.getComplaintType())) {
                return g;
            }
        }
        throw new ServiceException(500, "定级映射缺失：" + complaintType + "/" + impact + "，请维护定级配置");
    }

    private SoSlaGrade gradeByLevel(int level) {
        SoSlaGrade g = gradeDao.selectOne(new LambdaQueryWrapper<SoSlaGrade>()
                .eq(SoSlaGrade::getDelFlag, "0")
                .eq(SoSlaGrade::getEnabled, "1")
                .eq(SoSlaGrade::getLevel, level)
                .last("LIMIT 1"));
        if (g == null) {
            throw new ServiceException(500, "定级配置缺失 L" + level);
        }
        return g;
    }

    private Map<String, Long> inHandLoad() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ENGINEER_ID AS eng, COUNT(*) AS cnt FROM erp_pm_service_order "
                        + "WHERE DEL_FLAG = '0' AND STATUS IN ('DISPATCHED','PROCESSING') "
                        + "AND ENGINEER_ID IS NOT NULL GROUP BY ENGINEER_ID");
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            out.put(str(r.get("eng")), ((Number) r.get("cnt")).longValue());
        }
        return out;
    }

    private boolean ncrExists(String ncrNo) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_ncr WHERE NCR_NO = ? AND DEL_FLAG = '0'",
                Integer.class, ncrNo);
        return c != null && c > 0;
    }

    private void requireCustomer(String customerId) {
        // 口径对齐 SD 域（SoServiceImpl.requireCustomer）：customerId = 客户组主数据 id
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM erp_mdm_customer_group WHERE ID = ? AND DEL_FLAG = '0'",
                Integer.class, customerId);
        if (c == null || c == 0) {
            throw new ServiceException(422, "客户主数据不存在，请先建立客户档案");
        }
    }

    private boolean isQualityClass(SoOrder o) {
        return SoOrder.CT_PRODUCT_QUALITY.equals(o.getComplaintType())
                || SoOrder.RC_MANUFACTURE.equals(o.getRootCause())
                || SoOrder.RC_DESIGN.equals(o.getRootCause());
    }

    /** SLA 事件写入（唯一键幂等）；返回 1=新写入 0=已存在 */
    private int writeSlaLog(String orderId, String event, Integer stage, String target, String detail) {
        try {
            SoSlaLog l = new SoSlaLog();
            l.setId(uuid());
            l.setOrderId(orderId);
            l.setEvent(event);
            l.setEscalateStage(stage);
            l.setTarget(target);
            l.setDetail(detail);
            l.setCreateBy("SYSTEM");
            l.setCreateDate(LocalDateTime.now());
            slaLogDao.insert(l);
            return 1;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return 0;
        }
    }

    private void appendRecord(String orderId, String action, String who, String content, String attachIds) {
        SoProcessRecord r = new SoProcessRecord();
        r.setId(uuid());
        r.setOrderId(orderId);
        r.setAction(action);
        r.setContent(content);
        r.setAttachIds(attachIds);
        r.setCreateBy(who);
        r.setCreateDate(LocalDateTime.now());
        recordDao.insert(r);
    }

    private SoOrder require(String id) {
        SoOrder o = orderDao.selectById(id);
        if (o == null || "1".equals(o.getDelFlag())) {
            throw new ServiceException(404, "服务工单不存在：" + id);
        }
        return o;
    }

    private SoConfirmToken requireToken(String token) {
        if (!hasText(token)) {
            throw new ServiceException(422, "确认链接无效");
        }
        SoConfirmToken t = tokenDao.selectOne(new LambdaQueryWrapper<SoConfirmToken>()
                .eq(SoConfirmToken::getToken, token)
                .eq(SoConfirmToken::getDelFlag, "0")
                .last("LIMIT 1"));
        if (t == null) {
            throw new ServiceException(404, "确认链接不存在或已失效");
        }
        if ("1".equals(t.getUsed())) {
            throw new ServiceException(422, "确认链接已使用（一次性）");
        }
        if (t.getExpireAt() != null && t.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new ServiceException(422, "确认链接已过期");
        }
        return t;
    }

    private String nextOrderNo() {
        String prefix = "SO" + LocalDateTime.now().format(DAY) + "-";
        Integer max = orderDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private Map<String, Object> toMap(SoOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("orderNo", o.getOrderNo());
        m.put("customerId", o.getCustomerId());
        m.put("customerName", o.getCustomerName());
        m.put("contractNo", o.getContractNo());
        m.put("projectId", o.getProjectId());
        m.put("complaintType", o.getComplaintType());
        m.put("impactLevel", o.getImpactLevel());
        m.put("description", o.getDescription());
        m.put("expectDeadline", str(o.getExpectDeadline()));
        m.put("attachIds", o.getAttachIds());
        m.put("skillTag", o.getSkillTag());
        m.put("level", o.getLevel());
        m.put("respDeadline", str(o.getRespDeadline()));
        m.put("resolveDeadline", str(o.getResolveDeadline()));
        m.put("status", o.getStatus());
        m.put("engineerId", o.getEngineerId());
        m.put("engineerName", o.getEngineerName());
        m.put("respondedAt", str(o.getRespondedAt()));
        m.put("confirmSubmitAt", str(o.getConfirmSubmitAt()));
        m.put("suspendedFlag", o.getSuspendedFlag());
        m.put("suspendHours", o.getSuspendHours());
        m.put("escalateStage", o.getEscalateStage());
        m.put("duplicateOf", o.getDuplicateOf());
        m.put("ncrNo", o.getNcrNo());
        m.put("rootCause", o.getRootCause());
        m.put("rootCured", o.getRootCured());
        m.put("rejectCount", o.getRejectCount());
        m.put("confirmedVia", o.getConfirmedVia());
        m.put("confirmResult", o.getConfirmResult());
        m.put("closeDefaultFlag", o.getCloseDefaultFlag());
        m.put("materialCost", o.getMaterialCost());
        m.put("remark", o.getRemark());
        return m;
    }

    private Map<String, Object> surveyMap(SoSurvey s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("orderId", s.getOrderId());
        m.put("score", s.getScore());
        m.put("suggestion", s.getSuggestion());
        m.put("contactTries", s.getContactTries());
        m.put("unreachedFlag", s.getUnreachedFlag());
        m.put("surveyBy", s.getSurveyBy());
        m.put("surveyDate", str(s.getSurveyDate()));
        return m;
    }

    private Map<String, Object> improveMap(SoImproveTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("orderId", t.getOrderId());
        m.put("content", t.getContent());
        m.put("ownerId", t.getOwnerId());
        m.put("ownerName", t.getOwnerName());
        m.put("dueDate", str(t.getDueDate()));
        m.put("status", t.getStatus());
        m.put("escalatedSales", t.getEscalatedSales());
        m.put("closeBy", t.getCloseBy());
        m.put("closeDate", str(t.getCloseDate()));
        return m;
    }

    // ================================================================
    // 通用小工具
    // ================================================================

    private static boolean isKnownType(String t) {
        return SoOrder.CT_PRODUCT_QUALITY.equals(t) || SoOrder.CT_DELIVERY_DELAY.equals(t)
                || SoOrder.CT_SERVICE_ATTITUDE.equals(t) || SoOrder.CT_FUNCTION_DEFECT.equals(t)
                || SoOrder.CT_OTHER.equals(t);
    }

    private static String rootCauseName(String rc) {
        return switch (rc) {
            case SoOrder.RC_DESIGN -> "设计缺陷";
            case SoOrder.RC_MANUFACTURE -> "制造缺陷";
            case SoOrder.RC_OPERATION -> "操作不当";
            default -> "外部原因";
        };
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : roles) {
                if (r.equalsIgnoreCase(want)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "当前角色无权" + action);
    }

    private static <T> void eqIf(LambdaQueryWrapper<T> w,
                                 com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> col,
                                 Object val) {
        if (val != null && hasText(String.valueOf(val))) {
            w.eq(col, val);
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static BigDecimal nvlBig(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static int nvlInt(Integer v) {
        return v == null ? 0 : v;
    }

    private static Integer intOf(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "整数格式错误：" + o);
        }
    }

    private static LocalDateTime ts(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(String.valueOf(o));
        } catch (Exception e) {
            throw new ServiceException(422, "时间格式错误（yyyy-MM-ddTHH:mm:ss）：" + o);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
