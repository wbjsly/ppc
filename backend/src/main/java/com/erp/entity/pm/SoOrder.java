package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 服务工单头（add-service-order，FR-4.8-9）。 */
@Getter
@Setter
@TableName("erp_pm_service_order")
public class SoOrder extends BaseEntity {

    public static final String ST_REGISTERED = "REGISTERED";
    public static final String ST_DISPATCHED = "DISPATCHED";
    public static final String ST_PROCESSING = "PROCESSING";
    public static final String ST_PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String ST_CLOSED = "CLOSED";

    public static final String CT_PRODUCT_QUALITY = "PRODUCT_QUALITY";
    public static final String CT_DELIVERY_DELAY = "DELIVERY_DELAY";
    public static final String CT_SERVICE_ATTITUDE = "SERVICE_ATTITUDE";
    public static final String CT_FUNCTION_DEFECT = "FUNCTION_DEFECT";
    public static final String CT_OTHER = "OTHER";

    public static final String IMPACT_PRODUCTION = "PRODUCTION";
    public static final String IMPACT_CORE = "CORE";
    public static final String IMPACT_NORMAL = "NORMAL";

    public static final String RC_DESIGN = "DESIGN";
    public static final String RC_MANUFACTURE = "MANUFACTURE";
    public static final String RC_OPERATION = "OPERATION";
    public static final String RC_EXTERNAL = "EXTERNAL";

    public static final String VIA_AGENT = "AGENT";
    public static final String VIA_LINK = "LINK";
    public static final String VIA_DEFAULT = "DEFAULT";

    /** SO+yyyyMMdd-流水 */
    private String orderNo;
    /** 客户主数据 erp_mdm_customer_view.ID */
    private String customerId;
    private String customerName;
    /** 关联合同号（可选，引用 4.3） */
    private String contractNo;
    /** 关联项目（可选） */
    private String projectId;
    /** PRODUCT_QUALITY|DELIVERY_DELAY|SERVICE_ATTITUDE|FUNCTION_DEFECT|OTHER */
    private String complaintType;
    /** PRODUCTION|CORE|NORMAL（定级映射输入） */
    private String impactLevel;
    private String description;
    private LocalDateTime expectDeadline;
    /** 附件（逗号分隔） */
    private String attachIds;
    /** 所需技能（文本参考标签） */
    private String skillTag;
    /** 投诉等级 1/2/3 */
    private Integer level;
    /** 响应截止（BR-4.8-03 计时起点=登记时间） */
    private LocalDateTime respDeadline;
    /** 解决截止（驳回重计时后刷新） */
    private LocalDateTime resolveDeadline;
    /** REGISTERED→DISPATCHED→PROCESSING→PENDING_CONFIRM→CLOSED */
    private String status;
    private String engineerId;
    private String engineerName;
    /** 首次受理动作时间 */
    private LocalDateTime respondedAt;
    /** 提交客户确认时间 */
    private LocalDateTime confirmSubmitAt;
    /** 1=挂起「待客户信息」 */
    private String suspendedFlag;
    private LocalDateTime suspendAt;
    /** 累计挂起小时 */
    private BigDecimal suspendHours;
    /** 升级档 0 无|1 服务主管|2 销售经理 */
    private Integer escalateStage;
    /** 重复投诉关联前单 */
    private String duplicateOf;
    /** 关联质量域 NCR（BR-4.8-36） */
    private String ncrNo;
    /** DESIGN|MANUFACTURE|OPERATION|EXTERNAL */
    private String rootCause;
    /** 1=已根治（质量类无 NCR 不可置 1） */
    private String rootCured;
    /** 客户驳回次数 */
    private Integer rejectCount;
    /** AGENT|LINK|DEFAULT */
    private String confirmedVia;
    /** RESOLVED|DEFAULT_CLOSED */
    private String confirmResult;
    /** 1=默认关闭 */
    private String closeDefaultFlag;
    /** 服务成本-材料（AFTER_SALE 领料过账累加） */
    private BigDecimal materialCost;
    private String remark;
}
