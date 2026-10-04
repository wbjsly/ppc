package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.common.SimilarityUtil;
import com.erp.dao.mdm.MdmLegalEntityDao;
import com.erp.dao.mdm.MdmSupplierCertDao;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.mdm.MdmSupplierVersionDao;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.mdm.MdmSupplierCert;
import com.erp.entity.mdm.MdmSupplierVersion;
import com.erp.ops.OutboxPublisher;
import com.erp.service.mdm.MdmSupplierService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
public class MdmSupplierServiceImpl implements MdmSupplierService {

    private static final String SNAPSHOT_FIELDS =
            "supplierCode,supplierName,address,contactName,contactPhone,legalEntityId," +
            "bankName,taxNo,paymentTerms,blacklistResult,esgRating,status,reviewReason";
    /** 六态（023 五态 + 024 MERGED 合并终态） */
    private static final String ST_PENDING = "PENDING";
    private static final String ST_QUALIFIED = "QUALIFIED";
    private static final String ST_FROZEN = "FROZEN";
    private static final String ST_DISABLED = "DISABLED";
    private static final String ST_CERT_EXPIRED = "CERT_EXPIRED";
    private static final String ST_MERGED = "MERGED";

    /** 合法迁移矩阵（design D2 单点守卫；REVIEW 为独立动作不在矩阵内） */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            ST_QUALIFIED, Set.of(ST_FROZEN, ST_DISABLED),
            ST_FROZEN, Set.of(ST_QUALIFIED),
            ST_DISABLED, Set.of(ST_QUALIFIED, ST_CERT_EXPIRED),
            ST_CERT_EXPIRED, Set.of(ST_DISABLED));

    private final MdmSupplierDao supplierDao;
    private final MdmSupplierCertDao certDao;
    private final MdmSupplierVersionDao versionDao;
    private final MdmLegalEntityDao legalEntityDao;
    private final OutboxPublisher outbox;
    private final ObjectMapper objectMapper;

    @Value("${app.mdm.cert-expiry-alert-days:30}")
    private int certExpiryAlertDays;

    public MdmSupplierServiceImpl(MdmSupplierDao supplierDao,
                                  MdmSupplierCertDao certDao,
                                  MdmSupplierVersionDao versionDao,
                                  MdmLegalEntityDao legalEntityDao,
                                  OutboxPublisher outbox,
                                  ObjectMapper objectMapper) {
        this.supplierDao = supplierDao;
        this.certDao = certDao;
        this.versionDao = versionDao;
        this.legalEntityDao = legalEntityDao;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    // ---------- 分页/详情 ----------

    @Override
    public Page<MdmSupplier> page(long current, long size, String keyword, String status) {
        try {
            sweepExpiredCerts(); // 懒巡检前置（D3）
        } catch (Exception e) {
            log.warn("supplier cert sweep failed (non-blocking): {}", e.getMessage());
        }
        LambdaQueryWrapper<MdmSupplier> qw = new LambdaQueryWrapper<MdmSupplier>()
                .eq(isNotBlank(status), MdmSupplier::getStatus, status)
                .and(isNotBlank(keyword), w -> w
                        .like(MdmSupplier::getSupplierCode, keyword)
                        .or().like(MdmSupplier::getSupplierName, keyword)
                        .or().like(MdmSupplier::getTaxNo, keyword))
                .orderByAsc(MdmSupplier::getSupplierCode);
        return supplierDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public MdmSupplier getById(String id) {
        MdmSupplier s = supplierDao.selectById(id);
        if (s == null) {
            throw new ServiceException(404, "供应商不存在");
        }
        return s;
    }

    // ---------- 建档（行 550 + C-4.1-07 + 黑名单硬阻断） ----------

    @Override
    @Transactional
    public MdmSupplier create(MdmSupplier supplier, boolean forceCreate) {
        requireFields(supplier);
        // 合规前置：黑名单命中硬阻断（建档入口）
        if ("HIT".equals(supplier.getBlacklistResult())) {
            throw new ServiceException(422, "黑名单筛查命中，合规前置不通过，禁止建档");
        }
        // 税号占用（C-4.1-07 精确维）
        if (isNotBlank(supplier.getTaxNo())) {
            String holder = supplierDao.findTaxNoHolder(supplier.getTaxNo(), "");
            if (holder != null) {
                throw new ServiceException(422, "税号已存在于供应商 " + holder + "，须先处理重复");
            }
        }
        // 名称查重（编辑距离 ≤3 → 最近 3 条）
        List<MdmSupplier> similar = findSimilar(supplier.getSupplierName());
        if (!similar.isEmpty() && !forceCreate) {
            StringBuilder sb = new StringBuilder();
            for (MdmSupplier s : similar) {
                if (sb.length() > 0) sb.append("、");
                sb.append(s.getSupplierCode()).append(" ").append(s.getSupplierName());
            }
            throw new ServiceException(409, "名称高度相似：" + sb + "（确认非重复请带 forceCreate+差异说明）");
        }
        supplier.setId(null);
        supplier.setSupplierCode(generateCode());
        supplier.setStatus(ST_PENDING);
        supplier.setVerNo(0);
        try {
            supplierDao.insert(supplier);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(409, "供应商编码并发生成冲突，请重试");
        }
        saveSnapshot(supplier, "CREATE", "新建供应商（待审核）", null);
        outbox.publishEvent("MDM.SUPPLIER.CREATED", supplier.getSupplierCode(),
                supplier.getVerNo() + 1, supplier.getLegalEntityId(), null,
                Map.of("supplierName", supplier.getSupplierName(),
                        "taxNo", supplier.getTaxNo() == null ? "" : supplier.getTaxNo()));
        return supplier;
    }

    // ---------- 变更 ----------

    @Override
    @Transactional
    public MdmSupplier update(MdmSupplier supplier) {
        MdmSupplier stored = getById(supplier.getId());
        if (isNotBlank(supplier.getSupplierCode())
                && !supplier.getSupplierCode().equals(stored.getSupplierCode())) {
            throw new ServiceException(422, "供应商编码创建后不可修改");
        }
        // MERGED 终态锁定（C-4.1-10，024 扩展）
        if (ST_MERGED.equals(stored.getStatus())) {
            throw new ServiceException(422, "已合并供应商为终态，编码永久锁定（C-4.1-10），不可变更");
        }
        requireFields(supplier);
        // 合规前置：变更入口同样拦截 HIT
        if ("HIT".equals(supplier.getBlacklistResult())) {
            throw new ServiceException(422, "黑名单筛查命中，合规前置不通过，禁止保存");
        }
        String reason = isNotBlank(supplier.getChangeReason()) ? supplier.getChangeReason().trim() : "";
        if (reason.length() < 2) {
            throw new ServiceException(422, "变更原因必填（至少 2 字）");
        }
        if (isNotBlank(supplier.getTaxNo())) {
            String holder = supplierDao.findTaxNoHolder(supplier.getTaxNo(), stored.getId());
            if (holder != null) {
                throw new ServiceException(422, "税号已存在于供应商 " + holder + "，须先处理重复");
            }
        }
        String diff = buildDiff(stored, supplier);
        if (diff.isEmpty()) {
            throw new ServiceException(422, "无变更内容");
        }
        LambdaUpdateWrapper<MdmSupplier> uw = new LambdaUpdateWrapper<>();
        uw.eq(MdmSupplier::getId, stored.getId())
                .eq(MdmSupplier::getVerNo, stored.getVerNo())
                .set(MdmSupplier::getSupplierName, supplier.getSupplierName())
                .set(MdmSupplier::getAddress, supplier.getAddress())
                .set(MdmSupplier::getContactName, supplier.getContactName())
                .set(MdmSupplier::getContactPhone, supplier.getContactPhone())
                .set(MdmSupplier::getLegalEntityId, supplier.getLegalEntityId())
                .set(MdmSupplier::getBankName, supplier.getBankName())
                .set(MdmSupplier::getTaxNo, supplier.getTaxNo())
                .set(MdmSupplier::getPaymentTerms, supplier.getPaymentTerms())
                .set(MdmSupplier::getBlacklistResult, supplier.getBlacklistResult())
                .set(MdmSupplier::getEsgRating, supplier.getEsgRating())
                // changeReason 为 @TableField(exist=false)，不可进 update wrapper（MP lambda 缓存异常）
                .set(MdmSupplier::getVerNo, stored.getVerNo() + 1);
        if (supplierDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        MdmSupplier after = getById(stored.getId());
        saveSnapshot(after, "UPDATE", diff + "；原因：" + reason, reason);
        outbox.publish("MDM.SUPPLIER.UPDATED", after.getSupplierCode(),
                after.getVerNo() + 1, after.getLegalEntityId(), diff);
        return after;
    }

    // ---------- 单步审核 ----------

    @Override
    @Transactional
    public MdmSupplier review(String id, String result, String reason) {
        MdmSupplier stored = getById(id);
        if (!ST_PENDING.equals(stored.getStatus())) {
            throw new ServiceException(422, "仅待审核供应商可执行审核，当前状态：" + stored.getStatus());
        }
        String r = isNotBlank(reason) ? reason.trim() : "";
        if (r.length() < 2) {
            throw new ServiceException(422, "审核原因必填（至少 2 字）");
        }
        boolean approved = "APPROVED".equals(result);
        if (approved) {
            // 防带病进合格（D3 收紧）：存在过期证照 422
            if (certDao.selectEarliestExpire(id) != null
                    && certDao.selectEarliestExpire(id).isBefore(LocalDate.now())) {
                throw new ServiceException(422, "存在已过期资质证照，不可审核通过，请先更新证照");
            }
            LambdaUpdateWrapper<MdmSupplier> uw = new LambdaUpdateWrapper<>();
            uw.eq(MdmSupplier::getId, id)
                    .eq(MdmSupplier::getVerNo, stored.getVerNo())
                    .set(MdmSupplier::getStatus, ST_QUALIFIED)
                    .set(MdmSupplier::getReviewReason, r)
                    .set(MdmSupplier::getVerNo, stored.getVerNo() + 1);
            if (supplierDao.update(null, uw) == 0) {
                throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
            }
            MdmSupplier after = getById(id);
            saveSnapshot(after, "REVIEW", "PENDING → QUALIFIED（审核通过）；原因：" + r, r);
            outbox.publish("MDM.SUPPLIER.REVIEWED", after.getSupplierCode(),
                    after.getVerNo() + 1, after.getLegalEntityId(), "审核通过：" + r);
            return after;
        }
        // 驳回：状态保持 PENDING，留痕
        LambdaUpdateWrapper<MdmSupplier> uw = new LambdaUpdateWrapper<>();
        uw.eq(MdmSupplier::getId, id)
                .eq(MdmSupplier::getVerNo, stored.getVerNo())
                .set(MdmSupplier::getReviewReason, "驳回：" + r)
                .set(MdmSupplier::getVerNo, stored.getVerNo() + 1);
        if (supplierDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        MdmSupplier after = getById(id);
        saveSnapshot(after, "REJECTED", "驳回（保持 PENDING）；原因：" + r, r);
        outbox.publish("MDM.SUPPLIER.REJECTED", after.getSupplierCode(),
                after.getVerNo() + 1, after.getLegalEntityId(), "驳回：" + r);
        return after;
    }

    // ---------- 状态迁移（D2 矩阵单点守卫） ----------

    @Override
    @Transactional
    public MdmSupplier changeStatus(String id, String toStatus, String reason) {
        MdmSupplier stored = getById(id);
        requireReason(reason);
        if (ST_PENDING.equals(stored.getStatus())) {
            throw new ServiceException(422, "待审核供应商仅可执行审核动作，当前状态：PENDING");
        }
        String from = stored.getStatus();
        // 启用恢复态判定（D5）：DISABLED → 有证照过期则回 CERT_EXPIRED
        String target = toStatus;
        if (ST_DISABLED.equals(from) && ST_QUALIFIED.equals(toStatus)) {
            target = resolveAfterRelease(id);
        }
        if (ST_CERT_EXPIRED.equals(from) && ST_QUALIFIED.equals(toStatus)) {
            throw new ServiceException(422, "证照过期态须通过「证照核验解除」恢复，不能直接启用");
        }
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(target)) {
            throw new ServiceException(422, "非法状态迁移：" + from + " → " + target
                    + "（允许动作：" + ALLOWED.getOrDefault(from, Set.of()) + "）");
        }
        LambdaUpdateWrapper<MdmSupplier> uw = new LambdaUpdateWrapper<>();
        uw.eq(MdmSupplier::getId, id)
                .eq(MdmSupplier::getVerNo, stored.getVerNo())
                .set(MdmSupplier::getStatus, target)
                .set(MdmSupplier::getVerNo, stored.getVerNo() + 1);
        if (supplierDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        MdmSupplier after = getById(id);
        String opType = opTypeOf(from, target);
        saveSnapshot(after, opType, "status: " + from + " → " + target + "；原因：" + reason.trim(), reason.trim());
        outbox.publish("MDM.SUPPLIER." + opType, after.getSupplierCode(),
                after.getVerNo() + 1, after.getLegalEntityId(),
                from + " → " + target + "；原因：" + reason.trim());
        return after;
    }

    /** 启用恢复态（D5）：存在过期证照 → CERT_EXPIRED，否则 QUALIFIED（禁回 PENDING） */
    private String resolveAfterRelease(String id) {
        LocalDate earliest = certDao.selectEarliestExpire(id);
        if (earliest != null && earliest.isBefore(LocalDate.now())) {
            return ST_CERT_EXPIRED;
        }
        return ST_QUALIFIED;
    }

    private String opTypeOf(String from, String to) {
        if (ST_FROZEN.equals(to)) return "FROZEN";
        if (ST_FROZEN.equals(from)) return "UNFROZEN";
        if (ST_DISABLED.equals(to)) return "DISABLED";
        if (ST_DISABLED.equals(from)) return "ENABLED";
        if (ST_CERT_EXPIRED.equals(to)) return "CERT_EXPIRED";
        return "UPDATE";
    }

    // ---------- 证照 ----------

    @Override
    @Transactional
    public MdmSupplierCert saveCert(MdmSupplierCert cert) {
        MdmSupplier supplier = getById(cert.getSupplierId()); // 存在性校验
        if (ST_MERGED.equals(supplier.getStatus())) {
            throw new ServiceException(422, "已合并供应商为终态，不可维护证照");
        }
        if (cert.getExpireDate() == null) {
            throw new ServiceException(422, "证照有效期必填");
        }
        if (cert.getCertType() == null || cert.getCertType().isBlank()) {
            throw new ServiceException(422, "证照类型必填");
        }
        boolean creating = cert.getId() == null;
        if (creating) {
            cert.setId(null);
            cert.setVerNo(0);
            certDao.insert(cert);
        } else {
            MdmSupplierCert stored = certDao.selectById(cert.getId());
            if (stored == null) {
                throw new ServiceException(404, "证照不存在");
            }
            LambdaUpdateWrapper<MdmSupplierCert> uw = new LambdaUpdateWrapper<>();
            uw.eq(MdmSupplierCert::getId, cert.getId())
                    .eq(MdmSupplierCert::getVerNo, stored.getVerNo())
                    .set(MdmSupplierCert::getCertType, cert.getCertType())
                    .set(MdmSupplierCert::getCertNo, cert.getCertNo())
                    .set(MdmSupplierCert::getIssueDate, cert.getIssueDate())
                    .set(MdmSupplierCert::getExpireDate, cert.getExpireDate())
                    .set(MdmSupplierCert::getVerifyNote, cert.getVerifyNote())
                    .set(MdmSupplierCert::getVerNo, stored.getVerNo() + 1);
            if (certDao.update(null, uw) == 0) {
                throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
            }
        }
        MdmSupplierCert after = certDao.selectById(cert.getId());
        // 证照增改入供应商版本快照（spec：证照增改 MUST 入供应商版本快照）
        MdmSupplier fresh = getById(supplier.getId());
        saveSnapshot(fresh, "UPDATE",
                (creating ? "新增证照 " : "更新证照 ") + after.getCertType()
                        + " 有效期至 " + after.getExpireDate(), null);
        outbox.publishEvent("MDM.SUPPLIER.UPDATED", fresh.getSupplierCode() + "@cert",
                fresh.getVerNo() + 1, fresh.getLegalEntityId(),
                (creating ? "新增证照 " : "更新证照 ") + after.getCertType(),
                Map.of("certId", after.getId(), "expireDate", String.valueOf(after.getExpireDate())));
        return after;
    }

    @Override
    @Transactional
    public void deleteCert(String certId) {
        MdmSupplierCert cert = certDao.selectById(certId);
        if (cert == null) {
            throw new ServiceException(404, "证照不存在");
        }
        certDao.deleteById(certId); // @TableLogic 软删（基线禁硬删）
        MdmSupplier fresh = getById(cert.getSupplierId());
        saveSnapshot(fresh, "UPDATE", "删除证照 " + cert.getCertType(), null);
    }

    @Override
    public List<MdmSupplierCert> certs(String supplierId) {
        return certDao.selectList(new LambdaQueryWrapper<MdmSupplierCert>()
                .eq(MdmSupplierCert::getSupplierId, supplierId)
                .orderByAsc(MdmSupplierCert::getExpireDate));
    }

    /** 证照核验解除（D4）：前置 CERT_EXPIRED + 存在未来有效证照 → QUALIFIED */
    @Override
    @Transactional
    public MdmSupplier verifyCerts(String id, String reason) {
        MdmSupplier stored = getById(id);
        if (!ST_CERT_EXPIRED.equals(stored.getStatus())) {
            throw new ServiceException(422, "仅证照过期状态可执行核验解除，当前：" + stored.getStatus());
        }
        String r = isNotBlank(reason) ? reason.trim() : "";
        if (r.length() < 2) {
            throw new ServiceException(422, "核验原因必填（至少 2 字）");
        }
        if (certDao.countValidCerts(id, LocalDate.now()) == 0) {
            throw new ServiceException(422, "无有效证照（有效期均 ≤ 今天），请先更新证照再核验");
        }
        LambdaUpdateWrapper<MdmSupplier> uw = new LambdaUpdateWrapper<>();
        uw.eq(MdmSupplier::getId, id)
                .eq(MdmSupplier::getVerNo, stored.getVerNo())
                .set(MdmSupplier::getStatus, ST_QUALIFIED)
                .set(MdmSupplier::getVerNo, stored.getVerNo() + 1);
        if (supplierDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        MdmSupplier after = getById(id);
        saveSnapshot(after, "CERT_RENEWED", "CERT_EXPIRED → QUALIFIED（证照核验解除）；原因：" + r, r);
        outbox.publish("MDM.SUPPLIER.CERT_RENEWED", after.getSupplierCode(),
                after.getVerNo() + 1, after.getLegalEntityId(), "核验解除：" + r);
        return after;
    }

    // ---------- 影响分析 ----------

    @Override
    public Map<String, Object> impact(String id) {
        try {
            sweepExpiredCerts();
        } catch (Exception e) {
            log.warn("impact sweep failed: {}", e.getMessage());
        }
        MdmSupplier s = getById(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", s.getStatus());
        LocalDate earliest = certDao.selectEarliestExpire(id);
        result.put("earliestExpire", earliest);
        result.put("nearExpiry", earliest != null && !earliest.isBefore(LocalDate.now())
                && earliest.plusDays(certExpiryAlertDays).isAfter(LocalDate.now()));
        List<Map<String, String>> expired = new ArrayList<>();
        for (MdmSupplierCert c : certDao.findExpired(id)) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("certType", c.getCertType());
            row.put("certNo", c.getCertNo());
            row.put("expireDate", String.valueOf(c.getExpireDate()));
            expired.add(row);
        }
        result.put("expiredCerts", expired);
        // PO 桩（C-4.1-13 交期比对 / BR-4.2-18 阻断留采购域）
        result.put("downstream", List.of());
        result.put("downstreamStub", true);
        result.put("downstreamNote", "采购域模块未接入，PO 引用与交期比对暂不计入");
        return result;
    }

    @Override
    public List<Map<String, String>> options() {
        try {
            sweepExpiredCerts(); // 下拉口径同步（D3 触发点）
        } catch (Exception e) {
            log.warn("options sweep failed: {}", e.getMessage());
        }
        List<Map<String, String>> options = new ArrayList<>();
        for (MdmSupplier s : supplierDao.selectList(new LambdaQueryWrapper<MdmSupplier>()
                .eq(MdmSupplier::getStatus, ST_QUALIFIED)
                .orderByAsc(MdmSupplier::getSupplierCode))) {
            Map<String, String> o = new LinkedHashMap<>();
            o.put("id", s.getId());
            o.put("code", s.getSupplierCode());
            o.put("name", s.getSupplierName());
            options.add(o);
        }
        return options;
    }

    // ---------- 版本 ----------

    @Override
    public List<MdmSupplierVersion> versions(String entityId) {
        return versionDao.selectList(new LambdaQueryWrapper<MdmSupplierVersion>()
                .eq(MdmSupplierVersion::getEntityId, entityId)
                .orderByAsc(MdmSupplierVersion::getVersionNo));
    }

    @Override
    public Map<String, Object> diff(String entityId, int from, int to) {
        MdmSupplierVersion vFrom = findVersion(entityId, from);
        MdmSupplierVersion vTo = findVersion(entityId, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", Map.of("versionNo", vFrom.getVersionNo(), "opType", vFrom.getOpType()));
        result.put("to", Map.of("versionNo", vTo.getVersionNo(), "opType", vTo.getOpType()));
        result.put("fields", compareSnapshots(vFrom.getSnapshotJson(), vTo.getSnapshotJson()));
        return result;
    }

    // ---------- 懒巡检（D3） ----------

    @Override
    @Transactional
    public int sweepExpiredCerts() {
        int count = 0;
        LocalDate today = LocalDate.now();
        for (MdmSupplier s : supplierDao.findCertExpiredCandidates()) {
            if (ST_CERT_EXPIRED.equals(s.getStatus()) || ST_PENDING.equals(s.getStatus())) {
                continue; // 幂等 + PENDING 不参与（D3）
            }
            LambdaUpdateWrapper<MdmSupplier> uw = new LambdaUpdateWrapper<>();
            uw.eq(MdmSupplier::getId, s.getId())
                    .eq(MdmSupplier::getVerNo, s.getVerNo())
                    .set(MdmSupplier::getStatus, ST_CERT_EXPIRED)
                    .set(MdmSupplier::getVerNo, s.getVerNo() + 1);
            if (supplierDao.update(null, uw) == 0) {
                continue;
            }
            LocalDate earliest = certDao.selectEarliestExpire(s.getId());
            MdmSupplier after = getById(s.getId());
            saveSnapshot(after, "CERT_EXPIRED",
                    "status: " + s.getStatus() + " → CERT_EXPIRED（证照 " + earliest + " 已过期，行 805 到期受限）",
                    "证照到期自动受限");
            outbox.publish("MDM.SUPPLIER.CERT_EXPIRED", after.getSupplierCode(),
                    after.getVerNo() + 1, after.getLegalEntityId(),
                    "证照到期自动受限：" + earliest + "；today=" + today);
            count++;
        }
        return count;
    }

    // ---------- 私有工具 ----------

    private void requireFields(MdmSupplier s) {
        if (!isNotBlank(s.getSupplierName())) {
            throw new ServiceException(422, "供应商名称不能为空");
        }
        if (s.getSupplierName().contains("<") || s.getSupplierName().contains(">")
                || s.getSupplierName().contains("&")) {
            throw new ServiceException(422, "供应商名称含特殊字符，请去除 < > &");
        }
        if (!isNotBlank(s.getAddress())) {
            throw new ServiceException(422, "地址不能为空（基本信息）");
        }
        if (!isNotBlank(s.getBankName())) {
            throw new ServiceException(422, "开户行不能为空（财务信息）");
        }
        if (!isNotBlank(s.getTaxNo())) {
            throw new ServiceException(422, "税号不能为空（财务信息）");
        }
        if (!isNotBlank(s.getPaymentTerms())) {
            throw new ServiceException(422, "付款条件不能为空（财务信息）");
        }
        if (!isNotBlank(s.getBlacklistResult())) {
            throw new ServiceException(422, "黑名单筛查结果必填（合规信息）");
        }
        if (isNotBlank(s.getLegalEntityId())) {
            var le = legalEntityDao.selectById(s.getLegalEntityId());
            if (le == null) {
                throw new ServiceException(422, "挂靠法人主体不存在");
            }
            if (!"1".equals(le.getStatus())) {
                throw new ServiceException(422, "挂靠法人主体已停用：" + le.getLeCode());
            }
        }
    }

    private String generateCode() {
        String max = supplierDao.selectMaxCode();
        int next = 1;
        if (isNotBlank(max) && max.startsWith("SUP-")) {
            try {
                next = Integer.parseInt(max.substring(4)) + 1;
            } catch (NumberFormatException ignore) {
                next = 1;
            }
        }
        return String.format("SUP-%04d", next);
    }

    private List<MdmSupplier> findSimilar(String name) {
        if (!isNotBlank(name)) {
            return List.of();
        }
        long total = supplierDao.selectCount(null);
        List<MdmSupplier> pool;
        if (total <= 5000) {
            pool = supplierDao.selectList(new LambdaQueryWrapper<MdmSupplier>()
                    .orderByAsc(MdmSupplier::getSupplierCode));
        } else {
            String head = name.substring(0, Math.min(4, name.length()));
            pool = supplierDao.selectList(new LambdaQueryWrapper<MdmSupplier>()
                    .like(MdmSupplier::getSupplierName, head)
                    .orderByAsc(MdmSupplier::getSupplierCode)
                    .last("LIMIT 20"));
        }
        return SimilarityUtil.similarWithin(pool, name, 3, 3, MdmSupplier::getSupplierName);
    }

    private void requireReason(String reason) {
        if (!isNotBlank(reason)) {
            throw new ServiceException(422, "操作原因必填");
        }
    }

    /** 供合并服务复用的快照桥接（同包 MdmSupplierMergeServiceImpl 调用，避免快照逻辑双份漂移） */
    void saveSnapshotFor(MdmSupplier source, String opType, String diff, String reason) {
        saveSnapshot(source, opType, diff, reason);
    }

    private void saveSnapshot(MdmSupplier source, String opType, String diff, String reason) {
        int next = versionDao.selectCount(new LambdaQueryWrapper<MdmSupplierVersion>()
                .eq(MdmSupplierVersion::getEntityId, source.getId())).intValue() + 1;
        MdmSupplierVersion v = new MdmSupplierVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source, SNAPSHOT_FIELDS.split(",")));
        v.setDiffSummary(diff);
        v.setOpType(opType);
        v.setChangeReason(reason);
        versionDao.insert(v);
    }

    private MdmSupplierVersion findVersion(String entityId, int versionNo) {
        MdmSupplierVersion v = versionDao.selectOne(
                new LambdaQueryWrapper<MdmSupplierVersion>()
                        .eq(MdmSupplierVersion::getEntityId, entityId)
                        .eq(MdmSupplierVersion::getVersionNo, versionNo));
        if (v == null) {
            throw new ServiceException(404, "版本不存在：" + entityId + " V" + versionNo);
        }
        return v;
    }

    private String buildDiff(MdmSupplier a, MdmSupplier b) {
        StringBuilder sb = new StringBuilder();
        append(sb, "supplierName", a.getSupplierName(), b.getSupplierName());
        append(sb, "address", a.getAddress(), b.getAddress());
        append(sb, "contactName", a.getContactName(), b.getContactName());
        append(sb, "contactPhone", a.getContactPhone(), b.getContactPhone());
        append(sb, "legalEntityId", a.getLegalEntityId(), b.getLegalEntityId());
        append(sb, "bankName", a.getBankName(), b.getBankName());
        append(sb, "taxNo", a.getTaxNo(), b.getTaxNo());
        append(sb, "paymentTerms", a.getPaymentTerms(), b.getPaymentTerms());
        append(sb, "blacklistResult", a.getBlacklistResult(), b.getBlacklistResult());
        append(sb, "esgRating", a.getEsgRating(), b.getEsgRating());
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
        Map<String, String> from = parse(fromJson);
        Map<String, String> to = parse(toJson);
        List<String> keys = new ArrayList<>(from.keySet());
        to.keySet().forEach(k -> {
            if (!keys.contains(k)) keys.add(k);
        });
        List<Map<String, String>> fields = new ArrayList<>();
        for (String k : keys) {
            String x = from.getOrDefault(k, "");
            String y = to.getOrDefault(k, "");
            if (!Objects.equals(x, y)) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("field", k);
                row.put("from", x);
                row.put("to", y);
                fields.add(row);
            }
        }
        return fields;
    }

    private Map<String, String> parse(String json) {
        Map<String, String> data = new LinkedHashMap<>();
        try {
            JsonNodeReader node = new JsonNodeReader(objectMapper.readTree(
                    json == null || json.isEmpty() ? "{}" : json));
            node.forEach(data);
        } catch (JsonProcessingException e) {
            throw new ServiceException(500, "快照解析失败");
        }
        return data;
    }

    private record JsonNodeReader(com.fasterxml.jackson.databind.JsonNode node) {
        void forEach(Map<String, String> into) {
            node.fields().forEachRemaining(en ->
                    into.put(en.getKey(), en.getValue().isNull() ? "" : en.getValue().asText()));
        }
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
