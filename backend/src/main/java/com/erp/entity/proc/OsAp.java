package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 外协应付挂账（change add-outsourcing-processing，spec outsourcing-settlement，迁移 126）：
 * 对账单审批通过后生成（金额 = 对账单 TOTAL_AMT，来源对账单号唯一）。
 * 偏差 D-OS-02：本期仅状态与金额（OPEN），付款/核销留未来独立 AP 变更。
 */
@Getter
@Setter
@TableName("erp_proc_os_ap")
public class OsAp extends BaseEntity {

    public static final String ST_OPEN = "OPEN";

    /** 挂账单号 OSA+yyyyMMdd+流水 */
    private String apNo;
    /** 来源对账单（唯一） */
    private String osrNo;
    private String supplierCode;
    private String supplierName;
    /** 挂账金额（= 对账单 TOTAL_AMT） */
    private BigDecimal amt;
    /** OPEN（本期仅挂账） */
    private String status;
    private LocalDateTime postDate;
    private String remark;
}
