package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mrp.MrpOpWcStandardDao;
import com.erp.dao.mrp.MrpOperationDao;
import com.erp.dao.mrp.MrpRoutingDao;
import com.erp.dao.mrp.MrpRoutingOpDao;
import com.erp.dao.mrp.MrpWorkCenterDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpOperation;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.RoutingStatusRules;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工艺路线实现（change add-routing-management，spec routing-management / 00-erp-spec 4.5 消费方反推）。
 * 草稿覆盖保存 = 逻辑删旧行后按载荷重建（op_seq 按载荷序重排）；
 * 行内 L1 卡控（工序/工作中心启用 + 定额组合存在，spec「路线行发布前置卡控」）；
 * 版本与状态机（任务 4.x）：同产品唯一在途（产品行 FOR UPDATE + 阻断）、版本冲突自动递增、
 * 白名单 CAS（RoutingStatusRules）、变更克隆、发布同事务旧版 REVISED；
 * 审批接线（RoutingApprovalCallback）、提交挂审批（BIZ=RoutingPublish）、
 * 角色权限（维护类=ENG/MGR、废止= MGR，未登录 401、角色不符 403）。
 */
@Slf4j
@Service
public class RoutingServiceImpl implements RoutingService {

    private final MrpRoutingDao routingDao;
    private final MrpRoutingOpDao routingOpDao;
    private final MrpOperationDao operationDao;
    private final MrpWorkCenterDao workCenterDao;
    private final MrpOpWcStandardDao standardDao;
    private final MdmItemDao mdmItemDao;
    private final ObjectMapper objectMapper;
    private final ApprovalEngine approvalEngine;

    public RoutingServiceImpl(MrpRoutingDao routingDao, MrpRoutingOpDao routingOpDao,
                              MrpOperationDao operationDao, MrpWorkCenterDao workCenterDao,
                              MrpOpWcStandardDao standardDao, MdmItemDao mdmItemDao,
                              ObjectMapper objectMapper, ApprovalEngine approvalEngine) {
        this.routingDao = routingDao;
        this.routingOpDao = routingOpDao;
        this.operationDao = operationDao;
        this.workCenterDao = workCenterDao;
        this.standardDao = standardDao;
        this.mdmItemDao = mdmItemDao;
        this.objectMapper = objectMapper;
        this.approvalEngine = approvalEngine;
    }

    // ---------- 创建 / 编辑 / 变更 ----------

    @Override
    @Transactional
    public MrpRouting create(MrpRouting head, List<MrpRoutingOp> ops) {
        requireAny("创建工艺路线", "ROLE_PROCESS_ENG", "ROLE_PROCESS_MGR", "ROLE_ADMIN");
        if (head == null || !isNotBlank(head.getItemCode())) {
            throw new ServiceException(422, "父项（产品）物料必填");
        }
        MdmItem item = requireActiveItem(head.getItemCode(),
                "产品物料不存在或已停用，无法创建路线");
        requireOps(ops);
        lockProductVersions(item.getItemCode());
        requireNoInFlight(item.getItemCode());
        int[] ver = nextVersion(item.getItemCode());
        MrpRouting routing = new MrpRouting();
        routing.setItemCode(item.getItemCode());
        routing.setItemName(item.getItemName());
        routing.setVersionMajor(ver[0]);
        routing.setVersionMinor(ver[1]);
        routing.setStatus(MrpRouting.ST_DRAFT);
        insertRoutingResolvingVersionConflict(routing);
        insertOps(routing.getId(), ops);
        log.info("工艺路线草稿创建：{} V{}", routing.getItemCode(), routing.getVersionLabel());
        return routing;
    }

