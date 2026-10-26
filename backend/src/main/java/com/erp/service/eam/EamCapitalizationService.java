package com.erp.service.eam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.eam.EamCapitalization;

import java.util.List;
import java.util.Map;

/**
 * 资产转资（add-asset-capitalization，spec asset-capitalization FR-4.13-1-1~7）。
 * 双模式：PURCHASE 采购转资 / CIP 在建转资；
 * 申请 → 录卡校验 → 提交审核（单签）→ 一物一卡建卡 + 转资凭证 → COMPLETED。
 */
public interface EamCapitalizationService {

    /** 分页（模式/状态/关键字） */
    Page<EamCapitalization> page(long current, long size, String mode, String status, String keyword);

    /**
     * 创建转资申请（草稿）：
     * 来源校验（采购=入库过账+质检合格；在建=COMPLETED 且有归集）+ 重复转资阻断 +
     * BR-4.13-08 部分转资强制 + BR-4.13-09 资本化下限 + 年限范围预警（warnings）。
     *
     * @return { cap: 单据, warnings: [L4 预警文案] }
     */
    Map<String, Object> create(EamCapitalization cap);

    /** 草稿编辑（仅 DRAFT） */
    EamCapitalization update(EamCapitalization cap);

    /** 草稿详情（含来源信息带出与校验结果） */
    Map<String, Object> detail(String id);

    /**
     * 提交审核：BR-4.13-07 原值一致性（发票不含税额/PO 金额，超容差 422 回差异明细）+
     * 科目前置预检 → 审批底座单签（AssetCapital）。
     */
    EamCapitalization submit(String id);

    /**
     * 折旧预览（纯计算不落表）：残值、月折旧额（直线法 (原值−残值)/年限/12）。
     * FR-4.13-1-4 审核参考。
     */
    Map<String, Object> depreciationPreview(String id);

    /**
     * 审核通过后的建卡出凭证（由审批回调驱动；暴露供测试直调）：
     * 一物一卡拆分（上限 500）+ 转资凭证（借 分类科目 / 贷 CIP 或应付暂估）+ 置 COMPLETED。
     */
    Map<String, Object> completeFromApproval(String capId);

    /** 驳回回草稿（审批回调驱动） */
    EamCapitalization rejectToDraft(String capId, String reason);

    /**
     * 待转资清单（超期预警数据源）：入库过账质检合格未转资 PO + CIP COMPLETED 未转资项目，
     * 按验收合格日算超期天数分级（>grace 预警 / >60 升级）。
     */
    List<Map<String, Object>> pendingList();

    /**
     * 卡片更新（BR-4.13-10 锁定拦截）：LOCK_FLAG=1 后折旧政策五字段（原值/年限/残值率/
     * 折旧方法/启用日）不可改（422 提示走资产变更流程）；非锁定字段（存放地点/部门等）放行。
     */
    com.erp.entity.eam.EamAssetCard updateCard(com.erp.entity.eam.EamAssetCard card);
}
