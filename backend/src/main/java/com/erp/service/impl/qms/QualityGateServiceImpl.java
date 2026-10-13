package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.qms.ConcessionDao;
import com.erp.dao.qms.ConcessionWriteoffDao;
import com.erp.entity.inv.InvStock;
import com.erp.entity.qms.Concession;
import com.erp.entity.qms.ConcessionWriteoff;
import com.erp.service.qms.QualityGateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量放行闸口（tasks 7.5，spec concession-acceptance / D8）：
 * 给 4.5 出库等下游域的统一校验入口——按物料/批次给出放行决策。
 * 决策：ALLOWED 无锁定可直接放行；CONCESSION 让步额度内放行（需核销）；BLOCKED 锁定/超范围/无额度。
 * 4.5 出库未建，本期以 REST 接口 + Service 双出口留桩。
 */
@Slf4j
@Service
public class QualityGateServiceImpl implements QualityGateService {

    private final InvStockDao stockDao;
    private final ConcessionDao concessionDao;
    private final ConcessionWriteoffDao writeoffDao;
    private final com.erp.dao.qms.InspectionLotDao lotDao;
    private final com.erp.service.SysParamService sysParam;

    public QualityGateServiceImpl(InvStockDao stockDao,
                                  ConcessionDao concessionDao,
                                  ConcessionWriteoffDao writeoffDao,
                                  com.erp.dao.qms.InspectionLotDao lotDao,
                                  com.erp.service.SysParamService sysParam) {
        this.stockDao = stockDao;
        this.concessionDao = concessionDao;
        this.writeoffDao = writeoffDao;
        this.lotDao = lotDao;
        this.sysParam = sysParam;
    }

