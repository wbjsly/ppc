package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpMoBomDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpMoShortageDao;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoBom;
import com.erp.entity.mrp.MrpMoShortage;
import com.erp.service.mrp.KittingService;
import com.erp.service.mrp.MoDataSource;
import com.erp.service.SysParamService;
import com.erp.service.system.NoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 齐套计算实现（change add-work-order-management，spec「库存预检与缺料清单」/ BR-4.5-04；
 * proposal D5 全系统唯一算法，5.5 复用）。
 * 缺料行重建式维护（派生数据）：每次 check 删除旧清单后按当前库存/在途重算——
 * 在途到货后重新 check 齐套恢复 100% → 自动清「缺料待料」标记（spec 场景）。
 */
@Slf4j
@Service
public class KittingServiceImpl implements KittingService {

    /** 缺料/恢复通知接收方（spec L2843 三方中计划两方 + 仓库；无采购员/车间主管，偏差 D1） */
    /** 缺料/恢复通知接收方（change add-dispatch-management task 5.3：回补车间主管——5.5 偏差 D1 预留的回补点） */
    private static final String[] NOTIFY_ROLES = {"ROLE_PLANNER", "ROLE_PLAN_MGR",
            "ROLE_WAREHOUSE", "ROLE_SHOP_FLOOR"};
    static final String BIZ_SHORTAGE = "MO_SHORTAGE";
    static final String BIZ_RESTORED = "MO_KITTING_RESTORED";

    private final MrpMoDao moDao;
    private final MrpMoBomDao bomDao;
    private final MrpMoShortageDao shortageDao;
    private final MoDataSource moDataSource;
    private final SysParamService sysParam;
    private final NoticeService noticeService;

    public KittingServiceImpl(MrpMoDao moDao, MrpMoBomDao bomDao,
                              MrpMoShortageDao shortageDao, MoDataSource moDataSource,
                              SysParamService sysParam, NoticeService noticeService) {
        this.moDao = moDao;
        this.bomDao = bomDao;
        this.shortageDao = shortageDao;
        this.moDataSource = moDataSource;
        this.sysParam = sysParam;
        this.noticeService = noticeService;
    }

    /**
     * synchronized：重建式互踩的唯一互斥点——调度器/人工单张/批量三方同 JVM 串行
     * （synchronized 可重入，checkBatch 内再调 check 不死锁；多实例假设见 design Risks）。
     */
    @Override
    @Transactional
    public synchronized BigDecimal check(String moId, boolean notifyOnFlip) {
        MrpMo mo = moDao.selectById(moId);
        if (mo == null) {
            throw new ServiceException(404, "工单不存在：" + moId);
        }
        List<MrpMoBom> lines = bomDao.selectList(new LambdaQueryWrapper<MrpMoBom>()
                .eq(MrpMoBom::getMoId, moId)
                .orderByAsc(MrpMoBom::getLineNo));
        // 清单重建（旧缺料行是派生数据）
        shortageDao.delete(new LambdaQueryWrapper<MrpMoShortage>()
                .eq(MrpMoShortage::getMoId, moId));

        if (lines.isEmpty()) {
            applyFlag(mo, "0", notifyOnFlip);
            return BigDecimal.valueOf(100);
        }
        Set<String> codes = lines.stream().map(MrpMoBom::getItemCode)
                .filter(c -> c != null && !c.isEmpty()).collect(Collectors.toSet());
        Map<String, BigDecimal> onHand = moDataSource.base().onHand(codes);
        // 在途过滤（口径修正②，design D1）：开关 ON 时仅计计划开工日前到货；
        // OFF（dev 库 req_date 空值率 80.8% 的数据护栏默认，偏差 D5）或无开工日 → 全量计入
        Map<String, BigDecimal> inTransit;
        if (transitDateFilterOn() && mo.getPlanStartDate() != null) {
            inTransit = moDataSource.base().inTransit(codes, mo.getPlanStartDate());
        } else {
            inTransit = moDataSource.base().inTransit(codes);
        }

        // 需求基数（口径修正①）：工单剩余数量 = 计划数量 − 合格产出，不饱和（spec L2833）
        BigDecimal qualified = mo.getQualifiedQty() == null ? BigDecimal.ZERO : mo.getQualifiedQty();
        BigDecimal remaining = (mo.getQty() == null ? BigDecimal.ZERO : mo.getQty()).subtract(qualified);
        if (remaining.signum() < 0) {
            remaining = BigDecimal.ZERO;
        }
        int satisfied = 0;
        int shortCount = 0;
        for (MrpMoBom line : lines) {
            BigDecimal usage = line.getUnitQty() == null ? BigDecimal.ZERO : line.getUnitQty();
            BigDecimal loss = line.getLossRate() == null ? BigDecimal.ZERO : line.getLossRate();
            // 需求 = 工单剩余数量 × 单位用量 × (1 + 损耗率)（BR-4.5-04 / spec L2833 公式）
            BigDecimal req = remaining.multiply(usage)
                    .multiply(BigDecimal.ONE.add(loss))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal avail = onHand.getOrDefault(line.getItemCode(), BigDecimal.ZERO)
                    .add(inTransit.getOrDefault(line.getItemCode(), BigDecimal.ZERO));
            BigDecimal shortQty = req.subtract(avail);
            if (shortQty.signum() > 0) {
                shortCount++;
                MrpMoShortage row = new MrpMoShortage();
                row.setMoId(moId);
                row.setBomLine(line.getLineNo());
                row.setItemCode(line.getItemCode());
                row.setItemName(line.getItemName());
                row.setReqQty(req);
                row.setAvailQty(avail);
                row.setShortQty(shortQty);
                row.setResolvedFlag("0");
                shortageDao.insert(row);
            } else {
                satisfied++;
            }
        }
        BigDecimal rate = BigDecimal.valueOf(satisfied * 100L)
                .divide(BigDecimal.valueOf(lines.size()), 1, RoundingMode.HALF_UP);
        applyFlag(mo, shortCount > 0 ? "1" : "0", notifyOnFlip);
        log.info("工单齐套计算：{} 齐套率={}%% 缺料行={}/{}",
                mo.getMoNo(), rate, shortCount, lines.size());
        return rate;
    }

