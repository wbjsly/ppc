package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.dao.mdm.MdmCustomerGroupVersionDao;
import com.erp.dao.mdm.MdmCustomerViewDao;
import com.erp.dao.mdm.MdmCustomerViewVersionDao;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.mdm.MdmCustomerGroupVersion;
import com.erp.entity.mdm.MdmCustomerView;
import com.erp.entity.mdm.MdmCustomerViewVersion;
import com.erp.ops.OutboxPublisher;
import com.erp.service.mdm.MdmCreditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class MdmCreditServiceImpl implements MdmCreditService {

    private static final String VIEW_SNAPSHOT_FIELDS =
            "groupId,legalEntityId,shipAddress,contactName,contactPhone,paymentTerms," +
            "creditLimit,tempCreditLimit,tempExpireDate,lastReviewDate,compressedLimit,status";
    private static final String GROUP_SNAPSHOT_FIELDS =
            "customerCode,customerName,taxNo,uscc,creditRating,creditLimitTotal,status,mergedTo";

    /** 时间轴关注的额度类字段（快照 diff 中出现即收录） */
    private static final String[] CREDIT_FIELDS =
            {"creditLimit", "tempCreditLimit", "tempExpireDate", "compressedLimit",
            "lastReviewDate", "creditLimitTotal", "creditRating"};

    private final MdmCustomerGroupDao groupDao;
    private final MdmCustomerGroupVersionDao groupVersionDao;
    private final MdmCustomerViewDao viewDao;
    private final MdmCustomerViewVersionDao viewVersionDao;
    private final ObjectMapper objectMapper;
    private final CreditLimitSupport creditSupport;
    private final OutboxPublisher outbox;

    public MdmCreditServiceImpl(MdmCustomerGroupDao groupDao,
                                MdmCustomerGroupVersionDao groupVersionDao,
                                MdmCustomerViewDao viewDao,
                                MdmCustomerViewVersionDao viewVersionDao,
                                ObjectMapper objectMapper,
                                CreditLimitSupport creditSupport,
                                OutboxPublisher outbox) {
        this.groupDao = groupDao;
        this.groupVersionDao = groupVersionDao;
        this.viewDao = viewDao;
        this.viewVersionDao = viewVersionDao;
        this.objectMapper = objectMapper;
        this.creditSupport = creditSupport;
        this.outbox = outbox;
    }

    // ---------- 法人额度调整（FR-4.1-6-3） ----------

    @Override
    @Transactional
    public MdmCustomerView adjustViewLimit(MdmCustomerView incoming) {
        MdmCustomerView stored = viewDao.selectById(incoming.getId());
        if (stored == null) {
            throw new ServiceException(404, "法人视图不存在");
        }
        MdmCustomerGroup group = groupDao.selectById(stored.getGroupId());
        if (group != null && "3".equals(group.getStatus())) {
            throw new ServiceException(422, "已合并客户不可调整额度");
        }
        String reason = isNotBlank(incoming.getChangeReason()) ? incoming.getChangeReason().trim() : "";
        if (reason.length() < 2) {
            throw new ServiceException(422, "调整原因必填（至少 2 字）");
        }
        // 临时额度校验（BR-4.1-33）：设临时必填有效期 > 今天；清空临时（传 null）不受限
        if (incoming.getTempCreditLimit() != null) {
            if (incoming.getTempCreditLimit().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ServiceException(422, "临时额度须大于 0（清空请置空）");
            }
            if (incoming.getTempExpireDate() == null) {
                throw new ServiceException(422, "临时额度有效期必填（BR-4.1-33）");
            }
            if (!incoming.getTempExpireDate().isAfter(LocalDate.now())) {
                throw new ServiceException(422, "临时额度有效期须晚于当天");
            }
        }
        // 常规额度求和校验（BR-4.1-31，共享组件）——groupId 从存储行回填，调用方无需传
        incoming.setGroupId(stored.getGroupId());
        creditSupport.requireCreditSumWithinGroup(incoming);

        // MP updateById 忽略 null：临时额度清空须走 UpdateWrapper 显式置空（项目既有教训）+ 手动乐观锁
        LambdaUpdateWrapper<MdmCustomerView> uw = new LambdaUpdateWrapper<>();
        uw.eq(MdmCustomerView::getId, stored.getId())
                .eq(MdmCustomerView::getVerNo, stored.getVerNo())
                .set(MdmCustomerView::getCreditLimit, incoming.getCreditLimit())
                .set(MdmCustomerView::getTempCreditLimit, incoming.getTempCreditLimit())
                .set(MdmCustomerView::getTempExpireDate,
                        incoming.getTempCreditLimit() == null ? null : incoming.getTempExpireDate())
                .set(MdmCustomerView::getVerNo, stored.getVerNo() + 1);
        if (viewDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        MdmCustomerView after = viewDao.selectById(stored.getId());
        saveViewSnapshot(after, "UPDATE", buildCreditDiff(stored, after), reason);
        outbox.publish("MDM.CUSTOMER.CREDIT_UPDATED",
                (group == null ? "" : group.getCustomerCode() + "@") + after.getLegalEntityId(),
                after.getVerNo() + 1, after.getLegalEntityId(), buildCreditDiff(stored, after));
        log.info("MDM.CUSTOMER.CREDIT_UPDATED viewId={} code={} base={} temp={} reason={}",
                after.getId(), after.getLegalEntityId(), after.getCreditLimit(),
                after.getTempCreditLimit(), reason);
        return after;
    }

    // ---------- 集团基准调整 ----------

    @Override
    @Transactional
    public Map<String, Object> adjustGroupLimit(Map<String, String> payload) {
        String groupId = payload.get("groupId");
        MdmCustomerGroup stored = groupDao.selectById(groupId);
        if (stored == null) {
            throw new ServiceException(404, "客户集团视图不存在");
        }
        if ("3".equals(stored.getStatus())) {
            throw new ServiceException(422, "已合并客户为终态，不可调整基准");
        }
        String reason = isNotBlank(payload.get("reason")) ? payload.get("reason").trim() : "";
        if (reason.length() < 2) {
            throw new ServiceException(422, "调整原因必填（至少 2 字）");
        }
        BigDecimal newTotal = payload.containsKey("creditLimitTotal")
                && isNotBlank(payload.get("creditLimitTotal"))
                ? new BigDecimal(payload.get("creditLimitTotal").trim()) : null;
        String newRating = payload.get("creditRating");

        StringBuilder diff = new StringBuilder();
        if (payload.containsKey("creditLimitTotal")
                && !Objects.equals(stored.getCreditLimitTotal(), newTotal)) {
            appendDiff(diff, "creditLimitTotal", stored.getCreditLimitTotal(), newTotal);
        }
        if (payload.containsKey("creditRating")
                && !Objects.equals(stored.getCreditRating(), newRating)) {
            appendDiff(diff, "creditRating", stored.getCreditRating(), newRating);
        }
        if (diff.length() == 0) {
            throw new ServiceException(422, "无变更内容");
        }
        // 部分更新语义：payload 未携带的字段不得被 set 成 null 误清（踩过的坑）
        boolean hasTotal = payload.containsKey("creditLimitTotal");
        boolean hasRating = payload.containsKey("creditRating");
        LambdaUpdateWrapper<MdmCustomerGroup> uw = new LambdaUpdateWrapper<>();
        uw.eq(MdmCustomerGroup::getId, groupId)
                .eq(MdmCustomerGroup::getVerNo, stored.getVerNo())
                .set(hasTotal, MdmCustomerGroup::getCreditLimitTotal, newTotal)
                .set(hasRating, MdmCustomerGroup::getCreditRating, newRating)
                .set(MdmCustomerGroup::getVerNo, stored.getVerNo() + 1);
        if (groupDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        MdmCustomerGroup after = groupDao.selectById(groupId);
        saveGroupSnapshot(after, "UPDATE", diff + "；原因：" + reason, reason);
        outbox.publish("MDM.CUSTOMER.CREDIT_UPDATED", after.getCustomerCode(),
                after.getVerNo() + 1, null, diff.toString());
        log.info("MDM.CUSTOMER.CREDIT_UPDATED group={} total={} rating={} reason={}",
                after.getCustomerCode(), newTotal, newRating, reason);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group", after);
        result.put("occupancy", occupancy(groupId));
        return result;
    }

    // ---------- 占用率（BR-4.1-32） ----------

    @Override
    public Map<String, Object> occupancy(String groupId) {
        MdmCustomerGroup group = groupDao.selectById(groupId);
        if (group == null) {
            throw new ServiceException(404, "客户集团视图不存在");
        }
        BigDecimal sum = viewDao.sumCreditLimit(groupId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("creditSum", sum);
        result.put("creditTotal", group.getCreditLimitTotal());
        boolean configured = group.getCreditLimitTotal() != null
                && group.getCreditLimitTotal().compareTo(BigDecimal.ZERO) > 0;
        result.put("configured", configured);
        if (configured) {
            BigDecimal ratio = sum.divide(group.getCreditLimitTotal(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            result.put("occupancyRate", ratio);
            result.put("over80", ratio.compareTo(BigDecimal.valueOf(80)) > 0); // BR-4.1-32 L4
        }
        return result;
    }

    // ---------- 复审（C-4.3-13 台账） ----------

    @Override
    @Transactional
    public MdmCustomerView reviewPassed(String viewId, String reason) {
        MdmCustomerView stored = viewDao.selectById(viewId);
        if (stored == null) {
            throw new ServiceException(404, "法人视图不存在");
        }
        String r = isNotBlank(reason) ? reason.trim() : "";
        if (r.length() < 2) {
            throw new ServiceException(422, "复审原因必填（至少 2 字）");
        }
        if (stored.getLastReviewDate() != null
                && stored.getLastReviewDate().equals(LocalDate.now())
                && stored.getCompressedLimit() == null) {
            throw new ServiceException(422, "今日已复审通过（幂等拦截）");
        }
        LambdaUpdateWrapper<MdmCustomerView> uw = new LambdaUpdateWrapper<>();
        uw.eq(MdmCustomerView::getId, viewId)
                .eq(MdmCustomerView::getVerNo, stored.getVerNo())
                .set(MdmCustomerView::getLastReviewDate, LocalDate.now())
                .set(MdmCustomerView::getCompressedLimit, null) // 恢复原值（压缩列清空）
                .set(MdmCustomerView::getVerNo, stored.getVerNo() + 1);
        if (viewDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        MdmCustomerView after = viewDao.selectById(viewId);
        saveViewSnapshot(after, "UPDATE",
                buildCreditDiff(stored, after) + "；复审通过，压缩解除", r);
        outbox.publish("MDM.CUSTOMER.REVIEWED",
                after.getLegalEntityId() + "@" + viewId,
                after.getVerNo() + 1, after.getLegalEntityId(),
                "复审通过，压缩解除；原因：" + r);
        log.info("MDM.CUSTOMER.REVIEWED viewId={} date={} reason={}",
                viewId, LocalDate.now(), r);
        return after;
    }

    // ---------- 概览（列表数据源 + 懒校验入口） ----------

    @Override
    public Map<String, Object> overview(String keyword, String status, long current, long size) {
        // 懒校验双保险（design D4/D5）：查询前先跑幂等扫描，页面口径即真实口径
        try {
            sweepExpiredTemp();
            compressOverdueReviews();
        } catch (Exception e) {
            log.warn("credit lazy sweep failed (non-blocking): {}", e.getMessage());
        }
        LambdaQueryWrapper<MdmCustomerGroup> qw = new LambdaQueryWrapper<MdmCustomerGroup>()
                .ne(MdmCustomerGroup::getStatus, "3") // 已合并终态不进额度管理列表
                .eq(isNotBlank(status), MdmCustomerGroup::getStatus, status)
                .and(isNotBlank(keyword), w -> w
                        .like(MdmCustomerGroup::getCustomerCode, keyword)
                        .or().like(MdmCustomerGroup::getCustomerName, keyword)
                        .or().like(MdmCustomerGroup::getTaxNo, keyword))
                .orderByAsc(MdmCustomerGroup::getCustomerCode);
        var page = groupDao.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size), qw);

        List<Map<String, Object>> enriched = new ArrayList<>();
        for (MdmCustomerGroup g : page.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("group", g);
            List<MdmCustomerView> views = viewDao.selectList(new LambdaQueryWrapper<MdmCustomerView>()
                    .eq(MdmCustomerView::getGroupId, g.getId())
                    .orderByAsc(MdmCustomerView::getLegalEntityId));
            row.put("viewCount", views.size());
            row.put("creditSum", viewDao.sumCreditLimit(g.getId()));
            row.put("occupancy", occupancy(g.getId()));
            int overdue = 0, tempActive = 0, compressed = 0;
            List<Map<String, Object>> viewRows = new ArrayList<>();
            for (MdmCustomerView v : views) {
                Map<String, Object> vr = new LinkedHashMap<>();
                vr.put("view", v);
                vr.put("effectiveLimit", creditSupport.effectiveLimit(v));
                vr.put("reviewOverdue", creditSupport.reviewOverdue(v));
                vr.put("tempActive", v.getTempCreditLimit() != null && v.getTempExpireDate() != null
                        && v.getTempExpireDate().isAfter(LocalDate.now()));
                vr.put("compressed", v.getCompressedLimit() != null);
                if (creditSupport.reviewOverdue(v)) overdue++;
                if (Boolean.TRUE.equals(vr.get("tempActive"))) tempActive++;
                if (v.getCompressedLimit() != null) compressed++;
                viewRows.add(vr);
            }
            row.put("views", viewRows);
            row.put("overdueCount", overdue);
            row.put("tempActiveCount", tempActive);
            row.put("compressedCount", compressed);
            enriched.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", enriched);
        result.put("total", page.getTotal());
        return result;
    }

    // ---------- 时间轴 ----------

    @Override
    public List<Map<String, Object>> timeline(String groupId) {
        MdmCustomerGroup group = groupDao.selectById(groupId);
        if (group == null) {
            throw new ServiceException(404, "客户集团视图不存在");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        // 集团基准类变更（总额度/评级）
        for (MdmCustomerGroupVersion v : groupVersionDao.selectList(
                new LambdaQueryWrapper<MdmCustomerGroupVersion>()
                        .eq(MdmCustomerGroupVersion::getEntityId, groupId)
                        .orderByDesc(MdmCustomerGroupVersion::getVersionNo))) {
            if (containsCreditField(v.getDiffSummary())) {
                items.add(timelineItem("集团", group.getCustomerCode(), v.getVersionNo(),
                        v.getOpType(), v.getChangeReason(), v.getDiffSummary(),
                        v.getCreateBy(), v.getCreateDate()));
            }
        }
        // 法人视图额度类变更
        for (MdmCustomerView view : viewDao.selectList(new LambdaQueryWrapper<MdmCustomerView>()
                .eq(MdmCustomerView::getGroupId, groupId))) {
            for (MdmCustomerViewVersion v : viewVersionDao.selectList(
                    new LambdaQueryWrapper<MdmCustomerViewVersion>()
                            .eq(MdmCustomerViewVersion::getEntityId, view.getId())
                            .orderByDesc(MdmCustomerViewVersion::getVersionNo))) {
                if (containsCreditField(v.getDiffSummary())) {
                    items.add(timelineItem("法人视图", view.getLegalEntityId(), v.getVersionNo(),
                            v.getOpType(), v.getChangeReason(), v.getDiffSummary(),
                            v.getCreateBy(), v.getCreateDate()));
                }
            }
        }
        items.sort((a, b) -> String.valueOf(b.get("createDate"))
                .compareTo(String.valueOf(a.get("createDate"))));
        return items;
    }

    // ---------- 可用额度试算（FR-4.3-2-2 + 桩口径） ----------

    @Override
    public Map<String, Object> trial(String viewId) {
        MdmCustomerView view = viewDao.selectById(viewId);
        if (view == null) {
            throw new ServiceException(404, "法人视图不存在");
        }
        BigDecimal effective = creditSupport.effectiveLimit(view);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formula", "可用信用额度 = 信用额度（含有效期内临时额度） - 应收账款余额 - 未清SO预占用 - 本次订单金额");
        result.put("baseLimit", view.getCompressedLimit() != null ? view.getCompressedLimit() : view.getCreditLimit());
        result.put("baseIsCompressed", view.getCompressedLimit() != null);
        result.put("tempLimit", view.getTempCreditLimit() != null
                && view.getTempExpireDate() != null
                && view.getTempExpireDate().isAfter(LocalDate.now()) ? view.getTempCreditLimit() : BigDecimal.ZERO);
        result.put("tempExpireDate", view.getTempExpireDate());
        Map<String, Object> ar = new LinkedHashMap<>();
        ar.put("value", null);
        ar.put("stub", true);
        ar.put("note", "应收账款余额：财务域模块未接入，暂不计入");
        Map<String, Object> openSo = new LinkedHashMap<>();
        openSo.put("value", null);
        openSo.put("stub", true);
        openSo.put("note", "未清SO预占用：销售域模块未接入，暂不计入");
        result.put("accountsReceivable", ar);
        result.put("openSoReserved", openSo);
        result.put("orderAmount", null);
        result.put("available", effective); // 桩全不计入 → 试算值 = 当前有效额度
        result.put("stubNote", "应收/未清SO 模块未接入时，试算值 = 当前有效信用额度");
        return result;
    }

    // ---------- 复审超期懒压缩（D5，幂等） ----------

    @Override
    @Transactional
    public int compressOverdueReviews() {
        int count = 0;
        for (MdmCustomerView v : viewDao.findReviewOverdueCandidates()) {
            if (!creditSupport.reviewOverdue(v)) {
                continue; // SQL 粗筛 + 月级精判双保险
            }
            if (v.getCompressedLimit() != null || v.getCreditLimit() == null) {
                continue; // 已压缩 → 幂等跳过
            }
            BigDecimal compressed = v.getCreditLimit().multiply(creditSupport.getRatio())
                    .setScale(2, RoundingMode.HALF_UP);
            LambdaUpdateWrapper<MdmCustomerView> uw = new LambdaUpdateWrapper<>();
            uw.eq(MdmCustomerView::getId, v.getId())
                    .eq(MdmCustomerView::getVerNo, v.getVerNo())
                    .set(MdmCustomerView::getCompressedLimit, compressed)
                    .set(MdmCustomerView::getVerNo, v.getVerNo() + 1);
            if (viewDao.update(null, uw) == 0) {
                continue; // 并发冲突，下轮再压
            }
            MdmCustomerView after = viewDao.selectById(v.getId());
            saveViewSnapshot(after, "UPDATE",
                    "compressedLimit: （空） → " + compressed + "（C-4.3-13 复审超期压缩 ×"
                            + creditSupport.getRatio() + "）", "C-4.3-13 复审超期自动压缩");
            outbox.publish("MDM.CUSTOMER.CREDIT_UPDATED",
                    after.getLegalEntityId() + "@" + v.getId(),
                    after.getVerNo() + 1, after.getLegalEntityId(),
                    "C-4.3-13 复审超期压缩：" + v.getCreditLimit() + " → " + compressed);
            log.info("MDM.CUSTOMER.CREDIT_UPDATED compress viewId={} from={} to={}",
                    v.getId(), v.getCreditLimit(), compressed);
            count++;
        }
        return count;
    }

    // ---------- 临时额度到期回滚（BR-4.1-33，幂等） ----------

    @Override
    @Transactional
    public int sweepExpiredTemp() {
        int count = 0;
        for (MdmCustomerView v : viewDao.findExpiredTempLimits()) {
            String before = v.getTempCreditLimit() + " 至 " + v.getTempExpireDate();
            LambdaUpdateWrapper<MdmCustomerView> uw = new LambdaUpdateWrapper<>();
            uw.eq(MdmCustomerView::getId, v.getId())
                    .eq(MdmCustomerView::getVerNo, v.getVerNo())
                    .set(MdmCustomerView::getTempCreditLimit, null)
                    .set(MdmCustomerView::getTempExpireDate, null)
                    .set(MdmCustomerView::getVerNo, v.getVerNo() + 1);
            if (viewDao.update(null, uw) == 0) {
                continue;
            }
            MdmCustomerView after = viewDao.selectById(v.getId());
            saveViewSnapshot(after, "UPDATE",
                    "tempCreditLimit: " + before + " → （空）；BR-4.1-33 到期自动回滚",
                    "临时额度到期自动回滚");
            outbox.publish("MDM.CUSTOMER.TEMP_ROLLBACK",
                    after.getLegalEntityId() + "@" + v.getId(),
                    after.getVerNo() + 1, after.getLegalEntityId(),
                    "BR-4.1-33 到期回滚：" + before + " → 空");
            log.info("MDM.CUSTOMER.TEMP_ROLLBACK viewId={} before={}", v.getId(), before);
            count++;
        }
        return count;
    }

    // ---------- 私有工具 ----------

    private boolean containsCreditField(String diffSummary) {
        if (diffSummary == null) {
            return false;
        }
        for (String f : CREDIT_FIELDS) {
            if (diffSummary.contains(f)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> timelineItem(String entityType, String entityLabel, int versionNo,
                                             String opType, String reason, String diff,
                                             String createBy, Object createDate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entityType", entityType);
        m.put("entityLabel", entityLabel);
        m.put("versionNo", versionNo);
        m.put("opType", opType);
        m.put("changeReason", reason);
        m.put("diffSummary", diff);
        m.put("createBy", createBy);
        m.put("createDate", createDate);
        return m;
    }

    private String buildCreditDiff(MdmCustomerView oldRow, MdmCustomerView newRow) {
        StringBuilder sb = new StringBuilder();
        appendDiff(sb, "creditLimit", oldRow.getCreditLimit(), newRow.getCreditLimit());
        appendDiff(sb, "tempCreditLimit", oldRow.getTempCreditLimit(), newRow.getTempCreditLimit());
        appendDiff(sb, "tempExpireDate", oldRow.getTempExpireDate(), newRow.getTempExpireDate());
        appendDiff(sb, "lastReviewDate", oldRow.getLastReviewDate(), newRow.getLastReviewDate());
        appendDiff(sb, "compressedLimit", oldRow.getCompressedLimit(), newRow.getCompressedLimit());
        return sb.length() == 0 ? "（额度字段无变化）" : sb.toString();
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

    private void saveGroupSnapshot(MdmCustomerGroup source, String opType, String diffSummary, String reason) {
        int next = groupVersionDao.selectCount(new LambdaQueryWrapper<MdmCustomerGroupVersion>()
                .eq(MdmCustomerGroupVersion::getEntityId, source.getId())).intValue() + 1;
        MdmCustomerGroupVersion v = new MdmCustomerGroupVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source, GROUP_SNAPSHOT_FIELDS.split(",")));
        v.setDiffSummary(diffSummary);
        v.setOpType(opType);
        v.setChangeReason(reason);
        groupVersionDao.insert(v);
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

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
