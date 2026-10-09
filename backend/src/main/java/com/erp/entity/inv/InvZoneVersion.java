package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 区域版本快照（4.1.2，design D1，仿 erp_mdm_org_unit_version）：
 * 每次变更将整行新值写入 SNAPSHOT_JSON，VERSION_NO 递增。
 */
@Getter
@Setter
@TableName("erp_inv_zone_version")
public class InvZoneVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String entityId;

    private Integer versionNo;

    private String snapshotJson;

    private String diffSummary;

    /** CREATE / UPDATE / DISABLE / ENABLE */
    private String opType;

    private String createBy;

    private LocalDateTime createDate;
}