    @Override
    public List<MrpMoShortage> shortages(String moId) {
        return shortageDao.selectList(new LambdaQueryWrapper<MrpMoShortage>()
                .eq(MrpMoShortage::getMoId, moId)
                .orderByAsc(MrpMoShortage::getBomLine));
    }

    /**
     * 逐行明细（只读不落库，任务 5.1）：口径与 check() 完全一致（同一剩余量基数/在途开关），
     * 缺料行附源 BOM 替代候选（可抵量 = 替代料可用 ÷ 比例，仅展示，spec「替代候选提示」）。
     */
    @Override
    public Map<String, Object> detail(String moId) {
        MrpMo mo = moDao.selectById(moId);
        if (mo == null) {
            throw new ServiceException(404, "工单不存在：" + moId);
        }
        List<MrpMoBom> lines = bomDao.selectList(new LambdaQueryWrapper<MrpMoBom>()
                .eq(MrpMoBom::getMoId, moId)
                .orderByAsc(MrpMoBom::getLineNo));

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("moId", moId);
        out.put("moNo", mo.getMoNo());
        if (lines.isEmpty()) {
            out.put("rate", BigDecimal.valueOf(100));
            out.put("lines", List.of());
            return out;
        }

        Set<String> codes = lines.stream().map(MrpMoBom::getItemCode)
                .filter(c -> c != null && !c.isEmpty()).collect(Collectors.toSet());
        Map<String, BigDecimal> onHand = moDataSource.base().onHand(codes);
        Map<String, BigDecimal> inTransit = transitDateFilterOn() && mo.getPlanStartDate() != null
                ? moDataSource.base().inTransit(codes, mo.getPlanStartDate())
                : moDataSource.base().inTransit(codes);
        BigDecimal qualified = mo.getQualifiedQty() == null ? BigDecimal.ZERO : mo.getQualifiedQty();
        BigDecimal remaining = (mo.getQty() == null ? BigDecimal.ZERO : mo.getQty()).subtract(qualified);
        if (remaining.signum() < 0) {
            remaining = BigDecimal.ZERO;
        }
        // 替代候选数据源（仅缺料行消费；读源 BOM，偏差 D4）
        Map<String, List<com.erp.entity.mrp.MrpBomSubstitute>> subs =
                moDataSource.publishedSubstitutes(mo.getProductCode());
        Set<String> subCodes = subs.values().stream().flatMap(List::stream)
                .map(com.erp.entity.mrp.MrpBomSubstitute::getSubstituteItemCode)
                .collect(Collectors.toSet());
        Map<String, BigDecimal> subOnHand = subCodes.isEmpty()
                ? Map.of() : moDataSource.base().onHand(subCodes);

        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        int satisfied = 0;
        for (MrpMoBom line : lines) {
            BigDecimal usage = line.getUnitQty() == null ? BigDecimal.ZERO : line.getUnitQty();
            BigDecimal loss = line.getLossRate() == null ? BigDecimal.ZERO : line.getLossRate();
            BigDecimal req = remaining.multiply(usage)
                    .multiply(BigDecimal.ONE.add(loss))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal avail = onHand.getOrDefault(line.getItemCode(), BigDecimal.ZERO)
                    .add(inTransit.getOrDefault(line.getItemCode(), BigDecimal.ZERO));
            BigDecimal shortQty = req.subtract(avail);
            boolean shortage = shortQty.signum() > 0;
            if (!shortage) {
                satisfied++;
            }

            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("bomLine", line.getLineNo());
            row.put("itemCode", line.getItemCode());
            row.put("itemName", line.getItemName());
            row.put("reqQty", req);
            row.put("availQty", avail);
            row.put("shortQty", shortage ? shortQty : BigDecimal.ZERO);
            row.put("shortage", shortage);

            List<Map<String, Object>> candidates = new java.util.ArrayList<>();
            if (shortage) {
                for (com.erp.entity.mrp.MrpBomSubstitute sub
                        : subs.getOrDefault(line.getItemCode(), List.of())) {
                    BigDecimal sOnHand = subOnHand.getOrDefault(
                            sub.getSubstituteItemCode(), BigDecimal.ZERO);
                    BigDecimal ratio = sub.getRatio() == null || sub.getRatio().signum() <= 0
                            ? BigDecimal.ONE : sub.getRatio();
                    Map<String, Object> cand = new java.util.LinkedHashMap<>();
                    cand.put("itemCode", sub.getSubstituteItemCode());
                    cand.put("itemName", sub.getSubstituteItemName());
                    cand.put("ratio", sub.getRatio());
                    cand.put("priority", sub.getPriority());
                    cand.put("availQty", sOnHand);
                    // 可抵主料量 = 替代料可用 ÷ 比例（1 主料 = RATIO 替代料，spec「替代候选提示」）
                    cand.put("coverableQty", sOnHand.divide(ratio, 4, RoundingMode.HALF_UP));
                    candidates.add(cand);
                }
            }
            row.put("substitutes", candidates);
            rows.add(row);
        }
        BigDecimal rate = BigDecimal.valueOf(satisfied * 100L)
                .divide(BigDecimal.valueOf(lines.size()), 1, RoundingMode.HALF_UP);
        out.put("rate", rate);
        out.put("lines", rows);
        return out;
    }

