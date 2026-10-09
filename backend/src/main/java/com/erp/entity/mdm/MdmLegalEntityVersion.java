package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 法人主体版本快照：每次变更/停用前写入变更前整行，支持版本对比与回滚参照。
 */
@Data
@TableName("erp_mdm_legal_entity_version")
public class MdmLegalEntityVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String entityId;

    private Integer versionNo;

    /** 变更前整行 JSON 快照 */
    private String snapshotJson;

    /** 逐字段差异摘要，如 "regAddress: 旧值 → 新值" */
    private String diffSummary;

    /** CREATE / UPDATE / DISABLE */
    private String opType;

    private String createBy;

    private LocalDateTime createDate;
}
