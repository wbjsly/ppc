package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.ScrapOrderDao;
import com.erp.dao.inv.TransferOrderDao;
import com.erp.dao.sd.ShipmentDao;
import com.erp.dao.vmi.MaterialIssueDao;
import com.erp.entity.inv.InvScrapOrder;
import com.erp.entity.inv.InvTransferOrder;
import com.erp.entity.sd.Shipment;
import com.erp.entity.vmi.MaterialIssue;
import com.erp.service.inv.OutboundWorkbenchService;
import com.erp.service.inv.ScrapOrderService;
import com.erp.service.inv.TransferOrderService;
import com.erp.service.sd.ShipmentService;
import com.erp.service.vmi.MaterialIssueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出库作业台实现（spec outbound-workbench，design D2）。
 * 查询 = 四域队列归一化（只读聚合）；动作 = 委托域服务同一后端动作（不复制过账逻辑）。
 */
@Slf4j
@Service
public class OutboundWorkbenchServiceImpl implements OutboundWorkbenchService {

    private static final String T_SALES = "SALES_OUT";
    private static final String T_MATERIAL = "MATERIAL_OUT";
    private static final String T_TRANSFER = "TRANSFER_OUT";
    private static final String T_SCRAP = "SCRAP_OUT";

    private final ShipmentDao shipmentDao;
    private final MaterialIssueDao materialIssueDao;
    private final TransferOrderDao transferOrderDao;
    private final ScrapOrderDao scrapOrderDao;
    private final ShipmentService shipmentService;
    private final MaterialIssueService materialIssueService;
    private final TransferOrderService transferOrderService;
    private final ScrapOrderService scrapOrderService;
    /** 行级聚合（物料摘要/行合计/物料关键词命中）走轻量 JDBC，避免 N+1 DAO 调用 */
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public OutboundWorkbenchServiceImpl(ShipmentDao shipmentDao,
                                        MaterialIssueDao materialIssueDao,
                                        TransferOrderDao transferOrderDao,
                                        ScrapOrderDao scrapOrderDao,
                                        ShipmentService shipmentService,
                                        MaterialIssueService materialIssueService,
                                        TransferOrderService transferOrderService,
                                        ScrapOrderService scrapOrderService,
                                        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.shipmentDao = shipmentDao;
        this.materialIssueDao = materialIssueDao;
        this.transferOrderDao = transferOrderDao;
        this.scrapOrderDao = scrapOrderDao;
        this.shipmentService = shipmentService;
        this.materialIssueService = materialIssueService;
        this.transferOrderService = transferOrderService;
        this.scrapOrderService = scrapOrderService;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------- 6.1 四类型队列 ----------

    @Override
    public Map<String, Object> queue(String type, String queue, String keyword,
                                     long current, long size) {
        List<String> statuses = statusesOf(type, queue);
        if (statuses == null) {
            throw new ServiceException(422, "不支持的出库类型：" + type);
        }
        Map<String, Object> page;
        switch (type) {
            case T_SALES -> page = salesQueue(statuses, keyword, current, size);
            case T_MATERIAL -> page = materialQueue(statuses, keyword, current, size);
            case T_TRANSFER -> page = transferQueue(statuses, keyword, current, size);
            case T_SCRAP -> page = scrapQueue(statuses, keyword, current, size);
            default -> throw new ServiceException(422, "不支持的出库类型：" + type);
        }
        page.put("type", type);
        page.put("queue", queue == null ? "" : queue);
        return page;
    }

    private List<String> statusesOf(String type, String queue) {
        boolean a = queue == null || queue.isEmpty() || "A".equalsIgnoreCase(queue);
        boolean b = queue != null && "B".equalsIgnoreCase(queue);
        boolean both = queue == null || queue.isEmpty();
        switch (type) {
            case T_SALES, T_MATERIAL -> {
                List<String> st = new ArrayList<>();
                if (a || both) {
                    st.add("DRAFT");
                }
                if (b || both) {
                    st.add("POSTED");
                }
                return st;
            }
            case T_TRANSFER -> {
                List<String> st = new ArrayList<>();
                if (a || both) {
                    st.add("DRAFT");
                }
                if (b || both) {
                    st.add("OUT_POSTED");
                }
                return st;
            }
            case T_SCRAP -> {
                List<String> st = new ArrayList<>();
                if (a || both) {
                    st.add("APPROVED");
                }
                if (b || both) {
                    st.add("POSTED");
                }
                return st;
            }
            default -> {
                return null;
            }
        }
    }

    private Map<String, Object> salesQueue(List<String> statuses, String keyword,
                                           long current, long size) {
        Page<Shipment> p = shipmentDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<Shipment>()
                        .in(Shipment::getStatus, statuses)
                        .and(!isBlank(keyword), w -> {
                            w.like(Shipment::getShipNo, keyword)
                                    .or().like(Shipment::getCustomerName, keyword)
                                    .or().like(Shipment::getWarehouseCode, keyword);
                            applyItemHit(w, Shipment::getId,
                                    "erp_sd_shipment_line", "SHIP_ID", keyword);
                        })
                        .orderByDesc(Shipment::getCreateDate));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Shipment s : p.getRecords()) {
            Map<String, Object> m = base(s.getId(), s.getShipNo(), s.getStatus(), s.getCreateDate());
            m.put("sub1", s.getCustomerName());
            m.put("sub2", s.getWarehouseCode());
            m.put("qty", s.getTotalQty());
            m.put("amount", s.getTotalAmt());
            m.put("itemSummary", itemSummary("erp_sd_shipment_line", "SHIP_ID", s.getId()));
            m.put("note", null);
            rows.add(m);
        }
        return pageOut(rows, p.getTotal());
    }

    private Map<String, Object> materialQueue(List<String> statuses, String keyword,
                                              long current, long size) {
        Page<MaterialIssue> p = materialIssueDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<MaterialIssue>()
                        .in(MaterialIssue::getStatus, statuses)
                        .and(!isBlank(keyword), w -> {
                            w.like(MaterialIssue::getIssueNo, keyword)
                                    .or().like(MaterialIssue::getWorkOrderNo, keyword)
                                    .or().like(MaterialIssue::getIssueType, keyword);
                            applyItemHit(w, MaterialIssue::getId,
                                    "erp_inv_material_issue_line", "ISSUE_ID", keyword);
                        })
                        .orderByDesc(MaterialIssue::getCreateDate));
        Map<String, Long> qtyByDoc = sumLineQty("erp_inv_material_issue_line", "ISSUE_ID",
                p.getRecords(), MaterialIssue::getId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MaterialIssue mi : p.getRecords()) {
            Map<String, Object> m = base(mi.getId(), mi.getIssueNo(), mi.getStatus(),
                    mi.getCreateDate());
            m.put("sub1", mi.getIssueType());
            m.put("sub2", mi.getWorkOrderNo());
            m.put("qty", qtyByDoc.getOrDefault(mi.getId(), 0L));
            m.put("amount", null);
            m.put("itemSummary", itemSummary("erp_inv_material_issue_line", "ISSUE_ID", mi.getId()));
            m.put("note", null);
            rows.add(m);
        }
        return pageOut(rows, p.getTotal());
    }

    private Map<String, Object> transferQueue(List<String> statuses, String keyword,
                                              long current, long size) {
        Page<InvTransferOrder> p = transferOrderDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<InvTransferOrder>()
                        .in(InvTransferOrder::getStatus, statuses)
                        .and(!isBlank(keyword), w -> {
                            w.like(InvTransferOrder::getTransferNo, keyword)
                                    .or().like(InvTransferOrder::getOutWhCode, keyword)
                                    .or().like(InvTransferOrder::getInWhCode, keyword);
                            applyItemHit(w, InvTransferOrder::getId,
                                    "erp_inv_transfer_order_line", "ORDER_ID", keyword);
                        })
                        .orderByDesc(InvTransferOrder::getCreateDate));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InvTransferOrder o : p.getRecords()) {
            Map<String, Object> m = base(o.getId(), o.getTransferNo(), o.getStatus(),
                    o.getCreateDate());
            m.put("sub1", o.getOutWhCode() + " → " + o.getInWhCode());
            m.put("sub2", "1".equals(o.getCrossLe()) ? "跨法人" : "同法人");
            m.put("qty", o.getTotalQty());
            m.put("amount", o.getTotalAmount());
            m.put("itemSummary", itemSummary("erp_inv_transfer_order_line", "ORDER_ID", o.getId()));
            // 在途超期挂起标记（C-4.4-09）
            m.put("note", "1".equals(o.getSuspendedFlag()) ? "在途超期挂起" : null);
            rows.add(m);
        }
        return pageOut(rows, p.getTotal());
    }

    private Map<String, Object> scrapQueue(List<String> statuses, String keyword,
                                           long current, long size) {
        Page<InvScrapOrder> p = scrapOrderDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<InvScrapOrder>()
                        .in(InvScrapOrder::getStatus, statuses)
                        .and(!isBlank(keyword), w -> {
                            w.like(InvScrapOrder::getScrapNo, keyword)
                                    .or().like(InvScrapOrder::getNcrNo, keyword)
                                    .or().like(InvScrapOrder::getWarehouseCode, keyword);
                            applyItemHit(w, InvScrapOrder::getId,
                                    "erp_inv_scrap_order_line", "ORDER_ID", keyword);
                        })
                        .orderByDesc(InvScrapOrder::getCreateDate));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InvScrapOrder o : p.getRecords()) {
            Map<String, Object> m = base(o.getId(), o.getScrapNo(), o.getStatus(),
                    o.getCreateDate());
            m.put("sub1", reasonText(o.getReason())
                    + (isBlank(o.getNcrNo()) ? "" : " / " + o.getNcrNo()));
            m.put("sub2", o.getWarehouseCode());
            m.put("qty", o.getTotalQty());
            m.put("amount", o.getTotalAmount());
            m.put("itemSummary", itemSummary("erp_inv_scrap_order_line", "ORDER_ID", o.getId()));
            // 报废会签状态标记（呆滞=三方会签来源）
            m.put("note", InvScrapOrder.R_STALE.equals(o.getReason()) ? "呆滞·三方会签来源" : null);
            rows.add(m);
        }
        return pageOut(rows, p.getTotal());
    }

    // ---------- 6.2 动作委托域服务 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> post(String type, String docId) {
        requireWrite("出库过账");
        Object result;
        String status;
        switch (type) {
            case T_SALES -> {
                Shipment s = shipmentService.post(docId);
                status = s.getStatus();
                result = Map.of("shipNo", s.getShipNo());
            }
            case T_MATERIAL -> {
                Map<String, Object> r = materialIssueService.post(docId);
                // 返回结构 {issue: 实体, lineCount}——状态在 issue.status（POJO 非 Map）
                Object issue = r.get("issue");
                if (issue instanceof com.erp.entity.vmi.MaterialIssue mi) {
                    status = mi.getStatus();
                } else if (issue instanceof Map<?, ?> im) {
                    status = str(im.get("status"));
                } else {
                    status = str(r.get("status"));
                }
                result = r;
            }
            case T_TRANSFER -> {
                Map<String, Object> r = transferOrderService.postOut(docId);
                status = statusOf(r, "OUT_POSTED");
                result = Map.of("transferNo", noOf(r));
            }
            case T_SCRAP -> {
                Map<String, Object> r = scrapOrderService.post(docId);
                status = statusOf(r, "POSTED");
                result = Map.of("scrapNo", noOf(r));
            }
            default -> throw new ServiceException(422, "不支持的出库类型：" + type);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docId", docId);
        out.put("status", status);
        out.put("data", result);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirm(String docId, String logisticsCo, String logisticsNo) {
        requireWrite("发货确认");
        Shipment s = shipmentService.confirm(docId, logisticsCo, logisticsNo, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docId", docId);
        out.put("status", s.getStatus());
        out.put("data", Map.of("shipNo", s.getShipNo()));
        return out;
    }

    // ---------- helpers ----------

    private Map<String, Object> base(String id, String docNo, String status,
                                     java.time.LocalDateTime createDate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docId", id);
        m.put("docNo", docNo);
        m.put("status", status);
        m.put("createDate", createDate);
        return m;
    }

    private Map<String, Object> pageOut(List<Map<String, Object>> rows, long total) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        return out;
    }

    /** 表名/外键列为固定白名单（非用户输入）；关键词参数化预查行表命中的单据 ID */
    private java.util.Set<String> itemMatchedDocIds(String lineTable, String fkCol,
                                                    String keyword) {
        List<Map<String, Object>> rs = jdbcTemplate.queryForList(
                "SELECT DISTINCT " + fkCol + " AS docId FROM " + lineTable
                        + " WHERE ITEM_CODE LIKE ?", "%" + keyword + "%");
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (Map<String, Object> r : rs) {
            ids.add(String.valueOf(r.get("docId")));
        }
        return ids;
    }

    /**
     * 物料关键词命中分支（两步预查，避开 inSql 子查询在 OR 链中的行为差异）：
     * 命中 → id IN (…)；未命中 → 恒假 eq 保证结果为空而非忽略该条件。
     */
    private <T> void applyItemHit(LambdaQueryWrapper<T> w,
                                  com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> idCol,
                                  String lineTable, String fkCol, String keyword) {
        java.util.Set<String> ids = itemMatchedDocIds(lineTable, fkCol, keyword);
        if (ids.isEmpty()) {
            w.or().eq(idCol, "__ITEM_NO_MATCH__");
        } else {
            w.or().in(idCol, ids);
        }
    }

    /** 页内单据的行数量合计（key=单据 ID） */
    private <T> Map<String, Long> sumLineQty(String lineTable, String fkCol,
                                             List<T> docs,
                                             java.util.function.Function<T, String> idFn) {
        Map<String, Long> out = new HashMap<>();
        if (docs.isEmpty()) {
            return out;
        }
        List<String> ids = new ArrayList<>();
        for (T d : docs) {
            ids.add(idFn.apply(d));
        }
        for (Map<String, Object> r : jdbcRows(
                "SELECT " + fkCol + " AS docId, COALESCE(SUM(QTY),0) AS qty FROM " + lineTable
                        + " WHERE " + fkCol + " IN (?) GROUP BY " + fkCol, ids)) {
            out.put(String.valueOf(r.get("docId")),
                    ((Number) r.get("qty")).longValue());
        }
        return out;
    }

    /** 页内单据物料摘要（前 3 个名称 + 等 N 项） */
    private String itemSummary(String lineTable, String fkCol, String docId) {
        List<Map<String, Object>> rs = jdbcRows(
                "SELECT ITEM_NAME FROM " + lineTable + " WHERE " + fkCol
                        + " = ? ORDER BY LINE_NO LIMIT 4", List.of(docId));
        if (rs.isEmpty()) {
            return "-";
        }
        List<String> names = new ArrayList<>();
        for (Map<String, Object> r : rs) {
            names.add(String.valueOf(r.get("ITEM_NAME")));
        }
        if (names.size() > 3) {
            return String.join("、", names.subList(0, 3)) + " 等 " + names.size() + " 项";
        }
        return String.join("、", names);
    }

    /** 轻量 JDBC 查询（归一化聚合用；workbench 为纯读路径，参数化防注入） */
    private List<Map<String, Object>> jdbcRows(String sql, List<?> args) {
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    private String reasonText(String reason) {
        if (reason == null) {
            return "";
        }
        return switch (reason) {
            case "STALE" -> "呆滞";
            case "QUALITY" -> "质量";
            case "DAMAGE" -> "损坏";
            case "OTHER" -> "其他";
            default -> reason;
        };
    }

    private String statusOf(Map<String, Object> detail, String fallback) {
        Object o = detail.get("order");
        if (o instanceof InvTransferOrder t) {
            return t.getStatus();
        }
        if (o instanceof InvScrapOrder s) {
            return s.getStatus();
        }
        return fallback;
    }

    private String noOf(Map<String, Object> detail) {
        Object o = detail.get("order");
        if (o instanceof InvTransferOrder t) {
            return t.getTransferNo();
        }
        if (o instanceof InvScrapOrder s) {
            return s.getScrapNo();
        }
        return "";
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private void requireWrite(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String r : roles) {
            if ("ROLE_WAREHOUSE".equalsIgnoreCase(r)) {
                return;
            }
        }
        throw new ServiceException(403, "无权" + action + "（需 WAREHOUSE）");
    }
}