    @Override
    @Transactional
    public MrpRouting saveDraft(String routingId, MrpRouting head, List<MrpRoutingOp> ops) {
        requireAny("编辑路线草稿", "ROLE_PROCESS_ENG", "ROLE_PROCESS_MGR", "ROLE_ADMIN");
        MrpRouting routing = routingDao.selectById(routingId);
        if (routing == null) {
            throw new ServiceException(404, "路线版本不存在");
        }
        if (!MrpRouting.ST_DRAFT.equals(routing.getStatus())) {
            throw new ServiceException(422, "仅草稿可编辑（当前状态：" + routing.getStatus() + "）");
        }
        if (head != null && isNotBlank(head.getItemCode())
                && !head.getItemCode().equals(routing.getItemCode())) {
            throw new ServiceException(422, "产品物料不可修改（如需更换产品请新建路线）");
        }
        requireOps(ops);
        if (head != null && isNotBlank(head.getItemCode())) {
            MdmItem item = requireActiveItem(routing.getItemCode(),
                    "产品物料不存在或已停用，无法保存");
            routing.setItemName(item.getItemName());
        }
        // 覆盖保存：逻辑删除旧行后重建（草稿态覆盖语义，同 bom-management）
        List<MrpRoutingOp> olds = routingOpDao.selectList(new LambdaQueryWrapper<MrpRoutingOp>()
                .eq(MrpRoutingOp::getRoutingId, routingId));
        if (!olds.isEmpty()) {
            routingOpDao.delete(new LambdaQueryWrapper<MrpRoutingOp>()
                    .eq(MrpRoutingOp::getRoutingId, routingId));
        }
        insertOps(routingId, ops);
        routingDao.updateById(routing);
        return routingDao.selectById(routingId);
    }

    @Override
    @Transactional
    public MrpRouting change(String sourceId, String changeReason, boolean upgradeMajor) {
        requireAny("发起路线变更", "ROLE_PROCESS_ENG", "ROLE_PROCESS_MGR", "ROLE_ADMIN");
        if (!isNotBlank(changeReason)) {
            throw new ServiceException(422, "变更原因必填");
        }
        MrpRouting src = routingDao.selectById(sourceId);
        if (src == null) {
            throw new ServiceException(404, "源路线版本不存在");
        }
        if (!MrpRouting.ST_PUBLISHED.equals(src.getStatus())) {
            throw new ServiceException(422, "仅已发布版本可发起变更（当前状态：" + src.getStatus() + "）");
        }
        MdmItem item = requireActiveItem(src.getItemCode(), "产品物料不存在或已停用，无法变更");
        lockProductVersions(item.getItemCode());
        requireNoInFlight(item.getItemCode());
        int[] ver = nextVersion(item.getItemCode(), upgradeMajor);
        MrpRouting routing = new MrpRouting();
        routing.setItemCode(item.getItemCode());
        routing.setItemName(item.getItemName());
        routing.setVersionMajor(ver[0]);
        routing.setVersionMinor(ver[1]);
        routing.setStatus(MrpRouting.ST_DRAFT);
        routing.setChangeReason(changeReason.trim());
        routing.setChangeFromId(src.getId());
        insertRoutingResolvingVersionConflict(routing);

        cloneOps(src.getId(), routing.getId());
        log.info("路线变更发起：{} V{} → 新草稿 V{}（原因：{}）",
                src.getItemCode(), src.getVersionLabel(), routing.getVersionLabel(), routing.getChangeReason());
        return routing;
    }

    // ---------- 审批接线（5.1） ----------

    @Override
    @Transactional
    public void publishApproved(String routingId) {
        MrpRouting routing = routingDao.selectById(routingId);
        if (routing == null) {
            throw new ServiceException(404, "路线版本不存在");
        }
        lockProductVersions(routing.getItemCode());
        if (!MrpRouting.ST_PENDING.equals(routing.getStatus())) {
            throw new ServiceException(422, "仅待审核版本可发布（当前状态：" + routing.getStatus() + "）");
        }
        casStatus(routing, MrpRouting.ST_PUBLISHED);
        // 同产品旧已发布 → REVISED（同事务，保证唯一已发布）
        List<MrpRouting> oldPublished = routingDao.selectList(new LambdaQueryWrapper<MrpRouting>()
                .eq(MrpRouting::getItemCode, routing.getItemCode())
                .eq(MrpRouting::getStatus, MrpRouting.ST_PUBLISHED)
                .ne(MrpRouting::getId, routing.getId()));
        for (MrpRouting old : oldPublished) {
            casStatus(old, MrpRouting.ST_REVISED);
        }
        routing.setPublishBy(currentOperator());
        routing.setPublishAt(LocalDateTime.now());
        routingDao.updateById(routing);
        log.info("路线发布：{} V{}（旧版已迁移 REVISED {} 条）",
                routing.getItemCode(), routing.getVersionLabel(), oldPublished.size());
    }

