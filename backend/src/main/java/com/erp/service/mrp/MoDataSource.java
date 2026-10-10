package com.erp.service.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mrp.MrpBomDao;
import com.erp.dao.mrp.MrpBomItemDao;
import com.erp.dao.mrp.MrpBomSubstituteDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpOpWcStandardDao;
import com.erp.dao.mrp.MrpRoutingDao;
import com.erp.dao.mrp.MrpRoutingOpDao;
import com.erp.dao.mrp.MrpSuggestionDao;
import com.erp.dao.mrp.MrpWorkCenterDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
import com.erp.entity.mrp.MrpBomSubstitute;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.MrpSuggestion;
import com.erp.entity.mrp.MrpWorkCenter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工单数据源内聚类（change add-work-order-management，design D4/D1——口径变更只改此处）。
 *
 * 任务 1.3 核实结论（已写回 design Risks）：
 * 1) 在制余额：库存域无独立在制表；inProcess 以工单表自身算
 *    （RELEASED/HOLD/未关闭 COMPLETED 的 QTY − 已确认合格产出），供 5.3 InProcess 桩与下游消费。
 * 2) 超交校验（BR-4.5-15）：复用 soDemands 口径（CONFIRMED 头 + OPEN 行未交量），无 SO 需求时跳过。
 * 3) 工序四类工时来源 = 定额矩阵（erp_mrp_op_wc_standard，5.2.3）——路线行只带 lead_time，
 *    分配工作中心后按 (opCode, wcCode) 带出（FR-4.5-3-4「自动带入标准工时」）。
 */
@Slf4j
@Component
public class MoDataSource {

    /** BOM 快照行（展平）：unitQty/lossRate 为相对工单产品的有效值，保持 REQ=QTY×用量×(1+损耗) 口径 */
    public static class BomSnapshotLine {
        public int treeLevel;
        public String ownerItem;
        public Integer lineNo;
        public String itemCode;
        public String itemName;
        public BigDecimal unitQty;
        public BigDecimal lossRate;
        public String baseUnit;
        public String substituteInfo;
    }

    /** BOM 快照包：来源版本元数据 + 展平行 */
    public static class BomSnapshot {
        public String bomId;
        public String bomVersion;
        public List<BomSnapshotLine> lines = new ArrayList<>();
    }

    /** 路线快照包：来源版本元数据 + 工序行（WC 状态/定额校验由服务层做） */
    public static class RoutingSnapshot {
        public String routingId;
        public String routingVersion;
        public List<MrpRoutingOp> ops = new ArrayList<>();
    }

    private final MrpDataSource mrpDataSource;
    private final MrpBomDao bomDao;
    private final MrpBomItemDao bomItemDao;
    private final MrpBomSubstituteDao bomSubstituteDao;
    private final MrpRoutingDao routingDao;
    private final MrpRoutingOpDao routingOpDao;
    private final MrpWorkCenterDao workCenterDao;
    private final MrpOpWcStandardDao opWcStandardDao;
    private final MdmItemDao mdmItemDao;
    private final MrpSuggestionDao suggestionDao;
    private final MrpMoDao moDao;
    private final com.erp.service.SysParamService sysParam;
    private final ObjectMapper objectMapper;

    public MoDataSource(MrpDataSource mrpDataSource, MrpBomDao bomDao, MrpBomItemDao bomItemDao,
                        MrpBomSubstituteDao bomSubstituteDao, MrpRoutingDao routingDao,
                        MrpRoutingOpDao routingOpDao, MrpWorkCenterDao workCenterDao,
                        MrpOpWcStandardDao opWcStandardDao, MdmItemDao mdmItemDao,
                        MrpSuggestionDao suggestionDao, MrpMoDao moDao,
                        com.erp.service.SysParamService sysParamService, ObjectMapper objectMapper) {
        this.mrpDataSource = mrpDataSource;
        this.bomDao = bomDao;
        this.bomItemDao = bomItemDao;
        this.bomSubstituteDao = bomSubstituteDao;
        this.routingDao = routingDao;
        this.routingOpDao = routingOpDao;
        this.workCenterDao = workCenterDao;
        this.opWcStandardDao = opWcStandardDao;
        this.mdmItemDao = mdmItemDao;
        this.suggestionDao = suggestionDao;
        this.moDao = moDao;
        this.sysParam = sysParamService;
        this.objectMapper = objectMapper;
    }

    /** 复用 5.3 数据源口径（SO/库存/在途） */
    public MrpDataSource base() {
        return mrpDataSource;
    }

