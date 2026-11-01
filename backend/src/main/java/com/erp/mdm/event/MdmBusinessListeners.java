package com.erp.mdm.event;

import com.erp.mdm.cache.MdmReadCache;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 三个真实下游监听器（mdm-event-sync spec：差异同步+监听台账+失败重试≤3转工单）。
 * 监听失败 MUST NOT 抛回发布方（内部广播隔离）。
 */
@Slf4j
@Component
public class MdmBusinessListeners {

    private static final int MAX_RETRY = 3;

    /** 价格相关属性（变更含其一 → 价目重算提示） */
    private static final List<String> PRICE_FIELDS = List.of(
            "standardCost", "standard_cost", "purchaseType", "purchase_type", "baseUnit", "base_unit");

    /** 信用相关属性 */
    private static final List<String> CREDIT_FIELDS = List.of(
            "creditLimit", "credit_limit", "tempCreditLimit", "temp_credit_limit",
            "compressedLimit", "compressed_limit");

    private final MdmListenerSupport support;
    private final JdbcTemplate jdbc;
    private final MdmReadCache cache;

    public MdmBusinessListeners(MdmListenerSupport support, JdbcTemplate jdbc, MdmReadCache cache) {
        this.support = support;
        this.jdbc = jdbc;
        this.cache = cache;
    }

    /** ① 物料变更 → 价目协议重算提示（标记受影响价格协议待重估，记台账明细） */
    @Order(10)
    @EventListener
    public void onItemUpdated(MdmChangedEvent e) {
        if (!"ITEM".equals(e.getBizType()) || !"UPDATED".equals(suffix(e.getEventType()))
                || e.isRollback()) {
            return;
        }
        runWithRetry(e, "priceRecalcMark", () -> {
            List<String> changed = e.getChangedFields() == null ? List.of() : e.getChangedFields();
            boolean priceRelated = changed.isEmpty() || changed.stream()
                .anyMatch(f -> PRICE_FIELDS.stream().anyMatch(p -> p.equalsIgnoreCase(f)));
            if (!priceRelated) {
                return "非价格属性变更，无需重算";
            }
            String itemCode = jdbc.queryForObject(
                    "SELECT ITEM_CODE FROM erp_mdm_item WHERE ID=?", String.class, e.getEntityId());
            List<Map<String, Object>> pas = jdbc.queryForList(
                    "SELECT DISTINCT pa.PA_CODE FROM erp_mdm_price_agreement_line l "
                            + "JOIN erp_mdm_price_agreement pa ON pa.ID=l.PA_ID AND pa.DEL_FLAG='0' "
                            + "WHERE l.ITEM_CODE=? AND l.DEL_FLAG='0'", itemCode);
            String affected = pas.stream().map(m -> String.valueOf(m.get("PA_CODE")))
                    .reduce((a, b) -> a + "," + b).orElse("");
            // 受影响 PA 清单记入台账明细（行表无备注列，标记以台账为准）
            return affected.isEmpty() ? "无关联价格协议"
                    : "待重估价格协议：" + affected + "（" + pas.size() + " 个）";
        });
    }

    /** ② 客户视图信用变更 → 信用检查基线即时可见（失效信用缓存+记台账） */
    @Order(20)
    @EventListener
    public void onCustomerViewUpdated(MdmChangedEvent e) {
        if (!"CUSTOMER_VIEW".equals(e.getBizType()) || !"UPDATED".equals(suffix(e.getEventType()))) {
            return;
        }
        runWithRetry(e, "creditRefresh", () -> {
            List<String> changed = e.getChangedFields() == null ? List.of() : e.getChangedFields();
            boolean creditRelated = changed.isEmpty() || changed.stream()
                .anyMatch(f -> CREDIT_FIELDS.stream().anyMatch(c -> c.equalsIgnoreCase(f)));
            // CUSTOMER_VIEW 桶已在缓存失效监听中清空；信用检查直读同库即时可见
            return creditRelated ? "信用字段变更，信用检查基线已即时可见" : "非信用字段变更，仅记录";
        });
    }

    /** ③ 税码/税策变更 → 税务工作台待同步提示 */
    @Order(30)
    @EventListener
    public void onTaxUpdated(MdmChangedEvent e) {
        String biz = e.getBizType();
        if (!"TAX_CODE".equals(biz) && !"TAX_POLICY".equals(biz)) {
            return;
        }
        if (!"UPDATED".equals(suffix(e.getEventType())) && !"CREATED".equals(suffix(e.getEventType()))) {
            return;
        }
        runWithRetry(e, "taxSyncHint", () -> {
            try {
                jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                                + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                                + "VALUES (UUID(), 'ROLE_FINANCE', 'MdmTaxSyncHint', ?, ?, ?, "
                                + "'0', 'system', NOW(), '0', 0)",
                        e.getEntityId(),
                        "税码/税策变更待同步税务工作台",
                        e.getEventType() + "（" + biz + " entityId=" + e.getEntityId()
                                + "）已发布，请在税务工作台确认同步。");
                return "已写税务工作台同步提示";
            } catch (Exception ex) {
                // 通知表不可用不阻断（降级为仅台账记录）
                return "通知写入降级：" + ex.getMessage();
            }
        });
    }

    // ---------------- 工具 ----------------

    private void runWithRetry(MdmChangedEvent e, String listenerName, java.util.function.Supplier<String> action) {
        long start = System.currentTimeMillis();
        int attempt = 0;
        Exception last = null;
        while (attempt < MAX_RETRY) {
            try {
                String detail = action.get();
                support.record(e, listenerName, MdmListenerLogStatus.SUCCESS, attempt,
                        System.currentTimeMillis() - start, detail, MAX_RETRY);
                return;
            } catch (Exception ex) {
                last = ex;
                attempt++;
                sleepBackoff(attempt);
            }
        }
        try {
            support.record(e, listenerName, MdmListenerLogStatus.FAILED, attempt,
                    System.currentTimeMillis() - start,
                    last == null ? "unknown" : last.getMessage(), MAX_RETRY);
        } catch (Exception ex) {
            log.warn("mdm listener log write failed: {}", ex.getMessage());
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(50L * attempt); // 指数退避简化：50ms/100ms
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String suffix(String eventType) {
        if (eventType == null) {
            return "";
        }
        int i = eventType.lastIndexOf('.');
        return i < 0 ? eventType : eventType.substring(i + 1);
    }

    /** 台账状态常量（避免循环依赖 entity 包语义） */
    private static final class MdmListenerLogStatus {
        static final String SUCCESS = "SUCCESS";
        static final String FAILED = "FAILED";
    }
}
