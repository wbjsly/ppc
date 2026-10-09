package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.common.SimilarityUtil;
import com.erp.dao.fin.FinAccrualDao;
import com.erp.dao.mdm.MdmSupplierCertDao;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.mdm.MdmSupplierMergeLogDao;
import com.erp.entity.fin.FinAccrual;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.mdm.MdmSupplierCert;
import com.erp.entity.mdm.MdmSupplierMergeLog;
import com.erp.ops.OutboxPublisher;
import com.erp.service.mdm.MdmSupplierMergeService;
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
import java.util.Objects;

@Slf4j
@Service
public class MdmSupplierMergeServiceImpl implements MdmSupplierMergeService {

    /** 回退窗口（行 659：30 天内可回退，闭区间） */
    private static final int REVERT_WINDOW_DAYS = 30;

    private final MdmSupplierDao supplierDao;
    private final MdmSupplierCertDao certDao;
    private final MdmSupplierMergeLogDao logDao;
    private final OutboxPublisher outbox;
    /** 快照桥接（同包，避免快照逻辑双份漂移） */
    private final MdmSupplierServiceImpl supplierService;
    /** 未清应付暂估迁移（BR-4.1-28，add-accrual-three-way-match 4.3） */
    private final FinAccrualDao accrualDao;

    public MdmSupplierMergeServiceImpl(MdmSupplierDao supplierDao,
                                       MdmSupplierCertDao certDao,
                                       MdmSupplierMergeLogDao logDao,
                                       OutboxPublisher outbox,
                                       MdmSupplierServiceImpl supplierService,
                                       FinAccrualDao accrualDao) {
        this.supplierDao = supplierDao;
        this.certDao = certDao;
        this.logDao = logDao;
        this.outbox = outbox;
        this.supplierService = supplierService;
        this.accrualDao = accrualDao;
    }

    // ---------- 候选与对比（FR-4.1-5-1） ----------

