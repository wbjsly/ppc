package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** IP 资产档案（add-ip-management，只增不改 BR-4.8-26，一成果一档）。 */
@Getter
@Setter
@TableName("erp_pm_ip_asset")
public class PmIpAsset extends BaseEntity {

    /** 唯一（UK，幂等生成依据） */
    private String achieveId;
    private String achieveNo;
    private String name;
    private String type;
    private String projectId;
    /** 权属快照 JSON */
    private String ownershipResult;
    private java.time.LocalDate completeDate;
    /** 保护期届满（专利+20y/软著+50y/其余 NULL） */
    private java.time.LocalDate protectExpireDate;
    /** NOT_APPLIED|SUBMITTED|GRANTED|REJECTED */
    private String applyStatus;
    private java.time.LocalDateTime applyUpdatedAt;

    public static final String APPLY_NOT_APPLIED = "NOT_APPLIED";
    public static final String APPLY_SUBMITTED = "SUBMITTED";
    public static final String APPLY_GRANTED = "GRANTED";
    public static final String APPLY_REJECTED = "REJECTED";
}
