package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemCategoryDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mdm.MdmItemDictDao;
import com.erp.dao.mdm.MdmItemVersionDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mdm.MdmItemCategory;
import com.erp.entity.mdm.MdmItemVersion;
import com.erp.service.mdm.MdmItemService;
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
public class MdmItemServiceImpl implements MdmItemService {

    /** 采购类型（规格明文三项，后端枚举） */
    private static final Map<String, String> PURCHASE_TYPES = Map.of(
            "BUY", "外购", "MAKE", "自制", "OUTSOURCE", "委外");

    private static final String SNAPSHOT_FIELDS =
            "itemCode,itemName,categoryCode,baseUnit,materialGroup,purchaseType,storageCondition," +
            "tempLevel,hazardLevel,cleanLevel,abcClass," +
            "altItemCode,safetyStock,leadTimeDays,batchFlag,shelfLifeDays,packingSpec,barcode,bomVersion,status";

    private static final String ILLEGAL_CHARS = "<>&";

    private final MdmItemDao itemDao;
    private final MdmItemVersionDao versionDao;
    private final MdmItemCategoryDao categoryDao;
    private final MdmItemDictDao dictDao;
    private final ObjectMapper objectMapper;

    public MdmItemServiceImpl(MdmItemDao itemDao, MdmItemVersionDao versionDao,
                              MdmItemCategoryDao categoryDao, MdmItemDictDao dictDao,
                              ObjectMapper objectMapper) {
        this.itemDao = itemDao;
        this.versionDao = versionDao;
        this.categoryDao = categoryDao;
        this.dictDao = dictDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public Page<MdmItem> page(long current, long size, String keyword, String categoryCode, String status,
                             String hasSubstitute) {
        LambdaQueryWrapper<MdmItem> qw = new LambdaQueryWrapper<MdmItem>()
                .eq(isNotBlank(categoryCode), MdmItem::getCategoryCode, categoryCode)
                .eq(isNotBlank(status), MdmItem::getStatus, status)
                .and(isNotBlank(hasSubstitute) && "1".equals(hasSubstitute), w ->
                        w.isNotNull(MdmItem::getAltItemCode).ne(MdmItem::getAltItemCode, ""))
                .and(isNotBlank(hasSubstitute) && "0".equals(hasSubstitute), w ->
                        w.isNull(MdmItem::getAltItemCode).or().eq(MdmItem::getAltItemCode, ""))
                .and(isNotBlank(keyword), w -> w
                        .like(MdmItem::getItemCode, keyword)
                        .or()
                        .like(MdmItem::getItemName, keyword))
                .orderByAsc(MdmItem::getItemCode);
        return itemDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public MdmItem getById(String id) {
        MdmItem item = itemDao.selectById(id);
        if (item == null) {
            throw new ServiceException(404, "物料不存在");
        }
        return item;
    }

    @Override
    @Transactional
    public MdmItem create(MdmItem item, boolean forceCreate) {
        requireFields(item);
        MdmItemCategory category = requireCategory(item.getCategoryCode());
        requireDict("UNIT", item.getBaseUnit());
        requireDict("MATERIAL_GROUP", item.getMaterialGroup());
        requireDict("STORAGE", item.getStorageCondition());
        requireStorageAttrs(item);
        if (!PURCHASE_TYPES.containsKey(item.getPurchaseType())) {
            throw new ServiceException(422, "采购类型须为 BUY(外购)/MAKE(自制)/OUTSOURCE(委外) 之一");
        }
        // 名称特殊字符过滤（BR-4.1-10：含 <>& 阻断保存）
        for (char c : ILLEGAL_CHARS.toCharArray()) {
            if (item.getItemName().indexOf(c) >= 0) {
                throw new ServiceException(422, "物料名称含非法字符（< > &），请按「通用名+规格型号+材质」规范修改");
            }
        }
        // 批次管理必须填保质期（BR-4.1-08）
        if ("1".equals(item.getBatchFlag()) && (item.getShelfLifeDays() == null || item.getShelfLifeDays() <= 0)) {
            throw new ServiceException(422, "批次管理物料必须填写有效保质期天数");
        }
        // 差异化字段按分类前缀校验（简化属性集：原材料批次须保质期；成品须包装规格）
        if (category.getItemPrefix().equals("FG") && !isNotBlank(item.getPackingSpec())) {
            throw new ServiceException(422, "成品物料必须填写包装规格");
        }
        // 行 477 参照完整性：替代物料编码须已发布（新建时 sourceId=null，环与非自身按编码比对）
        if (isNotBlank(item.getAltItemCode())) {
            requireSubstituteValid(null, item.getItemCode(), item.getAltItemCode());
        }
        // 查重：名称编辑距离 ≤3 → 近3条，forceCreate+dupNote 放行（唯一性不放行）
        List<MdmItem> similar = findSimilarWithinDistance(item.getItemName(), 3);
        if (!similar.isEmpty()) {
            if (!forceCreate) {
                throw new ServiceException(409, "名称与既有物料高度相似，请确认是否重复（可携带 forceCreate=true 与 dupNote 继续）：" + summarize(similar));
            }
            if (!isNotBlank(item.getDupNote())) {
                throw new ServiceException(422, "确认非重复时必须填写差异说明");
            }
        }
        // 编码：自动生成 或 手输校验
        if (isNotBlank(item.getItemCode())) {
            validateManualCode(item, category);
            if (itemDao.selectCount(new LambdaQueryWrapper<MdmItem>().eq(MdmItem::getItemCode, item.getItemCode())) > 0) {
                throw new ServiceException(409, "物料编码已存在：" + item.getItemCode());
            }
        } else {
            item.setItemCode(null);
        }
        item.setId(null);
        item.setStatus("1");
        item.setVerNo(0);
        if (!isNotBlank(item.getItemCode())) {
            insertWithGeneratedCode(item, category);
        } else {
            itemDao.insert(item);
        }
        // 自制件 BOM 后补（BR-4.1-09 L4）：允许提交，响应携带 bomPending 提示
        if ("MAKE".equals(item.getPurchaseType()) && !isNotBlank(item.getBomVersion())) {
            item.setBomPending(true);
        }
        // TODO(FR-4.1-1-5 事件总线落地时回补)：发布 MDM.ITEM.PUBLISHED 事件
        saveSnapshot(item, "CREATE", null);
        return item;
    }

    @Override
    @Transactional
    public MdmItem update(MdmItem item) {
        MdmItem stored = itemDao.selectById(item.getId());
        if (stored == null) {
            throw new ServiceException(404, "物料不存在");
        }
        // BR-4.1-13：编码创建后不可修改
        if (item.getItemCode() != null && !item.getItemCode().equals(stored.getItemCode())) {
            throw new ServiceException(422, "物料编码创建后不可修改，如需更正请停用后新建");
        }
        requireFields(item);
        requireCategory(item.getCategoryCode());
        requireDict("UNIT", item.getBaseUnit());
        requireDict("MATERIAL_GROUP", item.getMaterialGroup());
        requireDict("STORAGE", item.getStorageCondition());
        requireStorageAttrs(item);
        if ("1".equals(item.getBatchFlag()) && (item.getShelfLifeDays() == null || item.getShelfLifeDays() <= 0)) {
            throw new ServiceException(422, "批次管理物料必须填写有效保质期天数");
        }
        // 行 477 参照完整性：替代物料编码须已发布
        if (isNotBlank(item.getAltItemCode())) {
            requireSubstituteValid(item.getId(), stored.getItemCode(), item.getAltItemCode());
        }
        String diff = buildDiff(stored, item);
        // 流程二 FR-4.1-2-1：无差异阻止提交（口径与 diffSummary 一致）
        if (diff == null || diff.isEmpty()) {
            throw new ServiceException(422, "无变更内容");
        }
        // 流程二 FR-4.1-2-1：变更原因必填（≥2字）
        if (item.getChangeReason() == null || item.getChangeReason().trim().length() < 2) {
            throw new ServiceException(422, "变更原因必填（至少 2 个字）");
        }
        // FR-4.1-2-2：变更分类 = 差异字段集 ∩ 关键属性清单 {计量单位、物料分类、采购类型}
        String changeType = resolveChangeType(stored, item);
        item.setItemCode(stored.getItemCode());
        item.setVerNo(item.getVerNo() == null ? stored.getVerNo() : item.getVerNo());
        if (itemDao.updateById(item) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        // TODO(BR-4.1-15 事件总线落地时回补)：发布 MDM.ITEM.UPDATED，仅携带 diffSummary 差异字段
        saveSnapshot(itemDao.selectById(item.getId()), "UPDATE", diff,
                item.getChangeReason().trim(), changeType);
        return itemDao.selectById(item.getId());
    }

    /** 关键属性清单：计量单位 baseUnit、物料分类 categoryCode、采购类型 purchaseType */
    private String resolveChangeType(MdmItem stored, MdmItem item) {
        boolean critical = !Objects.equals(stored.getBaseUnit(), item.getBaseUnit())
                || !Objects.equals(stored.getCategoryCode(), item.getCategoryCode())
                || !Objects.equals(stored.getPurchaseType(), item.getPurchaseType());
        return critical ? "CRITICAL" : "GENERAL";
    }

    @Override
    @Transactional
    public void disable(String id, String reason) {
        requireReason(reason);
        MdmItem stored = itemDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(404, "物料不存在");
        }
        // 仅 1→0：0 已停用 / 2 已归档均拒绝（归档态提交停用不得漏过）
        if (!"1".equals(stored.getStatus())) {
            throw new ServiceException(422, "仅启用中的物料可停用，当前状态："
                    + statusName(stored.getStatus()));
        }
        // 替代目标停用阻断：被启用物料指向 → 409 + 引用方清单（spec：替代目标停用阻断）
        List<MdmItem> referencing = itemDao.findSourcesByTarget(stored.getItemCode());
        if (!referencing.isEmpty()) {
            int total = itemDao.countSourcesByTarget(stored.getItemCode());
            throw new ServiceException(409, "该物料正被 " + total
                    + " 个物料作为替代引用，禁止停用（前10条）：" + summarize(referencing));
        }
        // C-4.1-03 下游引用校验桩：PO/SO/BOM 引用（流程一第 6 步影响分析）落地时回补
        List<String> references = queryDownstreamReferences(id);
        if (!references.isEmpty()) {
            throw new ServiceException(409, "该物料仍被下游业务数据引用，禁止停用：" + String.join("；", references));
        }
        transitionStatus(stored, "0", "DISABLE",
                "status: 1 → 0；原因：" + reason.trim(), reason.trim());
        // TODO(行 514 停用通知单：事件总线落地时回补)：发布 MDM.ITEM.DISABLED，推送采购/销售/库存
        log.info("MDM.ITEM.DISABLED itemCode={} reason={} by={}", stored.getItemCode(),
                reason.trim(), currentOperator());
    }

    @Override
    @Transactional
    public void enable(String id, String reason) {
        requireReason(reason);
        MdmItem stored = itemDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(404, "物料不存在");
        }
        if ("2".equals(stored.getStatus())) {
            throw new ServiceException(422, "已归档物料为终态，不可直接启用");
        }
        if (!"0".equals(stored.getStatus())) {
            throw new ServiceException(422, "仅停用状态可启用，当前状态：" + statusName(stored.getStatus()));
        }
        // 启用重校验：自身替代指向目标须仍启用（停用期间目标可能已失效）
        if (isNotBlank(stored.getAltItemCode())) {
            MdmItem target = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                    .eq(MdmItem::getItemCode, stored.getAltItemCode()));
            if (target == null) {
                throw new ServiceException(422, "替代目标不存在：" + stored.getAltItemCode()
                        + "，请先清除或改指替代");
            }
            if (!"1".equals(target.getStatus())) {
                throw new ServiceException(422, "替代目标已失效（" + statusName(target.getStatus())
                        + "）：" + stored.getAltItemCode() + "，请先清除或改指替代");
            }
        }
        transitionStatus(stored, "1", "ENABLE",
                "status: 0 → 1；原因：" + reason.trim(), reason.trim());
    }