    @Override
    public List<Map<String, Object>> candidates(String keyword, String excludeId) {
        if (!isNotBlank(keyword)) {
            return List.of();
        }
        // 税号精确 + 名称相似（C-4.1-07 复用），排除自身与已合并
        List<MdmSupplier> pool = supplierDao.selectList(new LambdaQueryWrapper<MdmSupplier>()
                .ne(isNotBlank(excludeId), MdmSupplier::getId, excludeId)
                .ne(MdmSupplier::getStatus, "MERGED")
                .and(w -> w.like(MdmSupplier::getTaxNo, keyword)
                        .or().like(MdmSupplier::getSupplierCode, keyword)
                        .or().like(MdmSupplier::getSupplierName, keyword))
                .orderByAsc(MdmSupplier::getSupplierCode)
                .last("LIMIT 50"));
        // 名称编辑距离 ≤3 补充命中（关键字在名称维上再精筛）
        List<MdmSupplier> bySimilar = supplierDao.selectList(new LambdaQueryWrapper<MdmSupplier>()
                .ne(isNotBlank(excludeId), MdmSupplier::getId, excludeId)
                .ne(MdmSupplier::getStatus, "MERGED")
                .orderByAsc(MdmSupplier::getSupplierCode));
        List<MdmSupplier> merged = new ArrayList<>(pool);
        for (MdmSupplier s : SimilarityUtil.similarWithin(bySimilar, keyword, 3, 10, MdmSupplier::getSupplierName)) {
            if (merged.stream().noneMatch(m -> m.getId().equals(s.getId()))) {
                merged.add(s);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (MdmSupplier s : merged) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("supplierCode", s.getSupplierCode());
            row.put("supplierName", s.getSupplierName());
            row.put("taxNo", s.getTaxNo());
            row.put("status", s.getStatus());
            row.put("legalEntityId", s.getLegalEntityId());
            row.put("paymentTerms", s.getPaymentTerms());
            result.add(row);
        }
        return result;
    }

    @Override
    public Map<String, Object> compare(String sourceId, String targetId) {
        MdmSupplier source = require(sourceId);
        MdmSupplier target = require(targetId);
        List<Map<String, String>> rows = new ArrayList<>();
        appendRow(rows, "付款条件", source.getPaymentTerms(), target.getPaymentTerms());
        appendRow(rows, "税号", source.getTaxNo(), target.getTaxNo());
        appendRow(rows, "开户行", source.getBankName(), target.getBankName());
        appendRow(rows, "证照最早有效期",
                str(certDao.selectEarliestExpire(sourceId)),
                str(certDao.selectEarliestExpire(targetId)));
        appendRow(rows, "状态", source.getStatus(), target.getStatus());
        appendRow(rows, "法人主体",
                Objects.toString(source.getLegalEntityId(), "（集团级）"),
                Objects.toString(target.getLegalEntityId(), "（集团级）"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        // 方向建议（FR-4.1-5-1 SOP：合格且资质齐全方优先为目标）
        result.put("advice", buildAdvice(source, target));
        return result;
    }

    private String buildAdvice(MdmSupplier source, MdmSupplier target) {
        boolean sourceOk = "QUALIFIED".equals(source.getStatus());
        boolean targetOk = "QUALIFIED".equals(target.getStatus());
        if (sourceOk && !targetOk) {
            return "建议以源供应商 " + source.getSupplierCode() + " 为目标（状态合格），反转方向后提交";
        }
        if (!sourceOk && targetOk) {
            return "建议以目标供应商 " + target.getSupplierCode() + " 为目标（状态合格），符合方向规则";
        }
        return "双方状态均为 " + source.getStatus() + "，按交易活跃度与资质齐全程度人工裁定方向";
    }

    @Override
    public Map<String, Object> impact(String sourceId) {
        require(sourceId);
        List<MdmSupplierCert> certs = certDao.selectList(new LambdaQueryWrapper<MdmSupplierCert>()
                .eq(MdmSupplierCert::getSupplierId, sourceId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("certCount", certs.size());
        result.put("certDetails", certs.stream().map(c ->
                c.getCertType() + " " + Objects.toString(c.getCertNo(), "") + " 至 " + c.getExpireDate()).toList());
        // 四类单据迁移：应付暂估真实迁移（BR-4.1-28，add-accrual-three-way-match 4.3）；
        // 其余三类仍为桩（采购/质量域未接入，明示不造假数字）
        List<Map<String, Object>> migrations = new ArrayList<>();
        for (String type : List.of("未清 PO", "在途收货", "质量绩效记录")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("count", null);
            m.put("stub", true);
            m.put("note", "采购/质量域模块未接入，暂不迁移（OriginalSupplierCode 锚点已预埋）");
            migrations.add(m);
        }
        // 应付暂估：真实计数（OPEN 暂估条数与金额）
        Long accrualCount = accrualDao.selectCount(new LambdaQueryWrapper<FinAccrual>()
                .eq(FinAccrual::getSupplierId, sourceId)
                .eq(FinAccrual::getStatus, FinAccrual.ST_OPEN));
        BigDecimal accrualAmount = BigDecimal.ZERO;
        for (FinAccrual a : accrualDao.selectList(new LambdaQueryWrapper<FinAccrual>()
                .eq(FinAccrual::getSupplierId, sourceId)
                .eq(FinAccrual::getStatus, FinAccrual.ST_OPEN))) {
            accrualAmount = accrualAmount.add(a.getAmount() == null ? BigDecimal.ZERO : a.getAmount());
        }
        Map<String, Object> accrualRow = new LinkedHashMap<>();
        accrualRow.put("type", "应付暂估");
        accrualRow.put("count", accrualCount == null ? 0 : accrualCount.intValue());
        accrualRow.put("amount", accrualAmount);
        accrualRow.put("stub", false);
        accrualRow.put("note", "合并时整体改挂目标并写 OriginalSupplierCode 快照（BR-4.1-28）");
        migrations.add(accrualRow);

        // 应付余额财务确认（BR-4.1-29）：批次挂起待 ADMIN 确认后自动重跑三方匹配（已落地）
        Long pending = accrualDao.selectCount(new LambdaQueryWrapper<FinAccrual>()
                .eq(FinAccrual::getMigrationConfirmed, 0));
        Map<String, Object> fin = new LinkedHashMap<>();
        fin.put("type", "应付余额财务确认");
        fin.put("count", pending == null ? 0 : pending.intValue());
        fin.put("stub", false);
        fin.put("note", "迁移批次默认挂起，财务（ADMIN）确认后自动重跑三方匹配（BR-4.1-29）");
        migrations.add(fin);
        result.put("migrations", migrations);
        result.put("disputeStub", "未结争议单据预检（BR-4.1-26）：采购/质量域模块未接入，暂不可检出");
        return result;
    }

    // ---------- 合并执行（design D3 五步） ----------

    @Override
    @Transactional
    public MdmSupplierMergeLog merge(String sourceId, String targetId, String reason) {
        // 1) 校验组
        if (Objects.equals(sourceId, targetId)) {
            throw new ServiceException(422, "源与目标不能为同一供应商");
        }
        MdmSupplier source = require(sourceId);
        MdmCustomerGroupLite.assertNotMerged(source, "源");
        MdmSupplier target = require(targetId);
        MdmCustomerGroupLite.assertNotMerged(target, "目标");
        if (!Objects.equals(source.getLegalEntityId(), target.getLegalEntityId())) {
            throw new ServiceException(422, "跨法人主体的供应商禁止直接合并（BR-4.1-25），"
                    + "请先完成组织架构层面的供应商归属调整");
        }
        String r = isNotBlank(reason) ? reason.trim() : "";
        if (r.length() < 2) {
            throw new ServiceException(422, "合并原因必填（至少 2 字）");
        }

        // 2) 证照改挂（真实迁移，逐条乐观锁）
        List<MdmSupplierCert> certs = certDao.selectList(new LambdaQueryWrapper<MdmSupplierCert>()
                .eq(MdmSupplierCert::getSupplierId, sourceId));
        for (MdmSupplierCert c : certs) {
            LambdaUpdateWrapper<MdmSupplierCert> uw = new LambdaUpdateWrapper<>();
            uw.eq(MdmSupplierCert::getId, c.getId())
                    .eq(MdmSupplierCert::getVerNo, c.getVerNo())
                    .set(MdmSupplierCert::getSupplierId, targetId)
                    .set(MdmSupplierCert::getVerNo, c.getVerNo() + 1);
            if (certDao.update(null, uw) == 0) {
                throw new ServiceException(409, "证照并发冲突，请刷新后重试：" + c.getCertNo());
            }
        }

        // 3) 源置 MERGED（乐观锁）：MERGED_TO + PRE_STATUS
        LambdaUpdateWrapper<MdmSupplier> sw = new LambdaUpdateWrapper<>();
        sw.eq(MdmSupplier::getId, sourceId)
                .eq(MdmSupplier::getVerNo, source.getVerNo())
                .set(MdmSupplier::getStatus, "MERGED")
                .set(MdmSupplier::getMergedTo, target.getSupplierCode())
                .set(MdmSupplier::getPreStatus, source.getStatus())
                .set(MdmSupplier::getVerNo, source.getVerNo() + 1);
        if (supplierDao.update(null, sw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }

        // 4) 未清应付暂估清点（BR-4.1-28：只迁 OPEN，已冲回历史单不迁仅留只读引用）
        List<FinAccrual> openAccruals = accrualDao.selectList(new LambdaQueryWrapper<FinAccrual>()
                .eq(FinAccrual::getSupplierId, sourceId)
                .eq(FinAccrual::getStatus, FinAccrual.ST_OPEN));
        BigDecimal accrualAmt = BigDecimal.ZERO;
        for (FinAccrual a : openAccruals) {
            accrualAmt = accrualAmt.add(a.getAmount() == null ? BigDecimal.ZERO : a.getAmount());
        }

        // 5) 合并日志 INSERT
        String impactSummary = "证照改挂 " + certs.size() + " 份；应付暂估迁移 " + openAccruals.size()
                + " 条（合计 " + accrualAmt.toPlainString() + "，批次挂起待财务确认）；"
                + "未清PO/在途收货/质量绩效：采购、质量域模块未接入，暂不迁移";
        MdmSupplierMergeLog logRow = new MdmSupplierMergeLog();
        logRow.setLogNo(generateLogNo());
        logRow.setSourceId(sourceId);
        logRow.setSourceCode(source.getSupplierCode());
        logRow.setTargetId(targetId);
        logRow.setTargetCode(target.getSupplierCode());
        logRow.setPreStatus(source.getStatus());
        logRow.setImpactSummary(impactSummary);
        logRow.setOriginalSupplierCode(source.getSupplierCode()); // BR-4.1-28 锚点
        logRow.setMergeAt(LocalDateTime.now());
        logRow.setOperator(currentOperator());
        logRow.setReverted("0");
        try {
            logDao.insert(logRow);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(409, "日志单号并发冲突，请重试");
        }

        // 6) 未清应付暂估迁移（BR-4.1-28/29，同事务）：改挂目标 + 原编码快照 + 批次挂起
        String batchNo = "MB" + logRow.getLogNo();
        for (FinAccrual a : openAccruals) {
            a.setSupplierId(targetId);
            a.setSupplierName(target.getSupplierName());
            a.setOriginalSupplierCode(source.getSupplierCode());
            a.setMigrationBatchNo(batchNo);
            a.setMigrationConfirmed(0);
            a.setUpdateBy(currentOperator());
            accrualDao.updateById(a);
        }
        if (!openAccruals.isEmpty()) {
            log.info("合并 {} → {} 迁移应付暂估 {} 条（批次 {}，挂起待财务确认）",
                    source.getSupplierCode(), target.getSupplierCode(), openAccruals.size(), batchNo);
        }

        // 7) 双方 MERGE 快照 + 事件
        MdmSupplier sourceAfter = require(sourceId);
        supplierService.saveSnapshotFor(sourceAfter, "MERGE",
                "合并至 " + target.getSupplierCode() + "；证照改挂 " + certs.size()
                        + " 份；应付暂估迁移 " + openAccruals.size() + " 条；原因：" + r, r);
        MdmSupplier targetAfter = require(targetId);
        supplierService.saveSnapshotFor(targetAfter, "MERGE",
                "接收合并：源 " + source.getSupplierCode() + "（原状态 " + source.getStatus()
                        + "）；改挂证照 " + certs.size() + " 份；接收应付暂估 " + openAccruals.size()
                        + " 条；原因：" + r, r);
        outbox.publish("MDM.SUPPLIER.MERGED", sourceAfter.getSupplierCode(),
                sourceAfter.getVerNo() + 1, sourceAfter.getLegalEntityId(),
                "mergedTo=" + target.getSupplierCode() + "；preStatus=" + source.getStatus()
                        + "；certs=" + certs.size() + "；accruals=" + openAccruals.size()
                        + "；原因：" + r);
        log.info("MDM.SUPPLIER.MERGED source={} target={} certs={} accruals={} reason={}",
                source.getSupplierCode(), target.getSupplierCode(), certs.size(),
                openAccruals.size(), r);
        return logRow;
    }

    // ---------- 30 天回退 ----------

    @Override
    @Transactional
    public MdmSupplierMergeLog revert(String logId, String reason) {
        MdmSupplierMergeLog logRow = logDao.selectById(logId);
        if (logRow == null) {
            throw new ServiceException(404, "合并日志不存在");
        }
        if ("1".equals(logRow.getReverted())) {
            throw new ServiceException(422, "该合并已回退，不可重复回退（幂等拒绝）");
        }
        String r = isNotBlank(reason) ? reason.trim() : "";
        if (r.length() < 2) {
            throw new ServiceException(422, "回退原因必填（至少 2 字）");
        }
        // 30 天闭区间：merge 第 30 天可回退，第 31 天拒绝
        if (logRow.getMergeAt() != null
                && logRow.getMergeAt().toLocalDate().plusDays(REVERT_WINDOW_DAYS).isBefore(LocalDate.now())) {
            throw new ServiceException(422, "已过回退期（30 天），须走新建供应商 + 人工关联历史流程");
        }

        // 1) 源恢复 PRE_STATUS + 清 MERGED_TO（乐观锁）
        MdmSupplier source = require(logRow.getSourceId());
        if (!"MERGED".equals(source.getStatus())) {
            throw new ServiceException(422, "源供应商当前非合并终态，状态异常（" + source.getStatus() + "），不可回退");
        }
        LambdaUpdateWrapper<MdmSupplier> sw = new LambdaUpdateWrapper<>();
        sw.eq(MdmSupplier::getId, source.getId())
                .eq(MdmSupplier::getVerNo, source.getVerNo())
                .set(MdmSupplier::getStatus, source.getPreStatus())
                .set(MdmSupplier::getMergedTo, null)
                .set(MdmSupplier::getVerNo, source.getVerNo() + 1);
        if (supplierDao.update(null, sw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }

        // 2) 证照回迁源（对称口径：目标下全部证照回源；目标自有证照的冲突场景
        //    在 design 风险中记录，冒烟以「目标无证照」构造规避）
        List<MdmSupplierCert> moved = certDao.selectList(new LambdaQueryWrapper<MdmSupplierCert>()
                .eq(MdmSupplierCert::getSupplierId, logRow.getTargetId()));
        for (MdmSupplierCert c : moved) {
            LambdaUpdateWrapper<MdmSupplierCert> cuw = new LambdaUpdateWrapper<>();
            cuw.eq(MdmSupplierCert::getId, c.getId())
                    .eq(MdmSupplierCert::getVerNo, c.getVerNo())
                    .set(MdmSupplierCert::getSupplierId, logRow.getSourceId())
                    .set(MdmSupplierCert::getVerNo, c.getVerNo() + 1);
            if (certDao.update(null, cuw) == 0) {
                throw new ServiceException(409, "证照并发冲突，请刷新后重试");
            }
        }

        // 2b) 未清应付暂估回迁（与证照对称，BR-4.1-28 镜像）：只回迁本批次仍 OPEN 的暂估，
        //     已冲回历史单不回迁（保持只读引用）；批次与快照字段一并清除
        List<FinAccrual> movedAccruals = accrualDao.selectList(new LambdaQueryWrapper<FinAccrual>()
                .eq(FinAccrual::getSupplierId, logRow.getTargetId())
                .eq(FinAccrual::getStatus, FinAccrual.ST_OPEN)
                .eq(FinAccrual::getMigrationBatchNo, "MB" + logRow.getLogNo()));
        for (FinAccrual a : movedAccruals) {
            a.setSupplierId(logRow.getSourceId());
            a.setSupplierName(source.getSupplierName());
            a.setOriginalSupplierCode(null);
            a.setMigrationBatchNo(null);
            a.setMigrationConfirmed(null);
            a.setMigrationConfirmBy(null);
            a.setMigrationConfirmAt(null);
            a.setUpdateBy(currentOperator());
            accrualDao.updateById(a);
        }
        if (!movedAccruals.isEmpty()) {
            log.info("合并回退 {} 应付暂估回迁 {} 条（批次 MB{}）",
                    source.getSupplierCode(), movedAccruals.size(), logRow.getLogNo());
        }

        // 3) 日志回填
        LambdaUpdateWrapper<MdmSupplierMergeLog> lw = new LambdaUpdateWrapper<>();
        lw.eq(MdmSupplierMergeLog::getId, logId)
                .eq(MdmSupplierMergeLog::getReverted, "0")
                .set(MdmSupplierMergeLog::getReverted, "1")
                .set(MdmSupplierMergeLog::getRevertAt, LocalDateTime.now())
                .set(MdmSupplierMergeLog::getRevertReason, r)
                .set(MdmSupplierMergeLog::getRevertOperator, currentOperator());
        if (logDao.update(null, lw) == 0) {
            throw new ServiceException(409, "日志已被回退（并发），不可重复回退");
        }

        // 4) 双方 REVERT 快照
        MdmSupplier sourceAfter = require(logRow.getSourceId());
        supplierService.saveSnapshotFor(sourceAfter, "REVERT",
                "合并回退：MERGED → " + source.getPreStatus() + "；证照回迁 " + moved.size()
                        + " 份；原因：" + r, r);
        MdmSupplier targetAfter = require(logRow.getTargetId());
        supplierService.saveSnapshotFor(targetAfter, "REVERT",
                "接收合并回退：源 " + source.getSupplierCode() + " 恢复；证照回迁 " + moved.size()
                        + " 份；原因：" + r, r);

        // 5) 事件
        outbox.publish("MDM.SUPPLIER.MERGE_REVERTED", sourceAfter.getSupplierCode(),
                sourceAfter.getVerNo() + 1, sourceAfter.getLegalEntityId(),
                "revert → " + source.getPreStatus() + "；certs=" + moved.size() + "；原因：" + r);
        log.info("MDM.SUPPLIER.MERGE_REVERTED source={} certs={} reason={}",
                source.getSupplierCode(), moved.size(), r);
        MdmSupplierMergeLog after = logDao.selectById(logId);
        return after;
    }

    // ---------- 日志分页 ----------

    @Override
    public Map<String, Object> logPage(long current, long size, String keyword) {
        LambdaQueryWrapper<MdmSupplierMergeLog> qw = new LambdaQueryWrapper<MdmSupplierMergeLog>()
                .and(isNotBlank(keyword), w -> w
                        .like(MdmSupplierMergeLog::getLogNo, keyword)
                        .or().like(MdmSupplierMergeLog::getSourceCode, keyword)
                        .or().like(MdmSupplierMergeLog::getTargetCode, keyword))
                .orderByDesc(MdmSupplierMergeLog::getMergeAt);
        var page = logDao.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, size), qw);
        // 联表补名称与 30 天窗口标记
        List<Map<String, Object>> records = new ArrayList<>();
        for (MdmSupplierMergeLog row : page.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("log", row);
            m.put("sourceName", nameOf(row.getSourceId()));
            m.put("targetName", nameOf(row.getTargetId()));
            boolean withinWindow = row.getMergeAt() != null
                    && !row.getMergeAt().toLocalDate().plusDays(REVERT_WINDOW_DAYS).isBefore(LocalDate.now());
            m.put("revertable", "0".equals(row.getReverted()) && withinWindow);
            m.put("withinWindow", withinWindow);
            records.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", page.getTotal());
        return result;
    }

    // ---------- 私有工具 ----------

    private MdmSupplier require(String id) {
        MdmSupplier s = supplierDao.selectById(id);
        if (s == null) {
            throw new ServiceException(404, "供应商不存在");
        }
        return s;
    }

    private String nameOf(String id) {
        MdmSupplier s = supplierDao.selectById(id);
        return s == null ? id : s.getSupplierName();
    }

    private void appendRow(List<Map<String, String>> rows, String field, String a, String b) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("field", field);
        row.put("sourceValue", a == null || a.isEmpty() ? "（空）" : a);
        row.put("targetValue", b == null || b.isEmpty() ? "（空）" : b);
        rows.add(row);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String generateLogNo() {
        String max = logDao.selectMaxLogNo();
        int next = 1;
        if (isNotBlank(max) && max.startsWith("MG-")) {
            try {
                next = Integer.parseInt(max.substring(3)) + 1;
            } catch (NumberFormatException ignore) {
                next = 1;
            }
        }
        return String.format("MG-%04d", next);
    }

    private String currentOperator() {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            return auth == null || auth.getName() == null ? "system" : auth.getName();
        } catch (Exception e) {
            return "system";
        }
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** 源/目标 MERGED 拒绝小工具（D2 校验组③） */
    private static final class MdmCustomerGroupLite {
        private static void assertNotMerged(MdmSupplier s, String role) {
            if ("MERGED".equals(s.getStatus())) {
                throw new ServiceException(422, role + "供应商已处于合并终态：" + s.getSupplierCode());
            }
        }
    }
}
