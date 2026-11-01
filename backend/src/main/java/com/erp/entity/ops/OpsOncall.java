package com.erp.entity.ops;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 运维值班表（18.3）。
 */
@Data
@TableName("erp_ops_oncall")
public class OpsOncall implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private LocalDate dutyDate;

    /** L1/L2/L3 */
    private String onLevel;

    private String userName;

    private String backupUser;

    private String remark;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;
}