    @Override
    @Transactional
    public void rejectBackToDraft(String routingId, String reason) {
        MrpRouting routing = routingDao.selectById(routingId);
        if (routing == null) {
            throw new ServiceException(404, "路线版本不存在");
        }
        if (!MrpRouting.ST_PENDING.equals(routing.getStatus())) {
            throw new ServiceException(422, "仅待审核版本可退回（当前状态：" + routing.getStatus() + "）");
        }
        casStatus(routing, MrpRouting.ST_DRAFT);
        routing.setRejectReason(isNotBlank(reason) ? reason.trim() : null);
        routingDao.updateById(routing);
        log.info("路线审核退回：{} → 草稿（意见：{}）", routingId, routing.getRejectReason());
    }

    // ---------- 提交 / 废止（5.2 / 5.3） ----------

    @Override
    @Transactional
    public MrpRouting submit(String routingId) {
        requireAny("提交路线审核", "ROLE_PROCESS_ENG", "ROLE_PROCESS_MGR", "ROLE_ADMIN");
        MrpRouting routing = routingDao.selectById(routingId);
        if (routing == null) {
            throw new ServiceException(404, "路线版本不存在");
        }
        if (!MrpRouting.ST_DRAFT.equals(routing.getStatus())) {
            throw new ServiceException(422, "仅草稿可提交审核（当前状态：" + routing.getStatus() + "）");
        }
        approvalEngine.submit(BIZ_PUBLISH, routing.getId(),
                "工艺路线发布审核：" + routing.getItemCode() + " V" + routing.getVersionLabel(),
                null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PROCESS_MGR", "工艺主管"))));
        casStatus(routing, MrpRouting.ST_PENDING);
        log.info("路线提交审核：{} V{}", routing.getItemCode(), routing.getVersionLabel());
        return routing;
    }

    @Override
    @Transactional
    public void obsolete(String routingId) {
        requireAny("废止路线", "ROLE_PROCESS_MGR", "ROLE_ADMIN");
        MrpRouting routing = routingDao.selectById(routingId);
        if (routing == null) {
            throw new ServiceException(404, "路线版本不存在");
        }
        if (!MrpRouting.ST_PUBLISHED.equals(routing.getStatus())
                && !MrpRouting.ST_REVISED.equals(routing.getStatus())) {
            throw new ServiceException(422,
                    "仅已发布/已变更版本可废止（当前状态：" + routing.getStatus() + "）");
        }
        casStatus(routing, MrpRouting.ST_OBSOLETE);
        log.info("路线手动废止：{} V{}", routing.getItemCode(), routing.getVersionLabel());
    }

    // ---------- 查询 ----------

    @Override
    public Map<String, Object> detail(String routingId) {
        MrpRouting routing = routingDao.selectById(routingId);
        if (routing == null) {
            throw new ServiceException(404, "路线版本不存在");
        }
        Map<String, Object> out = toMap(routing);
        out.put("ops", opMaps(routingId));
        return out;
    }

    @Override
    public List<Map<String, Object>> query(String status, String itemCode, String keyword) {
        LambdaQueryWrapper<MrpRouting> w = new LambdaQueryWrapper<>();
        w.eq(isNotBlank(status), MrpRouting::getStatus, status);
        w.eq(isNotBlank(itemCode), MrpRouting::getItemCode, itemCode);
        if (isNotBlank(keyword)) {
            String like = "%" + keyword.trim() + "%";
            w.and(q -> q.like(MrpRouting::getItemCode, like)
                    .or().like(MrpRouting::getItemName, like));
        }
        w.orderByAsc(MrpRouting::getItemCode)
                .orderByDesc(MrpRouting::getVersionMajor)
                .orderByDesc(MrpRouting::getVersionMinor);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MrpRouting routing : routingDao.selectList(w)) {
            out.add(toMap(routing));
        }
        return out;
    }

