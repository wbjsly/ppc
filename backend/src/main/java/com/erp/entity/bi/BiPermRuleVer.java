package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 行级规则版本快照（add-perm-control-console，spec bi-row-permission，BR-4.10-17 回退锚）。 */
@Getter
@Setter
@TableName("erp_bi_perm_rule_ver")
public class BiPermRuleVer extends BaseEntity {

    private String ruleId;
    /** 业务版本号（区别于 BaseEntity 乐观锁 VER_NO） */
    private Integer ruleVer;
    /** 规则全字段快照 JSON（不可变） */
    private String snapshotJson;
    private LocalDateTime effDate;
}
