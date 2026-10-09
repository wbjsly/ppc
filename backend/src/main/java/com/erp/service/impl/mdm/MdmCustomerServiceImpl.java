package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.common.SimilarityUtil;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.dao.mdm.MdmCustomerGroupVersionDao;
import com.erp.dao.mdm.MdmCustomerViewDao;
import com.erp.dao.mdm.MdmCustomerViewVersionDao;
import com.erp.dao.mdm.MdmLegalEntityDao;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.mdm.MdmCustomerGroupVersion;
import com.erp.entity.mdm.MdmCustomerView;
import com.erp.entity.mdm.MdmCustomerViewVersion;
import com.erp.entity.mdm.MdmLegalEntity;
import com.erp.ops.OutboxPublisher;
import com.erp.service.mdm.MdmCustomerService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class MdmCustomerServiceImpl implements MdmCustomerService {

    private static final String SNAPSHOT_FIELDS =
            "customerCode,customerName,taxNo,uscc,creditRating,creditLimitTotal,status,mergedTo";
    private static final String VIEW_SNAPSHOT_FIELDS =
            "groupId,legalEntityId,shipAddress,contactName,contactPhone,paymentTerms," +
            "creditLimit,tempCreditLimit,tempExpireDate,lastReviewDate,compressedLimit,status";

    /** 状态语义（D1）：集团 1/0/2/3，法人视图 1/0/2 */
    private static final String ST_ACTIVE = "1";
    private static final String ST_INACTIVE = "0";
    private static final String ST_FROZEN = "2";
    private static final String ST_MERGED = "3";

    private final MdmCustomerGroupDao groupDao;
    private final MdmCustomerGroupVersionDao groupVersionDao;
    private final MdmCustomerViewDao viewDao;
    private final MdmCustomerViewVersionDao viewVersionDao;
    private final MdmLegalEntityDao legalEntityDao;
    private final ObjectMapper objectMapper;
    /** 求和校验共享组件（design D1/D3，与信用额度 Service 同一实现） */
    private final CreditLimitSupport creditSupport;
    /** 跨域事件发布（7.2 Outbox，事务内与主操作原子） */
    private final OutboxPublisher outbox;

    public MdmCustomerServiceImpl(MdmCustomerGroupDao groupDao,
                                  MdmCustomerGroupVersionDao groupVersionDao,
                                  MdmCustomerViewDao viewDao,
                                  MdmCustomerViewVersionDao viewVersionDao,
                                  MdmLegalEntityDao legalEntityDao,
                                  ObjectMapper objectMapper,
                                  CreditLimitSupport creditSupport,
                                  OutboxPublisher outbox) {
        this.groupDao = groupDao;
        this.groupVersionDao = groupVersionDao;
        this.viewDao = viewDao;
        this.viewVersionDao = viewVersionDao;
        this.legalEntityDao = legalEntityDao;
        this.objectMapper = objectMapper;
        this.creditSupport = creditSupport;
        this.outbox = outbox;
    }

    // ---------- 集团视图（FR-4.1-6-1） ----------

    @Override
    public Page<MdmCustomerGroup> groupPage(long current, long size, String keyword, String status) {
        LambdaQueryWrapper<MdmCustomerGroup> qw = new LambdaQueryWrapper<MdmCustomerGroup>()
                .eq(isNotBlank(status), MdmCustomerGroup::getStatus, status)
                .and(isNotBlank(keyword), w -> w
                        .like(MdmCustomerGroup::getCustomerCode, keyword)
                        .or().like(MdmCustomerGroup::getCustomerName, keyword)
                        .or().like(MdmCustomerGroup::getTaxNo, keyword))
                .orderByAsc(MdmCustomerGroup::getCustomerCode);
        return groupDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public MdmCustomerGroup getGroup(String id) {
        MdmCustomerGroup g = groupDao.selectById(id);
        if (g == null) {
            throw new ServiceException(404, "客户集团视图不存在");
        }
        return g;
    }

    @Override
    @Transactional
    public MdmCustomerGroup createGroup(MdmCustomerGroup group, boolean forceCreate) {
        requireGroupFields(group);
        // BR-4.1-30：税号被其它集团视图占用 → 硬阻断，引导合并
        if (isNotBlank(group.getTaxNo())) {
            String holder = groupDao.findTaxNoHolder(group.getTaxNo(), "");
            if (holder != null) {
                throw new ServiceException(422, "税号已存在于客户 " + holder
                        + "，须先走客户合并（BR-4.1-30）");
            }
        }
        // 名称查重（C-4.1-07 同款）：编辑距离 ≤3 → 409+最近3条，forceCreate+dupNote 放行
        List<MdmCustomerGroup> similar = findSimilarGroups(group.getCustomerName());
        if (!similar.isEmpty() && !forceCreate) {
            StringBuilder sb = new StringBuilder();
            for (MdmCustomerGroup s : similar) {
                if (sb.length() > 0) sb.append("、");
                sb.append(s.getCustomerCode()).append(" ").append(s.getCustomerName());
            }
            throw new ServiceException(409, "名称高度相似：" + sb + "（确认非重复请带 forceCreate+差异说明）");
        }
        if (similar.isEmpty() && isNotBlank(group.getDupNote())) {
            // 无相似命中时忽略 dupNote（不阻断）
            group.setDupNote(null);
        }

        group.setId(null);
        group.setCustomerCode(generateCode());
        group.setStatus(ST_ACTIVE);
        group.setVerNo(0);
        insertGroup(group);
        saveGroupSnapshot(group, "CREATE", null, null);
        outbox.publishEvent("MDM.CUSTOMER.CREATED", group.getCustomerCode(),
                group.getVerNo() + 1, null, null, java.util.Map.of(
                        "customerName", group.getCustomerName() == null ? "" : group.getCustomerName(),
                        "taxNo", group.getTaxNo() == null ? "" : group.getTaxNo()));
        return group;
    }

    @Override
    @Transactional
    public MdmCustomerGroup updateGroup(MdmCustomerGroup group) {
        MdmCustomerGroup stored = getGroup(group.getId());
        // C-4.1-01 精神：编码创建后不可修改
        if (isNotBlank(group.getCustomerCode()) && !group.getCustomerCode().equals(stored.getCustomerCode())) {
            throw new ServiceException(422, "客户编码创建后不可修改");
        }
        if (ST_MERGED.equals(stored.getStatus())) {
            throw new ServiceException(422, "已合并客户为终态，不可变更");
        }
        requireGroupFields(group);
        String reason = isNotBlank(group.getChangeReason()) ? group.getChangeReason().trim() : "";
        if (reason.length() < 2) {
            throw new ServiceException(422, "变更原因必填（至少 2 字）");
        }
        // 税号改到他人占用 → 同 BR-4.1-30
        if (isNotBlank(group.getTaxNo())) {
            String holder = groupDao.findTaxNoHolder(group.getTaxNo(), stored.getId());
            if (holder != null) {
                throw new ServiceException(422, "税号已存在于客户 " + holder + "，须先走客户合并（BR-4.1-30）");
            }
        }
        String diff = buildGroupDiff(stored, group);
        if (diff.isEmpty()) {
            throw new ServiceException(422, "无变更内容");
        }
        MdmCustomerGroup patch = new MdmCustomerGroup();
        patch.setId(stored.getId());
        patch.setCustomerName(group.getCustomerName());
        patch.setTaxNo(group.getTaxNo());
        patch.setUscc(group.getUscc());
        patch.setCreditRating(group.getCreditRating());
        patch.setCreditLimitTotal(group.getCreditLimitTotal());
        patch.setRouteId(group.getRouteId());
        patch.setVerNo(stored.getVerNo());
        if (groupDao.updateById(patch) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        MdmCustomerGroup after = getGroup(stored.getId());
        saveGroupSnapshot(after, "UPDATE", diff, reason);
        outbox.publish("MDM.CUSTOMER.UPDATED", after.getCustomerCode(),
                after.getVerNo() + 1, null, diff);
        return after;
    }

    @Override
    public List<MdmCustomerGroupVersion> groupVersions(String entityId) {
        return groupVersionDao.selectList(new LambdaQueryWrapper<MdmCustomerGroupVersion>()
                .eq(MdmCustomerGroupVersion::getEntityId, entityId)
                .orderByAsc(MdmCustomerGroupVersion::getVersionNo));
    }

    @Override
    public Map<String, Object> groupDiff(String entityId, int from, int to) {
        MdmCustomerGroupVersion vFrom = findGroupVersion(entityId, from);
        MdmCustomerGroupVersion vTo = findGroupVersion(entityId, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", Map.of("versionNo", vFrom.getVersionNo(), "opType", vFrom.getOpType(),
                "createBy", Objects.toString(vFrom.getCreateBy(), "")));
        result.put("to", Map.of("versionNo", vTo.getVersionNo(), "opType", vTo.getOpType(),
                "createBy", Objects.toString(vTo.getCreateBy(), "")));
        result.put("fields", compareSnapshots(vFrom.getSnapshotJson(), vTo.getSnapshotJson()));
        return result;
    }

    // ---------- 法人视图（FR-4.1-6-2） ----------

    @Override
    public List<MdmCustomerView> viewsByGroup(String groupId) {
        return viewDao.selectList(new LambdaQueryWrapper<MdmCustomerView>()
                .eq(MdmCustomerView::getGroupId, groupId)
                .orderByAsc(MdmCustomerView::getLegalEntityId));
    }

    @Override
    @Transactional
    public MdmCustomerView saveView(MdmCustomerView view) {
        MdmCustomerGroup group = getGroup(view.getGroupId());
        if (ST_MERGED.equals(group.getStatus())) {
            throw new ServiceException(422, "已合并客户不可挂载法人视图");
        }
        requireViewFields(view);
        // 法人主体参照：须存在且启用（同 requireCategory 口径）
        MdmLegalEntity le = legalEntityDao.selectById(view.getLegalEntityId());
        if (le == null) {
            throw new ServiceException(422, "法人主体不存在");
        }
        if (!ST_ACTIVE.equals(le.getStatus())) {
            throw new ServiceException(422, "法人主体已停用：" + le.getLeCode());
        }
        // 同集团同法人唯一（UK 兜底，友好提示前置）
        MdmCustomerView dup = viewDao.selectOne(new LambdaQueryWrapper<MdmCustomerView>()
                .eq(MdmCustomerView::getGroupId, view.getGroupId())
                .eq(MdmCustomerView::getLegalEntityId, view.getLegalEntityId())
                .ne(view.getId() != null, MdmCustomerView::getId, view.getId()));
        if (dup != null) {
            throw new ServiceException(422, "该法人主体已挂载本客户（存在既有法人视图）");
        }
        String reason = isNotBlank(view.getChangeReason()) ? view.getChangeReason().trim() : "";
        boolean creating = view.getId() == null || viewDao.selectById(view.getId()) == null;
        if (!creating && reason.length() < 2) {
            throw new ServiceException(422, "变更原因必填（至少 2 字）");
        }

        // BR-4.1-31：Σ法人额度 × ratio ≤ 集团总额度（总额度空 → 放行）——共享组件实现
        creditSupport.requireCreditSumWithinGroup(view);

        if (creating) {
            view.setId(null);
            view.setStatus(ST_ACTIVE);
            view.setVerNo(0);
            viewDao.insert(view);
            saveViewSnapshot(view, "CREATE", null, isNotBlank(reason) ? reason : null);
            outbox.publish("MDM.CUSTOMER.CREDIT_UPDATED",
                    group.getCustomerCode() + "@" + view.getLegalEntityId(),
                    view.getVerNo() + 1, view.getLegalEntityId(), "法人视图挂载，额度初始");
        } else {
            MdmCustomerView stored = viewDao.selectById(view.getId());
            String diff = buildViewDiff(stored, view);
            MdmCustomerView patch = new MdmCustomerView();
            patch.setId(stored.getId());
            patch.setGroupId(stored.getGroupId());
            patch.setLegalEntityId(view.getLegalEntityId());
            patch.setShipAddress(view.getShipAddress());
            patch.setContactName(view.getContactName());
            patch.setContactPhone(view.getContactPhone());
            patch.setPaymentTerms(view.getPaymentTerms());
            patch.setCreditLimit(view.getCreditLimit());
            patch.setVerNo(stored.getVerNo());
            if (viewDao.updateById(patch) == 0) {
                throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
            }
            MdmCustomerView after = viewDao.selectById(stored.getId());
            saveViewSnapshot(after, "UPDATE", diff.isEmpty() ? "无字段变化" : diff, reason);
            outbox.publish("MDM.CUSTOMER.CREDIT_UPDATED",
                    group.getCustomerCode() + "@" + after.getLegalEntityId(),
                    after.getVerNo() + 1, after.getLegalEntityId(),
                    diff.isEmpty() ? "无字段变化" : diff);
        }
        return view.getId() == null ? view : viewDao.selectById(view.getId());
    }

    @Override
    public List<MdmCustomerViewVersion> viewVersions(String entityId) {
        return viewVersionDao.selectList(new LambdaQueryWrapper<MdmCustomerViewVersion>()
                .eq(MdmCustomerViewVersion::getEntityId, entityId)
                .orderByAsc(MdmCustomerViewVersion::getVersionNo));
    }

    // ---------- 状态机 ----------

    @Override
    @Transactional
    public void changeGroupStatus(String id, String toStatus, String reason) {
        MdmCustomerGroup stored = getGroup(id);
        requireReason(reason);
        requireNotMerged(stored);
        if (ST_ACTIVE.equals(toStatus)) {
            // 启用：仅停用 → 启用（冻结须走解冻）
            if (!ST_INACTIVE.equals(stored.getStatus())) {
                throw new ServiceException(422, "仅停用状态可启用，当前：" + statusName(stored.getStatus()));
            }
        } else if (ST_INACTIVE.equals(toStatus)) {
            if (!ST_ACTIVE.equals(stored.getStatus())) {
                throw new ServiceException(422, "仅启用状态可停用，当前：" + statusName(stored.getStatus()));
            }
        } else {
            throw new ServiceException(422, "不支持的目标状态：" + toStatus);
        }
        transitionGroup(stored, toStatus,
                ST_INACTIVE.equals(toStatus) ? "DISABLE" : "UPDATE",
                "status: " + stored.getStatus() + " → " + toStatus + "；原因：" + reason.trim(),
                reason.trim());
        MdmCustomerGroup fresh = getGroup(id);
        String eventType = ST_INACTIVE.equals(toStatus)
                ? "MDM.CUSTOMER.DISABLED" : "MDM.CUSTOMER.ENABLED";
        outbox.publish(eventType, fresh.getCustomerCode(), fresh.getVerNo() + 1, null,
                "status → " + toStatus + "；原因：" + reason.trim());
    }

    @Override
    @Transactional
    public void freezeGroup(String id, String reason) {
        MdmCustomerGroup stored = getGroup(id);
        requireReason(reason);
        requireNotMerged(stored);
        if (ST_FROZEN.equals(stored.getStatus())) {
            throw new ServiceException(422, "客户已处于冻结状态");
        }
        if (!ST_ACTIVE.equals(stored.getStatus())) {
            throw new ServiceException(422, "仅启用状态可冻结，当前：" + statusName(stored.getStatus()));
        }
        transitionGroup(stored, ST_FROZEN, "FREEZE",
                "status: 1 → 2（冻结，级联法人视图）；原因：" + reason.trim(), reason.trim());
        // BR-4.1-34：级联冻结其下法人视图（1 → 2，不动停用的）
        cascadeViews(id, ST_ACTIVE, ST_FROZEN);
        MdmCustomerGroup freshF = getGroup(id);
        outbox.publish("MDM.CUSTOMER.FROZEN", freshF.getCustomerCode(),
                freshF.getVerNo() + 1, null, "status 1 → 2；原因：" + reason.trim());
    }

    @Override
    @Transactional
    public void unfreezeGroup(String id, String reason) {
        MdmCustomerGroup stored = getGroup(id);
        requireReason(reason);
        requireNotMerged(stored);
        if (!ST_FROZEN.equals(stored.getStatus())) {
            throw new ServiceException(422, "客户未处于冻结状态");
        }
        transitionGroup(stored, ST_ACTIVE, "FREEZE",
                "status: 2 → 1（解冻，级联恢复）；原因：" + reason.trim(), reason.trim());
        // 解冻只恢复 2 → 1，不覆盖期间被停用的法人视图
        cascadeViews(id, ST_FROZEN, ST_ACTIVE);
        MdmCustomerGroup freshU = getGroup(id);
        outbox.publish("MDM.CUSTOMER.UNFROZEN", freshU.getCustomerCode(),
                freshU.getVerNo() + 1, null, "status 2 → 1；原因：" + reason.trim());
    }

    @Override
    public Map<String, Object> impact(String id) {
        MdmCustomerGroup stored = getGroup(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("viewCount", viewDao.countByGroup(id));
        List<Map<String, String>> views = new ArrayList<>();
        for (MdmCustomerView v : viewsByGroup(id)) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("legalEntityId", v.getLegalEntityId());
            row.put("status", v.getStatus());
            views.add(row);
        }
        result.put("views", views);
        // 下游 SO 引用桩（BR-4.3-08 语义预留，销售域未接入）
        result.put("downstream", List.of());
        result.put("downstreamStub", true);
        result.put("downstreamNote", "销售域模块未接入，暂无数据");
        result.put("status", stored.getStatus());
        return result;
    }

    // ---------- 合并 ----------

    @Override
    public List<MdmCustomerGroup> mergeCandidates(String keyword, String excludeId) {
        LambdaQueryWrapper<MdmCustomerGroup> qw = new LambdaQueryWrapper<MdmCustomerGroup>()
                .ne(isNotBlank(excludeId), MdmCustomerGroup::getId, excludeId)
                .ne(MdmCustomerGroup::getStatus, ST_MERGED)
                .and(isNotBlank(keyword), w -> w
                        .like(MdmCustomerGroup::getCustomerCode, keyword)
                        .or().like(MdmCustomerGroup::getCustomerName, keyword)
                        .or().like(MdmCustomerGroup::getTaxNo, keyword))
                .orderByAsc(MdmCustomerGroup::getCustomerCode)
                .last("LIMIT 20");
        return groupDao.selectList(qw);
    }

    @Override
    @Transactional
    public void merge(String sourceId, String targetId, String reason) {
        requireReason(reason);
        if (Objects.equals(sourceId, targetId)) {
            throw new ServiceException(422, "源客户与目标客户不能相同");
        }
        MdmCustomerGroup source = getGroup(sourceId);
        MdmCustomerGroup target = getGroup(targetId);
        if (ST_MERGED.equals(source.getStatus())) {
            throw new ServiceException(422, "源客户已处于合并终态");
        }
        if (ST_MERGED.equals(target.getStatus())) {
            throw new ServiceException(422, "目标客户已合并，不可作为合并目标");
        }
        int viewCount = viewDao.countByGroup(sourceId);

        // 同法人冲突预检（UK_GROUP_LE）：源与目标存在相同法人主体的视图 → 阻断列出清单
        List<MdmCustomerView> sourceViews = viewsByGroup(sourceId);
        if (!sourceViews.isEmpty()) {
            List<String> targetLeIds = new ArrayList<>();
            for (MdmCustomerView v : viewsByGroup(targetId)) {
                targetLeIds.add(v.getLegalEntityId());
            }
            List<String> conflicts = new ArrayList<>();
            for (MdmCustomerView v : sourceViews) {
                if (targetLeIds.contains(v.getLegalEntityId())) {
                    conflicts.add(v.getLegalEntityId());
                }
            }
            if (!conflicts.isEmpty()) {
                throw new ServiceException(422, "源与目标客户存在相同法人主体的视图（"
                        + String.join("、", conflicts)
                        + "），改挂会重复挂载；请先调整任一侧法人视图后再合并");
            }
        }

        // ① 法人视图改挂目标集团（逐条走乐观锁与快照，量小语义清晰）
        if (viewCount > 0) {
            for (MdmCustomerView v : sourceViews) {
                MdmCustomerView patch = new MdmCustomerView();
                patch.setId(v.getId());
                patch.setGroupId(targetId);
                patch.setVerNo(v.getVerNo());
                if (viewDao.updateById(patch) == 0) {
                    throw new ServiceException(409, "法人视图并发冲突，请刷新后重试：" + v.getId());
                }
                MdmCustomerView after = viewDao.selectById(v.getId());
                saveViewSnapshot(after, "MERGE",
                        "groupId: " + sourceId + " → " + targetId + "（合并改挂）", reason.trim());
            }
        }
        // ② 源客户置已合并 + 编码锁定（C-4.1-10）
        MdmCustomerGroup patch = new MdmCustomerGroup();
        patch.setId(sourceId);
        patch.setStatus(ST_MERGED);
        patch.setMergedTo(target.getCustomerCode());
        patch.setVerNo(source.getVerNo());
        if (groupDao.updateById(patch) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        // ③ 双方 MERGE 快照
        MdmCustomerGroup sourceAfter = getGroup(sourceId);
        saveGroupSnapshot(sourceAfter, "MERGE",
                "status: " + source.getStatus() + " → 3（合并至 " + target.getCustomerCode()
                        + "）；改挂法人视图 " + viewCount + " 个；原因：" + reason.trim(), reason.trim());
        saveGroupSnapshot(getGroup(targetId), "MERGE",
                "接收合并：源 " + source.getCustomerCode() + "，改挂法人视图 " + viewCount
                        + " 个；原因：" + reason.trim(), reason.trim());
        // ④ 事件（Outbox 台账 + 日志双写）
        MdmCustomerGroup sourceFresh = getGroup(sourceId);
        outbox.publish("MDM.CUSTOMER.MERGED", sourceFresh.getCustomerCode(),
                sourceFresh.getVerNo() + 1, null,
                "mergedTo=" + target.getCustomerCode() + "；改挂视图" + viewCount
                        + "；原因：" + reason.trim());
        log.info("MDM.CUSTOMER.MERGED source={} target={} views={} reason={}",
                source.getCustomerCode(), target.getCustomerCode(), viewCount, reason.trim());
    }

    // ---------- 私有工具 ----------

    private void insertGroup(MdmCustomerGroup group) {
        try {
            groupDao.insert(group);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(409, "客户编码并发生成冲突，请重试");
        }
    }

    private String generateCode() {
        String max = groupDao.selectMaxCode();
        int next = 1;
        if (isNotBlank(max) && max.startsWith("CUST-")) {
            try {
                next = Integer.parseInt(max.substring(5)) + 1;
            } catch (NumberFormatException ignore) {
                next = 1;
            }
        }
        return String.format("CUST-%04d", next);
    }

    private void requireNotMerged(MdmCustomerGroup g) {
        if (ST_MERGED.equals(g.getStatus())) {
            throw new ServiceException(422, "已合并客户为终态（编码永久锁定），不可执行状态操作");
        }
    }

    private void transitionGroup(MdmCustomerGroup stored, String toStatus, String opType,
                                 String diff, String reason) {
        MdmCustomerGroup patch = new MdmCustomerGroup();
        patch.setId(stored.getId());
        patch.setStatus(toStatus);
        patch.setVerNo(stored.getVerNo());
        if (groupDao.updateById(patch) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        saveGroupSnapshot(getGroup(stored.getId()), opType, diff, reason);
    }

    private void cascadeViews(String groupId, String fromStatus, String toStatus) {
        for (MdmCustomerView v : viewsByGroup(groupId)) {
            if (!fromStatus.equals(v.getStatus())) {
                continue;
            }
            MdmCustomerView patch = new MdmCustomerView();
            patch.setId(v.getId());
            patch.setStatus(toStatus);
            patch.setVerNo(v.getVerNo());
            if (viewDao.updateById(patch) == 0) {
                throw new ServiceException(409, "法人视图并发冲突，请刷新后重试");
            }
            saveViewSnapshot(viewDao.selectById(v.getId()), "FREEZE",
                    "status: " + fromStatus + " → " + toStatus + "（集团级联）", null);
        }
    }

    private void requireGroupFields(MdmCustomerGroup g) {
        if (!isNotBlank(g.getCustomerName())) {
            throw new ServiceException(422, "客户名称不能为空");
        }
        if (g.getCustomerName().contains("<") || g.getCustomerName().contains(">") || g.getCustomerName().contains("&")) {
            throw new ServiceException(422, "客户名称含特殊字符，请去除 < > &");
        }
    }

    private void requireViewFields(MdmCustomerView v) {
        if (!isNotBlank(v.getGroupId())) {
            throw new ServiceException(422, "须挂靠客户集团视图");
        }
        if (!isNotBlank(v.getLegalEntityId())) {
            throw new ServiceException(422, "法人主体不能为空");
        }
        if (!isNotBlank(v.getShipAddress())) {
            throw new ServiceException(422, "收货地址不能为空");
        }
    }

    private void requireReason(String reason) {
        if (!isNotBlank(reason)) {
            throw new ServiceException(422, "操作原因必填");
        }
    }

    private List<MdmCustomerGroup> findSimilarGroups(String name) {
        if (!isNotBlank(name)) {
            return List.of();
        }
        long total = groupDao.selectCount(null);
        List<MdmCustomerGroup> pool;
        if (total <= 5000) {
            pool = groupDao.selectList(new LambdaQueryWrapper<MdmCustomerGroup>()
                    .orderByAsc(MdmCustomerGroup::getCustomerCode));
        } else {
            String head = name.substring(0, Math.min(4, name.length()));
            pool = groupDao.selectList(new LambdaQueryWrapper<MdmCustomerGroup>()
                    .like(MdmCustomerGroup::getCustomerName, head)
                    .orderByAsc(MdmCustomerGroup::getCustomerCode)
                    .last("LIMIT 20"));
        }
        return SimilarityUtil.similarWithin(pool, name, 3, 3, MdmCustomerGroup::getCustomerName);
    }

    private void saveGroupSnapshot(MdmCustomerGroup source, String opType, String diffSummary, String reason) {
        int next = groupVersionDao.selectCount(new LambdaQueryWrapper<MdmCustomerGroupVersion>()
                .eq(MdmCustomerGroupVersion::getEntityId, source.getId())).intValue() + 1;
        MdmCustomerGroupVersion v = new MdmCustomerGroupVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source, SNAPSHOT_FIELDS.split(",")));
        v.setDiffSummary(diffSummary);
        v.setOpType(opType);
        v.setChangeReason(reason);
        groupVersionDao.insert(v);
    }

    private void saveViewSnapshot(MdmCustomerView source, String opType, String diffSummary, String reason) {
        int next = viewVersionDao.selectCount(new LambdaQueryWrapper<MdmCustomerViewVersion>()
                .eq(MdmCustomerViewVersion::getEntityId, source.getId())).intValue() + 1;
        MdmCustomerViewVersion v = new MdmCustomerViewVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source, VIEW_SNAPSHOT_FIELDS.split(",")));
        v.setDiffSummary(diffSummary);
        v.setOpType(opType);
        v.setChangeReason(reason);
        viewVersionDao.insert(v);
    }

    private MdmCustomerGroupVersion findGroupVersion(String entityId, int versionNo) {
        MdmCustomerGroupVersion v = groupVersionDao.selectOne(
                new LambdaQueryWrapper<MdmCustomerGroupVersion>()
                        .eq(MdmCustomerGroupVersion::getEntityId, entityId)
                        .eq(MdmCustomerGroupVersion::getVersionNo, versionNo));
        if (v == null) {
            throw new ServiceException(404, "版本不存在：" + entityId + " V" + versionNo);
        }
        return v;
    }

    private String buildGroupDiff(MdmCustomerGroup oldRow, MdmCustomerGroup newRow) {
        StringBuilder sb = new StringBuilder();
        appendDiff(sb, "customerName", oldRow.getCustomerName(), newRow.getCustomerName());
        appendDiff(sb, "taxNo", oldRow.getTaxNo(), newRow.getTaxNo());
        appendDiff(sb, "uscc", oldRow.getUscc(), newRow.getUscc());
        appendDiff(sb, "creditRating", oldRow.getCreditRating(), newRow.getCreditRating());
        appendDiff(sb, "creditLimitTotal", oldRow.getCreditLimitTotal(), newRow.getCreditLimitTotal());
        appendDiff(sb, "routeId", oldRow.getRouteId(), newRow.getRouteId());
        return sb.toString();
    }

    private String buildViewDiff(MdmCustomerView oldRow, MdmCustomerView newRow) {
        StringBuilder sb = new StringBuilder();
        appendDiff(sb, "legalEntityId", oldRow.getLegalEntityId(), newRow.getLegalEntityId());
        appendDiff(sb, "shipAddress", oldRow.getShipAddress(), newRow.getShipAddress());
        appendDiff(sb, "contactName", oldRow.getContactName(), newRow.getContactName());
        appendDiff(sb, "contactPhone", oldRow.getContactPhone(), newRow.getContactPhone());
        appendDiff(sb, "paymentTerms", oldRow.getPaymentTerms(), newRow.getPaymentTerms());
        appendDiff(sb, "creditLimit", oldRow.getCreditLimit(), newRow.getCreditLimit());
        return sb.toString();
    }

    private void appendDiff(StringBuilder sb, String field, Object a, Object b) {
        String x = a == null ? "" : String.valueOf(a);
        String y = b == null ? "" : String.valueOf(b);
        if (Objects.equals(x, y)) {
            return;
        }
        if (sb.length() > 0) sb.append("；");
        sb.append(field).append(": ").append(x.isEmpty() ? "（空）" : x)
                .append(" → ").append(y.isEmpty() ? "（空）" : y);
    }

    private String statusName(String status) {
        if (ST_ACTIVE.equals(status)) return "启用";
        if (ST_INACTIVE.equals(status)) return "停用";
        if (ST_FROZEN.equals(status)) return "冻结";
        if (ST_MERGED.equals(status)) return "已合并";
        return "未知(" + status + ")";
    }

    private String toJson(Object entity, String[] fields) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String f : fields) {
            try {
                var getter = entity.getClass().getMethod("get" + Character.toUpperCase(f.charAt(0)) + f.substring(1));
                Object val = getter.invoke(entity);
                map.put(f, val == null ? "" : String.valueOf(val));
            } catch (ReflectiveOperationException ignore) {
                map.put(f, "");
            }
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new ServiceException(500, "快照序列化失败");
        }
    }

    private List<Map<String, String>> compareSnapshots(String fromJson, String toJson) {
        JsonNode from = readTree(fromJson);
        JsonNode to = readTree(toJson);
        List<Map<String, String>> fields = new ArrayList<>();
        for (String field : unionKeys(from, to)) {
            String a = from.has(field) && !from.get(field).isNull() ? from.get(field).asText() : "";
            String b = to.has(field) && !to.get(field).isNull() ? to.get(field).asText() : "";
            if (!Objects.equals(a, b)) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("field", field);
                row.put("from", a);
                row.put("to", b);
                fields.add(row);
            }
        }
        return fields;
    }

    private List<String> unionKeys(JsonNode from, JsonNode to) {
        List<String> keys = new ArrayList<>();
        from.fieldNames().forEachRemaining(keys::add);
        to.fieldNames().forEachRemaining(k -> {
            if (!keys.contains(k)) keys.add(k);
        });
        return keys;
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json == null || json.isEmpty() ? "{}" : json);
        } catch (JsonProcessingException e) {
            throw new ServiceException(500, "快照解析失败");
        }
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