    @Override
    public Map<String, Object> publishedRoute(String itemCode) {
        if (!isNotBlank(itemCode)) {
            return Map.of();
        }
        MrpRouting routing = routingDao.selectOne(new LambdaQueryWrapper<MrpRouting>()
                .eq(MrpRouting::getItemCode, itemCode.trim())
                .eq(MrpRouting::getStatus, MrpRouting.ST_PUBLISHED)
                .orderByDesc(MrpRouting::getVersionMajor)
                .orderByDesc(MrpRouting::getVersionMinor)
                .last("LIMIT 1"));
        if (routing == null) {
            return Map.of();
        }
        Map<String, Object> out = toMap(routing);
        out.put("ops", opMaps(routing.getId()));
        return out;
    }

    // ---------- 私有辅助 ----------

    /** 有序工序行 + 每行定额四类工时 + 工作中心产能三要素（下游契约字段） */
    private List<Map<String, Object>> opMaps(String routingId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<MrpRoutingOp> ops = routingOpDao.selectList(new LambdaQueryWrapper<MrpRoutingOp>()
                .eq(MrpRoutingOp::getRoutingId, routingId)
                .orderByAsc(MrpRoutingOp::getOpSeq));
        for (MrpRoutingOp op : ops) {
            Map<String, Object> m = toMap(op);
            MrpOpWcStandard std = standardDao.selectOne(new LambdaQueryWrapper<MrpOpWcStandard>()
                    .eq(MrpOpWcStandard::getOpCode, op.getOpCode())
                    .eq(MrpOpWcStandard::getWcCode, op.getWcCode()));
            m.put("standard", std == null ? null : toMap(std));
            MrpWorkCenter wc = workCenterDao.selectOne(new LambdaQueryWrapper<MrpWorkCenter>()
                    .eq(MrpWorkCenter::getWcCode, op.getWcCode()));
            m.put("workCenter", wc == null ? null : toMap(wc));
            out.add(m);
        }
        return out;
    }

    /**
     * 行写入：校验 + 按载荷序分配 op_seq（任务 3.2）。
     * L1 卡控：空行 422 / 工序启用 / 工作中心启用 / （工序×工作中心）定额存在（消息含两编码）。
     */
    private void insertOps(String routingId, List<MrpRoutingOp> ops) {
        requireOps(ops);
        for (int i = 0; i < ops.size(); i++) {
            MrpRoutingOp in = ops.get(i);
            int lineNo = i + 1;
            if (in == null || !isNotBlank(in.getOpCode())) {
                throw new ServiceException(422, "第 " + lineNo + " 行工序编码必填");
            }
            if (!isNotBlank(in.getWcCode())) {
                throw new ServiceException(422, "第 " + lineNo + " 行工作中心编码必填");
            }
            MrpOperation op = requireActiveOperation(in.getOpCode(),
                    "第 " + lineNo + " 行工序不存在或已停用：" + in.getOpCode());
            MrpWorkCenter wc = requireActiveWorkCenter(in.getWcCode(),
                    "第 " + lineNo + " 行工作中心不存在或已停用：" + in.getWcCode());
            MrpOpWcStandard std = standardDao.selectOne(new LambdaQueryWrapper<MrpOpWcStandard>()
                    .eq(MrpOpWcStandard::getOpCode, op.getOpCode())
                    .eq(MrpOpWcStandard::getWcCode, wc.getWcCode()));
            if (std == null) {
                throw new ServiceException(422, "（" + op.getOpCode() + " × " + wc.getWcCode()
                        + "）未维护标准工时定额，请先在 5.2.3 标准工时页维护后再装配");
            }
            if (in.getLeadTime() != null && in.getLeadTime().signum() < 0) {
                throw new ServiceException(422, "第 " + lineNo + " 行工序提前期不得为负数");
            }
            MrpRoutingOp row = new MrpRoutingOp();
            row.setRoutingId(routingId);
            row.setOpSeq((i + 1) * 10);
            row.setOpCode(op.getOpCode());
            row.setOpName(op.getOpName());
            row.setWcCode(wc.getWcCode());
            row.setWcName(wc.getWcName());
            row.setLeadTime(in.getLeadTime());
            row.setRemark(in.getRemark());
            routingOpDao.insert(row);
        }
    }

