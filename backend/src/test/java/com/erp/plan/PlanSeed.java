package com.erp.plan;

import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpOperation;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.WorkCenterService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 需求计划测试共享造数（change add-mrp-demand-planning）：
 * 跨域数据源（SO/库存/在途 PO）以 JDBC 直插（无 FK 约束），BOM/路线经服务发布；
 * 全部使用「计划测试」前缀，cleanup 幂等清扫。
 */
final class PlanSeed {

    static final String ITEM_PREFIX = "计划测试";
    static final String CAT_CODE = "RPLA";
    static final String CUSTOMER_ID = "CUST-RPLAN-TEST";
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private PlanSeed() {
    }

    static void cleanup(JdbcTemplate jdbc) {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID " +
                "WHERE a.BIZ_TYPE IN ('BomPublish','RoutingPublish')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('BomPublish','RoutingPublish')");
        jdbc.update("DELETE FROM erp_mrp_run");
        jdbc.update("DELETE FROM erp_mrp_suggestion");
        jdbc.update("DELETE FROM erp_sd_so_line WHERE SO_ID IN (SELECT ID FROM erp_sd_so WHERE SO_NO LIKE 'SO-RPLAN%')");
        jdbc.update("DELETE FROM erp_sd_so WHERE SO_NO LIKE 'SO-RPLAN%'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '计划测试%')");
        jdbc.update("DELETE FROM erp_proc_po_line WHERE PO_ID IN (SELECT ID FROM erp_proc_po WHERE PO_NO LIKE 'PO-RPLAN%')");
        jdbc.update("DELETE FROM erp_proc_po WHERE PO_NO LIKE 'PO-RPLAN%'");
        jdbc.update("DELETE FROM erp_mrp_routing_op WHERE ROUTING_ID IN " +
                "(SELECT ID FROM erp_mrp_routing WHERE ITEM_CODE IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '计划测试%'))");
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '计划测试%')");
        jdbc.update("DELETE FROM erp_mrp_routing_op WHERE ROUTING_ID NOT IN (SELECT ID FROM erp_mrp_routing)");
        jdbc.update("DELETE FROM erp_mrp_bom_item WHERE BOM_ID IN " +
                "(SELECT ID FROM erp_mrp_bom WHERE PARENT_ITEM_CODE IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '计划测试%'))");
        jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '计划测试%')");
        jdbc.update("DELETE FROM erp_mrp_bom_item WHERE BOM_ID NOT IN (SELECT ID FROM erp_mrp_bom)");
        jdbc.update("DELETE FROM erp_mrp_op_wc_standard WHERE OP_CODE LIKE 'PLANOP%' OR WC_CODE LIKE 'PLANWC%'");
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'PLANOP%'");
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'PLANWC%'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE '计划测试%'");
        jdbc.update("DELETE FROM erp_mdm_item_category WHERE ID='cat-rplan'");
    }

    /** 专属测试分类（范围隔离：真实物料不使用该分类，GROUP 两组均被占用故用分类维度） */
    static String mkCategory(JdbcTemplate jdbc) {
        jdbc.update("INSERT IGNORE INTO erp_mdm_item_category (ID, CATEGORY_CODE, CATEGORY_NAME, ITEM_PREFIX) " +
                "VALUES ('cat-rplan', ?, '计划测试分类', 'RPLAN')", CAT_CODE);
        return CAT_CODE;
    }

    /** 建物料（BUY/MAKE 可指定，扩展字段由调用方在 create 前 set） */
    static MdmItem mkItem(MdmItemService itemService, String categoryCode, String name,
                          String purchaseType) {
        return mkItem(itemService, categoryCode, name, purchaseType, item -> {
        });
    }

    /** 建物料（extra 在 create 前注入 LT/MOQ/ROP/MAX_STOCK 等扩展字段） */
    static MdmItem mkItem(MdmItemService itemService, String categoryCode, String name,
                          String purchaseType, java.util.function.Consumer<MdmItem> extra) {
        MdmItem item = new MdmItem();
        item.setItemName(ITEM_PREFIX + name);
        item.setCategoryCode(categoryCode);
        item.setBaseUnit("PC");
        item.setMaterialGroup("STRUCT");
        item.setPurchaseType(purchaseType);
        item.setStorageCondition("NORMAL");
        item.setBatchFlag("0");
        item.setPackingSpec("箱");
        item.setDupNote("需求计划模块测试物料，非业务重复数据");
        extra.accept(item);
        return itemService.create(item, true);
    }

    /** 造已确认 SO 行（需求源；无 FK，直接 JDBC） */
    static void mkSo(JdbcTemplate jdbc, String itemCode, String qty, String shippedQty, LocalDate date) {
        String id = "so-rplan-" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO erp_sd_so (ID, SO_NO, CUSTOMER_ID, STATUS) VALUES (?,?,?, 'CONFIRMED')",
                id, "SO-RPLAN-" + id, CUSTOMER_ID);
        jdbc.update("INSERT INTO erp_sd_so_line (ID, SO_ID, LINE_NO, ITEM_CODE, QTY, SHIPPED_QTY, " +
                        "EXPECT_DELIVERY_DATE, LINE_STATUS) VALUES (?,?,1,?,?,?,?,'OPEN')",
                id + "-L1", id, itemCode, new BigDecimal(qty), new BigDecimal(shippedQty), date);
    }

    /** 造库存（OnHand = ΣAVAILABLE_QTY） */
    static void mkStock(JdbcTemplate jdbc, String itemCode, String available) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, ITEM_CODE, ITEM_NAME, QTY, AVAILABLE_QTY) " +
                        "VALUES (?,?,?,?,?)",
                "st-rplan-" + SEQ.incrementAndGet(), itemCode, ITEM_PREFIX,
                new BigDecimal(available), new BigDecimal(available));
    }

    /** 造 APPROVED 在途 PO（在途 = QTY − RECEIVED_QTY） */
    static void mkPo(JdbcTemplate jdbc, String itemCode, String qty, String received) {
        String id = "po-rplan-" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO erp_proc_po (ID, PO_NO, SOURCE, SUPPLIER_ID, STATUS) " +
                "VALUES (?,?, 'MANUAL', 'SUP-RPLAN-TEST', 'APPROVED')", id, "PO-RPLAN-" + id);
        jdbc.update("INSERT INTO erp_proc_po_line (ID, PO_ID, LINE_NO, ITEM_CODE, QTY, UNIT_PRICE, RECEIVED_QTY) " +
                        "VALUES (?,?,1,?,?,1,?)",
                id + "-L1", id, itemCode, new BigDecimal(qty), new BigDecimal(received));
    }

    /** 发布 BOM（服务创建 + JDBC 推进 + publishApproved） */
    static String publishBom(BomService bomService, JdbcTemplate jdbc,
                             MdmItem parent, MdmItem child, String qty, String lossRate) {
        com.erp.entity.mrp.MrpBom head = new com.erp.entity.mrp.MrpBom();
        head.setParentItemCode(parent.getItemCode());
        com.erp.entity.mrp.MrpBomItem row = new com.erp.entity.mrp.MrpBomItem();
        row.setItemCode(child.getItemCode());
        row.setQty(new BigDecimal(qty));
        row.setLossRate(new BigDecimal(lossRate));
        com.erp.entity.mrp.MrpBom draft = bomService.create(head, List.of(row));
        jdbc.update("UPDATE erp_mrp_bom SET STATUS='PENDING' WHERE ID=?", draft.getId());
        bomService.publishApproved(draft.getId());
        return draft.getId();
    }

    /** 发布路线（字典 + 定额 + 路线全链） */
    static String publishRouting(RoutingService routingService, OperationService operationService,
                                 WorkCenterService workCenterService,
                                 OpWcStandardService standardService,
                                 JdbcTemplate jdbc, MdmItem product, int leadDays) {
        int n = SEQ.incrementAndGet();
        String opCode = "PLANOP-" + n;
        String wcCode = "PLANWC-" + n;
        MrpOperation op = new MrpOperation();
        op.setOpCode(opCode);
        op.setOpName("计划测试工序" + n);
        operationService.create(op);
        MrpWorkCenter wc = new MrpWorkCenter();
        wc.setWcCode(wcCode);
        wc.setWcName("计划测试中心" + n);
        workCenterService.create(wc);
        MrpOpWcStandard std = new MrpOpWcStandard();
        std.setOpCode(opCode);
        std.setWcCode(wcCode);
        std.setRunHours(BigDecimal.ONE);
        standardService.create(std);

        MrpRouting head = new MrpRouting();
        head.setItemCode(product.getItemCode());
        MrpRoutingOp row = new MrpRoutingOp();
        row.setOpCode(opCode);
        row.setWcCode(wcCode);
        row.setLeadTime(new BigDecimal(leadDays));
        MrpRouting draft = routingService.create(head, List.of(row));
        jdbc.update("UPDATE erp_mrp_routing SET STATUS='PENDING' WHERE ID=?", draft.getId());
        routingService.publishApproved(draft.getId());
        return draft.getId();
    }
}
