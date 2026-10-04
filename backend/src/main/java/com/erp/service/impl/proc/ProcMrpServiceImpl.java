package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.proc.ProcRequisition;
import com.erp.service.proc.ProcMrpService;
import com.erp.service.proc.ProcRequisitionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟 MRP 净算实现（design D4）：
 * NetReq = 需求 − 库存 − 在制 − 在途（FR-4.5-2-3）；preview dry-run、generate 一张 PR 承载合法行。
 */
@Slf4j
@Service
public class ProcMrpServiceImpl implements ProcMrpService {

    private static final int MAX_ROWS = 200;

    private final MdmItemDao itemDao;
    private final MdmSupplierDao supplierDao;
    private final ProcRequisitionService requisitionService;

    public ProcMrpServiceImpl(MdmItemDao itemDao,
                              MdmSupplierDao supplierDao,
                              ProcRequisitionService requisitionService) {
        this.itemDao = itemDao;
        this.supplierDao = supplierDao;
        this.requisitionService = requisitionService;
    }

    @Override
    public Map<String, Object> preview(List<Map<String, Object>> rows) {
        List<Map<String, Object>> results = new ArrayList<>();
        int invalid = 0;
        for (Map<String, Object> row : prepare(rows, true)) {
            results.add(row);
            if (!Boolean.TRUE.equals(row.get("valid"))) {
                invalid++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("results", results);
        result.put("total", results.size());
        result.put("invalid", invalid);
        return result;
    }

    @Override
    public Map<String, Object> generate(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException(422, "输入行不能为空");
        }
        if (rows.size() > MAX_ROWS) {
            throw new ServiceException(422, "单批上限 " + MAX_ROWS + " 行，当前 " + rows.size());
        }
        List<Map<String, Object>> prepared = prepare(rows, false);
        List<Map<String, Object>> details = new ArrayList<>();
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (Map<String, Object> p : prepared) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("rowNo", p.get("rowNo"));
            d.put("itemCode", p.get("itemCode"));
            if (!Boolean.TRUE.equals(p.get("valid")) || !Boolean.TRUE.equals(p.get("generatable"))) {
                d.put("result", "FAILED");
                d.put("reason", p.get("reason"));
            } else {
                accepted.add(p);
                d.put("result", "SUCCESS");
                d.put("reason", null);
            }
            details.add(d);
        }
        if (accepted.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", prepared.size());
            result.put("succeeded", 0);
            result.put("failed", details.size());
            result.put("prNo", null);
            result.put("details", details);
            return result;
        }
        // 一张 PR 承载全部合法行（Head+行 由 createFromMrp 组装，含 Active 二次校验）
        Map<String, Object> created = requisitionService.createFromMrp(accepted);
        // 组装报告
        int succeeded = accepted.size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", prepared.size());
        result.put("succeeded", succeeded);
        result.put("failed", details.size() - succeeded);
        result.put("prNo", created.get("prNo"));
        result.put("prId", created.get("prId"));
        result.put("details", details);
        log.info("MRP generate done: total={} ok={} fail={} pr={}",
                prepared.size(), succeeded, details.size() - succeeded, created.get("prNo"));
        return result;
    }

