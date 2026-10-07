package com.erp.ops;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 门户推送事件类型与幂等键构造（spec portal-event-push，design D10）。
 * 幂等键全局唯一（uk on IDEMPOTENCY_KEY）→ 业务键内嵌事件语义段，避免跨类型撞键（C-0-06）。
 */
public final class PortalEvents {

    private PortalEvents() {
    }

    public static final String PO_PUSHED = "PROC.PO_PUSHED";
    public static final String PO_CONFIRMED = "PROC.PO_CONFIRMED";
    public static final String ASN_CREATED = "PROC.ASN_CREATED";
    public static final String WATER_SYNCED = "VMI.WATER_SYNCED";
    public static final String REPLENISH_PUSHED = "VMI.REPLENISH_PUSHED";
    public static final String SETTLE_PUSHED = "VMI.SETTLE_PUSHED";

    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyyMMddHH");

    /** PO 下达：biz = {poNo}#PUSH，版本 = PO 版本 */
    public static String poPushedKey(String poNo, int version) {
        return poNo + "#PUSH";
    }

    public static int poPushedVersion(int version) {
        return version;
    }

    /** 交期确认：biz = {poNo}#CONFIRM，版本 = 确认序（支持改期再确认） */
    public static String poConfirmedKey(String poNo) {
        return poNo + "#CONFIRM";
    }

    /** ASN 创建：biz = {asnNo}，v1 */
    public static String asnCreatedKey(String asnNo) {
        return asnNo;
    }

    /** 水位同步：biz = WATER:{sid}:H{yyyyMMddHH}（小时桶），同桶重复不写 */
    public static String waterSyncedKey(String supplierId, LocalDateTime at) {
        return "WATER:" + supplierId + ":H" + HOUR_FMT.format(at);
    }

    /** 补货建议推送：biz = {alertId}#RPL */
    public static String replenishPushedKey(String alertId) {
        return alertId + "#RPL";
    }

    /** 补货确认：biz = {alertId}#RPLC */
    public static String replenishConfirmedKey(String alertId) {
        return alertId + "#RPLC";
    }

    /** 结算推送：biz = {settleNo}#STL，版本 = 推送序（生成/双确认发起可多次） */
    public static String settlePushedKey(String settleNo) {
        return settleNo + "#STL";
    }
}
