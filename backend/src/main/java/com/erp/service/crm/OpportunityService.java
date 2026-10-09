package com.erp.service.crm;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.crm.OppFollowup;
import com.erp.entity.crm.OppStageLog;
import com.erp.entity.crm.Opportunity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 商机域服务（spec opportunity-management，FR-4.8-1-5~1-7 + BR-4.3-07/BR-4.8-09）。
 */
public interface OpportunityService {

    Page<Opportunity> page(long current, long size, String keyword, String status,
                           String stage, String ownerId);

    Opportunity get(String id);

    /**
     * 创建：手工登记 或 线索转化（带 leadId 时校验线索等级 ≥ B、回写线索已转化）。
     * 客户主数据缺失 422 引导先建档；预期金额 >100 万自动通知销售经理。
     */
    Opportunity create(Opportunity opp);

    /** 变更（仅 OPEN 可改；商机编号不可变；金额跨百万线时补通知） */
    Opportunity update(Opportunity opp);

    /**
     * 阶段推进（FR-4.8-1-6）：生成 PENDING 日志 + 提交销售经理审批；
     * 审批通过前商机停在原阶段（阶段跃迁由 OppStageCallback 完成）。
     */
    OppStageLog advanceStage(String oppId, String toStage, Integer probability,
                             String nextAction, LocalDate nextActionDate);

    /** 丢失归档：原因分类 + 说明必填，归档后只读并进入漏斗统计 */
    Opportunity markLost(String oppId, String category, String remark);

    List<OppStageLog> stageLogs(String oppId);

    List<OppFollowup> followups(String oppId);

    /** 追加跟进（仅追加：不提供改/删） */
    OppFollowup addFollowup(OppFollowup f);

    /**
     * 3.1.2 转化闸口（BR-4.3-07）：校验商机状态与客户状态；
     * 不通过 → 原因回写商机追踪记录并返回 ok=false；通过 → 返回报价预填数据。
     */
    Map<String, Object> checkQuoteGate(String oppId);

    /** 漏斗 / 转化率 / 平均销售周期 / 赢率 / 丢失原因分布（BR-4.8-09，全自动取数） */
    Map<String, Object> stats();

    /** 调度入口：同阶段停留 >30 天标记（L4），返回处理条数 */
    int sweepOverdue();
}
