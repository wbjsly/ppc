package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 工艺路线版本头（change add-routing-management，spec routing-management / 00-erp-spec 4.5 消费方反推；迁移 118）。
 * 一行 = 一个版本（design D2 生命周期复刻 BOM）：DRAFT → PENDING → PUBLISHED → REVISED → OBSOLETE。
 * 版本号自动分配（主.次不可手填）；同产品唯一在途/唯一已发布由服务层产品行锁保证；
 * CHANGE_FROM_ID 自引用构成变更链（变更只新增版本，源头不动）。
 */
@Getter
@Setter
@TableName("erp_mrp_routing")
public class MrpRouting extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING";
    public static final String ST_PUBLISHED = "PUBLISHED";
    public static final String ST_REVISED = "REVISED";
    public static final String ST_OBSOLETE = "OBSOLETE";
    /** 试产已审批（FR-4.5-8-2 特别审批入态；转量产经复制为量产草稿走正式发布——迁移 125） */
    public static final String ST_TRIAL = "TRIAL";

    /** 父项（产品）物料编码 */
    /** 路线类型：正常（迁移 124；唯一在途/唯一已发布按类型分池） */
    public static final String T_NORMAL = "NORMAL";
    /** 路线类型：返工（FR-4.5-7-3 专用返工路线，原路线子集沉淀为 REWORK 型） */
    public static final String T_REWORK = "REWORK";

    private String itemCode;
    /** 路线类型 NORMAL/REWORK（迁移 124；缺省 NORMAL） */
    private String routeType;
    /** 产品名称（冗余展示） */
    private String itemName;
    /** 主版本号（自动分配，不可手填） */
    private Integer versionMajor;
    /** 次版本号（变更默认 +1，可勾主升级归零） */
    private Integer versionMinor;
    private String status;
    /** 试产阶段 EVT/DVT/PVT（非空=试产版，试产池唯一性分池，迁移 125） */
    private String trialStage;
    /** 转量产来源试产工单号 TP-（发布时校验其总结评审 APPROVED，FR-4.5-8-7，迁移 125） */
    private String srcTrialNo;
    /** 变更原因（发起变更必填） */
    private String changeReason;
    /** 变更来源版本 ID（自引用） */
    private String changeFromId;
    /** 发布人（留痕） */
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
