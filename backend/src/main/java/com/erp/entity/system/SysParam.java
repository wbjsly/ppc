package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 通用业务参数（spec 见本变更 tasks 1.1 / design D13）。
 * 阈值集中一处、运行时可调并留痕；键唯一，读取走 {@code SysParamService} 的进程内缓存。
 */
@Getter
@Setter
@TableName("erp_sys_param")
public class SysParam extends BaseEntity {

    /** 参数键，如 SO_APPROVAL_AUTO */
    private String paramKey;

    /** 当前值 */
    private String paramValue;

    /** STRING / NUMBER / RATE / AMOUNT / DAYS / ENUM */
    private String valueType;

    /** 分组 SD / CREDIT / DISCOUNT / ATP / RETURN / COMMON */
    private String paramGroup;

    /** 口径说明（含默认值与规则出处） */
    private String remark;

    /** 调整历史 JSON [{old,new,by,at}] */
    private String historyJson;
}
