package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.inv.InvBatchDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.entity.inv.InvBatch;
import com.erp.entity.inv.InvStock;
import com.erp.entity.mdm.MdmItem;
import com.erp.service.SysParamService;
import com.erp.service.inv.ExpiryPriorityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 效期预警清单（4.6.2，spec outbound-strategy 效期预警清单）：
 * 扫描标记位 + 当日实时计算（两者任一命中即视为锁定，与推荐/引擎口径一致，design D4）。
 * 只读：不回写标记位（落位归 ExpiryLockScheduler）。
 */
@Slf4j
@Service
public class ExpiryPriorityServiceImpl implements ExpiryPriorityService {

    private static final int YELLOW = 90;
    private static final int ORANGE = 60;
    private static final int RED = 30;

    private final InvBatchDao batchDao;
    private final InvStockDao stockDao;
    private final MdmItemDao itemDao;
    private final SysParamService sysParamService;

    public ExpiryPriorityServiceImpl(InvBatchDao batchDao, InvStockDao stockDao,
                                     MdmItemDao itemDao, SysParamService sysParamService) {
        this.batchDao = batchDao;
        this.stockDao = stockDao;
        this.itemDao = itemDao;
        this.sysParamService = sysParamService;
    }

    @Override
    public Map<String, Object> warnings(String itemCode, String warehouseCode, String level,
                                        Boolean locked, long current, long size) {
        LocalDate today = LocalDate.now();
        BigDecimal lockRatio = sysParamService.getRate("EXPIRY_LOCK_RATIO", new BigDecimal("0.5"));

        // 批次管理物料集合（非批次管理物料的台账行不返回——spec 场景）
        Set<String> batchManaged = new HashSet<>();
        LambdaQueryWrapper<MdmItem> iq = new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getBatchFlag, "1")
                .eq(notBlank(itemCode), MdmItem::getItemCode, trimOrNull(itemCode));
        for (MdmItem it : itemDao.selectList(iq)) {
            batchManaged.add(it.getItemCode());
        }

        // 有库存的仓库 → 物料在该仓的批次（仓库筛选经库存行连接）
        Set<String> itemPool = null;
        Set<String> batchKeyFilter = null;   // "item|batch" 白名单（仓库维度过滤）
        if (notBlank(warehouseCode)) {
            itemPool = new HashSet<>();
            batchKeyFilter = new HashSet<>();
            LambdaQueryWrapper<InvStock> sq = new LambdaQueryWrapper<InvStock>()
                    .eq(InvStock::getWarehouseCode, warehouseCode.trim());
            if (notBlank(itemCode)) {
                sq.eq(InvStock::getItemCode, itemCode.trim());
            }
            for (InvStock s : stockDao.selectList(sq)) {
                itemPool.add(s.getItemCode());
                batchKeyFilter.add(s.getItemCode() + "|" + str(s.getBatchNo()));
            }
        }

        LambdaQueryWrapper<InvBatch> qw = new LambdaQueryWrapper<InvBatch>()
                .isNotNull(InvBatch::getExpiryDate)
                .eq(notBlank(itemCode), InvBatch::getItemCode, trimOrNull(itemCode));
        List<Map<String, Object>> all = new ArrayList<>();
        for (InvBatch b : batchDao.selectList(qw)) {
            if (!batchManaged.contains(b.getItemCode())) {
                continue;
            }
            if (itemPool != null && (!itemPool.contains(b.getItemCode())
                    || !batchKeyFilter.contains(b.getItemCode() + "|" + b.getBatchNo()))) {
                continue;
            }
            long remaining = ChronoUnit.DAYS.between(today, b.getExpiryDate());
            String lv;
            if (remaining <= RED) {
                lv = "RED";
            } else if (remaining <= ORANGE) {
                lv = "ORANGE";
            } else if (remaining <= YELLOW) {
                lv = "YELLOW";
            } else {
                continue;   // 未进预警线不进清单
            }
            boolean isLocked = isLocked(b, today, lockRatio);
            if (locked != null && locked != isLocked) {
                continue;
            }
            if (notBlank(level) && !lv.equalsIgnoreCase(level.trim())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batchNo", b.getBatchNo());
            row.put("itemCode", b.getItemCode());
            row.put("itemName", b.getItemName());
            row.put("expiryDate", b.getExpiryDate().toString());
            row.put("remainingDays", remaining);
            row.put("level", lv);
            row.put("locked", isLocked);
            row.put("qty", batchAvailable(b.getItemCode(), b.getBatchNo()));
            all.add(row);
        }

        // 排序：剩余天数升序（最紧急在前）
        all.sort(Comparator.comparingLong(m -> (Long) m.get("remainingDays")));

        long from = Math.max(0, (current - 1) * size);
        List<Map<String, Object>> page = from >= all.size()
                ? List.of() : all.subList((int) from, (int) Math.min(all.size(), from + size));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", page);
        out.put("total", all.size());
        out.put("thresholds", Map.of("yellow", YELLOW, "orange", ORANGE, "red", RED));
        out.put("lockRatio", lockRatio);
        return out;
    }

    /** 锁定判定：台账标记位 OR 当日实时计算（与推荐池剔除口径一致） */
    private boolean isLocked(InvBatch b, LocalDate today, BigDecimal ratio) {
        if ("1".equals(str(b.getExpiryLockFlag()))) {
            return true;
        }
        if (b.getProductionDate() == null || b.getExpiryDate() == null) {
            return false;
        }
        long total = ChronoUnit.DAYS.between(b.getProductionDate(), b.getExpiryDate());
        if (total <= 0) {
            return false;
        }
        long remaining = ChronoUnit.DAYS.between(today, b.getExpiryDate());
        BigDecimal threshold = BigDecimal.valueOf(total).multiply(ratio).setScale(0, RoundingMode.DOWN);
        return BigDecimal.valueOf(remaining).compareTo(threshold) < 0;
    }

    /** 批次在全部仓库的可用量合计（清单展示口径） */
    private BigDecimal batchAvailable(String itemCode, String batchNo) {
        BigDecimal sum = BigDecimal.ZERO;
        for (InvStock s : stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getItemCode, itemCode)
                .eq(InvStock::getBatchNo, batchNo))) {
            if (s.getAvailableQty() != null) {
                sum = sum.add(s.getAvailableQty());
            }
        }
        return sum;
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private static boolean notBlank(String v) {
        return !isBlank(v);
    }

    private static String trimOrNull(String v) {
        return v == null ? null : v.trim();
    }

    private static String str(String v) {
        return v == null ? "" : v;
    }
}
