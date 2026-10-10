package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * BOM 版本头（change add-bom-management，spec bom-management / 00-erp-spec 4.5-1；迁移 117）。
 * 一行 = 一个版本：DRAFT → PENDING → PUBLISHED → REVISED → OBSOLETE，退回回 DRAFT。
 * 版本号自动分配（FR-4.5-1-5，主.次不可手填）；同父项唯一已发布（BR-4.5-08）由服务层父项锁保证；
 * CHANGE_FROM_ID 自引用构成变更链（BR-4.5-09：变更只新增版本，不原地修改已发布数据）。
 */
@Getter
@Setter
@TableName("erp_mrp_bom")
public class MrpBom extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING";
    public static final String ST_PUBLISHED = "PUBLISHED";
    public static final String ST_REVISED = "REVISED";
    public static final String ST_OBSOLETE = "OBSOLETE";

    private String parentItemCode;
    private String parentItemName;
    /** 主版本号（自动分配，不可手填） */
    private Integer versionMajor;
    /** 次版本号（变更默认 +1） */
    private Integer versionMinor;
    private String status;
    /** 变更原因（发起变更必填） */
    private String changeReason;
    /** 变更来源版本 ID（自引用） */
    private String changeFromId;
    /** 复制来源版本 ID */
    private String copyFromId;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    /** 发布人（FR-4.5-1-7 降级留痕） */
    private String publishBy;
    /** 发布留痕时间 */
    private LocalDateTime publishAt;
    /** 最近驳回意见（审批留痕） */
    private String rejectReason;

    /** 展示版本号（主.次）——非库字段，供 API 直接输出 */
    public String getVersionLabel() {
        return (versionMajor == null ? 0 : versionMajor) + "." + (versionMinor == null ? 0 : versionMinor);
    }
}
