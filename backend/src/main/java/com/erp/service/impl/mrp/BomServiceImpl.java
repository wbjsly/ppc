package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mrp.MrpBomDao;
import com.erp.dao.mrp.MrpBomItemDao;
import com.erp.dao.mrp.MrpBomSubstituteDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
import com.erp.entity.mrp.MrpBomSubstitute;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.mrp.BomCycleChecker;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.BomStatusRules;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * BOM 管理实现（change add-bom-management，spec bom-management / 00-erp-spec 4.5-1）。
 * 草稿覆盖保存 = 逻辑删除旧行与替代后按载荷重建（行号按载荷序重排）；
 * 版本号自动分配（FR-4.5-1-5）；行级替代同单位 L1 校验（偏差 D2：本期无单位换算）。
 * 保存/复制内嵌循环校验（FR-4.5-1-4，任务 3.2）；工具页扫描（5.1.4，任务 3.3）。
 * 版本与状态机（任务 4.x）：同父项唯一在途（父项行 FOR UPDATE + 阻断）、版本冲突自动递增
 * （FR-4.5-1-5）、白名单 CAS 迁移（BomStatusRules + ver_no 条件）、变更克隆（BR-4.5-09）、
 * 发布同事务旧版 REVISED（BR-4.5-08）。
 * 审批回调接线（5.1 BomApprovalCallback）、提交挂审批（5.2 submit）、角色权限
 * （5.3：维护类=PROCESS_ENG、废止=PROCESS_MGR，未登录 401、角色不符 403）；
 * REST 收口（6.1）由后续任务接入。
 */
@Slf4j
@Service
public class BomServiceImpl implements BomService {

    private final MrpBomDao bomDao;
    private final MrpBomItemDao itemDao;
    private final MrpBomSubstituteDao substituteDao;
    private final MdmItemDao mdmItemDao;
    private final ObjectMapper objectMapper;
    private final BomCycleChecker cycleChecker;
    private final SysParamService sysParamService;
    private final ApprovalEngine approvalEngine;
    private final com.erp.dao.mrp.TrialSummaryDao trialSummaryDao;
    private final com.erp.dao.mrp.MrpMoDao moDao;

    public BomServiceImpl(MrpBomDao bomDao, MrpBomItemDao itemDao,
                          MrpBomSubstituteDao substituteDao, MdmItemDao mdmItemDao,
                          ObjectMapper objectMapper, BomCycleChecker cycleChecker,
                          SysParamService sysParamService, ApprovalEngine approvalEngine,
                          com.erp.dao.mrp.TrialSummaryDao trialSummaryDao,
                          com.erp.dao.mrp.MrpMoDao moDao) {
        this.bomDao = bomDao;
        this.itemDao = itemDao;
        this.substituteDao = substituteDao;
        this.mdmItemDao = mdmItemDao;
        this.objectMapper = objectMapper;
        this.cycleChecker = cycleChecker;
        this.sysParamService = sysParamService;
        this.approvalEngine = approvalEngine;
        this.trialSummaryDao = trialSummaryDao;
        this.moDao = moDao;
    }

    /**
     * 转量产发布闸口（trial-execution「未评审草稿提交发布 422」/ design D3）：
     * SRC_TRIAL_NO 非空 → 回查该试产工单总结评审，非 APPROVED 一律 L1 阻断；空 = 普通版本放行。
     */
    private void requireTrialReviewed(String srcTrialNo) {
        if (srcTrialNo == null || srcTrialNo.isBlank()) {
            return;
        }
        com.erp.entity.mrp.MrpMo mo = moDao.selectOne(
                new LambdaQueryWrapper<com.erp.entity.mrp.MrpMo>()
                        .eq(com.erp.entity.mrp.MrpMo::getMoNo, srcTrialNo).last("LIMIT 1"));
        com.erp.entity.mrp.TrialSummary summary = mo == null ? null
                : trialSummaryDao.selectOne(new LambdaQueryWrapper<com.erp.entity.mrp.TrialSummary>()
                        .eq(com.erp.entity.mrp.TrialSummary::getMoId, mo.getId()).last("LIMIT 1"));
        if (summary == null || !com.erp.entity.mrp.TrialSummary.ST_APPROVED.equals(summary.getStatus())) {
            throw new ServiceException(422, "来源试产评审未通过，禁止发布（FR-4.5-8-7 未经评审阻断）："
                    + srcTrialNo + "（评审状态 " + (summary == null ? "未建总结单" : summary.getStatus()) + "）");
        }
    }

    // ---------- 创建 / 编辑 / 复制 ----------

