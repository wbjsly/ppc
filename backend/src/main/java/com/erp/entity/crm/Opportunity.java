package com.erp.entity.crm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商机主档（4.8 流程一 FR-4.8-1-5~1-7，spec opportunity-management）。
 * 两入口创建：线索转化（等级 ≥ B）与手工登记；编号 OPP+日期+流水创建后不可改。
 * 5 阶段推进全审批（审批通过前停在原阶段）；丢失归档只读并进入漏斗统计分母。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_crm_opportunity")
public class Opportunity extends BaseEntity {

    // ---------- 5 阶段（顺序即推进方向，仅允许推进到下一阶段） ----------
    public static final String ST_REQUIREMENT = "REQUIREMENT";   // 需求确认
    public static final String ST_DEMO = "DEMO";                 // 方案演示
    public static final String ST_NEGOTIATION = "NEGOTIATION";   // 商务谈判
    public static final String ST_QUOTE = "QUOTE";               // 报价
    public static final String ST_CONTRACT = "CONTRACT";         // 合同签订

    /** 阶段顺序表（下标即阶段深度） */
    public static final String[] STAGES = {ST_REQUIREMENT, ST_DEMO, ST_NEGOTIATION, ST_QUOTE, ST_CONTRACT};

    // ---------- 状态 ----------
    public static final String ST_OPEN = "OPEN";
    public static final String ST_WON = "WON";
    public static final String ST_LOST = "LOST";
    public static final String ST_CLOSED = "CLOSED";

    /** 丢失原因分类（FR-4.8-1-6 必填） */
    public static final String[] LOSS_CATEGORIES = {"PRICE", "FUNCTION", "COMPETITOR", "DEMAND_CHANGE", "OTHER"};

    private String oppNo;
    private String oppName;

    private String customerId;
    private String customerCode;
    private String customerName;

    /** 来源线索（线索转化时回填） */
    private String leadId;

    private BigDecimal expectAmount;
    private LocalDate expectCloseDate;
    /** 竞争分析 */
    private String competition;
    /** 客户需求摘要 */
    private String demandSummary;
    /** 预期项目预算（线索转化带出） */
    private String budgetRef;

    private String stage;
    private LocalDateTime stageEnteredAt;
    /** 1 = 同阶段停留 >30 天（调度标记 L4） */
    private String stageOverdue;
    /** 阶段概率 %（阶段转换必填） */
    private Integer stageProbability;
    private String nextAction;
    private LocalDate nextActionDate;

    private String status;
    private String lossCategory;
    private String lossRemark;
    private LocalDateTime lossAt;
    /** 关闭/赢单时间（平均销售周期统计基准） */
    private LocalDateTime closeAt;

    /** 3.1.2 转化闸口回写的不可引用原因（BR-4.3-07） */
    private String quoteBlockReason;

    private String ownerId;
    private String ownerName;

    /** 在途阶段审批实例 ID */
    private String approvalId;

    private String remark;

    // ---------- 非持久化辅助字段 ----------

    /** 进入当前阶段的天数（列表实时计算，L4 超期判定） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private transient Integer stageDays;

    /** 实时计算的超期标记（与 STAGE_OVERDUE 列口径一致，但不受调度周期影响） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private transient String stageOverdueLive;

    /** 是否存在在途阶段审批（前端禁用推进/丢失按钮） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private transient Boolean stagePending;

    /** 阶段显示名（列表/详情展示） */
    public String stageName() {
        switch (stage == null ? "" : stage) {
            case ST_REQUIREMENT: return "需求确认";
            case ST_DEMO: return "方案演示";
            case ST_NEGOTIATION: return "商务谈判";
            case ST_QUOTE: return "报价";
            case ST_CONTRACT: return "合同签订";
            default: return stage;
        }
    }

    public static int stageIndex(String stage) {
        for (int i = 0; i < STAGES.length; i++) {
            if (STAGES[i].equals(stage)) {
                return i;
            }
        }
        return -1;
    }

    public static String nextStage(String stage) {
        int idx = stageIndex(stage);
        return idx >= 0 && idx < STAGES.length - 1 ? STAGES[idx + 1] : null;
    }
}
