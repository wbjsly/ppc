package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** HR 职级（M10-2-4，带宽挂职级：岗位经 GRADE_ID 间接获得带宽）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_grade")
public class HrGrade extends BaseEntity {

    public static final String SERIES_P = "P";
    public static final String SERIES_M = "M";
    public static final String ST_ACTIVE = "1";
    public static final String ST_INACTIVE = "0";

    /** 职级编码 G+3 位流水 */
    private String gradeNo;
    private String name;
    /** 序列 P 管理 / M 技术 */
    private String series;
    /** 带宽下限（月薪） */
    private BigDecimal salMin;
    /** 带宽上限（月薪） */
    private BigDecimal salMax;
    /** 1 启用 / 0 停用 */
    private String status;
}
