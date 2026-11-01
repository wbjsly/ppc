package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 临时报表（add-adhoc-query-console，spec bi-temp-report）。
 * 存查询定义（非结果快照）——打开时按访问者身份重跑，权限与掩码实时生效；
 * 30 天到期归档 + 前 3 天提醒；一键转正式报表走口径校验。
 */
@Getter
@Setter
@TableName("erp_bi_temp_report")
public class TempReport extends BaseEntity {

    private String code;
    private String name;
    private String remark;
    /** 数据集=模板 ID */
    private String templateId;
    /** 查询定义快照（打开时重跑） */
    private String queryJson;
    /** 共享清单 [{type:user|role,code}] */
    private String shareJson;
    /** 创建+30 天 */
    private LocalDateTime expireAt;
    private Boolean expireNotified;
    private Boolean archivedFlag;
    /** 转正后模板 ID */
    private String promotedTo;
    private LocalDateTime promoteAt;
}
