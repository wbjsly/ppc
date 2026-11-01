package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 凭证/版本灰度切流量规则（add-intf-portal-menus，spec interface-canary-release，
 * 对齐 SOP-5.5-B 步4 计划内轮换灰度切流量）。
 * 双凭证并行 + 阶梯百分比切量 + 观察窗口错误率超阈自动回滚。
 */
@Getter
@Setter
@TableName("erp_intf_gray_release")
public class IntfGrayRelease extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_RUNNING = "RUNNING";
    public static final String ST_ROLLBACK = "ROLLBACK";
    public static final String ST_COMPLETED = "COMPLETED";
    public static final String ST_ABORTED = "ABORTED";

    private String channelCode;
    private String oldCredId;
    private String newCredId;
    private String ladderJson;
    private Integer currentStep;
    private Integer observeMin;
    private BigDecimal rollbackErrorRate;
    private String status;
}
