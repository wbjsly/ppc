package com.erp.service.bi;

import java.util.List;
import java.util.Map;

/**
 * 报表模板全生命周期（add-report-template-console，spec report-template-management）：
 * 登记（口径 diff 硬阻断 C-4.10-01）→ 测试（三项报告 + 变更强制重测）→
 * 三方发布审批（二次校验 BR-4.10-11）→ 快照发布与回退（BR-4.10-07）→ 渲染预览。
 *
 * 权限：设计/测试/提交/回退 = ROLE_BI_DESIGNER（写 403 兜底 ADMIN）；
 * 渲染 = PUBLISHED 任意登录角色，非 PUBLISHED 仅 BI_DESIGNER/ADMIN。
 */
public interface ReportTemplateService {

    // ---------- 14.1.1 模板设计（登记 + 口径校验） ----------

    /** 模板列表（status/category/keyword 过滤；普通角色仅见 PUBLISHED，设计师全量） */
    List<Map<String, Object>> list(String status, String category, String keyword);

    /** 模板详情（含当前生效版本号） */
    Map<String, Object> detail(String id);

    /**
     * 新建模板：SQL 白名单校验（SELECT-only，D2）+ 逐指标 diffCheck
     * （不一致 422 附差异 C-4.10-01 / 未注册 422 BR-4.10-09）+ bindUsage 登记。
     * 状态 DRAFT。
     */
    Map<String, Object> create(Map<String, Object> payload);

    /**
     * 修改模板：同 create 校验；元数据（SQL/列/指标）任一变化 → SQL_HASH 变
     * → 既有 VALID 测试报告作废（强制重测，D5）；PUBLISHED 模板修改走草稿语义
     * （ACTIVE_VERSION_ID 不动，生效线不变）。状态回 DRAFT/TESTING 由提交流程驱动。
     */
    Map<String, Object> update(String id, Map<String, Object> payload);

    /** 删除模板（逻辑删；PUBLISHED 模板 422 拒删，须先回退下线语义——首版直接拒） */
    void delete(String id);

    // ---------- 14.1.2 模板测试（三项报告） ----------

    /**
     * 执行测试：包 LIMIT 跑 SQL 记时 + 前 10 行样本 + 行数；权限版注入行级过滤
     * 对比行数；性能阈值（STANDARD 10s / COMPLEX 30s）；报告落表。
     * 模板须 DRAFT/TESTING 态（PUBLISHED 重测走新草稿）。
     */
    Map<String, Object> runTest(String templateId);

    /** 准确性人工核对确认（样本核对后置 ACCURACY_OK，三项合取刷新总体 PASS/FAIL） */
    Map<String, Object> confirmAccuracy(String testId);

    /** 模板的测试报告列表（最新在前） */
    List<Map<String, Object>> listTests(String templateId);

    // ---------- 14.1.3 模板发布（三方审批 + 二次校验） ----------

    /**
     * 提交发布审批：前置校验（存在未作废且总体 PASS 的测试报告 422 / 指标全部
     * 已注册 422 BR-4.10-09 / 状态合法）→ 置 PENDING_APPROVAL + 审批引擎三方
     * 串行链（DATA_GOV→BIZ_OWNER→ADMIN，3/5 工作日超时升级）。
     */
    Map<String, Object> submit(String templateId);

    // ---------- 14.1.4 版本控制（快照 + 回退） ----------

    /** 版本列表（版本号/口径版本/发布人/时间/回退溯源） */
    List<Map<String, Object>> listVersions(String templateId);

    /**
     * 回退到历史版本：取该版本 DEF_JSON 生成**新版本号**快照
     * （ROLLBACK_FROM 溯源，历史保留）+ ACTIVE_VERSION_ID 切换。
     */
    Map<String, Object> rollback(String versionId);

    // ---------- 渲染预览（通用渲染页） ----------

    /**
     * 渲染：按列定义出分页表格数据；PUBLISHED 任意登录角色（行级注入 + 敏感列
     * 掩码），非 PUBLISHED 仅 BI_DESIGNER/ADMIN（403）。
     */
    Map<String, Object> render(String templateId, int page, int size);

    /**
     * 治理通道执行已发布模板 SELECT（add-export-console design D2）：
     * PUBLISHED 校验 → 行级过滤（空集 403 默认拒绝）→ LIMIT maxRows 截断
     * → 列级掩码；render 与导出共用同一治理语义。
     *
     * @return {"headers": [...], "rows": [[...]], "truncated": bool}
     */
    Map<String, Object> executeGovernedSelect(String templateId, int maxRows);

    /**
     * 同上，但指定行级权限校验主体（worker 线程无 SecurityContext，传任务属主）。
     */
    Map<String, Object> executeGovernedSelect(String templateId, int maxRows, String asUser);
}