    @Override
    @Transactional
    public void archive(String id, String reason) {
        requireReason(reason);
        MdmItem stored = itemDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(404, "物料不存在");
        }
        if (!"0".equals(stored.getStatus())) {
            throw new ServiceException(422, "仅停用状态可归档，当前状态：" + statusName(stored.getStatus()));
        }
        List<MdmItem> referencing = itemDao.findSourcesByTarget(stored.getItemCode());
        if (!referencing.isEmpty()) {
            int total = itemDao.countSourcesByTarget(stored.getItemCode());
            throw new ServiceException(409, "该物料正被 " + total
                    + " 个物料作为替代引用，禁止归档（前10条）：" + summarize(referencing));
        }
        transitionStatus(stored, "2", "ARCHIVE",
                "status: 0 → 2；原因：" + reason.trim(), reason.trim());
    }

    @Override
    public Map<String, Object> impact(String id) {
        MdmItem stored = itemDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(404, "物料不存在");
        }
        List<MdmItem> sources = itemDao.findSourcesByTarget(stored.getItemCode());
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, String>> substituteList = new ArrayList<>();
        for (MdmItem s : sources) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("itemCode", s.getItemCode());
            row.put("itemName", s.getItemName());
            row.put("status", s.getStatus());
            substituteList.add(row);
        }
        result.put("substituteSources", substituteList);
        result.put("substituteTotal", itemDao.countSourcesByTarget(stored.getItemCode()));
        result.put("downstream", queryDownstreamReferences(id));
        // 桩口径明示（spec：不得伪装为「无引用」的确定性结论）
        result.put("downstreamStub", true);
        result.put("downstreamNote", "采购/销售/生产模块未接入，暂无数据");
        return result;
    }

    @Override
    public Map<String, Object> disableBatch(List<String> ids, String reason) {
        requireReason(reason);
        List<Map<String, String>> succeeded = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();
        for (String id : ids) {
            try {
                disable(id, reason);
                MdmItem after = itemDao.selectById(id);
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("itemCode", after == null ? "" : after.getItemCode());
                succeeded.add(row);
            } catch (ServiceException e) {
                MdmItem failedItem = itemDao.selectById(id);
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("itemCode", failedItem == null ? "" : failedItem.getItemCode());
                row.put("code", String.valueOf(e.getCode()));
                row.put("message", e.getMessage());
                failed.add(row);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("succeeded", succeeded);
        result.put("failed", failed);
        return result;
    }

    /** 三态迁移共用：乐观锁更新 + 快照（changeReason 入版本链） */
    private void transitionStatus(MdmItem stored, String toStatus, String opType,
                                  String diffSummary, String reason) {
        MdmItem patch = new MdmItem();
        patch.setId(stored.getId());
        patch.setStatus(toStatus);
        patch.setVerNo(stored.getVerNo());
        if (itemDao.updateById(patch) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        saveSnapshot(itemDao.selectById(stored.getId()), opType, diffSummary, reason, "GENERAL");
    }

    private void requireReason(String reason) {
        if (!isNotBlank(reason)) {
            throw new ServiceException(422, "操作原因必填");
        }
    }

    private String statusName(String status) {
        if ("1".equals(status)) return "启用";
        if ("0".equals(status)) return "停用";
        if ("2".equals(status)) return "已归档";
        return "未知(" + status + ")";
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

    @Override
    public List<MdmItem> checkSimilar(String itemName) {
        List<MdmItem> hits = findSimilarWithinDistance(itemName, 3);
        return hits.subList(0, Math.min(3, hits.size()));
    }

    @Override
    public List<Map<String, String>> categories() {
        List<Map<String, String>> list = new ArrayList<>();
        for (MdmItemCategory c : categoryDao.selectAllActive()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("code", c.getCategoryCode());
            m.put("name", c.getCategoryName());
            m.put("prefix", c.getItemPrefix());
            m.put("level", String.valueOf(c.getLevel()));
            list.add(m);
        }
        return list;
    }

    @Override
    public List<Map<String, String>> dicts(String type) {
        List<Map<String, String>> list = new ArrayList<>();
        for (var d : dictDao.selectByType(type)) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("code", d.getDictCode());
            m.put("name", d.getDictName());
            list.add(m);
        }
        return list;
    }

    @Override
    public List<Map<String, String>> options() {
        List<MdmItem> list = itemDao.selectList(
                new LambdaQueryWrapper<MdmItem>()
                        .eq(MdmItem::getStatus, "1")
                        .orderByAsc(MdmItem::getItemCode));
        List<Map<String, String>> options = new ArrayList<>();
        for (MdmItem i : list) {
            Map<String, String> o = new LinkedHashMap<>();
            o.put("id", i.getId());
            o.put("code", i.getItemCode());
            o.put("name", i.getItemName());
            options.add(o);
        }
        return options;
    }

    @Override
    public List<MdmItemVersion> versions(String entityId) {
        return versionDao.selectList(
                new LambdaQueryWrapper<MdmItemVersion>()
                        .eq(MdmItemVersion::getEntityId, entityId)
                        .orderByDesc(MdmItemVersion::getVersionNo));
    }

    @Override
    public Map<String, Object> diff(String entityId, int from, int to) {
        MdmItemVersion vFrom = findVersion(entityId, from);
        MdmItemVersion vTo = findVersion(entityId, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", versionMeta(vFrom));
        result.put("to", versionMeta(vTo));
        result.put("fields", compareSnapshots(vFrom.getSnapshotJson(), vTo.getSnapshotJson()));
        return result;
    }

    // ---------- 替代关系（1.2.5） ----------

    @Override
    public Map<String, Object> substituteList(String keyword, String direction, String status,
                                              long current, long size) {
        long total = itemDao.countSubstituteRelations(keyword, direction, status);
        List<Map<String, Object>> records = itemDao.selectSubstituteRelations(
                keyword, direction, status, size, (current - 1) * size);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", records);
        page.put("total", total);
        return page;
    }

    @Override
    @Transactional
    public MdmItem setSubstitute(String id, String substituteCode) {
        MdmItem stored = itemDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(404, "物料不存在");
        }
        if (!isNotBlank(substituteCode)) {
            throw new ServiceException(422, "替代物料编码不能为空（清空请用清除替代）");
        }
        requireSubstituteValid(stored.getId(), stored.getItemCode(), substituteCode);
        if (substituteCode.equals(stored.getAltItemCode())) {
            throw new ServiceException(422, "替代未变化");
        }
        MdmItem patch = new MdmItem();
        patch.setId(id);
        patch.setAltItemCode(substituteCode);
        patch.setVerNo(stored.getVerNo());
        if (itemDao.updateById(patch) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        // TODO(BR-4.1-15 事件总线落地时回补)：差异同步
        saveSnapshot(itemDao.selectById(id), "UPDATE",
                "altItemCode: " + (isNotBlank(stored.getAltItemCode()) ? stored.getAltItemCode() : "（空）")
                        + " → " + substituteCode,
                "设置替代", "GENERAL");
        return itemDao.selectById(id);
    }

    @Override
    @Transactional
    public MdmItem clearSubstitute(String id) {
        MdmItem stored = itemDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(404, "物料不存在");
        }
        if (!isNotBlank(stored.getAltItemCode())) {
            throw new ServiceException(422, "该物料未配置替代");
        }
        MdmItem patch = new MdmItem();
        patch.setId(id);
        // updateById 忽略 null，用空串清空（列表 SQL 已排除空串）
        patch.setAltItemCode("");
        patch.setVerNo(stored.getVerNo());
        if (itemDao.updateById(patch) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        saveSnapshot(itemDao.selectById(id), "UPDATE",
                "altItemCode: " + stored.getAltItemCode() + " → （空）", "清除替代", "GENERAL");
        return itemDao.selectById(id);
    }

    @Override
    public void checkSubstitute(String itemCode, String substituteCode) {
        MdmItem source = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, itemCode));
        if (source == null) {
            throw new ServiceException(404, "物料不存在：" + itemCode);
        }
        // dry-run：仅执行三校验，无任何写入（表单失焦实时提示用）
        requireSubstituteValid(source.getId(), source.getItemCode(), substituteCode);
    }

    /**
     * 替代三校验（行 477 gap 补齐）：已发布 + 非自身 + 间接环。
     * sourceCode 为空（自动编码新建）时链上不可能存在本物料，跳过环回溯。
     */
    private void requireSubstituteValid(String sourceId, String sourceCode, String substituteCode) {
        MdmItem target = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, substituteCode));
        if (target == null) {
            throw new ServiceException(422, "替代物料不存在：" + substituteCode);
        }
        if (!"1".equals(target.getStatus())) {
            throw new ServiceException(422, "替代物料须已发布（启用中）：" + substituteCode);
        }
        if ((sourceId != null && target.getId().equals(sourceId))
                || (isNotBlank(sourceCode) && target.getItemCode().equals(sourceCode))) {
            throw new ServiceException(422, "物料不能替代自身");
        }
        if (!isNotBlank(sourceCode)) {
            return; // 自动编码新建：本物料尚不存在于任何链上
        }
        // 间接环：从目标沿 altItemCode 回溯，visited 判重（连既有脏环一并拦）
        java.util.Set<String> visited = new java.util.HashSet<>();
        visited.add(target.getItemCode());
        StringBuilder chain = new StringBuilder(target.getItemCode());
        String next = target.getAltItemCode();
        int depth = 0;
        while (isNotBlank(next) && depth < 10) {
            if (next.equals(sourceCode)) {
                chain.append(" → ").append(next);
                throw new ServiceException(422, "替代链成环：" + chain);
            }
            if (!visited.add(next)) {
                throw new ServiceException(422, "替代链异常（存在既有环）：" + chain);
            }
            chain.append(" → ").append(next);
            MdmItem node = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                    .eq(MdmItem::getItemCode, next));
            if (node == null) {
                break; // 悬空指向：链断，无环
            }
            next = node.getAltItemCode();
            depth++;
        }
        if (depth >= 10 && isNotBlank(next)) {
            throw new ServiceException(422, "替代链超过 10 层（疑似脏数据）：" + chain);
        }
    }

    // ---------- 编码 ----------

    /**
     * 自动编码：{前缀} + 4位分类码 + 6位流水，按分类独立计数。
     * 前缀长 = prefix.length() + 4（分类码恒 4 位），流水取 substring(prefixLen)。
     */
    private void insertWithGeneratedCode(MdmItem item, MdmItemCategory category) {
        String prefix = category.getItemPrefix() + category.getCategoryCode();
        int prefixLen = prefix.length();
        String maxCode = itemDao.selectMaxCodeByCategory(category.getCategoryCode());
        long serial = 0;
        if (maxCode != null && maxCode.matches(prefix + "\\d{6}")) {
            serial = Long.parseLong(maxCode.substring(prefixLen));
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            item.setItemCode(prefix + String.format("%06d", serial + 1 + attempt));
            try {
                itemDao.insert(item);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                String current = itemDao.selectMaxCodeByCategory(category.getCategoryCode());
                if (current != null && current.matches(prefix + "\\d{6}")) {
                    serial = Long.parseLong(current.substring(prefixLen));
                }
            }
        }
        throw new ServiceException(500, "物料编码生成失败，请重试");
    }

    /** 手输编码：格式 {FG|RM|WIP}+4位+6位，且前缀与分类码须与所选分类一致（BR-4.1-07 L1） */
    private void validateManualCode(MdmItem item, MdmItemCategory category) {
        String code = item.getItemCode();
        if (!code.matches("(FG|RM|WIP)\\d{4}\\d{6}")) {
            List<MdmItem> similar = itemDao.findSimilarCandidates(code.substring(0, Math.min(6, code.length())));
            throw new ServiceException(422, "编码格式须为 {FG|RM|WIP}+4位分类码+6位流水，相似编码：" + summarize(similar));
        }
        String expectPrefix = category.getItemPrefix() + category.getCategoryCode();
        if (!code.startsWith(expectPrefix)) {
            List<MdmItem> similar = itemDao.findSimilarCandidates(code.substring(0, 6));
            throw new ServiceException(422, "编码前缀与所选分类不一致（应为 " + expectPrefix + "），相似编码：" + summarize(similar));
        }
    }

    // ---------- 校验 ----------

    private MdmItemCategory requireCategory(String categoryCode) {
        if (!isNotBlank(categoryCode)) {
            throw new ServiceException(422, "物料分类不能为空");
        }
        MdmItemCategory c = categoryDao.selectOne(new LambdaQueryWrapper<MdmItemCategory>()
                .eq(MdmItemCategory::getCategoryCode, categoryCode));
        if (c == null) {
            throw new ServiceException(422, "所选物料分类不存在：" + categoryCode);
        }
        // 停用分类不可被新业务引用（C-4.1-03「停用后新业务不可引用」，本轮分类维护补强）
        if ("0".equals(c.getStatus())) {
            throw new ServiceException(422, "所选物料分类已停用：" + categoryCode);
        }
        return c;
    }

    private void requireDict(String dictType, String code) {
        if (!isNotBlank(code) || dictDao.countActiveCode(dictType, code) == 0) {
            throw new ServiceException(422, "参照数据无效（" + dictType + "）：" + code);
        }
    }

    /**
     * 三个存储属性校验（change add-bin-assignment，spec item-master-creation）：
     * 可空（NULL=无要求放行，偏差 D2）；非空 MUST 命中对应 4.1.3 字典启用条目，非法 422。
     */
    private void requireStorageAttrs(MdmItem item) {
        requireDictIfPresent("TEMP_LEVEL", item.getTempLevel());
        requireDictIfPresent("HAZARD_LEVEL", item.getHazardLevel());
        requireDictIfPresent("CLEAN_LEVEL", item.getCleanLevel());
        requireAbcClass(item);
    }

    /**
     * ABC 分类取值域校验（change add-inventory-reports，spec item-master-creation MODIFIED）：
     * A/B/C 或空（NULL=未分类，报表汇总归入「未分类」），非法 422。
     */
    private void requireAbcClass(MdmItem item) {
        String v = item.getAbcClass();
        if (!isNotBlank(v)) {
            item.setAbcClass(null);   // 归一化：空串 → NULL（未分类）
            return;
        }
        v = v.trim();
        if (!List.of("A", "B", "C").contains(v)) {
            throw new ServiceException(422, "ABC 分类仅支持 A/B/C 或留空（当前：" + v + "）");
        }
        item.setAbcClass(v);
    }

    private void requireDictIfPresent(String dictType, String code) {
        if (isNotBlank(code) && dictDao.countActiveCode(dictType, code) == 0) {
            throw new ServiceException(422, "参照数据无效（" + dictType + "）：" + code);
        }
    }

    /**
     * 编辑距离候选池：存量 ≤5000 时全量扫描（精确符合编辑距离 ≤N 语义），
     * 超过则退化为前 4 字符 LIKE 预筛（避免大表全扫）。
     */
    private List<MdmItem> similarityPool(String itemName) {
        long total = itemDao.selectCount(null);
        if (total <= 5000) {
            return itemDao.selectList(new LambdaQueryWrapper<MdmItem>()
                    .orderByAsc(MdmItem::getItemCode));
        }
        String head = itemName.substring(0, Math.min(4, itemName.length()));
        return itemDao.findSimilarCandidates(head);
    }

    private List<MdmItem> findSimilarWithinDistance(String itemName, int maxDistance) {
        if (!isNotBlank(itemName)) {
            return new ArrayList<>();
        }
        return com.erp.common.SimilarityUtil.similarWithin(
                similarityPool(itemName), itemName, maxDistance, 3, MdmItem::getItemName);
    }

    // ---------- 快照与工具 ----------

    private void saveSnapshot(MdmItem source, String opType, String diffSummary) {
        saveSnapshot(source, opType, diffSummary, null, null);
    }

    private void saveSnapshot(MdmItem source, String opType, String diffSummary,
                              String changeReason, String changeType) {
        int next = versionDao.selectCount(
                new LambdaQueryWrapper<MdmItemVersion>()
                        .eq(MdmItemVersion::getEntityId, source.getId())).intValue() + 1;
        MdmItemVersion v = new MdmItemVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source));
        v.setDiffSummary(diffSummary);
        v.setOpType(opType);
        v.setChangeReason(changeReason);
        v.setChangeType(changeType);
        versionDao.insert(v);
    }

    private MdmItemVersion findVersion(String entityId, int versionNo) {
        MdmItemVersion v = versionDao.selectOne(
                new LambdaQueryWrapper<MdmItemVersion>()
                        .eq(MdmItemVersion::getEntityId, entityId)
                        .eq(MdmItemVersion::getVersionNo, versionNo));
        if (v == null) {
            throw new ServiceException(404, "版本不存在：" + entityId + " V" + versionNo);
        }
        return v;
    }

    private List<Map<String, String>> compareSnapshots(String fromJson, String toJson) {
        JsonNode from = readTree(fromJson);
        JsonNode to = readTree(toJson);
        List<Map<String, String>> fields = new ArrayList<>();
        for (String field : SNAPSHOT_FIELDS.split(",")) {
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

    private String buildDiff(MdmItem oldRow, MdmItem newRow) {
        StringBuilder sb = new StringBuilder();
        appendDiff(sb, "itemName", oldRow.getItemName(), newRow.getItemName());
        appendDiff(sb, "categoryCode", oldRow.getCategoryCode(), newRow.getCategoryCode());
        appendDiff(sb, "baseUnit", oldRow.getBaseUnit(), newRow.getBaseUnit());
        appendDiff(sb, "materialGroup", oldRow.getMaterialGroup(), newRow.getMaterialGroup());
        appendDiff(sb, "purchaseType", oldRow.getPurchaseType(), newRow.getPurchaseType());
        appendDiff(sb, "storageCondition", oldRow.getStorageCondition(), newRow.getStorageCondition());
        appendDiff(sb, "tempLevel", oldRow.getTempLevel(), newRow.getTempLevel());
        appendDiff(sb, "hazardLevel", oldRow.getHazardLevel(), newRow.getHazardLevel());
        appendDiff(sb, "cleanLevel", oldRow.getCleanLevel(), newRow.getCleanLevel());
        appendDiff(sb, "abcClass", oldRow.getAbcClass(), newRow.getAbcClass());
        appendDiff(sb, "altItemCode", oldRow.getAltItemCode(), newRow.getAltItemCode());
        appendDiff(sb, "safetyStock", str(oldRow.getSafetyStock()), str(newRow.getSafetyStock()));
        appendDiff(sb, "leadTimeDays", str(oldRow.getLeadTimeDays()), str(newRow.getLeadTimeDays()));
        appendDiff(sb, "batchFlag", oldRow.getBatchFlag(), newRow.getBatchFlag());
        appendDiff(sb, "shelfLifeDays", str(oldRow.getShelfLifeDays()), str(newRow.getShelfLifeDays()));
        appendDiff(sb, "packingSpec", oldRow.getPackingSpec(), newRow.getPackingSpec());
        appendDiff(sb, "barcode", oldRow.getBarcode(), newRow.getBarcode());
        appendDiff(sb, "bomVersion", oldRow.getBomVersion(), newRow.getBomVersion());
        String s = sb.toString();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private void appendDiff(StringBuilder sb, String field, String a, String b) {
        String oldVal = a == null ? "" : a;
        String newVal = b == null ? "" : b;
        if (!Objects.equals(oldVal, newVal)) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(field).append(": ").append(oldVal).append(" → ").append(newVal);
        }
    }

    private void requireFields(MdmItem item) {
        if (!isNotBlank(item.getItemName())) {
            throw new ServiceException(422, "物料名称不能为空");
        }
        if (!isNotBlank(item.getBaseUnit())) {
            throw new ServiceException(422, "基本计量单位不能为空");
        }
        if (!isNotBlank(item.getMaterialGroup())) {
            throw new ServiceException(422, "物料组不能为空");
        }
        if (!isNotBlank(item.getStorageCondition())) {
            throw new ServiceException(422, "存储条件不能为空");
        }
    }

    /** TODO(行 801：物料被 200+ 生效 BOM 引用时改分类须转批量影响评估，BOM 模块落地时回补)；另含流程一第 6 步 PO/SO 引用影响分析 */
    private List<String> queryDownstreamReferences(String id) {
        return new ArrayList<>();
    }

    private Map<String, Object> versionMeta(MdmItemVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionNo", v.getVersionNo());
        m.put("opType", v.getOpType());
        m.put("createBy", v.getCreateBy());
        m.put("createDate", v.getCreateDate());
        return m;
    }

    private String summarize(List<MdmItem> list) {
        StringBuilder sb = new StringBuilder();
        for (MdmItem i : list) {
            if (sb.length() > 0) sb.append("、");
            sb.append(i.getItemCode()).append(" ").append(i.getItemName());
        }
        return sb.length() > 200 ? sb.substring(0, 200) + "…" : sb.toString();
    }

    private String toJson(MdmItem item) {
        return snapshotJson(item, objectMapper);
    }

    /**
     * 物料快照序列化（静态共享）：分类合并的批量改挂由分类服务写物料版本快照，
     * 复用本口径避免 SNAPSHOT_FIELDS 双处维护漂移。
     */
    public static String snapshotJson(MdmItem item, ObjectMapper om) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String field : SNAPSHOT_FIELDS.split(",")) {
            map.put(field.trim(), readFieldStatic(item, field.trim()));
        }
        try {
            return om.writeValueAsString(map);
        } catch (JsonProcessingException ex) {
            throw new ServiceException(500, "版本快照序列化失败");
        }
    }

    private static Object readFieldStatic(MdmItem i, String field) {
        switch (field) {
            case "itemCode": return i.getItemCode();
            case "itemName": return i.getItemName();
            case "categoryCode": return i.getCategoryCode();
            case "baseUnit": return i.getBaseUnit();
            case "materialGroup": return i.getMaterialGroup();
            case "purchaseType": return i.getPurchaseType();
            case "storageCondition": return i.getStorageCondition();
            case "altItemCode": return i.getAltItemCode();
            case "safetyStock": return i.getSafetyStock();
            case "leadTimeDays": return i.getLeadTimeDays();
            case "batchFlag": return i.getBatchFlag();
            case "shelfLifeDays": return i.getShelfLifeDays();
            case "packingSpec": return i.getPackingSpec();
            case "barcode": return i.getBarcode();
            case "bomVersion": return i.getBomVersion();
            case "abcClass": return i.getAbcClass();
            case "status": return i.getStatus();
            default: return null;
        }
    }

    private Object readField(MdmItem i, String field) {
        return readFieldStatic(i, field);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
