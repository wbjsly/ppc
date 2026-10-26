package com.erp.service.crm;

import com.erp.entity.crm.CrmAssignRule;
import com.erp.entity.crm.CrmSalesProfile;
import com.erp.entity.crm.Lead;

import java.util.List;
import java.util.Map;

/**
 * 线索自动分配（add-lead-auto-assign，FR-4.8-1-3）：
 * 评分后按等级路由——A→资深轮询、B/C→规则命中目标集轮询、无命中回落全员「在手最少」；
 * 仅处理无跟进人的线索，改派/认领机制不变。
 */
public interface LeadAutoAssignService {

    /** 评分成功后路由一条线索（已分配/池内 SKIP；返回 assigned/ownerId/source/warning 供评分响应回显） */
    Map<String, Object> route(Lead lead);

    /** 资深销售名单（IS_SENIOR=1 的账号） */
    List<CrmSalesProfile> seniorList();

    /** 批量保存资深名单（勾选集合；不在集合内的既有标记清除） */
    void saveSeniors(List<Map<String, String>> seniors);

    /** 规则清单（按优先级升序） */
    List<CrmAssignRule> ruleList();

    /** 保存规则（新建/更新；目标集空 422） */
    CrmAssignRule saveRule(CrmAssignRule rule);

    /** 停用/删除规则（逻辑删） */
    void deleteRule(String id);
}
