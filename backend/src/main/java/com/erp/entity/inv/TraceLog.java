package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 追溯操作审计（C-4.4-10/BR-4.4-53：User/Action/Object/Before/After/TS；迁移 115）。
 * 简化留痕表（链式哈希不可篡改记偏差），按追溯单号时间序回放；保存期 AUDIT_RETENTION_DAYS。
 * 独立表结构（无 BaseEntity 逻辑删除/乐观锁列），不可更新只追加。
 */
@Getter
@Setter
@TableName("erp_inv_trace_log")
public class TraceLog implements Serializable {

    private static final long serialVersionUID = 1L;

    // ---- 动作常量（审计 ACTION 列） ----
    public static final String A_ANALYZE = "ANALYZE";
    public static final String A_MATERIALIZE = "MATERIALIZE";
    public static final String A_FREEZE = "FREEZE";
    public static final String A_LINK = "LINK";
    public static final String A_INTERCEPT = "INTERCEPT";
    public static final String A_INTERCEPT_FAIL = "INTERCEPT_FAIL";
    public static final String A_RETURN = "RETURN";
    public static final String A_RECEIVE = "RECEIVE";
    public static final String A_DISPOSE = "DISPOSE";
    public static final String A_CLOSE = "CLOSE";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String traceId;

    /** 操作人（系统动作用 system:xxx） */
    private String actionUser;

    private String action;

    /** 对象类型：ORDER / FLOW / STOCK */
    private String objectType;

    private String objectId;

    private String beforeVal;

    private String afterVal;

    private LocalDateTime opAt;
}
