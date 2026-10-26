package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** 出差/外勤单（BR-4.7-12：覆盖时段自动认定正常出勤；登记即生效离线留痕）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_business_trip")
public class HrBusinessTrip extends BaseEntity {
    public static final String ST_PENDING = "PENDING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";

    private String empId;
    private LocalDate startDate;
    private LocalDate endDate;
    private String destination;
    private String reason;
    private String status;
    private String apprId;
}
