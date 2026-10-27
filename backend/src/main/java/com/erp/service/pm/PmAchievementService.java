package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/**
 * 研发成果登记 + 权属判定 + 确认状态机（add-ip-management，FR-4.8-6-1/2/3）。
 * 判定引擎读协议结构化字段（design D2 四路径）；确认走自建状态机（不占审批待办）。
 * CONFIRMED 后禁改禁删（BR-4.8-26 永久保留）。
 */
public interface PmAchievementService {

    /** 保存/提交成果（isNew 显式；WBS L1；同任务同类型 409；参与人快照；判定引擎） */
    Map<String, Object> save(Map<String, Object> payload);

    /** WBS 任务参与人提取（工时归集聚合，design D3） */
    List<Map<String, Object>> extractMembers(String wbsId);

    /** 研发负责人确认（PENDING_CONFIRM → CONFIRMED + 自动建档） */
    Map<String, Object> confirm(String id, Map<String, Object> payload);

    /** 有异议（OBJ_MULTI 多方主张→自动建仲裁单；OBJ_UNILATERAL 单方→法务复核） */
    Map<String, Object> object(String id, Map<String, Object> payload);

    /** 法务复核结论（LEGAL_REVIEW → PENDING_CONFIRM 或改判权属） */
    Map<String, Object> legalReview(String id, Map<String, Object> payload);

    /** 成果列表（分页+项目/状态过滤） */
    Map<String, Object> list(Map<String, Object> query);

    /** 单条详情（含参与人清单） */
    Map<String, Object> detail(String id);
}
