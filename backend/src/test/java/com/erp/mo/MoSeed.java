package com.erp.mo;

import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
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
 * 工单管理测试共享造数（change add-work-order-management）：
 * 物料/BOM/路线经服务（需相应角色登录态），SO/库存/建议（PMO）以 JDBC 直插（无 FK）。
 * 全部使用「工单测试」前缀与 RMO 系列单号，cleanup 幂等清扫。
 */
final class MoSeed {

    static final String ITEM_PREFIX = "工单测试";
    static final String CAT_CODE = "RMOT";
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private MoSeed() {
    }

    static void cleanup(JdbcTemplate jdbc) {
        // 审批（本模块 bizType）
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID " +
                "WHERE a.BIZ_TYPE='MoApprove'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='MoApprove'");
        // 工单四表（按测试产品级联 + 孤儿兜底）
        jdbc.update("DELETE FROM erp_mrp_mo_shortage WHERE MO_ID IN " +
                "(SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%'))");
        jdbc.update("DELETE FROM erp_mrp_mo_bom WHERE MO_ID IN " +
                "(SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%'))");
        jdbc.update("DELETE FROM erp_mrp_mo_op WHERE MO_ID IN " +
                "(SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%'))");
        jdbc.update("DELETE FROM erp_mrp_mo WHERE PRODUCT_CODE IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%')");
        jdbc.update("DELETE FROM erp_mrp_mo_shortage WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo)");
        jdbc.update("DELETE FROM erp_mrp_mo_bom WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo)");
        jdbc.update("DELETE FROM erp_mrp_mo_op WHERE MO_ID NOT IN (SELECT ID FROM erp_mrp_mo)");
        // PMO 建议与运行（本测试专用前缀）
        jdbc.update("DELETE FROM erp_mrp_suggestion WHERE RUN_ID IN " +
                "(SELECT ID FROM erp_mrp_run WHERE RUN_NO LIKE 'RUN-RMO%')");
        jdbc.update("DELETE FROM erp_mrp_run WHERE RUN_NO LIKE 'RUN-RMO%'");
        // SO / 库存
        jdbc.update("DELETE FROM erp_sd_so_line WHERE SO_ID IN " +
                "(SELECT ID FROM erp_sd_so WHERE SO_NO LIKE 'SO-RMO%')");
        jdbc.update("DELETE FROM erp_sd_so WHERE SO_NO LIKE 'SO-RMO%'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%')");
        // BOM / 路线 / 工艺主数据
        jdbc.update("DELETE FROM erp_mrp_routing_op WHERE ROUTING_ID IN " +
                "(SELECT ID FROM erp_mrp_routing WHERE ITEM_CODE IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%'))");
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%')");
        jdbc.update("DELETE FROM erp_mrp_routing_op WHERE ROUTING_ID NOT IN (SELECT ID FROM erp_mrp_routing)");
        jdbc.update("DELETE FROM erp_mrp_bom_item WHERE BOM_ID IN " +
                "(SELECT ID FROM erp_mrp_bom WHERE PARENT_ITEM_CODE IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%'))");
        jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%')");
        jdbc.update("DELETE FROM erp_mrp_bom_item WHERE BOM_ID NOT IN (SELECT ID FROM erp_mrp_bom)");
        jdbc.update("DELETE FROM erp_mrp_op_wc_standard WHERE OP_CODE LIKE 'MOOP%' OR WC_CODE LIKE 'MOWC%'");
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'MOOP%'");
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'MOWC%'");
        // 物料与分类
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%'");
        jdbc.update("DELETE FROM erp_mdm_item_category WHERE ID='cat-rmo'");
    }

    /** 专属测试分类（4 字符码，隔离真实物料） */
    static String mkCategory(JdbcTemplate jdbc) {
        jdbc.update("INSERT IGNORE INTO erp_mdm_item_category (ID, CATEGORY_CODE, CATEGORY_NAME, ITEM_PREFIX) " +
                "VALUES ('cat-rmo', ?, '工单测试分类', 'RMOT')", CAT_CODE);
        return CAT_CODE;
    }

    /** 建物料（MAKE/BUY；extra 在 create 前注入扩展字段） */
    static MdmItem mkItem(MdmItemService itemService, String categoryCode, String name,
                          String purchaseType) {
        return mkItem(itemService, categoryCode, name, purchaseType, item -> {
        });
    }

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
        item.setDupNote("工单管理模块测试物料，非业务重复数据");
        extra.accept(item);
        return itemService.create(item, true);
    }

    /** 发布 BOM（服务创建 + JDBC 推进 + publishApproved；需 PROCESS_ENG/ADMIN 登录态） */
    static String publishBom(BomService bomService, JdbcTemplate jdbc,
                             MdmItem parent, MdmItem child, String qty, String lossRate) {
        MrpBom head = new MrpBom();
        head.setParentItemCode(parent.getItemCode());
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(child.getItemCode());
        row.setQty(new BigDecimal(qty));
        row.setLossRate(new BigDecimal(lossRate));
        MrpBom draft = bomService.create(head, List.of(row));
        jdbc.update("UPDATE erp_mrp_bom SET STATUS='PENDING' WHERE ID=?", draft.getId());
        bomService.publishApproved(draft.getId());
        return draft.getId();
    }

    /** 发布路线（字典 + 定额 + 路线全链；返回 wcCode 便于停用操作） */
    static String publishRouting(RoutingService routingService, OperationService operationService,
                                 WorkCenterService workCenterService,
                                 OpWcStandardService standardService,
                                 JdbcTemplate jdbc, MdmItem product, int leadDays) {
        int n = SEQ.incrementAndGet();
        String opCode = "MOOP-" + n;
        String wcCode = "MOWC-" + n;
        MrpOperation op = new MrpOperation();
        op.setOpCode(opCode);
        op.setOpName("工单测试工序" + n);
        operationService.create(op);
        MrpWorkCenter wc = new MrpWorkCenter();
        wc.setWcCode(wcCode);
        wc.setWcName("工单测试中心" + n);
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
        return wcCode;
    }

    /** 造已确认 SO 行（超交校验需求源；JDBC 直插） */
    static void mkSo(JdbcTemplate jdbc, String itemCode, String qty, LocalDate date) {
        String id = "so-rmo-" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO erp_sd_so (ID, SO_NO, CUSTOMER_ID, STATUS) VALUES (?,?, 'CUST-RMO-TEST', 'CONFIRMED')",
                id, "SO-RMO-" + id);
        jdbc.update("INSERT INTO erp_sd_so_line (ID, SO_ID, LINE_NO, ITEM_CODE, QTY, SHIPPED_QTY, " +
                        "EXPECT_DELIVERY_DATE, LINE_STATUS) VALUES (?,?,1,?,?,0,?,'OPEN')",
                id + "-L1", id, itemCode, new BigDecimal(qty), date);
    }

    /** 造库存（OnHand = ΣAVAILABLE_QTY） */
    static void mkStock(JdbcTemplate jdbc, String itemCode, String available) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, ITEM_CODE, ITEM_NAME, QTY, AVAILABLE_QTY) VALUES (?,?,?,?,?)",
                "st-rmo-" + SEQ.incrementAndGet(), itemCode, ITEM_PREFIX,
                new BigDecimal(available), new BigDecimal(available));
    }

    /**
     * 造已转正且未关联工单的 PMO 建议（RUN-RMO 前缀；D6 对接入口测试数据）。
     * 返回 suggestionId。
     */
    static String mkPmoSuggestion(JdbcTemplate jdbc, String itemCode, String pmoNo, String qty) {
        String runId = "run-rmo-" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO erp_mrp_run (ID, RUN_NO, SCOPE_TYPE, RUN_STATUS) VALUES (?,?, 'FULL', 'DONE')",
                runId, "RUN-RMO-" + runId);
        String sugId = "sug-rmo-" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO erp_mrp_suggestion (ID, RUN_ID, ITEM_CODE, TYPE, NET_REQ, SUGGEST_QTY, " +
                        "STATUS, TARGET_NO, CONFIRM_QTY) VALUES (?,?,?, 'PRODUCTION', ?, ?, 'CONVERTED', ?, ?)",
                sugId, runId, itemCode, new BigDecimal(qty), new BigDecimal(qty), pmoNo, new BigDecimal(qty));
        return sugId;
    }
}
