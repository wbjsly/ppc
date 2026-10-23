package com.erp.service.eam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.eam.EamCountPlan;
import com.erp.entity.eam.EamCountTeamMember;

import java.util.List;
import java.util.Map;

/**
 * 资产盘点（add-asset-count，spec asset-count，design D2/D3/D4）。
 * 计划 → 清单下发（基准日物理快照，BR-29）→ 抽样（分类 RISK_LEVEL 比例，BR-26）→
 * 实盘录入（扫码/手工/CSV 导入导出）→ 差异比对（EamCountDiffService）→
 * 覆盖率结案卡控（BR-26 L1）→ 报告审批归档。
 */
public interface EamCountService {

    Page<EamCountPlan> page(long current, long size, String status, String countType);

    Map<String, Object> detail(String id);

    /** 创建盘点计划（范围/基准日/方式校验） */
    Map<String, Object> create(EamCountPlan plan);

    Map<String, Object> update(EamCountPlan plan);

    /** 维护盘点人员分组（整体覆盖式维护，独立性留痕） */
    Map<String, Object> maintainTeam(String planId, List<EamCountTeamMember> members);

    List<EamCountTeamMember> team(String planId);

    /**
     * 清单下发（BR-29）：BASE_DATE 在册卡片批量插入快照列（分批 500）；
     * SAMPLE 型先按分类 RISK_LEVEL 抽样（BR-26：类内随机 + 类内至少 1 张 + 全类别覆盖）。
     * 下发后状态置 ISSUED，快照冻结。
     */
    Map<String, Object> issue(String planId);

    /** 抽样预览（不落库）：按当前参数给出各分类抽样比例与预计抽样数 */
    Map<String, Object> samplePreview();

    /** 盘点明细分页（快照 + 实盘 + 差异筛选） */
    Map<String, Object> linePage(String planId, long current, long size,
                                 String actualStatus, String diffType, String cardNo);

    /**
     * 单条实盘录入：扫码卡号命中快照行 / 无标签手工记录（PENDING_CONFIRM）/
     * SURPLUS 盘盈新行 / MISSING 盘亏；可选刷新 LAST_USED_AT（S-4.13-13）。
     */
    Map<String, Object> countEntry(String planId, String cardNo, String actualStatus,
                                   String actualNote, boolean refreshLastUsed);

    /** 实盘录入（categoryCode：盘盈时指定分类，BR-27 自动带价的前提；可空） */
    Map<String, Object> countEntry(String planId, String cardNo, String actualStatus,
                                   String actualNote, boolean refreshLastUsed, String categoryCode);

    /**
     * CSV 批量导入实盘（UTF-8 BOM 容错；行级错误清单不整批回滚）。
     * 行格式：cardNo,actualStatus,actualNote,refreshLastUsed
     */
    Map<String, Object> batchImport(String planId, String csvContent);

    /** 导出盘点清单 CSV（卡号/名称/部门/成本中心/地点/分类/原值/NBV/快照状态，实盘列留空） */
    String exportCsv(String planId);

    /** 覆盖率刷新（回写 plan.COUNTED_QTY/COVERAGE_RATE） */
    Map<String, Object> refreshCoverage(String planId);

    /** 基准日后变动单独列示（BR-29）：BASE_DATE 之后新增/完成的卡片与单据 */
    Map<String, Object> postBaseChanges(String planId);

    /**
     * 结案（BR-26 L1）：FULL <100% 422 列未盘清单；SAMPLE 校验比例与全类别；
     * 前置=全部差异 CLOSED；报告（覆盖率/差异率/原因分析）生成 → 财务资产会计单签归档 → CLOSED。
     */
    Map<String, Object> submitReport(String planId);

    /** 报告审批通过回调入口：置 CLOSED */
    Map<String, Object> onReportApproved(String planId);

    /** 报告审批驳回回调入口 */
    Map<String, Object> onReportRejected(String planId, String reason);
}
