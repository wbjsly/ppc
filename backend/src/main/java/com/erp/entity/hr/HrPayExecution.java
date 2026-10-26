package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 发放执行登记（出纳手工登记；网银接口留桩）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_pay_execution")
public class HrPayExecution extends BaseEntity {
    public static final String ST_PAID = "PAID";
    public static final String ST_PARTIAL = "PARTIAL";

    private String runId;
    private String disbId;
    private String period;
    private LocalDate payDate;
    private String payAccount;
    /** 实付合计（须=文件合计） */
    private BigDecimal paidTotal;
    private String bankSerial;
    private Integer successCount;
    private Integer failCount;
    /** 失败笔清单（员工/原因） */
    private String failDetail;
    private String status;
}
