package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvBatchDeviationDao;
import com.erp.entity.inv.InvBatchDeviation;
import com.erp.service.inv.BatchRecommendService;
import com.erp.service.inv.FifoStrategyService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 先进先出策略（4.6.1，spec outbound-strategy）：试算委托 BatchRecommendService（只读），
 * 偏离台账查询/落账——仅留痕不挂审批（偏差 D1），原因必填由本层强制。
 */
@Slf4j
@Service
public class FifoStrategyServiceImpl implements FifoStrategyService {

    private final BatchRecommendService recommendService;
    private final InvBatchDeviationDao deviationDao;

    public FifoStrategyServiceImpl(BatchRecommendService recommendService,
                                   InvBatchDeviationDao deviationDao) {
        this.recommendService = recommendService;
        this.deviationDao = deviationDao;
    }

    @Override
    public Map<String, Object> simulate(String warehouseCode, String itemCode,
                                        BigDecimal qty, boolean binLevel) {
        return recommendService.recommend(warehouseCode, itemCode, qty, binLevel);
    }

    @Override
    public Map<String, Object> deviations(String docNo, String itemCode, String createBy,
                                          String dateFrom, String dateTo,
                                          long current, long size) {
        LambdaQueryWrapper<InvBatchDeviation> qw = new LambdaQueryWrapper<InvBatchDeviation>()
                .like(notBlank(docNo), InvBatchDeviation::getSrcDocNo, trimOrNull(docNo))
                .like(notBlank(itemCode), InvBatchDeviation::getItemCode, trimOrNull(itemCode))
                .like(notBlank(createBy), InvBatchDeviation::getCreateBy, trimOrNull(createBy))
                .orderByDesc(InvBatchDeviation::getCreateDate);
        LocalDateTime from = parseStart(dateFrom);
        LocalDateTime to = parseEnd(dateTo);
        if (from != null) {
            qw.ge(InvBatchDeviation::getCreateDate, from);
        }
        if (to != null) {
            qw.le(InvBatchDeviation::getCreateDate, to);
        }
        IPage<InvBatchDeviation> p = deviationDao.selectPage(new Page<>(current, size), qw);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (InvBatchDeviation d : p.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("srcDocType", d.getSrcDocType());
            m.put("srcDocNo", d.getSrcDocNo());
            m.put("lineNo", d.getLineNo());
            m.put("itemCode", d.getItemCode());
            m.put("warehouseCode", d.getWarehouseCode());
            m.put("recommendedBatch", d.getRecommendedBatch());
            m.put("actualBatch", d.getActualBatch());
            m.put("reason", d.getReason());
            m.put("createBy", d.getCreateBy());
            m.put("createDate", d.getCreateDate());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows);
        out.put("total", p.getTotal());
        return out;
    }

    @Override
    public void recordDeviation(String srcDocType, String srcDocNo, Integer lineNo,
                                String itemCode, String warehouseCode,
                                String recommendedBatch, String actualBatch, String reason) {
        // 无偏离不落账（spec 场景：一致不记录）
        if (actualBatch == null || actualBatch.trim().isEmpty()) {
            return;
        }
        String actual = actualBatch.trim();
        String recommended = recommendedBatch == null ? null : recommendedBatch.trim();
        if (actual.equals(recommended)) {
            return;
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new ServiceException(422, "请填写改批原因");
        }
        InvBatchDeviation d = new InvBatchDeviation();
        d.setSrcDocType(srcDocType);
        d.setSrcDocNo(srcDocNo);
        d.setLineNo(lineNo);
        d.setItemCode(itemCode);
        d.setWarehouseCode(warehouseCode);
        d.setRecommendedBatch(recommended);
        d.setActualBatch(actual);
        d.setReason(reason.trim());
        d.setCreateBy(SecurityUtils.getCurrentUserId());
        deviationDao.insert(d);
    }

    // ---------- 筛选辅助（like null = 条件不生效，LambdaQueryWrapper 约定） ----------

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private static boolean notBlank(String v) {
        return !isBlank(v);
    }

    private static String trimOrNull(String v) {
        return v == null ? null : v.trim();
    }

    private static LocalDateTime parseStart(String v) {
        try {
            return LocalDateTime.parse(v.trim() + "T00:00:00");
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime parseEnd(String v) {
        try {
            return LocalDateTime.parse(v.trim() + "T23:59:59");
        } catch (Exception e) {
            return null;
        }
    }
}
