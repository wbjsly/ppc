package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** 里程碑定义与状态机（add-milestone-management，FR-4.8-3-1/2/3；11.4.1/11.4.2）。 */
public interface PmMilestoneService {

    /** 列表（projectId 过滤；PM 裁剪只见自己项目） */
    List<Map<String, Object>> list(String projectId);

    /** 创建（ACTIVE 项目校验 + WBS level1 校验；状态 PLANNED） */
    Map<String, Object> create(Map<String, Object> payload);

    /** 编辑（仅 PLANNED/REWORK 可改；依赖成环校验上限 50） */
    Map<String, Object> update(String id, Map<String, Object> payload);

    /** 删除（仅 PLANNED 且无附件无评审） */
    void delete(String id);

    /** 发起评审：PLANNED/REWORK → SUBMITTING → REVIEWING（两道 L1：附件≥1 + 前序 DONE） */
    Map<String, Object> submit(String id);

    /** 评审三态（PMO）：PASS→APPROVED（自动发起审批）；CONDITIONAL→保持 REVIEWING；FAIL→REWORK+计数 */
    Map<String, Object> review(String id, Map<String, Object> payload);

    /** 交付物版本列表（附件派生：按上传时间排序，版本=序号） */
    List<Map<String, Object>> deliverables(String milestoneId);

    /** 手工催办（PM/PMO） */
    Map<String, Object> remind(String id);

    /** 进度看板：完成率 + 逐里程碑偏差工作日（PM 裁剪；PMO/ADMIN/FINANCE 全量） */
    Map<String, Object> progress(String projectId);

    /** 每日扫描三类预警（审批超时/L4 滞后/升级滞后），返回处理条数（幂等标记列） */
    Map<String, Object> dailyScan();
}
