package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 打卡记录（三通道统一：WEB/GPS/CSV/MANUAL；同人同日同时刻同向去重）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_punch_record")
public class HrPunchRecord extends BaseEntity {
    public static final String DIR_IN = "IN";
    public static final String DIR_OUT = "OUT";
    public static final String SRC_WEB = "WEB";
    public static final String SRC_GPS = "GPS";
    public static final String SRC_CSV = "CSV";
    public static final String SRC_MANUAL = "MANUAL";

    private String empId;
    private LocalDate punchDate;
    /** 打卡时刻 HH:mm:ss */
    private String punchTime;
    /** IN/OUT */
    private String dir;
    /** WEB/GPS/CSV/MANUAL */
    private String source;
    private BigDecimal lat;
    private BigDecimal lng;
    /** 可疑标记（定位超范围等） */
    private String suspicious;
    /** 设备标识（留桩） */
    private String deviceId;
    /** CSV 导入批次 */
    private String importBatchId;
    private String remark;
}
