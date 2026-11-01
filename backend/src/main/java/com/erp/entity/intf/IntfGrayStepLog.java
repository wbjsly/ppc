package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 灰度切量/回滚记录（add-intf-portal-menus，spec interface-canary-release）。
 * ACTION：STEP_IN（切量）/ ROLLBACK（回滚）/ ABORT（中止）；OPERATOR=system 表示自动回滚。
 */
@Getter
@Setter
@TableName("erp_intf_gray_step_log")
public class IntfGrayStepLog extends BaseEntity {

    public static final String ACT_STEP_IN = "STEP_IN";
    public static final String ACT_ROLLBACK = "ROLLBACK";
    public static final String ACT_ABORT = "ABORT";

    public static final String RES_SUCCESS = "SUCCESS";
    public static final String RES_FAILED = "FAILED";

    private String releaseId;
    private Integer step;
    private Integer pct;
    private String action;
    private String result;
    private String operator;
    private BigDecimal errorRate;
    private String remark;
}
