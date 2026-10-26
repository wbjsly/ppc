package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 工序字典（change add-routing-management，spec routing-management / 00-erp-spec 4.5 消费方反推；迁移 118）。
 * 全局复用：编码创建后不可修改（服务层校验），禁止硬删除（仅停用/逻辑删除）；
 * STATUS '1' 启用 / '0' 停用——停用工序不可选入新路线行（spec「工序字典维护」）。
 */
@Getter
@Setter
@TableName("erp_mrp_operation")
public class MrpOperation extends BaseEntity {

    public static final String ST_ACTIVE = "1";
    public static final String ST_INACTIVE = "0";

    /** 工序编码（创建后不可修改） */
    private String opCode;
    private String opName;
    /** 技能要求（自由文本；结构化口径待 5.4 人员匹配，design Open Question 1） */
    private String skillReq;
    /** 状态 1启用/0停用 */
    private String status;
}