    /** 深克隆工序行（变更共用；工序/工作中心停用或定额缺失则 422，源头不被修改） */
    private void cloneOps(String sourceRoutingId, String targetRoutingId) {
        List<MrpRoutingOp> srcOps = routingOpDao.selectList(new LambdaQueryWrapper<MrpRoutingOp>()
                .eq(MrpRoutingOp::getRoutingId, sourceRoutingId)
                .orderByAsc(MrpRoutingOp::getOpSeq));
        List<MrpRoutingOp> payload = new ArrayList<>();
        for (MrpRoutingOp src : srcOps) {
            MrpRoutingOp in = new MrpRoutingOp();
            in.setOpCode(src.getOpCode());
            in.setWcCode(src.getWcCode());
            in.setLeadTime(src.getLeadTime());
            in.setRemark(src.getRemark());
            payload.add(in);
        }
        insertOps(targetRoutingId, payload);
    }

    /** 版本号自动分配：首版 1.0，否则最新版本次 +1；upgradeMajor=true → 主+1 次归零 */
    private int[] nextVersion(String itemCode) {
        return nextVersion(itemCode, false);
    }

    private int[] nextVersion(String itemCode, boolean upgradeMajor) {
        MrpRouting latest = routingDao.selectOne(new LambdaQueryWrapper<MrpRouting>()
                .eq(MrpRouting::getItemCode, itemCode)
                .orderByDesc(MrpRouting::getVersionMajor)
                .orderByDesc(MrpRouting::getVersionMinor)
                .last("LIMIT 1"));
        if (latest == null) {
            return new int[]{1, 0};
        }
        int major = latest.getVersionMajor() == null ? 1 : latest.getVersionMajor();
        int minor = latest.getVersionMinor() == null ? 0 : latest.getVersionMinor();
        return upgradeMajor ? new int[]{major + 1, 0} : new int[]{major, minor + 1};
    }