    /**
     * 归一化 + 净算 + 标记：
     * valid=false → 字段/物料/供应商问题；generatable=false → 过量供给（有效但不可生成）。
     */
    private List<Map<String, Object>> prepare(List<Map<String, Object>> rows, boolean dryRun) {
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException(422, "输入行不能为空");
        }
        if (rows.size() > MAX_ROWS) {
            throw new ServiceException(422, "单批上限 " + MAX_ROWS + " 行，当前 " + rows.size());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int idx = 1;
        for (Map<String, Object> row : rows) {
            Map<String, Object> r = new LinkedHashMap<>();
            int rowNo = intOf(row.get("rowNo"), idx);
            r.put("rowNo", rowNo);
            String itemCode = str(row.get("itemCode"));
            r.put("itemCode", itemCode);
            r.put("valid", false);
            r.put("generatable", false);
            r.put("reason", null);
            r.put("flag", "OK");
            try {
                if (!isNotBlank(itemCode)) {
                    r.put("reason", "物料编码必填");
                    out.add(r);
                    continue;
                }
                MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                        .eq(MdmItem::getItemCode, itemCode.trim().toUpperCase()).last("LIMIT 1"));
                if (item == null) {
                    r.put("reason", "物料不存在：" + itemCode);
                    out.add(r);
                    continue;
                }
                if (!"1".equals(item.getStatus())) {
                    r.put("reason", "物料状态非启用（BR-4.2-07），当前 " + item.getStatus());
                    out.add(r);
                    continue;
                }
                BigDecimal demand = dec(row.get("demand"), "需求量");
                BigDecimal onHand = dec(row.get("onHand"), "现有库存");
                BigDecimal inProcess = dec(row.get("inProcess"), "在制");
                BigDecimal inTransit = dec(row.get("inTransit"), "在途");
                LocalDate reqDate = date(str(row.get("reqDate")));
                if (reqDate == null) {
                    r.put("reason", "需求日期必填");
                    out.add(r);
                    continue;
                }
                if (demand == null || onHand == null || inProcess == null || inTransit == null) {
                    r.put("reason", "四类输入（需求/库存/在制/在途）必填，0 须显式录入");
                    out.add(r);
                    continue;
                }
                BigDecimal netReq = demand.subtract(onHand).subtract(inProcess).subtract(inTransit)
                        .setScale(4, RoundingMode.HALF_UP);
                r.put("netReq", netReq);
                r.put("reqDate", reqDate.toString());
                r.put("demand", demand);
                r.put("onHand", onHand);
                r.put("inProcess", inProcess);
                r.put("inTransit", inTransit);
                String sourceEnum = str(row.get("sourceEnum"));
                r.put("sourceEnum", isNotBlank(sourceEnum) ? sourceEnum.trim().toUpperCase() : "SIMULATED");
                r.put("sourceDocNo", str(row.get("sourceDocNo")));
                r.put("estUnitPrice", str(row.get("estUnitPrice")));
                boolean overdue = reqDate.isBefore(LocalDate.now());
                r.put("overdue", overdue);
                if (overdue) {
                    r.put("flag", "OVERDUE");
                }
                // 建议供应商（编码解析，未命中行失败）
                String supCode = str(row.get("supplierCode"));
                String supplierId = null;
                if (isNotBlank(supCode)) {
                    MdmSupplier sup = supplierDao.selectOne(new LambdaQueryWrapper<MdmSupplier>()
                            .eq(MdmSupplier::getSupplierCode, supCode.trim().toUpperCase()).last("LIMIT 1"));
                    if (sup == null) {
                        r.put("reason", "建议供应商不存在：" + supCode);
                        out.add(r);
                        continue;
                    }
                    supplierId = sup.getId();
                }
                r.put("supplierId", supplierId);
                if (netReq.signum() <= 0) {
                    // FR-4.5-2-3 过量供给：有效但不可生成
                    r.put("valid", true);
                    r.put("generatable", false);
                    r.put("flag", "OVER_SUPPLY");
                    r.put("reason", "过量供给（NetReq ≤ 0），不生成 PR");
                } else {
                    r.put("valid", true);
                    r.put("generatable", true);
                    r.put("reason", overdue ? "逾期需求（可生成，异常标记随行）" : null);
                }
            } catch (ServiceException e) {
                r.put("reason", e.getMessage());
            }
            out.add(r);
            idx++;
        }
        return out;
    }

    private BigDecimal dec(Object o, String name) {
        String s = str(o);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(422, name + "格式非法：" + s);
        }
    }

    private LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ServiceException(422, "日期格式须为 yyyy-MM-dd：" + s);
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private int intOf(Object o, int def) {
        if (o == null) return def;
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
