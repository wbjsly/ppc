package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 站内通知记录（按角色投递，与审批待办同一聚合口径）。
 * 首个用例：商机预期金额 >100 万自动通知销售经理（FR-4.8-1-5）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sys_notice")
public class SysNotice extends BaseEntity {

    /** 业务类型：大额商机提醒 */
    public static final String BIZ_OPP_LARGE = "OPP_LARGE_AMOUNT";

    private String targetRole;
    private String targetUser;
    private String title;
    private String content;
    private String bizType;
    private String bizId;
    /** 1 = 已读 */
    private String readFlag;
    private LocalDateTime readAt;
}
