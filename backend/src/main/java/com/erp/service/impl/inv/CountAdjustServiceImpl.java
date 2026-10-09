package com.erp.service.impl.inv;

import com.erp.common.ServiceException;
import com.erp.dao.inv.InvCountLineDao;
import com.erp.entity.inv.InvCountLine;
import com.erp.entity.inv.InvCountTask;
import com.erp.entity.inv.InvStock;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.inv.CountAdjustService;
import com.erp.service.inv.StockPostingEngine;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 盘点调整执行实现（4.11，spec count-management 调整双轨留痕；design D4/D5）。
 * 盘亏 → ADJUST_OUT（扣库存）+ 凭证 借1901/贷1403；
 * 盘盈 → ADJUST_IN（增库存）+ 凭证 借1403/贷1901；金额 = |DIFF_QTY| × standardCost。
 * 每方向一个引擎请求（引擎单请求单方向）+ 一张凭证，源单号 = 盘点任务号（业财锚点）。
 */
@Slf4j
@Service
public class CountAdjustServiceImpl implements CountAdjustService {

    private static final String VT_COUNT = "ADJ";
    private static final String SOURCE_COUNT = "COUNT_ADJUST";
    private static final String AC_WAITING = "1901";
    private static final String AC_MATERIAL = "1403";

    private final StockPostingEngine engine;
    private final InvCountLineDao lineDao;
    private final GlVoucherService voucherService;

    public CountAdjustServiceImpl(StockPostingEngine engine, InvCountLineDao lineDao,
                                  GlVoucherService voucherService) {
        this.engine = engine;
        this.lineDao = lineDao;
        this.voucherService = voucherService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> executeAdjustment(InvCountTask task, List<InvCountLine> lines,
                                          String reason) {
        if (lines == null || lines.isEmpty()) {
            throw new ServiceException(422, "无待调整盘点行");
        }
        List<InvCountLine> loss = new ArrayList<>();
        List<InvCountLine> gain = new ArrayList<>();
        for (InvCountLine l : lines) {
            if (l.getDiffQty() == null || l.getDiffQty().signum() == 0) {
                continue;
            }
            if (l.getDiffQty().signum() < 0) {
                loss.add(l);
            } else {
                gain.add(l);
            }
        }

        List<String> docs = new ArrayList<>();
        // 盘亏：ADJUST_OUT（数量 = |DIFF_QTY|）+ 凭证 借1901/贷1403
        if (!loss.isEmpty()) {
            String outDoc = task.getTaskNo() + "-O";
            engine.post(StockPostingEngine.Request.of("ADJUST_OUT", SOURCE_COUNT, outDoc,
                    toEngineLines(task, loss)));
            BigDecimal amount = sumAmount(loss);
            if (amount.signum() > 0) {
                voucherService.create(VT_COUNT, LocalDate.now(),
                        "盘点调整（盘亏）" + task.getTaskNo() + "（" + reason + "）",
                        SOURCE_COUNT, outDoc, null, List.of(
                                GlVoucherService.FinVoucherLineSpec.of(AC_WAITING, "DR", amount,
                                        "盘点待处理 " + task.getTaskNo()),
                                GlVoucherService.FinVoucherLineSpec.of(AC_MATERIAL, "CR", amount,
                                        "盘亏核减原材料 " + task.getTaskNo())));
            }
            markAdjusted(loss, outDoc);
            docs.add(outDoc);
        }
        // 盘盈：ADJUST_IN（数量 = DIFF_QTY）+ 凭证 借1403/贷1901
        if (!gain.isEmpty()) {
            String inDoc = task.getTaskNo() + "-I";
            engine.post(StockPostingEngine.Request.of("ADJUST_IN", SOURCE_COUNT, inDoc,
                    toEngineLines(task, gain)));
            BigDecimal amount = sumAmount(gain);
            if (amount.signum() > 0) {
                voucherService.create(VT_COUNT, LocalDate.now(),
                        "盘点调整（盘盈）" + task.getTaskNo() + "（" + reason + "）",
                        SOURCE_COUNT, inDoc, null, List.of(
                                GlVoucherService.FinVoucherLineSpec.of(AC_MATERIAL, "DR", amount,
                                        "盘盈核增原材料 " + task.getTaskNo()),
                                GlVoucherService.FinVoucherLineSpec.of(AC_WAITING, "CR", amount,
                                        "盘盈待处理 " + task.getTaskNo())));
            }
            markAdjusted(gain, inDoc);
            docs.add(inDoc);
        }
        log.info("count adjust {} executed: loss={} gain={} docs={} reason={}",
                task.getTaskNo(), loss.size(), gain.size(), docs, reason);
        return docs;
    }

    private List<StockPostingEngine.Line> toEngineLines(InvCountTask task,
                                                        List<InvCountLine> lines) {
        List<StockPostingEngine.Line> out = new ArrayList<>();
        for (InvCountLine l : lines) {
            StockPostingEngine.Line ln = new StockPostingEngine.Line();
            ln.warehouseCode = task.getWarehouseCode();
            ln.itemCode = l.getItemCode();
            ln.itemName = l.getItemName();
            ln.batchNo = l.getBatchNo();
            ln.binCode = l.getBinCode();
            ln.qty = (l.getDiffQty() == null ? BigDecimal.ZERO : l.getDiffQty()).abs();
            out.add(ln);
        }
        return out;
    }

    private void markAdjusted(List<InvCountLine> lines, String docNo) {
        for (InvCountLine l : lines) {
            l.setCountStatus(InvCountLine.ST_ADJUSTED);
            l.setAdjustDocNo(docNo);
            lineDao.updateById(l);
        }
    }

    private BigDecimal sumAmount(List<InvCountLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvCountLine l : lines) {
            if (l.getDiffAmount() != null) {
                total = total.add(l.getDiffAmount());
            }
        }
        return total;
    }

    static String currentUser() {
        return SecurityUtils.getCurrentUserId();
    }
}
