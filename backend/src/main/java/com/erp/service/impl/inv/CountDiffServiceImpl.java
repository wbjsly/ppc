package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.dao.inv.InvCheckDiffDao;
import com.erp.dao.inv.InvCountLineDao;
import com.erp.entity.inv.InvCheckDiff;
import com.erp.entity.inv.InvCountLine;
import com.erp.service.inv.CountDiffService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 盘点差异台账实现（4.11.3，spec count-management；design D7 页内审批范式的台账侧）。
 */
@Slf4j
@Service
public class CountDiffServiceImpl implements CountDiffService {

    /** 监控清单门槛：同仓位累计超容差差异单数（BR-4.4-41 "连续两次"首期口径） */
    private static final int WATCH_THRESHOLD = 2;

    private final InvCheckDiffDao diffDao;
    private final InvCountLineDao lineDao;

    public CountDiffServiceImpl(InvCheckDiffDao diffDao, InvCountLineDao lineDao) {
        this.diffDao = diffDao;
        this.lineDao = lineDao;
    }

    @Override
    public Map<String, Object> page(String status, String taskNo, String itemCode,
                                    long current, long size) {
        Page<InvCheckDiff> p = diffDao.selectPage(new Page<>(Math.max(current, 1),
                Math.max(size, 1)), new LambdaQueryWrapper<InvCheckDiff>()
                .eq(InvCheckDiff::getDiffType, InvCheckDiff.T_COUNT)
                .eq(notBlank(status), InvCheckDiff::getStatus, trimOrNull(status))
                .like(notBlank(taskNo), InvCheckDiff::getSrcDocNo, trimOrNull(taskNo))
                .like(notBlank(itemCode), InvCheckDiff::getItemCode, trimOrNull(itemCode))
                .orderByDesc(InvCheckDiff::getCreateDate));
        List<Map<String, Object>> out = new ArrayList<>();
        for (InvCheckDiff d : p.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("diffNo", d.getDiffNo());
            m.put("taskNo", d.getSrcDocNo());
            m.put("itemCode", d.getItemCode());
            m.put("warehouseCode", d.getWarehouseCode());
            m.put("batchNo", d.getBatchNo());
            m.put("expectQty", d.getExpectQty());
            m.put("actualQty", d.getActualQty());
            m.put("deltaQty", d.getDeltaQty());
            m.put("diffNote", d.getDiffNote());
            m.put("status", d.getStatus());
            m.put("resolveNote", d.getResolveNote());
            m.put("createDate", d.getCreateDate());
            out.add(m);
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", out);
        page.put("total", p.getTotal());
        return page;
    }

    @Override
    public List<Map<String, Object>> watchList() {
        // 聚合：每张 COUNT 差异单的行所在仓位 → 出现 ≥2 张差异单的仓位入清单
        Map<String, java.util.Set<String>> diffsByBin = new LinkedHashMap<>();
        for (InvCheckDiff d : diffDao.selectList(new LambdaQueryWrapper<InvCheckDiff>()
                .eq(InvCheckDiff::getDiffType, InvCheckDiff.T_COUNT))) {
            for (InvCountLine l : lineDao.selectList(new LambdaQueryWrapper<InvCountLine>()
                    .eq(InvCountLine::getDiffId, d.getId()))) {
                diffsByBin.computeIfAbsent(str(l.getBinCode()), k -> new java.util.HashSet<>())
                        .add(str(d.getId()));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, java.util.Set<String>> e : diffsByBin.entrySet()) {
            if (e.getValue().size() >= WATCH_THRESHOLD) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("binCode", e.getKey());
                m.put("diffCount", e.getValue().size());
                m.put("reason", "累计 " + e.getValue().size()
                        + " 次盘点差异超容差（重点监控，频次上调待 ABC 排程落地）");
                out.add(m);
            }
        }
        return out;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String trimOrNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
