package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 拣货扫码校验记录（BR-4.4-27，spec picking-review 偏差 D1）：
 * 仅记失败事件（FAIL）+ 每行最终放行标记（PASS），不做逐码事件流。
 */
@Getter
@Setter
@TableName("erp_inv_pick_scan_log")
public class PickScanLog extends BaseEntity {

    public static final String T_BIN = "BIN";
    public static final String T_ITEM = "ITEM";
    public static final String T_BATCH = "BATCH";
    public static final String T_SERIAL = "SERIAL";
    public static final String T_PASS = "PASS";

    public static final String R_FAIL = "FAIL";
    public static final String R_OK = "OK";

    private String taskId;

    private String lineId;

    private String scanType;

    private String inputCode;

    private String expectCode;

    private String result;

    private String failReason;
}