    /**
     * 人工批量校验（design D3）：synchronized 与调度器/其他批次互斥（check 重建式互踩），
     * LinkedHashSet 批次内去重（同批重复提交只执行一次）；逐张串行、单张失败不阻断整批。
     */
    @Override
    public synchronized List<Map<String, Object>> checkBatch(List<String> moIds) {
        // LinkedHashMap 保序 + 天然按 moId 去重
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        if (moIds == null || moIds.isEmpty()) {
            return List.of();
        }
        for (String moId : new java.util.LinkedHashSet<>(moIds)) {
            MrpMo mo = moDao.selectById(moId);
            if (mo == null) {
                continue;
            }
            try {
                BigDecimal rate = check(moId, false); // 人工源静默（偏差 D2）
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("moId", moId);
                row.put("moNo", mo.getMoNo());
                row.put("rate", rate);
                row.put("shortageFlag", moDao.selectById(moId).getShortageFlag());
                out.put(moId, row);
            } catch (Exception e) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("moId", moId);
                row.put("moNo", mo.getMoNo());
                row.put("error", e.getMessage());
                out.put(moId, row);
            }
        }
        return List.copyOf(out.values());
    }

    /** 置/清「缺料待料」标记 + 翻转通知（design D2：仅 notifyOnFlip 源推送，变化时才写避免无谓 ver_no 递增） */
    private void applyFlag(MrpMo mo, String flag, boolean notifyOnFlip) {
        String old = mo.getShortageFlag() == null ? "0" : mo.getShortageFlag();
        if (flag.equals(old)) {
            return;
        }
        moDao.update(null, new LambdaUpdateWrapper<MrpMo>()
                .eq(MrpMo::getId, mo.getId())
                .set(MrpMo::getShortageFlag, flag));
        mo.setShortageFlag(flag);
        if (notifyOnFlip) {
            boolean shortage = "1".equals(flag);
            String bizType = shortage ? BIZ_SHORTAGE : BIZ_RESTORED;
            String title = shortage
                    ? "工单缺料预警：" + mo.getMoNo()
                    : "工单齐套恢复可派工：" + mo.getMoNo();
            String content = shortage
                    ? "工单 " + mo.getMoNo() + "（" + mo.getProductName() + "）缺料待料，请查看缺料清单（5.5.2）"
                    : "工单 " + mo.getMoNo() + "（" + mo.getProductName() + "）齐套率恢复 100%，可安排派工";
            for (String role : NOTIFY_ROLES) {
                noticeService.push(role, null, title, content, bizType, mo.getId());
            }
            log.info("齐套翻转通知：{} {} role={}", mo.getMoNo(), bizType, String.join(",", NOTIFY_ROLES));
        }
    }

    /** KITTING_TRANSIT_DATE_FILTER=ON 才启用派工日前在途过滤（未登记默认 ON，迁移 121 数据护栏置 OFF） */
    private boolean transitDateFilterOn() {
        return "ON".equalsIgnoreCase(sysParam.getValue("KITTING_TRANSIT_DATE_FILTER", "ON"));
    }

    // ---------- 5.5 用户入口（design D9 权限门 + 偏差 D2 静默） ----------

    @Override
    public List<Map<String, Object>> checkUser(List<String> moIds) {
        requireAnyKitting("齐套校验");
        return checkBatch(moIds);
    }

    /**
     * 缺料聚合（5.5.2）：未关闭工单的活跃缺料行按物料 group。
     * 过滤 CLOSED/CANCELLED 工单（历史噪音），聚合维度 = itemCode，明细保留 moId 供下钻。
     */
    @Override
    public List<Map<String, Object>> aggregate(String itemCode, String moNo) {
        requireLoginKitting("缺料聚合查询");
        List<MrpMo> openMos = moDao.selectList(new LambdaQueryWrapper<MrpMo>()
                .notIn(MrpMo::getStatus, MrpMo.ST_CLOSED, MrpMo.ST_CANCELLED));
        if (openMos.isEmpty()) {
            return List.of();
        }
        Map<String, MrpMo> moById = new HashMap<>();
        for (MrpMo m : openMos) {
            moById.put(m.getId(), m);
        }
        List<MrpMoShortage> rows = shortageDao.selectList(new LambdaQueryWrapper<MrpMoShortage>()
                .in(MrpMoShortage::getMoId, moById.keySet()));
        // 按物料聚合
        Map<String, Map<String, Object>> groups = new java.util.LinkedHashMap<>();
        for (MrpMoShortage row : rows) {
            MrpMo mo = moById.get(row.getMoId());
            if (mo == null) {
                continue;
            }
            if (isNotBlank(itemCode) && (row.getItemCode() == null
                    || !row.getItemCode().toLowerCase()
                            .contains(itemCode.trim().toLowerCase()))) {
                continue;
            }
            if (isNotBlank(moNo) && (mo.getMoNo() == null
                    || !mo.getMoNo().toLowerCase().contains(moNo.trim().toLowerCase()))) {
                continue;
            }
            Map<String, Object> g = groups.computeIfAbsent(row.getItemCode(), k -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("itemCode", row.getItemCode());
                m.put("itemName", row.getItemName());
                m.put("moCount", 0);
                m.put("totalShortQty", BigDecimal.ZERO);
                m.put("mos", new java.util.ArrayList<Map<String, Object>>());
                return m;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mos = (List<Map<String, Object>>) g.get("mos");
            Map<String, Object> d = new java.util.LinkedHashMap<>();
            d.put("shortageId", row.getId()); // 转采购建议入参（5.5.2 → from-shortage）
            d.put("moId", mo.getId());
            d.put("moNo", mo.getMoNo());
            d.put("productCode", mo.getProductCode());
            d.put("productName", mo.getProductName());
            d.put("status", mo.getStatus());
            d.put("planStartDate", mo.getPlanStartDate());
            d.put("bomLine", row.getBomLine());
            d.put("reqQty", row.getReqQty());
            d.put("availQty", row.getAvailQty());
            d.put("shortQty", row.getShortQty());
            mos.add(d);
            g.put("moCount", mos.size());
            g.put("totalShortQty", ((BigDecimal) g.get("totalShortQty")).add(row.getShortQty()));
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>(groups.values());
        out.sort((a, b) -> ((BigDecimal) b.get("totalShortQty"))
                .compareTo((BigDecimal) a.get("totalShortQty")));
        return out;
    }

    // ---------- 权限（design D9：四角色；沿 MoServiceImpl.requireAny 范式） ----------

    /** 需登录（读操作：未认证 401） */
    private void requireLoginKitting(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
    }

    /** 写操作四角色门（M5-5 权限矩阵）：未登录 401、角色不符 403、ADMIN 直通 */
    private void requireAnyKitting(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        for (String r : new String[]{"ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_WAREHOUSE", "ROLE_ADMIN"}) {
            if (auth.getAuthorities().contains(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(r))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限：" + action + "（需计划员/计划主管/仓库/管理员）");
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
