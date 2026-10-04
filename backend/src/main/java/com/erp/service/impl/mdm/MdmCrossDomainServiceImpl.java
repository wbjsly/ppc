package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmCustomerGroupDao;
import com.erp.dao.mdm.MdmCustomerViewDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mdm.MdmPriceAgreementDao;
import com.erp.dao.mdm.MdmPriceAgreementLineDao;
import com.erp.dao.mdm.MdmPriceAgreementVersionDao;
import com.erp.dao.ops.MdmOutboxDao;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.mdm.MdmCustomerView;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mdm.MdmPriceAgreement;
import com.erp.entity.mdm.MdmPriceAgreementLine;
import com.erp.entity.mdm.MdmPriceAgreementVersion;
import com.erp.entity.ops.MdmOutboxEvent;
import com.erp.service.mdm.MdmCrossDomainService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class MdmCrossDomainServiceImpl implements MdmCrossDomainService {

    private static final String SNAPSHOT_FIELDS =
            "paCode,paName,agreementType,customerGroupId,customerViewId,effectiveDate,expireDate,status,stopReason";

    private static final Map<String, Integer> TYPE_PRIORITY = Map.of(
            "EXCLUSIVE", 3, "LADDER", 2, "TIME", 1);

    private final MdmOutboxDao outboxDao;
    private final MdmPriceAgreementDao agreementDao;
    private final MdmPriceAgreementLineDao lineDao;
    private final MdmPriceAgreementVersionDao versionDao;
    private final MdmCustomerGroupDao groupDao;
    private final MdmCustomerViewDao viewDao;
    private final MdmItemDao itemDao;
    private final ObjectMapper objectMapper;

    public MdmCrossDomainServiceImpl(MdmOutboxDao outboxDao,
                                     MdmPriceAgreementDao agreementDao,
                                     MdmPriceAgreementLineDao lineDao,
                                     MdmPriceAgreementVersionDao versionDao,
                                     MdmCustomerGroupDao groupDao,
                                     MdmCustomerViewDao viewDao,
                                     MdmItemDao itemDao,
                                     ObjectMapper objectMapper) {
        this.outboxDao = outboxDao;
        this.agreementDao = agreementDao;
        this.lineDao = lineDao;
        this.versionDao = versionDao;
        this.groupDao = groupDao;
        this.viewDao = viewDao;
        this.itemDao = itemDao;
        this.objectMapper = objectMapper;
    }

    // ---------- 事件流 ----------

    @Override
    public Map<String, Object> outboxPage(long current, long size, String eventType,
                                          String status, String keyword) {
        LambdaQueryWrapper<MdmOutboxEvent> qw = new LambdaQueryWrapper<MdmOutboxEvent>()
                .eq(isNotBlank(eventType), MdmOutboxEvent::getEventType, eventType)
                .eq(isNotBlank(status), MdmOutboxEvent::getStatus, status)
                .and(isNotBlank(keyword), w -> w
                        .like(MdmOutboxEvent::getIdempotencyKey, keyword)
                        .or().like(MdmOutboxEvent::getLegalEntityId, keyword)
                        .or().like(MdmOutboxEvent::getPayload, keyword))
                .orderByDesc(MdmOutboxEvent::getOccurredAt);
        var page = outboxDao.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size), qw);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        // 桩口径（spec：不得伪装投递成功）
        result.put("deliveryStub", true);
        result.put("deliveryNote", "消息总线未接入，PENDING 为预期态");
        return result;
    }

    @Override
    public Map<String, Object> outboxDetail(String id) {
        MdmOutboxEvent e = outboxDao.selectById(id);
        if (e == null) {
            throw new ServiceException(404, "事件不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("event", e);
        // 事件目录（spec：生产方/消费方/幂等键规则/Schema 版本登记）
        result.put("catalog", Map.of(
                "producer", "MDM（mdm-service）",
                "consumers", "销售域 SO 信用与价格检查 / 财务域应收 / CRM（均未接入，桩）",
                "idempotencyRule", "业务编码:vN（同键重放拒绝，C-0-06）",
                "schemaVersion", "v1"));
        result.put("deliveryStub", true);
        result.put("deliveryNote", "消息总线未接入，PENDING 为预期态");
        return result;
    }

    // ---------- 协议列表（含懒过期） ----------

    @Override
    public Map<String, Object> agreementPage(long current, long size, String keyword,
                                             String agreementType, String status) {
        try {
            sweepExpiredAgreements();
        } catch (Exception ex) {
            log.warn("agreement lazy sweep failed (non-blocking): {}", ex.getMessage());
        }
        LambdaQueryWrapper<MdmPriceAgreement> qw = new LambdaQueryWrapper<MdmPriceAgreement>()
                .eq(isNotBlank(agreementType), MdmPriceAgreement::getAgreementType, agreementType)
                .eq(isNotBlank(status), MdmPriceAgreement::getStatus, status)
                .and(isNotBlank(keyword), w -> w
                        .like(MdmPriceAgreement::getPaCode, keyword)
                        .or().like(MdmPriceAgreement::getPaName, keyword))
                .orderByDesc(MdmPriceAgreement::getPaCode);
        var page = agreementDao.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size), qw);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        return result;
    }

    @Override
    public MdmPriceAgreement getAgreement(String id) {
        MdmPriceAgreement a = agreementDao.selectById(id);
        if (a == null) {
            throw new ServiceException(404, "价格协议不存在");
        }
        a.setLines(linesOf(id)); // 详情回显（编辑弹窗行数据）
        return a;
    }

    // ---------- 创建 ----------

    @Override
    @Transactional
    public MdmPriceAgreement createAgreement(MdmPriceAgreement agreement, List<Map<String, Object>> lines) {
        requireHeadFields(agreement);
        requireAttachExactlyOne(agreement);
        requireLines(agreement, lines, null);

        agreement.setId(null);
        agreement.setPaCode(generateCode());
        agreement.setStatus(resolveCreateStatus(agreement));
        agreement.setStopReason(null);
        agreement.setVerNo(0);
        try {
            agreementDao.insert(agreement);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(409, "协议编码并发生成冲突，请重试");
        }
        insertLines(agreement.getId(), lines);
        saveSnapshot(agreement, "CREATE", "新建协议（" + typeName(agreement.getAgreementType())
                + "，挂靠 " + attachLabel(agreement) + "）", null);
        return agreement;
    }

    // ---------- 变更 ----------

    @Override
    @Transactional
    public MdmPriceAgreement updateAgreement(MdmPriceAgreement agreement, List<Map<String, Object>> lines) {
        MdmPriceAgreement stored = getAgreement(agreement.getId());
        if (isNotBlank(agreement.getPaCode()) && !agreement.getPaCode().equals(stored.getPaCode())) {
            throw new ServiceException(422, "协议编码创建后不可修改");
        }
        if ("2".equals(stored.getStatus()) || "3".equals(stored.getStatus())) {
            throw new ServiceException(422, "过期/停用协议仅可查看，不可编辑（可在状态恢复后编辑）");
        }
        String reason = isNotBlank(agreement.getChangeReason()) ? agreement.getChangeReason().trim() : "";
        if (reason.length() < 2) {
            throw new ServiceException(422, "变更原因必填（至少 2 字）");
        }
        requireHeadFields(agreement);
        // 挂靠锁定（改挂靠=换协议归属，须新建；类型与期间可改）
        if (!Objects.equals(stored.getCustomerGroupId(), agreement.getCustomerGroupId())
                || !Objects.equals(stored.getCustomerViewId(), agreement.getCustomerViewId())) {
            throw new ServiceException(422, "协议挂靠创建后不可修改（换挂靠请新建协议）");
        }
        requireAttachExactlyOne(agreement);
        if (agreement.getExpireDate() != null && agreement.getExpireDate().isBefore(agreement.getEffectiveDate())) {
            throw new ServiceException(422, "失效日期不得早于生效日期");
        }
        requireLines(agreement, lines, stored.getId());

        String diff = buildHeadDiff(stored, agreement);
        if (diff.isEmpty() && lines != null && !lines.isEmpty()) {
            diff = "价格行更新（" + lines.size() + " 行）";
        }
        LambdaUpdateWrapper<MdmPriceAgreement> uw = new LambdaUpdateWrapper<>();
        uw.eq(MdmPriceAgreement::getId, stored.getId())
                .eq(MdmPriceAgreement::getVerNo, stored.getVerNo())
                .set(MdmPriceAgreement::getPaName, agreement.getPaName())
                .set(MdmPriceAgreement::getAgreementType, agreement.getAgreementType())
                .set(MdmPriceAgreement::getEffectiveDate, agreement.getEffectiveDate())
                .set(MdmPriceAgreement::getExpireDate, agreement.getExpireDate())
                .set(MdmPriceAgreement::getStatus, resolveCreateStatus(agreement))
                .set(MdmPriceAgreement::getChangeReason, reason)
                .set(MdmPriceAgreement::getVerNo, stored.getVerNo() + 1);
        if (agreementDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        // 行全量替换（软删旧行 + 插新行，量小语义清晰）
        lineDao.delete(new LambdaQueryWrapper<MdmPriceAgreementLine>()
                .eq(MdmPriceAgreementLine::getPaId, stored.getId()));
        insertLines(stored.getId(), lines);
        MdmPriceAgreement after = getAgreement(stored.getId());
        saveSnapshot(after, "UPDATE", diff + "；原因：" + reason, reason);
        return after;
    }

    // ---------- 停用/恢复 ----------

    @Override
    @Transactional
    public MdmPriceAgreement stopAgreement(String id, String reason) {
        MdmPriceAgreement stored = getAgreement(id);
        String r = isNotBlank(reason) ? reason.trim() : "";
        if (r.length() < 2) {
            throw new ServiceException(422, "原因必填（至少 2 字）");
        }
        String toStatus;
        String opType;
        String diff;
        if ("0".equals(stored.getStatus()) || "1".equals(stored.getStatus())) {
            toStatus = "3";
            opType = "STOP";
            diff = "status: " + stored.getStatus() + " → 3（停用）；原因：" + r;
        } else if ("3".equals(stored.getStatus())) {
            // 恢复：按日期推算（design D3 主动决策，spec 未定义）
            toStatus = resolveCreateStatus(stored);
            opType = "RESTORE";
            diff = "status: 3 → " + toStatus + "（恢复）；原因：" + r;
        } else {
            throw new ServiceException(422, "已过期协议为终态，不可恢复");
        }
        LambdaUpdateWrapper<MdmPriceAgreement> uw = new LambdaUpdateWrapper<>();
        uw.eq(MdmPriceAgreement::getId, id)
                .eq(MdmPriceAgreement::getVerNo, stored.getVerNo())
                .set(MdmPriceAgreement::getStatus, toStatus)
                .set(MdmPriceAgreement::getStopReason, r)
                .set(MdmPriceAgreement::getVerNo, stored.getVerNo() + 1);
        if (agreementDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        MdmPriceAgreement after = getAgreement(id);
        saveSnapshot(after, opType, diff, r);
        return after;
    }

    @Override
    public List<MdmPriceAgreementVersion> agreementVersions(String entityId) {
        return versionDao.selectList(new LambdaQueryWrapper<MdmPriceAgreementVersion>()
                .eq(MdmPriceAgreementVersion::getEntityId, entityId)
                .orderByAsc(MdmPriceAgreementVersion::getVersionNo));
    }

    @Override
    public Map<String, Object> agreementDiff(String entityId, int from, int to) {
        MdmPriceAgreementVersion vFrom = findVersion(entityId, from);
        MdmPriceAgreementVersion vTo = findVersion(entityId, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", Map.of("versionNo", vFrom.getVersionNo(), "opType", vFrom.getOpType()));
        result.put("to", Map.of("versionNo", vTo.getVersionNo(), "opType", vTo.getOpType()));
        result.put("fields", compareSnapshots(vFrom.getSnapshotJson(), vTo.getSnapshotJson()));
        return result;
    }

    // ---------- 试算（design D4） ----------

    @Override
    public Map<String, Object> trial(String groupId, String viewId, String itemCode,
                                     BigDecimal qty, LocalDate date) {
        if (!isNotBlank(itemCode)) {
            throw new ServiceException(422, "SKU 必填");
        }
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ServiceException(422, "数量须大于 0");
        }
        LocalDate d = date == null ? LocalDate.now() : date;
        try {
            sweepExpiredAgreements();
        } catch (Exception ex) {
            log.warn("trial sweep failed: {}", ex.getMessage());
        }
        // 挂靠解析：viewId → (其集团, 其自身)；groupId → (集团, null)
        String resolvedGroupId;
        if (isNotBlank(viewId)) {
            MdmCustomerView v = viewDao.selectById(viewId);
            if (v == null) {
                throw new ServiceException(404, "法人视图不存在");
            }
            resolvedGroupId = v.getGroupId();
        } else if (isNotBlank(groupId)) {
            if (groupDao.selectById(groupId) == null) {
                throw new ServiceException(404, "客户集团视图不存在");
            }
            resolvedGroupId = groupId;
        } else {
            throw new ServiceException(422, "须指定客户集团或法人视图");
        }
        final String effGroupId = resolvedGroupId;
        final String effViewId = viewId;

        List<MdmPriceAgreement> candidates = agreementDao.selectList(
                new LambdaQueryWrapper<MdmPriceAgreement>()
                        .in(MdmPriceAgreement::getStatus, "0", "1")
                        .in(MdmPriceAgreement::getAgreementType, "EXCLUSIVE", "LADDER", "TIME")
                        .and(w -> {
                            if (isNotBlank(effViewId)) {
                                w.or().eq(MdmPriceAgreement::getCustomerViewId, effViewId);
                            }
                            w.or().eq(MdmPriceAgreement::getCustomerGroupId, effGroupId);
                        })
                        .le(MdmPriceAgreement::getEffectiveDate, d)
                        .and(w -> w.isNull(MdmPriceAgreement::getExpireDate)
                                .or().ge(MdmPriceAgreement::getExpireDate, d)));

        // 优先级：类型 DESC（EXCLUSIVE=3 最高）→ 法人级优先 → 生效日期 DESC
        candidates.sort(Comparator
                .comparingInt((MdmPriceAgreement a) -> TYPE_PRIORITY.getOrDefault(a.getAgreementType(), 0))
                .reversed()
                .thenComparing(a -> isNotBlank(effViewId)
                        && effViewId.equals(a.getCustomerViewId()) ? 0 : 1)
                .thenComparing(MdmPriceAgreement::getEffectiveDate, Comparator.reverseOrder()));

        List<String> reasons = new ArrayList<>();
        for (MdmPriceAgreement pa : candidates) {
            if ("3".equals(pa.getStatus()) || "2".equals(pa.getStatus())) {
                reasons.add(pa.getPaCode() + " 已停用/过期");
                continue;
            }
            if ("LADDER".equals(pa.getAgreementType())) {
                MdmPriceAgreementLine hit = findLadderLine(pa.getId(), itemCode, qty);
                if (hit == null) {
                    reasons.add(pa.getPaCode() + " 阶梯区间不命中（数量 " + qty + "）");
                    continue;
                }
                return trialHit(pa, hit, effViewId);
            }
            MdmPriceAgreementLine line = findFixedLine(pa.getId(), itemCode);
            if (line == null) {
                reasons.add(pa.getPaCode() + " 无该 SKU 价格行");
                continue;
            }
            return trialHit(pa, line, effViewId);
        }
        if (candidates.isEmpty()) {
            reasons.add("无生效协议覆盖所查日期（" + d + "）");
        }
        // 无适用协议明示（spec：非空对象）
        Map<String, Object> miss = new LinkedHashMap<>();
        miss.put("applicable", false);
        miss.put("matched", false);
        miss.put("message", "无适用协议");
        miss.put("reasons", reasons);
        miss.put("checkedTypes", "EXCLUSIVE > LADDER > TIME；法人级 > 集团级");
        return miss;
    }

    private Map<String, Object> trialHit(MdmPriceAgreement pa, MdmPriceAgreementLine line, String viewId) {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("applicable", true);
        hit.put("matched", true);
        hit.put("paCode", pa.getPaCode());
        hit.put("paName", pa.getPaName());
        hit.put("agreementType", pa.getAgreementType());
        hit.put("attachLevel", viewId != null && viewId.equals(pa.getCustomerViewId())
                ? "法人视图" : "客户集团");
        hit.put("itemCode", line.getItemCode());
        hit.put("unitPrice", line.getMinQty() != null
                ? line.getMinQty() + " ≤ qty ≤ " + line.getMaxQty() + " → " + line.getUnitPrice()
                : line.getUnitPrice());
        hit.put("effectiveDate", pa.getEffectiveDate());
        hit.put("expireDate", pa.getExpireDate());
        return hit;
    }

    /** LADDER：闭区间 MIN_QTY ≤ qty ≤ MAX_QTY（design D7） */
    private MdmPriceAgreementLine findLadderLine(String paId, String itemCode, BigDecimal qty) {
        for (MdmPriceAgreementLine l : linesOf(paId)) {
            if (!itemCode.equals(l.getItemCode()) || l.getMinQty() == null || l.getMaxQty() == null) {
                continue;
            }
            if (l.getMinQty().compareTo(qty) <= 0 && qty.compareTo(l.getMaxQty()) <= 0) {
                return l;
            }
        }
        return null;
    }

    private MdmPriceAgreementLine findFixedLine(String paId, String itemCode) {
        for (MdmPriceAgreementLine l : linesOf(paId)) {
            if (itemCode.equals(l.getItemCode()) && l.getUnitPrice() != null) {
                return l;
            }
        }
        return null;
    }

    private List<MdmPriceAgreementLine> linesOf(String paId) {
        return lineDao.selectList(new LambdaQueryWrapper<MdmPriceAgreementLine>()
                .eq(MdmPriceAgreementLine::getPaId, paId));
    }

    // ---------- 懒过期 ----------

    @Override
    @Transactional
    public int sweepExpiredAgreements() {
        int count = 0;
        List<MdmPriceAgreement> expired = agreementDao.selectList(
                new LambdaQueryWrapper<MdmPriceAgreement>()
                        .in(MdmPriceAgreement::getStatus, "0", "1")
                        .isNotNull(MdmPriceAgreement::getExpireDate)
                        .lt(MdmPriceAgreement::getExpireDate, LocalDate.now())
                        .last("LIMIT 200"));
        for (MdmPriceAgreement a : expired) {
            LambdaUpdateWrapper<MdmPriceAgreement> uw = new LambdaUpdateWrapper<>();
            uw.eq(MdmPriceAgreement::getId, a.getId())
                    .eq(MdmPriceAgreement::getVerNo, a.getVerNo())
                    .set(MdmPriceAgreement::getStatus, "2")
                    .set(MdmPriceAgreement::getVerNo, a.getVerNo() + 1);
            if (agreementDao.update(null, uw) == 0) {
                continue;
            }
            MdmPriceAgreement after = getAgreement(a.getId());
            saveSnapshot(after, "SWEEP_EXPIRE",
                    "status: " + a.getStatus() + " → 2（失效日期 " + a.getExpireDate() + " 已过，懒过期）",
                    "懒过期自动回写");
            count++;
        }
        return count;
    }

    // ---------- 私有工具 ----------

    private String generateCode() {
        String max = agreementDao.selectMaxCode();
        int next = 1;
        if (isNotBlank(max) && max.startsWith("PA-")) {
            try {
                next = Integer.parseInt(max.substring(3)) + 1;
            } catch (NumberFormatException ignore) {
                next = 1;
            }
        }
        return String.format("PA-%04d", next);
    }

    /** 创建时状态按日期推算：已过期 422；生效日 > 今天 → 0；否则 1 */
    private String resolveCreateStatus(MdmPriceAgreement a) {
        LocalDate today = LocalDate.now();
        if (a.getExpireDate() != null && a.getExpireDate().isBefore(today)) {
            throw new ServiceException(422, "失效日期早于今天，不能创建已过期协议");
        }
        if (a.getEffectiveDate().isAfter(today)) {
            return "0";
        }
        return "1";
    }

    private void requireHeadFields(MdmPriceAgreement a) {
        if (!isNotBlank(a.getPaName())) {
            throw new ServiceException(422, "协议名称不能为空");
        }
        if (!isNotBlank(a.getAgreementType())
                || !TYPE_PRIORITY.containsKey(a.getAgreementType())) {
            throw new ServiceException(422, "协议类型须为 EXCLUSIVE/LADDER/TIME 之一");
        }
        if (a.getEffectiveDate() == null) {
            throw new ServiceException(422, "生效日期必填");
        }
        if (a.getExpireDate() != null && a.getExpireDate().isBefore(a.getEffectiveDate())) {
            throw new ServiceException(422, "失效日期不得早于生效日期");
        }
    }

    /** 挂靠二选一（FR-4.1-6-2 两级共享）：恰好一个非空 */
    private void requireAttachExactlyOne(MdmPriceAgreement a) {
        boolean hasGroup = isNotBlank(a.getCustomerGroupId());
        boolean hasView = isNotBlank(a.getCustomerViewId());
        if (hasGroup == hasView) {
            throw new ServiceException(422, "挂靠须二选一：客户集团 或 法人视图（不得同时或同时为空）");
        }
        if (hasGroup && groupDao.selectById(a.getCustomerGroupId()) == null) {
            throw new ServiceException(422, "挂靠的客户集团不存在");
        }
        if (hasView && viewDao.selectById(a.getCustomerViewId()) == null) {
            throw new ServiceException(422, "挂靠的法人视图不存在");
        }
    }

    /** 行校验：≥1 行、SKU 启用、LADDER 闭区间不重叠、非阶梯单价必填且区间留空 */
    private void requireLines(MdmPriceAgreement a, List<Map<String, Object>> lines, String excludePaId) {
        if (lines == null || lines.isEmpty()) {
            throw new ServiceException(422, "至少一行价格行");
        }
        boolean ladder = "LADDER".equals(a.getAgreementType());
        List<MdmPriceAgreementLine> pool = new ArrayList<>();
        for (Map<String, Object> row : lines) {
            String itemCode = str(row.get("itemCode"));
            if (!isNotBlank(itemCode)) {
                throw new ServiceException(422, "价格行 SKU 必填");
            }
            MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                    .eq(MdmItem::getItemCode, itemCode));
            if (item == null) {
                throw new ServiceException(422, "SKU 不存在：" + itemCode);
            }
            if (!"1".equals(item.getStatus())) {
                throw new ServiceException(422, "SKU 非启用状态：" + itemCode);
            }
            BigDecimal price = dec(row.get("unitPrice"));
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ServiceException(422, "价格行单价须大于 0：" + itemCode);
            }
            BigDecimal min = dec(row.get("minQty"));
            BigDecimal max = dec(row.get("maxQty"));
            if (ladder) {
                if (min == null || max == null) {
                    throw new ServiceException(422, "量价阶梯行须填写数量区间：" + itemCode);
                }
                if (min.compareTo(max) > 0) {
                    throw new ServiceException(422, "阶梯下限不得大于上限：" + itemCode);
                }
            } else if (min != null || max != null) {
                throw new ServiceException(422, "非阶梯类型不得填写数量区间：" + itemCode);
            }
            MdmPriceAgreementLine tmp = new MdmPriceAgreementLine();
            tmp.setItemCode(itemCode);
            tmp.setUnitPrice(price);
            tmp.setMinQty(min);
            tmp.setMaxQty(max);
            pool.add(tmp);
        }
        // 同 SKU 闭区间重叠校验（含既有行）
        for (int i = 0; i < pool.size(); i++) {
            for (int j = i + 1; j < pool.size(); j++) {
                requireNoOverlap(pool.get(i), pool.get(j));
            }
        }
        if (ladder && excludePaId != null) {
            for (MdmPriceAgreementLine old : linesOf(excludePaId)) {
                for (MdmPriceAgreementLine cur : pool) {
                    requireNoOverlap(old, cur);
                }
            }
        }
    }

    private void requireNoOverlap(MdmPriceAgreementLine a, MdmPriceAgreementLine b) {
        if (a.getMinQty() == null || b.getMinQty() == null
                || !a.getItemCode().equals(b.getItemCode())) {
            return;
        }
        // 闭区间重叠：minA <= maxB && minB <= maxA
        boolean overlap = a.getMinQty().compareTo(b.getMaxQty()) <= 0
                && b.getMinQty().compareTo(a.getMaxQty()) <= 0;
        if (overlap) {
            throw new ServiceException(422, "同 SKU 阶梯区间重叠（闭区间）：[" + a.getMinQty() + ","
                    + a.getMaxQty() + "] 与 [" + b.getMinQty() + "," + b.getMaxQty() + "] → "
                    + a.getItemCode());
        }
    }

    private void insertLines(String paId, List<Map<String, Object>> lines) {
        for (Map<String, Object> row : lines) {
            MdmPriceAgreementLine l = new MdmPriceAgreementLine();
            l.setPaId(paId);
            l.setItemCode(str(row.get("itemCode")));
            l.setUnitPrice(dec(row.get("unitPrice")));
            l.setMinQty(dec(row.get("minQty")));
            l.setMaxQty(dec(row.get("maxQty")));
            lineDao.insert(l);
        }
    }

    private void saveSnapshot(MdmPriceAgreement source, String opType, String diff, String reason) {
        int next = versionDao.selectCount(new LambdaQueryWrapper<MdmPriceAgreementVersion>()
                .eq(MdmPriceAgreementVersion::getEntityId, source.getId())).intValue() + 1;
        MdmPriceAgreementVersion v = new MdmPriceAgreementVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source));
        v.setDiffSummary(diff);
        v.setOpType(opType);
        v.setChangeReason(reason);
        versionDao.insert(v);
    }

    private MdmPriceAgreementVersion findVersion(String entityId, int versionNo) {
        MdmPriceAgreementVersion v = versionDao.selectOne(
                new LambdaQueryWrapper<MdmPriceAgreementVersion>()
                        .eq(MdmPriceAgreementVersion::getEntityId, entityId)
                        .eq(MdmPriceAgreementVersion::getVersionNo, versionNo));
        if (v == null) {
            throw new ServiceException(404, "版本不存在：" + entityId + " V" + versionNo);
        }
        return v;
    }

    private String buildHeadDiff(MdmPriceAgreement a, MdmPriceAgreement b) {
        StringBuilder sb = new StringBuilder();
        append(sb, "paName", a.getPaName(), b.getPaName());
        append(sb, "agreementType", a.getAgreementType(), b.getAgreementType());
        append(sb, "effectiveDate", a.getEffectiveDate(), b.getEffectiveDate());
        append(sb, "expireDate", a.getExpireDate(), b.getExpireDate());
        append(sb, "status", a.getStatus(), b.getStatus());
        return sb.toString();
    }

    private void append(StringBuilder sb, String field, Object x, Object y) {
        String a = x == null ? "" : String.valueOf(x);
        String b = y == null ? "" : String.valueOf(y);
        if (Objects.equals(a, b)) {
            return;
        }
        if (sb.length() > 0) sb.append("；");
        sb.append(field).append(": ").append(a.isEmpty() ? "（空）" : a)
                .append(" → ").append(b.isEmpty() ? "（空）" : b);
    }

    private List<Map<String, String>> compareSnapshots(String fromJson, String toJson) {
        JsonTree from = read(fromJson);
        JsonTree to = read(toJson);
        List<Map<String, String>> fields = new ArrayList<>();
        for (String field : union(from.keys(), to.keys())) {
            String a = from.get(field);
            String b = to.get(field);
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

    private String toJson(MdmPriceAgreement entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String f : SNAPSHOT_FIELDS.split(",")) {
            try {
                var getter = entity.getClass().getMethod("get" + Character.toUpperCase(f.charAt(0)) + f.substring(1));
                Object val = getter.invoke(entity);
                map.put(f, val == null ? "" : String.valueOf(val));
            } catch (ReflectiveOperationException ignore) {
                map.put(f, "");
            }
        }
        // 价格行随头快照记录（试算可追溯）
        map.put("lines", linesOf(entity.getId()).stream().map(l ->
                l.getItemCode() + "@" + l.getUnitPrice()
                        + (l.getMinQty() != null ? "[" + l.getMinQty() + "-" + l.getMaxQty() + "]" : "")
        ).toList());
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new ServiceException(500, "快照序列化失败");
        }
    }

    // 极简 JSON 文本读取（快照均为平面对象，避免引大依赖）
    private record JsonTree(Map<String, String> data) {
        String get(String k) { return data.getOrDefault(k, ""); }
        java.util.Set<String> keys() { return data.keySet(); }
    }

    private JsonTree read(String json) {
        Map<String, String> data = new LinkedHashMap<>();
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(
                    json == null || json.isEmpty() ? "{}" : json);
            node.fields().forEachRemaining(en ->
                    data.put(en.getKey(), en.getValue().isNull() ? "" : en.getValue().asText()));
        } catch (JsonProcessingException e) {
            throw new ServiceException(500, "快照解析失败");
        }
        return new JsonTree(data);
    }

    private List<String> union(java.util.Set<String> a, java.util.Set<String> b) {
        List<String> keys = new ArrayList<>(a);
        b.forEach(k -> {
            if (!keys.contains(k)) keys.add(k);
        });
        return keys;
    }

    private String typeName(String t) {
        return "EXCLUSIVE".equals(t) ? "客户专属价"
                : "LADDER".equals(t) ? "量价阶梯" : "时间价";
    }

    private String attachLabel(MdmPriceAgreement a) {
        return isNotBlank(a.getCustomerViewId()) ? "法人视图" : "客户集团";
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        return new BigDecimal(String.valueOf(o).trim());
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