    @Override
    public Map<String, Object> assertOqcForShipment(List<Map<String, Object>> lines) {
        List<String> blocked = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean enforce = "ON".equalsIgnoreCase(sysParam.getValue("OQC_ENFORCE", "ON"));
        for (Map<String, Object> line : lines) {
            String itemCode = str(line.get("itemCode"));
            String batchNo = str(line.get("batchNo"));
            if (itemCode == null || itemCode.isBlank()) {
                continue;
            }
            // 该行物料+批次的 OQC 批（最新优先）；免检 SKIPPED 视同放行
            var lots = lotDao.selectList(new LambdaQueryWrapper<com.erp.entity.qms.InspectionLot>()
                    .eq(com.erp.entity.qms.InspectionLot::getLotType, "OQC")
                    .eq(com.erp.entity.qms.InspectionLot::getItemCode, itemCode)
                    .eq(batchNo != null && !batchNo.isBlank(),
                            com.erp.entity.qms.InspectionLot::getBatchNo, batchNo)
                    .orderByDesc(com.erp.entity.qms.InspectionLot::getCreateDate)
                    .last("LIMIT 5"));
            boolean released = lots.stream().anyMatch(l ->
                    "RELEASED".equals(l.getStatus()) || "SKIPPED".equals(l.getStatus()));
            if (released) {
                continue;
            }
            boolean hasLot = !lots.isEmpty();
            if (hasLot) {
                String states = lots.stream()
                        .map(l -> l.getLotNo() + "（" + l.getStatus() + "）")
                        .reduce((a, b) -> a + "、" + b).orElse("");
                blocked.add(itemCode + (batchNo == null || batchNo.isBlank() ? "" : "/" + batchNo)
                        + "：OQC 批未放行 " + states);
            } else if (enforce) {
                blocked.add(itemCode + (batchNo == null || batchNo.isBlank() ? "" : "/" + batchNo)
                        + "：无 OQC 检验批，请先建批或办理免检（OQC_ENFORCE=ON）");
            } else {
                warnings.add(itemCode + "：无 OQC 检验批，OQC_ENFORCE=OFF 降级放行");
            }
        }
        if (!blocked.isEmpty()) {
            throw new com.erp.common.ServiceException(422,
                    "发货确认被 OQC 前置校验阻断：" + String.join("；", blocked));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decision", warnings.isEmpty() ? "ALLOWED" : "DEGRADED");
        out.put("warnings", warnings);
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @Override
    public Map<String, Object> checkIssue(String itemCode, String batchNo, BigDecimal qty, String scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemCode", itemCode);
        out.put("batchNo", batchNo == null ? "" : batchNo);
        out.put("qty", qty);
        LocalDate today = LocalDate.now();

        // 1) 该批次已批准且未过期的让步单
        List<Concession> concessions = concessionDao.selectList(new LambdaQueryWrapper<Concession>()
                .eq(Concession::getItemCode, itemCode)
                .eq(Concession::getBatchNo, batchNo == null ? "" : batchNo)
                .eq(Concession::getStatus, "APPROVED")
                .orderByDesc(Concession::getCreateDate));
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Concession c : concessions) {
            if (c.getLimitUntil() != null && c.getLimitUntil().isBefore(today)) {
                continue;
            }
            BigDecimal used = usedOf(c.getId());
            BigDecimal remain = (c.getLimitQty() == null ? c.getQty() : c.getLimitQty()).subtract(used);
            boolean scopeOk = !hasText(c.getLimitScope()) || (hasText(scope) && scope.contains(c.getLimitScope()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("concessionId", c.getId());
            m.put("concessionNo", c.getConcessionNo());
            m.put("limitQty", c.getLimitQty());
            m.put("usedQty", used);
            m.put("remainQty", remain);
            m.put("limitUntil", c.getLimitUntil());
            m.put("limitScope", c.getLimitScope());
            m.put("scopeMatched", scopeOk);
            matched.add(m);

            if (remain.signum() > 0 && (qty == null || remain.compareTo(qty) >= 0) && scopeOk) {
                out.put("decision", "CONCESSION");
                out.put("message", "让步额度内放行（核销后可用），剩余额度 " + remain.stripTrailingZeros().toPlainString());
                out.put("concession", m);
                return out;
            }
        }
        out.put("concessions", matched);

        // 2) 库存锁定检查（QC_QTY > 0 → 待检/让步锁定）
        // 位行粒度：同批次跨多仓位 → QC/可用取批次合计（LIMIT 1 只见单行会漏锁，A1 适配）
        List<InvStock> rows = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, InvStock.DEFAULT_WH)
                .eq(InvStock::getItemCode, itemCode)
                .eq(InvStock::getBatchNo, batchNo == null ? "" : batchNo));
        BigDecimal qc = BigDecimal.ZERO;
        BigDecimal available = BigDecimal.ZERO;
        for (InvStock r : rows) {
            qc = qc.add(r.getQcQty() == null ? BigDecimal.ZERO : r.getQcQty());
            available = available.add(r.getAvailableQty() == null ? BigDecimal.ZERO : r.getAvailableQty());
        }
        out.put("qcQty", qc);
        out.put("availableQty", available);

        if (matched.isEmpty()) {
            out.put("decision", qc.signum() > 0 ? "BLOCKED" : "ALLOWED");
            out.put("message", qc.signum() > 0
                    ? "批次处于质检/让步锁定（QC_QTY=" + qc.stripTrailingZeros().toPlainString() + "），不可出库"
                    : "无锁定，可直接放行");
            return out;
        }
        out.put("decision", "BLOCKED");
        out.put("message", "让步额度不足或使用范围不符（详见 concessions），且批次锁定中");
        return out;
    }

    /** 已核销量（仅 OK 记录） */
    private BigDecimal usedOf(String concessionId) {
        List<ConcessionWriteoff> list = writeoffDao.selectList(new LambdaQueryWrapper<ConcessionWriteoff>()
                .eq(ConcessionWriteoff::getConcessionId, concessionId)
                .eq(ConcessionWriteoff::getStatus, "OK"));
        BigDecimal used = BigDecimal.ZERO;
        for (ConcessionWriteoff w : list) {
            if (w.getQty() != null) {
                used = used.add(w.getQty());
            }
        }
        return used;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
