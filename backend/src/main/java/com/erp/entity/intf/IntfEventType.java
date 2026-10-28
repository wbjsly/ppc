package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 事件定义注册表（add-event-subscription，FR-4.9-2-1）。
 * 类型码 {对象}_{动作} 命名规范；Schema JSON 描述必填字段清单；
 * 版本 Major.Minor：删必填/改类型/收紧须升 Major，增可选升 Minor，落事件信封。
 */
@Getter
@Setter
@TableName("erp_intf_event_type")
public class IntfEventType extends BaseEntity {

    public static final String ST_ENABLED = "ENABLED";
    public static final String ST_DISABLED = "DISABLED";

    private String eventType;
    private String typeName;
    /** {"required":[{"field":"poNo","type":"string"},...]} */
    private String schemaJson;
    private Integer versionMajor;
    private Integer versionMinor;
    private String status;
    private String remark;
}
