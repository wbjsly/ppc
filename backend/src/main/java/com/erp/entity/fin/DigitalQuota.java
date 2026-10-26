package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数电票授信额度台账（M9-2-3，BR-4.14-36，D5 一期一行）。
 */
@Data
@TableName("erp_fin_digital_quota")
public class DigitalQuota {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String period;
    private BigDecimal totalLimit;
    private BigDecimal usedAmt;
    private String remark;
    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
