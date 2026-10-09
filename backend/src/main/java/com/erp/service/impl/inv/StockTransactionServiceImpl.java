package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvTransactionDao;
import com.erp.entity.inv.InvTransaction;
import com.erp.service.inv.StockTransactionService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出入库流水查询实现（spec stock-posting-engine）。
 * 方向合计忽略 direction 过滤（对账需同口径看 IN/OUT 两侧）；纯读无缓存。
 */
@Service
public class StockTransactionServiceImpl implements StockTransactionService {

    private final InvTransactionDao txnDao;

    public StockTransactionServiceImpl(InvTransactionDao txnDao) {
        this.txnDao = txnDao;
    }

    @Override
    public Map<String, Object> page(long current, long size, String bizDocType, String bizDocNo,
                                    String typeCode, String direction, String itemCode,
                                    String warehouseCode, String batchNo, String binCode,
                                    LocalDateTime from, LocalDateTime to, String keyword) {
        Page<InvTransaction> p = txnDao.selectPage(new Page<>(current, size),
                base(bizDocType, bizDocNo, typeCode, itemCode, warehouseCode, batchNo, binCode,
                        from, to, keyword)
                        // direction 只过滤行，不进合计（base 不含 direction）
                        .eq(!isBlank(direction), InvTransaction::getDirection, direction)
                        .orderByDesc(InvTransaction::getCreateDate)
                        .orderByDesc(InvTransaction::getTxnNo));

        // 方向合计：同过滤但不带 direction（对账口径）
        BigDecimal sumIn = BigDecimal.ZERO;
        BigDecimal sumOut = BigDecimal.ZERO;
        for (InvTransaction t : txnDao.selectList(base(bizDocType, bizDocNo, typeCode,
                itemCode, warehouseCode, batchNo, binCode, from, to, keyword))) {
            if (InvTransaction.DIR_IN.equals(t.getDirection())) {
                sumIn = sumIn.add(t.getQty() == null ? BigDecimal.ZERO : t.getQty());
            } else {
                sumOut = sumOut.add(t.getQty() == null ? BigDecimal.ZERO : t.getQty());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", p.getRecords());
        out.put("total", p.getTotal());
        out.put("sumIn", sumIn);
        out.put("sumOut", sumOut);
        out.put("asOf", LocalDateTime.now());
        return out;
    }

    @Override
    public List<InvTransaction> byBizDoc(String bizDocType, String bizDocNo) {
        if (isBlank(bizDocType) || isBlank(bizDocNo)) {
            throw new ServiceException(422, "来源单据类型与单号必填");
        }
        return txnDao.selectByBizDoc(bizDocType, bizDocNo);
    }

    private LambdaQueryWrapper<InvTransaction> base(String bizDocType, String bizDocNo,
                                                    String typeCode, String itemCode,
                                                    String warehouseCode, String batchNo,
                                                    String binCode,
                                                    LocalDateTime from, LocalDateTime to,
                                                    String keyword) {
        return new LambdaQueryWrapper<InvTransaction>()
                .eq(!isBlank(bizDocType), InvTransaction::getBizDocType, bizDocType)
                .eq(!isBlank(bizDocNo), InvTransaction::getBizDocNo, bizDocNo)
                .eq(!isBlank(typeCode), InvTransaction::getTypeCode, typeCode)
                .eq(!isBlank(itemCode), InvTransaction::getItemCode, itemCode)
                .eq(!isBlank(warehouseCode), InvTransaction::getWarehouseCode, warehouseCode)
                .eq(!isBlank(batchNo), InvTransaction::getBatchNo, batchNo)
                .eq(!isBlank(binCode), InvTransaction::getBinCode, binCode)
                .ge(from != null, InvTransaction::getCreateDate, from)
                .lt(to != null, InvTransaction::getCreateDate, to)
                .and(!isBlank(keyword), w -> w
                        .like(InvTransaction::getBizDocNo, keyword)
                        .or().like(InvTransaction::getItemCode, keyword)
                        .or().like(InvTransaction::getBatchNo, keyword)
                        .or().like(InvTransaction::getBinCode, keyword));
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