    @Override
    @Transactional
    public MrpBom create(MrpBom head, List<MrpBomItem> items) {
        requireAny("创建 BOM", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        if (head == null || !isNotBlank(head.getParentItemCode())) {
            throw new ServiceException(422, "父项物料必填");
        }
        MdmItem parent = requireActiveItem(head.getParentItemCode(),
                "父项物料不存在或已废止，无法创建");
        requireLines(items);
        // 同父项唯一在途（任务 4.3）：先锁父项既有版本行，再判定在途
        // （change add-trial-production-management：按试产/量产池分池——TRIAL_STAGE 非空入试产池）
        lockParentVersions(parent.getItemCode());
        String trialStage = normalizeTrialStage(head.getTrialStage());
        requireNoInFlight(parent.getItemCode(), trialStage != null);
        int[] ver = nextVersion(parent.getItemCode());
        MrpBom bom = new MrpBom();
        bom.setParentItemCode(parent.getItemCode());
        bom.setParentItemName(parent.getItemName());
        bom.setVersionMajor(ver[0]);
        bom.setVersionMinor(ver[1]);
        bom.setStatus(MrpBom.ST_DRAFT);
        bom.setTrialStage(trialStage);
        bom.setEffectiveDate(head.getEffectiveDate());
        bom.setExpiryDate(head.getExpiryDate());
        insertBomResolvingVersionConflict(bom);
        insertLines(bom.getId(), items);
        validateNoCycle(bom.getId(), bom.getParentItemCode());
        log.info("BOM 草稿创建：{} V{}{}", bom.getParentItemCode(), bom.getVersionLabel(),
                trialStage == null ? "" : "（试产版 " + trialStage + "）");
        return bom;
    }

    @Override
    @Transactional
    public MrpBom saveDraft(String bomId, MrpBom head, List<MrpBomItem> items) {
        requireAny("编辑 BOM 草稿", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        MrpBom bom = bomDao.selectById(bomId);
        if (bom == null) {
            throw new ServiceException(404, "BOM 版本不存在");
        }
        if (!MrpBom.ST_DRAFT.equals(bom.getStatus())) {
            throw new ServiceException(422, "仅草稿可编辑（当前状态：" + bom.getStatus() + "）");
        }
        if (head != null && isNotBlank(head.getParentItemCode())
                && !head.getParentItemCode().equals(bom.getParentItemCode())) {
            throw new ServiceException(422, "父项物料不可修改（如需更换父项请新建清单）");
        }
        requireLines(items);
        if (head != null) {
            bom.setEffectiveDate(head.getEffectiveDate());
            bom.setExpiryDate(head.getExpiryDate());
            if (isNotBlank(head.getParentItemCode())) {
                MdmItem parent = requireActiveItem(bom.getParentItemCode(),
                        "父项物料不存在或已废止，无法保存");
                bom.setParentItemName(parent.getItemName());
            }
        }
        // 覆盖保存：逻辑删除旧行与替代后重建（草稿态覆盖语义，design D1）
        List<MrpBomItem> olds = itemDao.selectList(
                new LambdaQueryWrapper<MrpBomItem>().eq(MrpBomItem::getBomId, bomId));
        if (!olds.isEmpty()) {
            List<String> oldIds = new ArrayList<>();
            for (MrpBomItem old : olds) {
                oldIds.add(old.getId());
            }
            substituteDao.delete(new LambdaQueryWrapper<MrpBomSubstitute>()
                    .in(MrpBomSubstitute::getBomItemId, oldIds));
            itemDao.delete(new LambdaQueryWrapper<MrpBomItem>().eq(MrpBomItem::getBomId, bomId));
        }
        insertLines(bomId, items);
        validateNoCycle(bomId, bom.getParentItemCode());
        bomDao.updateById(bom);
        return bomDao.selectById(bomId);
    }

    @Override
    @Transactional
    public MrpBom copy(String sourceId) {
        requireAny("复制 BOM", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        MrpBom src = bomDao.selectById(sourceId);
        if (src == null) {
            throw new ServiceException(404, "源 BOM 版本不存在");
        }
        MdmItem parent = requireActiveItem(src.getParentItemCode(),
                "父项物料不存在或已废止，无法创建");
        lockParentVersions(parent.getItemCode());
        requireNoInFlight(parent.getItemCode());
        int[] ver = nextVersion(parent.getItemCode());
        MrpBom bom = new MrpBom();
        bom.setParentItemCode(parent.getItemCode());
        bom.setParentItemName(parent.getItemName());
        bom.setVersionMajor(ver[0]);
        bom.setVersionMinor(ver[1]);
        bom.setStatus(MrpBom.ST_DRAFT);
        bom.setEffectiveDate(src.getEffectiveDate());
        bom.setExpiryDate(src.getExpiryDate());
        bom.setCopyFromId(src.getId());
        insertBomResolvingVersionConflict(bom);

        // 克隆行与替代（源版本状态不限：已废止/已变更版本同样可复制为独立草稿）
        cloneLines(src.getId(), bom.getId());
        validateNoCycle(bom.getId(), bom.getParentItemCode());
        log.info("BOM 复制：{} → {}（源版本 {}）", src.getId(), bom.getId(), src.getVersionLabel());
        return bom;
    }

    @Override
    @Transactional
    public MrpBom change(String sourceId, String changeReason, boolean upgradeMajor) {
        requireAny("发起 BOM 变更", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        if (!isNotBlank(changeReason)) {
            throw new ServiceException(422, "变更原因必填（FR-4.5-1-5）");
        }
        MrpBom src = bomDao.selectById(sourceId);
        if (src == null) {
            throw new ServiceException(404, "源 BOM 版本不存在");
        }
        if (!MrpBom.ST_PUBLISHED.equals(src.getStatus())
                && !MrpBom.ST_TRIAL.equals(src.getStatus())) {
            throw new ServiceException(422,
                    "仅已发布/试产已审批版本可发起变更（当前状态：" + src.getStatus() + "）");
        }
        MdmItem parent = requireActiveItem(src.getParentItemCode(),
                "父项物料不存在或已废止，无法变更");
        lockParentVersions(parent.getItemCode());
        // 分池：试产版变更克隆入试产池（TRIAL_STAGE 继承），量产版入量产池（spec bom delta）
        boolean trialSrc = MrpBom.ST_TRIAL.equals(src.getStatus());
        requireNoInFlight(parent.getItemCode(), trialSrc);
        int[] ver = nextVersion(parent.getItemCode(), upgradeMajor);
        MrpBom bom = new MrpBom();
        bom.setParentItemCode(parent.getItemCode());
        bom.setParentItemName(parent.getItemName());
        bom.setVersionMajor(ver[0]);
        bom.setVersionMinor(ver[1]);
        bom.setStatus(MrpBom.ST_DRAFT);
        bom.setTrialStage(src.getTrialStage());
        bom.setChangeReason(changeReason.trim());
        bom.setChangeFromId(src.getId());
        bom.setEffectiveDate(src.getEffectiveDate());
        bom.setExpiryDate(src.getExpiryDate());
        insertBomResolvingVersionConflict(bom);

        cloneLines(src.getId(), bom.getId());
        validateNoCycle(bom.getId(), bom.getParentItemCode());
        log.info("BOM 变更发起：{} V{} → 新草稿 V{}（原因：{}）",
                src.getParentItemCode(), src.getVersionLabel(), bom.getVersionLabel(), bom.getChangeReason());
        return bom;
    }

    @Override
    @Transactional
    public MrpBom promoteFromTrial(String sourceId, String trialMoNo) {
        requireAny("转量产复制", "ROLE_PROCESS_ENG", "ROLE_PLANNER", "ROLE_PM", "ROLE_ADMIN");
        MrpBom src = bomDao.selectById(sourceId);
        if (src == null) {
            throw new ServiceException(404, "试产 BOM 版本不存在");
        }
        if (src.getTrialStage() == null || src.getTrialStage().isBlank()) {
            throw new ServiceException(422, "仅试产版可转量产复制（该版本无试产阶段标记）：" + src.getVersionLabel());
        }
        MdmItem parent = requireActiveItem(src.getParentItemCode(),
                "父项物料不存在或已废止，无法复制");
        lockParentVersions(parent.getItemCode());
        requireNoInFlight(parent.getItemCode());
        int[] ver = nextVersion(parent.getItemCode());
        MrpBom bom = new MrpBom();
        bom.setParentItemCode(parent.getItemCode());
        bom.setParentItemName(parent.getItemName());
        bom.setVersionMajor(ver[0]);
        bom.setVersionMinor(ver[1]);
        bom.setStatus(MrpBom.ST_DRAFT);
        bom.setEffectiveDate(src.getEffectiveDate());
        bom.setExpiryDate(src.getExpiryDate());
        bom.setCopyFromId(src.getId());
        bom.setSrcTrialNo(trialMoNo);   // 来源留痕（发布闸口回查评审，design D11）
        // TRIAL_STAGE 不设 —— 量产草稿（试产版本身零改动）
        insertBomResolvingVersionConflict(bom);
        cloneLines(src.getId(), bom.getId());
        validateNoCycle(bom.getId(), bom.getParentItemCode());
        log.info("试产转量产复制 BOM：{} → {} V{}（来源 {}）", src.getId(), bom.getId(),
                bom.getVersionLabel(), trialMoNo);
        return bom;
    }

    @Override
    @Transactional
    public void publishApproved(String bomId) {
        MrpBom bom = bomDao.selectById(bomId);
        if (bom == null) {
            throw new ServiceException(404, "BOM 版本不存在");
        }
        lockParentVersions(bom.getParentItemCode());
        if (!MrpBom.ST_PENDING.equals(bom.getStatus())) {
            throw new ServiceException(422, "仅待审核版本可发布（当前状态：" + bom.getStatus() + "）");
        }
        // 转量产草稿发布闸口（签署处二次校验——评审可被驳回于审批期间，design D3）
        requireTrialReviewed(bom.getSrcTrialNo());
        // PENDING → PUBLISHED（白名单 + CAS）
        casStatus(bom, MrpBom.ST_PUBLISHED);
        // 同父项旧已发布 → REVISED（同事务，保证 BR-4.5-08 唯一已发布）
        List<MrpBom> oldPublished = bomDao.selectList(new LambdaQueryWrapper<MrpBom>()
                .eq(MrpBom::getParentItemCode, bom.getParentItemCode())
                .eq(MrpBom::getStatus, MrpBom.ST_PUBLISHED)
                .ne(MrpBom::getId, bom.getId()));
        for (MrpBom old : oldPublished) {
            casStatus(old, MrpBom.ST_REVISED);
        }
        // 发布留痕（FR-4.5-1-7 降级出口）
        bom.setPublishBy(currentOperator());
        bom.setPublishAt(LocalDateTime.now());
        bomDao.updateById(bom);
        log.info("BOM 发布：{} V{}（旧版已迁移 REVISED {} 条）",
                bom.getParentItemCode(), bom.getVersionLabel(), oldPublished.size());
    }

    @Override
    @Transactional
    public void rejectBackToDraft(String bomId, String reason) {
        MrpBom bom = bomDao.selectById(bomId);
        if (bom == null) {
            throw new ServiceException(404, "BOM 版本不存在");
        }
        if (!MrpBom.ST_PENDING.equals(bom.getStatus())) {
            throw new ServiceException(422, "仅待审核版本可退回（当前状态：" + bom.getStatus() + "）");
        }
        casStatus(bom, MrpBom.ST_DRAFT);
        bom.setRejectReason(isNotBlank(reason) ? reason.trim() : null);
        bomDao.updateById(bom);
        log.info("BOM 审核退回：{} → 草稿（意见：{}）", bomId, bom.getRejectReason());
    }

    // ---------- 试产特别审批（change add-trial-production-management，spec bom-management delta） ----------

    @Override
    @Transactional
    public MrpBom submitTrial(String bomId) {
        requireAny("提交试产版特别审批", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        MrpBom bom = bomDao.selectById(bomId);
        if (bom == null) {
            throw new ServiceException(404, "BOM 版本不存在");
        }
        if (!MrpBom.ST_DRAFT.equals(bom.getStatus())) {
            throw new ServiceException(422,
                    "仅草稿可提交试产特别审批（当前状态：" + bom.getStatus() + "）：" + bom.getVersionLabel());
        }
        if (!isNotBlank(bom.getTrialStage())) {
            throw new ServiceException(422,
                    "非试产版 BOM（未标记试产阶段），请在创建时标记 EVT/DVT/PVT：" + bom.getVersionLabel());
        }
        // 挂审批底座单节点（BIZ=TrialBom，PROCESS_MGR 签）；审批期间状态保持 DRAFT，
        // 通过回调 onTrialApproved 同事务 DRAFT→TRIAL（delta「由 DRAFT → TRIAL」语义）
        approvalEngine.submit(BIZ_TRIAL, bom.getId(),
                "试产版特别审批：" + bom.getParentItemCode() + " V" + bom.getVersionLabel()
                        + "（" + bom.getTrialStage() + "）",
                null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PROCESS_MGR", "工艺主管"))));
        log.info("BOM 试产特别审批提交：{} V{} 阶段={}", bom.getParentItemCode(),
                bom.getVersionLabel(), bom.getTrialStage());
        return bom;
    }

    @Override
    @Transactional
    public void onTrialApproved(String bomId) {
        MrpBom bom = bomDao.selectById(bomId);
        if (bom == null) {
            throw new ServiceException(404, "BOM 版本不存在");
        }
        lockParentVersions(bom.getParentItemCode());
        if (!MrpBom.ST_DRAFT.equals(bom.getStatus())) {
            throw new ServiceException(422,
                    "试产审批落态冲突（当前状态：" + bom.getStatus() + "），请刷新后重试：" + bomId);
        }
        // 同父项旧 TRIAL → REVISED（试产唯一已审批，spec delta「新试产版取代旧 TRIAL」）
        List<MrpBom> oldTrial = bomDao.selectList(new LambdaQueryWrapper<MrpBom>()
                .eq(MrpBom::getParentItemCode, bom.getParentItemCode())
                .eq(MrpBom::getStatus, MrpBom.ST_TRIAL)
                .ne(MrpBom::getId, bom.getId()));
        for (MrpBom old : oldTrial) {
            casStatus(old, MrpBom.ST_REVISED);
        }
        casStatus(bom, MrpBom.ST_TRIAL);
        log.info("BOM 试产特别审批通过：{} V{} 阶段={}（旧 TRIAL→REVISED {} 条）",
                bom.getParentItemCode(), bom.getVersionLabel(), bom.getTrialStage(), oldTrial.size());
    }

    @Override
    @Transactional
    public void onTrialRejected(String bomId, String reason) {
        MrpBom bom = bomDao.selectById(bomId);
        if (bom == null) {
            throw new ServiceException(404, "BOM 版本不存在");
        }
        // 审批期间状态保持 DRAFT（驳回回 DRAFT 语义即状态不动），仅留痕意见
        bom.setRejectReason(isNotBlank(reason) ? reason.trim() : null);
        bomDao.updateById(bom);
        log.info("BOM 试产特别审批驳回：{} 意见={}", bomId, bom.getRejectReason());
    }

    @Override
    @Transactional
    public MrpBom submit(String bomId) {
        requireAny("提交 BOM 审核", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        MrpBom bom = bomDao.selectById(bomId);
        if (bom == null) {
            throw new ServiceException(404, "BOM 版本不存在");
        }
        if (MrpBom.ST_TRIAL.equals(bom.getStatus())) {
            // spec bom-management delta「试产版不可直接发布」场景：TRIAL 态阻断正式发布
            throw new ServiceException(422,
                    "试产版（TRIAL）不可直接发布，请经转量产复制为量产草稿后走正式发布：" + bom.getVersionLabel());
        }
        if (!MrpBom.ST_DRAFT.equals(bom.getStatus())) {
            throw new ServiceException(422, "仅草稿可提交审核（当前状态：" + bom.getStatus() + "）");
        }
        // 转量产草稿发布闸口（trial-execution「转量产复制与发布阻断」：未评审 L1 阻断）
        requireTrialReviewed(bom.getSrcTrialNo());
        // 挂审批底座单节点（任务 5.2）：同事务提交实例 + CAS DRAFT→PENDING，
        // 重复提交由底座「同一业务 PENDING 实例」422 拒绝
        approvalEngine.submit(BIZ_PUBLISH, bom.getId(),
                "BOM 发布审核：" + bom.getParentItemCode() + " V" + bom.getVersionLabel(),
                null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PROCESS_MGR", "工艺主管"))));
        casStatus(bom, MrpBom.ST_PENDING);
        log.info("BOM 提交审核：{} V{}", bom.getParentItemCode(), bom.getVersionLabel());
        return bom;
    }

    @Override
    @Transactional
    public void obsolete(String bomId) {
        requireAny("废止 BOM", "ROLE_PROCESS_MGR", "ROLE_ADMIN");
        MrpBom bom = bomDao.selectById(bomId);
        if (bom == null) {
            throw new ServiceException(404, "BOM 版本不存在");
        }
        if (!MrpBom.ST_PUBLISHED.equals(bom.getStatus())
                && !MrpBom.ST_REVISED.equals(bom.getStatus())) {
            throw new ServiceException(422,
                    "仅已发布/已变更版本可废止（当前状态：" + bom.getStatus() + "）");
        }
        casStatus(bom, MrpBom.ST_OBSOLETE);
        log.info("BOM 手动废止：{} V{}", bom.getParentItemCode(), bom.getVersionLabel());
    }

    // ---------- 查询 ----------

    @Override
    public Map<String, Object> detail(String bomId) {
        MrpBom bom = bomDao.selectById(bomId);
        if (bom == null) {
            throw new ServiceException(404, "BOM 版本不存在");
        }
        Map<String, Object> out = toMap(bom);
        List<Map<String, Object>> lines = new ArrayList<>();
        List<MrpBomItem> items = itemDao.selectList(
                new LambdaQueryWrapper<MrpBomItem>().eq(MrpBomItem::getBomId, bomId)
                        .orderByAsc(MrpBomItem::getLineNo));
        for (MrpBomItem item : items) {
            Map<String, Object> line = toMap(item);
            List<Map<String, Object>> subs = new ArrayList<>();
            List<MrpBomSubstitute> subsRaw = substituteDao.selectList(
                    new LambdaQueryWrapper<MrpBomSubstitute>()
                            .eq(MrpBomSubstitute::getBomItemId, item.getId())
                            .orderByAsc(MrpBomSubstitute::getPriority));
            for (MrpBomSubstitute s : subsRaw) {
                subs.add(toMap(s));
            }
            line.put("substitutes", subs);
            lines.add(line);
        }
        out.put("items", lines);
        return out;
    }

    @Override
    public List<Map<String, Object>> query(String status, String parentItemCode, String keyword) {
        LambdaQueryWrapper<MrpBom> w = new LambdaQueryWrapper<>();
        w.eq(isNotBlank(status), MrpBom::getStatus, status);
        w.eq(isNotBlank(parentItemCode), MrpBom::getParentItemCode, parentItemCode);
        if (isNotBlank(keyword)) {
            String like = "%" + keyword.trim() + "%";
            w.and(q -> q.like(MrpBom::getParentItemCode, like)
                    .or().like(MrpBom::getParentItemName, like));
        }
        w.orderByAsc(MrpBom::getParentItemCode)
                .orderByDesc(MrpBom::getVersionMajor)
                .orderByDesc(MrpBom::getVersionMinor);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MrpBom bom : bomDao.selectList(w)) {
            out.add(toMap(bom));
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> substituteCandidates(String itemCode) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!isNotBlank(itemCode)) {
            return out;
        }
        MdmItem src = mdmItemDao.selectOne(
                new LambdaQueryWrapper<MdmItem>().eq(MdmItem::getItemCode, itemCode.trim()));
        if (src == null || !isNotBlank(src.getAltItemCode())) {
            return out;
        }
        MdmItem target = mdmItemDao.selectOne(
                new LambdaQueryWrapper<MdmItem>().eq(MdmItem::getItemCode, src.getAltItemCode()));
        if (target == null) {
            return out;
        }
        Map<String, Object> cand = new LinkedHashMap<>();
        cand.put("itemCode", target.getItemCode());
        cand.put("itemName", target.getItemName());
        cand.put("baseUnit", target.getBaseUnit());
        cand.put("status", target.getStatus());
        cand.put("ratio", BigDecimal.ONE);
        cand.put("priority", 1);
        cand.put("source", "MDM");
        out.add(cand);
        return out;
    }

    // ---------- 循环校验（FR-4.5-1-4 / 5.1.4 工具页） ----------

    /** 保存/复制内嵌校验（L1 阻断）：根 = 本次保存的行；其余父项 = 在用版本并集 */
    private void validateNoCycle(String bomId, String parentItemCode) {
        int maxDepth = sysParamService.getInt("BOM_MAX_NESTING_DEPTH", 8);
        List<String> draftChildren = itemCodes(itemDao.selectList(
                new LambdaQueryWrapper<MrpBomItem>().eq(MrpBomItem::getBomId, bomId)));
        Function<String, List<String>> provider = x ->
                x.equals(parentItemCode) ? draftChildren : liveChildren(x);
        BomCycleChecker.Result r = cycleChecker.check(List.of(parentItemCode), provider, maxDepth);
        if (!r.getCycles().isEmpty()) {
            List<String> path = r.getCycles().get(0);
            throw new ServiceException(422,
                    "检测到 BOM 循环引用，已阻断保存（环路径：" + String.join("→", path) + "）");
        }
        if (r.isDepthExceeded()) {
            log.warn("BOM {} 嵌套层数超过系统参数 BOM_MAX_NESTING_DEPTH={}（超限提示，不阻断）",
                    parentItemCode, maxDepth);
        }
    }

    @Override
    public Map<String, Object> scan(String parentItemCode) {
        requireAny("执行循环校验", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        int maxDepth = sysParamService.getInt("BOM_MAX_NESTING_DEPTH", 8);
        boolean all = !isNotBlank(parentItemCode);
        List<String> roots = new ArrayList<>();
        long bomCount;
        if (all) {
            List<MrpBom> live = bomDao.selectList(new LambdaQueryWrapper<MrpBom>()
                    .in(MrpBom::getStatus, liveStatuses()));
            Set<String> parents = new LinkedHashSet<>();
            for (MrpBom b : live) {
                parents.add(b.getParentItemCode());
            }
            roots.addAll(parents);
            bomCount = live.size();
        } else {
            roots.add(parentItemCode.trim());
            bomCount = bomDao.selectCount(new LambdaQueryWrapper<MrpBom>()
                    .eq(MrpBom::getParentItemCode, parentItemCode.trim())
                    .in(MrpBom::getStatus, liveStatuses()));
        }
        BomCycleChecker.Result r = cycleChecker.check(roots, this::liveChildren, maxDepth);
        List<String> cyclePaths = new ArrayList<>();
        for (List<String> c : r.getCycles()) {
            cyclePaths.add(String.join("→", c));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", all ? "ALL" : parentItemCode.trim());
        report.put("scannedRoots", roots.size());
        report.put("scannedBoms", bomCount);
        report.put("visitedNodes", r.getVisitedCount());
        report.put("maxDepth", maxDepth);
        report.put("depthExceeded", r.isDepthExceeded());
        report.put("cycles", cyclePaths);
        report.put("passed", cyclePaths.isEmpty());
        return report;
    }

    /** 父项在用版本（DRAFT∪PENDING∪PUBLISHED）的子项并集 */
    private List<String> liveChildren(String parentItemCode) {
        List<MrpBom> versions = bomDao.selectList(new LambdaQueryWrapper<MrpBom>()
                .eq(MrpBom::getParentItemCode, parentItemCode)
                .in(MrpBom::getStatus, liveStatuses()));
        if (versions.isEmpty()) {
            return List.of();
        }
        List<String> bomIds = new ArrayList<>();
        for (MrpBom v : versions) {
            bomIds.add(v.getId());
        }
        return itemCodes(itemDao.selectList(new LambdaQueryWrapper<MrpBomItem>()
                .in(MrpBomItem::getBomId, bomIds)));
    }

    private List<String> itemCodes(List<MrpBomItem> rows) {
        Set<String> codes = new LinkedHashSet<>();
        for (MrpBomItem row : rows) {
            if (isNotBlank(row.getItemCode())) {
                codes.add(row.getItemCode());
            }
        }
        return new ArrayList<>(codes);
    }

    private List<String> liveStatuses() {
        return Arrays.asList(MrpBom.ST_DRAFT, MrpBom.ST_PENDING, MrpBom.ST_PUBLISHED);
    }

    // ---------- 私有辅助 ----------

    /** 行写入：校验 + 按载荷序分配行号；UOM/名称以 MDM 主数据为准（服务端权威快照） */
    private void insertLines(String bomId, List<MrpBomItem> items) {
        requireLines(items);
        for (int i = 0; i < items.size(); i++) {
            MrpBomItem in = items.get(i);
            int lineNo = i + 1;
            if (in == null || !isNotBlank(in.getItemCode())) {
                throw new ServiceException(422, "第 " + lineNo + " 行子项物料必填");
            }
            MdmItem child = requireActiveItem(in.getItemCode(),
                    "子项物料不存在或已停用：" + in.getItemCode());
            if (in.getQty() == null || in.getQty().signum() <= 0) {
                throw new ServiceException(422, "第 " + lineNo + " 行用量必须大于 0");
            }
            BigDecimal loss = in.getLossRate() == null ? BigDecimal.ZERO : in.getLossRate();
            if (loss.signum() < 0 || loss.compareTo(new BigDecimal("100")) > 0) {
                throw new ServiceException(422, "第 " + lineNo + " 行损耗率须在 0~100% 区间");
            }
            MrpBomItem row = new MrpBomItem();
            row.setBomId(bomId);
            row.setLineNo(lineNo);
            row.setItemCode(child.getItemCode());
            row.setItemName(child.getItemName());
            row.setQty(in.getQty());
            row.setLossRate(loss);
            row.setUom(child.getBaseUnit());
            row.setEffectiveDate(in.getEffectiveDate());
            row.setExpiryDate(in.getExpiryDate());
            row.setRemark(in.getRemark());
            itemDao.insert(row);
            insertSubstitutes(row, in.getSubstitutes(), child);
        }
    }

    private void insertSubstitutes(MrpBomItem row, List<MrpBomSubstitute> subs, MdmItem child) {
        if (subs == null || subs.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (MrpBomSubstitute s : subs) {
            if (s == null || !isNotBlank(s.getSubstituteItemCode())) {
                throw new ServiceException(422, "第 " + row.getLineNo() + " 行替代料编码必填");
            }
            String code = s.getSubstituteItemCode().trim();
            if (!seen.add(code)) {
                throw new ServiceException(422, "第 " + row.getLineNo() + " 行重复配置替代料：" + code);
            }
            MdmItem target = requireActiveItem(code, "替代料不存在或已停用：" + code);
            if (isNotBlank(child.getBaseUnit()) && isNotBlank(target.getBaseUnit())
                    && !child.getBaseUnit().equals(target.getBaseUnit())) {
                throw new ServiceException(422, "主料与替代料计量单位须一致（本期不提供单位换算）：主料 "
                        + child.getBaseUnit() + " / 替代料 " + target.getBaseUnit());
            }
            if (s.getRatio() == null || s.getRatio().signum() <= 0) {
                throw new ServiceException(422, "第 " + row.getLineNo() + " 行替代比例必须大于 0");
            }
            MrpBomSubstitute sub = new MrpBomSubstitute();
            sub.setBomItemId(row.getId());
            sub.setSubstituteItemCode(target.getItemCode());
            sub.setSubstituteItemName(target.getItemName());
            sub.setRatio(s.getRatio());
            sub.setPriority(s.getPriority() == null || s.getPriority() < 1 ? 1 : s.getPriority());
            substituteDao.insert(sub);
        }
    }

    /** 版本号自动分配：首版 1.0，否则最新版本次 +1；upgradeMajor=true → 主+1 次归零（FR-4.5-1-5） */
    private int[] nextVersion(String parentItemCode) {
        return nextVersion(parentItemCode, false);
    }

    private int[] nextVersion(String parentItemCode, boolean upgradeMajor) {
        MrpBom latest = bomDao.selectOne(new LambdaQueryWrapper<MrpBom>()
                .eq(MrpBom::getParentItemCode, parentItemCode)
                .orderByDesc(MrpBom::getVersionMajor)
                .orderByDesc(MrpBom::getVersionMinor)
                .last("LIMIT 1"));
        if (latest == null) {
            return new int[]{1, 0};
        }
        int major = latest.getVersionMajor() == null ? 1 : latest.getVersionMajor();
        int minor = latest.getVersionMinor() == null ? 0 : latest.getVersionMinor();
        return upgradeMajor ? new int[]{major + 1, 0} : new int[]{major, minor + 1};
    }

    /**
     * 版本唯一键冲突（并发提交 / 逻辑删除行占键）自动递增重试（FR-4.5-1-5），最多 5 次。
     * 每次重试保证版本号严格递增（防在不可见冲突行间振荡）。
     */
    private void insertBomResolvingVersionConflict(MrpBom bom) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                bomDao.insert(bom);
                return;
            } catch (DuplicateKeyException e) {
                int curMajor = bom.getVersionMajor();
                int curMinor = bom.getVersionMinor();
                int[] next = nextVersion(bom.getParentItemCode());
                // 重算结果必须严格大于当前尝试值，否则强制次 +1
                if (next[0] < curMajor || (next[0] == curMajor && next[1] <= curMinor)) {
                    next = new int[]{curMajor, curMinor + 1};
                }
                bom.setVersionMajor(next[0]);
                bom.setVersionMinor(next[1]);
                log.warn("BOM 版本号冲突重试 {}/5：{} 调整为 V{}.{}",
                        attempt + 1, bom.getParentItemCode(), next[0], next[1]);
            }
        }
        throw new ServiceException(422, "版本号分配冲突重试超限，请稍后重试");
    }

    /** 同父项唯一在途（任务 4.3：存在草稿/待审核 → L1 阻断再创建/变更/复制）
     *  change add-trial-production-management：按试产/量产池分池（spec bom-management delta
     *  「试产版与量产版并存」——量产在途不挡试产创建，反之亦然；池内唯一照旧） */
    private void requireNoInFlight(String parentItemCode, boolean trialPool) {
        long inFlight = trialPool
                ? bomDao.selectCount(new LambdaQueryWrapper<MrpBom>()
                        .eq(MrpBom::getParentItemCode, parentItemCode)
                        .in(MrpBom::getStatus, MrpBom.ST_DRAFT, MrpBom.ST_PENDING)
                        .isNotNull(MrpBom::getTrialStage))
                : bomDao.selectCount(new LambdaQueryWrapper<MrpBom>()
                        .eq(MrpBom::getParentItemCode, parentItemCode)
                        .in(MrpBom::getStatus, MrpBom.ST_DRAFT, MrpBom.ST_PENDING)
                        .isNull(MrpBom::getTrialStage));
        if (inFlight > 0) {
            throw new ServiceException(422, trialPool
                    ? "该父项试产池存在在途版本（草稿/待审核），请先处理试产在途版本"
                    : "该父项存在在途版本（草稿/待审核），请先处理在途版本");
        }
    }

    /** 兼容旧调用（量产池语义） */
    private void requireNoInFlight(String parentItemCode) {
        requireNoInFlight(parentItemCode, false);
    }

    /** 试产阶段归一：空 → null（量产版）；EVT/DVT/PVT 大写；非法值 422 */
    private String normalizeTrialStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return null;
        }
        String s = stage.trim().toUpperCase();
        if (!"EVT".equals(s) && !"DVT".equals(s) && !"PVT".equals(s)) {
            throw new ServiceException(422, "试产阶段仅支持 EVT/DVT/PVT：" + stage);
        }
        return s;
    }

    /** 父项行级锁（design D2）：串行化唯一在途/唯一已发布判定（无行时靠唯一索引+重试兜底） */
    private void lockParentVersions(String parentItemCode) {
        bomDao.selectList(new LambdaQueryWrapper<MrpBom>()
                .eq(MrpBom::getParentItemCode, parentItemCode)
                .last("FOR UPDATE"));
    }

    /** 白名单 + CAS（id + status + ver_no），影响行数 0 → 422 并发冲突（任务 4.1） */
    private void casStatus(MrpBom bom, String to) {
        BomStatusRules.assertTransit(bom.getStatus(), to);
        int rows = bomDao.update(null, new LambdaUpdateWrapper<MrpBom>()
                .eq(MrpBom::getId, bom.getId())
                .eq(MrpBom::getStatus, bom.getStatus())
                .eq(MrpBom::getVerNo, bom.getVerNo())
                .set(MrpBom::getStatus, to)
                .set(MrpBom::getVerNo, bom.getVerNo() + 1));
        if (rows == 0) {
            throw new ServiceException(422,
                    "状态并发冲突（" + bom.getStatus() + " → " + to + "），请刷新后重试");
        }
        bom.setStatus(to);
        bom.setVerNo(bom.getVerNo() + 1);
    }

    /** 深克隆行与替代（复制 / 变更共用；子项停用则 422，停用物料不可被新业务引用） */
    private void cloneLines(String sourceBomId, String targetBomId) {
        List<MrpBomItem> srcItems = itemDao.selectList(
                new LambdaQueryWrapper<MrpBomItem>().eq(MrpBomItem::getBomId, sourceBomId)
                        .orderByAsc(MrpBomItem::getLineNo));
        for (MrpBomItem srcItem : srcItems) {
            MdmItem child = requireActiveItem(srcItem.getItemCode(),
                    "子项物料不存在或已停用：" + srcItem.getItemCode());
            MrpBomItem row = new MrpBomItem();
            row.setBomId(targetBomId);
            row.setLineNo(srcItem.getLineNo());
            row.setItemCode(child.getItemCode());
            row.setItemName(child.getItemName());
            row.setQty(srcItem.getQty());
            row.setLossRate(srcItem.getLossRate());
            row.setUom(srcItem.getUom());
            row.setEffectiveDate(srcItem.getEffectiveDate());
            row.setExpiryDate(srcItem.getExpiryDate());
            row.setRemark(srcItem.getRemark());
            itemDao.insert(row);

            List<MrpBomSubstitute> srcSubs = substituteDao.selectList(
                    new LambdaQueryWrapper<MrpBomSubstitute>()
                            .eq(MrpBomSubstitute::getBomItemId, srcItem.getId())
                            .orderByAsc(MrpBomSubstitute::getPriority));
            for (MrpBomSubstitute srcSub : srcSubs) {
                MrpBomSubstitute sub = new MrpBomSubstitute();
                sub.setBomItemId(row.getId());
                sub.setSubstituteItemCode(srcSub.getSubstituteItemCode());
                sub.setSubstituteItemName(srcSub.getSubstituteItemName());
                sub.setRatio(srcSub.getRatio());
                sub.setPriority(srcSub.getPriority());
                substituteDao.insert(sub);
            }
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

    /** 角色门（任务 5.3，freeze requireAny 同范式）：未登录 401、角色不符 403、ADMIN 直通 */
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
            String state = "0".equals(item.getStatus()) ? "停用" : "2".equals(item.getStatus()) ? "已归档" : "未发布";
            throw new ServiceException(422, notFoundMessage + "（当前状态：" + state + "）");
        }
        return item;
    }

    private void requireLines(List<MrpBomItem> items) {
        if (items == null || items.isEmpty()) {
            throw new ServiceException(422, "BOM 至少需要一行子项物料");
        }
    }

    private Map<String, Object> toMap(Object bean) {
        return objectMapper.convertValue(bean, new TypeReference<Map<String, Object>>() {
        });
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