    /**
     * 已发布 BOM 多层展平快照（design D4）：产品直接子项为层级 1，MAKE 子件有已发布 BOM 则继续展开；
     * 展平行的 unitQty = 链上单耗乘积、lossRate = Π(1+损耗)−1（保持齐套公式口径）；
     * 深度上限复用 bom-max-nesting-depth。返回 null = 产品无已发布 BOM（创建时 L1 阻断）。
     */
    public BomSnapshot publishedBomSnapshot(String itemCode) {
        MrpBom rootBom = bomDao.selectOne(new LambdaQueryWrapper<MrpBom>()
                .eq(MrpBom::getParentItemCode, itemCode)
                .eq(MrpBom::getStatus, MrpBom.ST_PUBLISHED)
                .orderByDesc(MrpBom::getVersionMajor)
                .orderByDesc(MrpBom::getVersionMinor)
                .last("LIMIT 1"));
        if (rootBom == null) {
            return null;
        }
        BomSnapshot snap = new BomSnapshot();
        snap.bomId = rootBom.getId();
        snap.bomVersion = rootBom.getVersionLabel();
        int maxDepth = sysParam.getInt("BOM_MAX_NESTING_DEPTH", 8);

        // BFS：队列元素 = (父件编码, 父 BOM id, 层级, 链单耗, 链 (1+损耗) 乘积)
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{itemCode, rootBom.getId(), 1, BigDecimal.ONE, BigDecimal.ONE});
        int seq = 0;
        Set<String> visited = new HashSet<>();
        visited.add(itemCode);
        while (!queue.isEmpty()) {
            Object[] cur = queue.poll();
            String owner = (String) cur[0];
            String bomId = (String) cur[1];
            int level = (Integer) cur[2];
            BigDecimal chainUsage = (BigDecimal) cur[3];
            BigDecimal chainLoss = (BigDecimal) cur[4];
            if (level > maxDepth) {
                log.warn("BOM 快照展开达到深度上限 {}，截断 {}", maxDepth, owner);
                continue;
            }
            List<MrpBomItem> rows = bomItemDao.selectList(new LambdaQueryWrapper<MrpBomItem>()
                    .eq(MrpBomItem::getBomId, bomId)
                    .orderByAsc(MrpBomItem::getLineNo));
            for (MrpBomItem row : rows) {
                BomSnapshotLine line = new BomSnapshotLine();
                line.treeLevel = level;
                line.ownerItem = owner;
                line.lineNo = ++seq;
                line.itemCode = row.getItemCode();
                MdmItem it = mdmItemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                        .eq(MdmItem::getItemCode, row.getItemCode()));
                line.itemName = it != null ? it.getItemName() : row.getItemName();
                line.baseUnit = it != null ? it.getBaseUnit() : null;
                BigDecimal rowLoss = row.getLossRate() == null ? BigDecimal.ZERO : row.getLossRate();
                line.unitQty = chainUsage.multiply(row.getQty());
                // 有效损耗：Π(1+损耗)−1（链上累乘），行级损耗并入链
                line.lossRate = chainLoss.multiply(rowLoss.add(BigDecimal.ONE))
                        .subtract(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
                line.substituteInfo = substituteJson(row.getId());
                snap.lines.add(line);

                // 子件若为自制且有已发布 BOM → 继续展开（环由保存时卡控保证 + 深度上限兜底）
                if (it != null && "MAKE".equals(it.getPurchaseType())
                        && level < maxDepth && visited.add(row.getItemCode())) {
                    MrpBom childBom = bomDao.selectOne(new LambdaQueryWrapper<MrpBom>()
                            .eq(MrpBom::getParentItemCode, row.getItemCode())
                            .eq(MrpBom::getStatus, MrpBom.ST_PUBLISHED)
                            .orderByDesc(MrpBom::getVersionMajor)
                            .orderByDesc(MrpBom::getVersionMinor)
                            .last("LIMIT 1"));
                    if (childBom != null) {
                        queue.add(new Object[]{row.getItemCode(), childBom.getId(), level + 1,
                                chainUsage.multiply(row.getQty()),
                                chainLoss.multiply(rowLoss.add(BigDecimal.ONE))});
                    }
                }
            }
        }
        return snap;
    }

    /** 行级替代料 JSON 快照（无替代返回 null） */
    private String substituteJson(String bomItemId) {
        List<MrpBomSubstitute> subs = bomSubstituteDao.selectList(new LambdaQueryWrapper<MrpBomSubstitute>()
                .eq(MrpBomSubstitute::getBomItemId, bomItemId)
                .orderByAsc(MrpBomSubstitute::getPriority));
        if (subs.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (MrpBomSubstitute sub : subs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemCode", sub.getSubstituteItemCode());
            m.put("itemName", sub.getSubstituteItemName());
            m.put("ratio", sub.getRatio());
            m.put("priority", sub.getPriority());
            out.add(m);
        }
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("替代料快照 JSON 序列化失败：{}", bomItemId, e);
            return null;
        }
    }

    /** 已发布路线快照（工序行）；null = 产品无已发布路线（创建时提示放行） */
    public RoutingSnapshot publishedRoutingSnapshot(String itemCode) {
        MrpRouting routing = routingDao.selectOne(new LambdaQueryWrapper<MrpRouting>()
                .eq(MrpRouting::getItemCode, itemCode)
                .eq(MrpRouting::getStatus, MrpRouting.ST_PUBLISHED)
                .orderByDesc(MrpRouting::getVersionMajor)
                .orderByDesc(MrpRouting::getVersionMinor)
                .last("LIMIT 1"));
        if (routing == null) {
            return null;
        }
        RoutingSnapshot snap = new RoutingSnapshot();
        snap.routingId = routing.getId();
        snap.routingVersion = routing.getVersionMajor() + "." + routing.getVersionMinor();
        snap.ops = routingOpDao.selectList(new LambdaQueryWrapper<MrpRoutingOp>()
                .eq(MrpRoutingOp::getRoutingId, routing.getId())
                .orderByAsc(MrpRoutingOp::getOpSeq));
        return snap;
    }

    /** 工作中心索引（停用校验用） */
    public Map<String, MrpWorkCenter> workCenters(Collection<String> wcCodes) {
        Map<String, MrpWorkCenter> out = new HashMap<>();
        if (wcCodes == null || wcCodes.isEmpty()) {
            return out;
        }
        Set<String> codes = new HashSet<>(wcCodes);
        codes.remove(null);
        if (codes.isEmpty()) {
            return out;
        }
        for (MrpWorkCenter wc : workCenterDao.selectList(new LambdaQueryWrapper<MrpWorkCenter>()
                .in(MrpWorkCenter::getWcCode, codes))) {
            out.put(wc.getWcCode(), wc);
        }
        return out;
    }

    /** 定额矩阵行（四类标准工时带出；null = 该工序×工作中心无定额 → 创建提示更换） */
    public MrpOpWcStandard standardOf(String opCode, String wcCode) {
        if (opCode == null || wcCode == null) {
            return null;
        }
        return opWcStandardDao.selectOne(new LambdaQueryWrapper<MrpOpWcStandard>()
                .eq(MrpOpWcStandard::getOpCode, opCode)
                .eq(MrpOpWcStandard::getWcCode, wcCode)
                .last("LIMIT 1"));
    }

    /** 可选 PMO 列表：已转正生产建议且未关联工单（5.3 D6 对接） */
    public List<MrpSuggestion> candidatePmos() {
        return suggestionDao.selectList(new LambdaQueryWrapper<MrpSuggestion>()
                .eq(MrpSuggestion::getType, MrpSuggestion.TYPE_PRODUCTION)
                .eq(MrpSuggestion::getStatus, MrpSuggestion.ST_CONVERTED)
                .likeRight(MrpSuggestion::getTargetNo, "PMO-")
                .isNull(MrpSuggestion::getMoNo)
                .orderByDesc(MrpSuggestion::getCreateDate));
    }

    /**
     * 在制供给（任务 1.3 结论：库存域无在制表，以工单表自身算）：
     * RELEASED/HOLD/未关闭 COMPLETED 的剩余量 = QTY − 已确认合格产出（下限 0）。
     * 供 5.3 InProcess 桩与下游只读消费（spec「下游消费契约」②）。
     */
    public Map<String, BigDecimal> inProcess(Set<String> itemCodes) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (itemCodes == null || itemCodes.isEmpty()) {
            return out;
        }
        List<MrpMo> mos = moDao.selectList(new LambdaQueryWrapper<MrpMo>()
                .in(MrpMo::getProductCode, itemCodes)
                .in(MrpMo::getStatus, MrpMo.ST_RELEASED, MrpMo.ST_HOLD, MrpMo.ST_COMPLETED));
        for (MrpMo mo : mos) {
            BigDecimal done = mo.getQualifiedQty() == null ? BigDecimal.ZERO : mo.getQualifiedQty();
            BigDecimal remain = mo.getQty().subtract(done);
            if (remain.signum() > 0) {
                out.merge(mo.getProductCode(), remain, BigDecimal::add);
            }
        }
        return out;
    }
}
