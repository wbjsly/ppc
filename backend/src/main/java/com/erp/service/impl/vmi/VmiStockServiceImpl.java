package com.erp.service.impl.vmi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.dao.vmi.VmiStockDao;
import com.erp.entity.vmi.VmiStock;
import com.erp.service.vmi.VmiDisposalService;
import com.erp.service.vmi.VmiAlertService;
import com.erp.service.vmi.VmiStockService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 寄售库存台账实现（spec vmi-consignment R4，design D4 惰性扫描挂点）。 */
@Service
public class VmiStockServiceImpl implements VmiStockService {

    private final VmiStockDao stockDao;
    private final VmiAlertService alertService;
    private final VmiDisposalService disposalService;

    public VmiStockServiceImpl(VmiStockDao stockDao,
                               VmiAlertService alertService,
                               VmiDisposalService disposalService) {
        this.stockDao = stockDao;
        this.alertService = alertService;
        this.disposalService = disposalService;
    }

    @Override
    public Page<Map<String, Object>> page(long current, long size,
                                          String supplierId, String itemCode) {
        // 台账加载即惰性扫描：补货建议（低于最低水位）+ 账龄处置建议（design D4）
        alertService.scanReplenish();
        disposalService.scan();

        Page<VmiStock> p = stockDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<VmiStock>()
                        .eq(hasText(supplierId), VmiStock::getSupplierId, supplierId)
                        .eq(hasText(itemCode), VmiStock::getItemCode, itemCode)
                        .orderByAsc(VmiStock::getInboundDate));
        Page<Map<String, Object>> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (VmiStock s : p.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("itemCode", s.getItemCode());
            row.put("itemName", s.getItemName());
            row.put("batchNo", s.getBatchNo());
            row.put("supplierId", s.getSupplierId());
            row.put("supplierName", s.getSupplierName());
            row.put("qty", s.getQty());
            row.put("issuedQty", s.getIssuedQty());
            row.put("inboundDate", s.getInboundDate());
            row.put("agreeNo", s.getAgreeNo());
            row.put("ageDays", s.getInboundDate() == null ? null
                    : ChronoUnit.DAYS.between(s.getInboundDate(), today));
            rows.add(row);
        }
        out.setRecords(rows);
        return out;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