    /** 版本唯一键冲突自动递增重试，最多 5 次（每次严格递增） */
    private void insertRoutingResolvingVersionConflict(MrpRouting routing) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                routingDao.insert(routing);
                return;
            } catch (DuplicateKeyException e) {
                int curMajor = routing.getVersionMajor();
                int curMinor = routing.getVersionMinor();
                int[] next = nextVersion(routing.getItemCode());
                if (next[0] < curMajor || (next[0] == curMajor && next[1] <= curMinor)) {
                    next = new int[]{curMajor, curMinor + 1};
                }
                routing.setVersionMajor(next[0]);
                routing.setVersionMinor(next[1]);
                log.warn("路线版本号冲突重试 {}/5：{} 调整为 V{}.{}",
                        attempt + 1, routing.getItemCode(), next[0], next[1]);
            }
        }
        throw new ServiceException(422, "版本号分配冲突重试超限，请稍后重试");
    }

    /** 同产品唯一在途（任务 4.3）：存在草稿/待审核 → L1 阻断再创建/变更 */
    private void requireNoInFlight(String itemCode) {
        long inFlight = routingDao.selectCount(new LambdaQueryWrapper<MrpRouting>()
                .eq(MrpRouting::getItemCode, itemCode)
                .in(MrpRouting::getStatus, MrpRouting.ST_DRAFT, MrpRouting.ST_PENDING));
        if (inFlight > 0) {
            throw new ServiceException(422, "该产品存在在途路线（草稿/待审核），请先处理在途版本");
        }
    }

    /** 产品行级锁：串行化唯一在途/唯一已发布判定 */
    private void lockProductVersions(String itemCode) {
        routingDao.selectList(new LambdaQueryWrapper<MrpRouting>()
                .eq(MrpRouting::getItemCode, itemCode)
                .last("FOR UPDATE"));
    }

    /** 白名单 + CAS（id + status + ver_no），影响行数 0 → 422 并发冲突（任务 4.1） */
    private void casStatus(MrpRouting routing, String to) {
        RoutingStatusRules.assertTransit(routing.getStatus(), to);
        int rows = routingDao.update(null, new LambdaUpdateWrapper<MrpRouting>()
                .eq(MrpRouting::getId, routing.getId())
                .eq(MrpRouting::getStatus, routing.getStatus())
                .eq(MrpRouting::getVerNo, routing.getVerNo())
                .set(MrpRouting::getStatus, to)
                .set(MrpRouting::getVerNo, routing.getVerNo() + 1));
        if (rows == 0) {
            throw new ServiceException(422,
                    "状态并发冲突（" + routing.getStatus() + " → " + to + "），请刷新后重试");
        }
        routing.setStatus(to);
        routing.setVerNo(routing.getVerNo() + 1);
    }

    private MdmItem requireActiveItem(String itemCode, String notFoundMessage) {
        if (!isNotBlank(itemCode)) {
            throw new ServiceException(422, notFoundMessage);
        }
        MdmItem item = mdmItemDao.selectOne(
                new LambdaQueryWrapper<MdmItem>().eq(MdmItem::getItemCode, itemCode.trim()));
        if (item == null) {
            throw new ServiceException(422, notFoundMessage);
        }
        if (!"1".equals(item.getStatus())) {
            throw new ServiceException(422, notFoundMessage + "（当前状态：" + item.getStatus() + "）");
        }
        return item;
    }

    private MrpOperation requireActiveOperation(String opCode, String notFoundMessage) {
        MrpOperation op = operationDao.selectOne(new LambdaQueryWrapper<MrpOperation>()
                .eq(MrpOperation::getOpCode, opCode.trim()));
        if (op == null) {
            throw new ServiceException(422, notFoundMessage);
        }
        if (!MrpOperation.ST_ACTIVE.equals(op.getStatus())) {
            throw new ServiceException(422, notFoundMessage + "（工序已停用）");
        }
        return op;
    }

    private MrpWorkCenter requireActiveWorkCenter(String wcCode, String notFoundMessage) {
        MrpWorkCenter wc = workCenterDao.selectOne(new LambdaQueryWrapper<MrpWorkCenter>()
                .eq(MrpWorkCenter::getWcCode, wcCode.trim()));
        if (wc == null) {
            throw new ServiceException(422, notFoundMessage);
        }
        if (!MrpWorkCenter.ST_ACTIVE.equals(wc.getStatus())) {
            throw new ServiceException(422, notFoundMessage + "（工作中心已停用）");
        }
        return wc;
    }

    private void requireOps(List<MrpRoutingOp> ops) {
        if (ops == null || ops.isEmpty()) {
            throw new ServiceException(422, "路线至少需要一道工序");
        }
    }

    private String currentOperator() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            return auth == null || auth.getName() == null ? "system" : auth.getName();
        } catch (Exception e) {
            return "system";
        }
    }

    /** 角色门（任务 5.3）：未登录 401、角色不符 403 */
    private void requireAny(String action, String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        for (String r : roles) {
            if (auth.getAuthorities().contains(new SimpleGrantedAuthority(r))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限：" + action);
    }

    private Map<String, Object> toMap(Object bean) {
        return objectMapper.convertValue(bean, new TypeReference<Map<String, Object>>() {
        });
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
