package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * SO 变更记录（tasks 7.9，spec sales-order BR-4.3-30）。
 * CREDIT_RERUN：变更重跑信用的结论 PASS/FROZEN/NEED_APPROVAL。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_so_change")
public class SoChange extends BaseEntity {

    public static final String RERUN_PASS = "PASS";
    public static final String RERUN_FROZEN = "FROZEN";
    public static final String RERUN_NEED_APPROVAL = "NEED_APPROVAL";

    private String soId;
    private Integer lineNo;
    private String fieldName;
    private String oldValue;
    private String newValue;
    private String creditRerun;
    private String reason;
    private String operatorId;
    private LocalDateTime operateAt;
}
