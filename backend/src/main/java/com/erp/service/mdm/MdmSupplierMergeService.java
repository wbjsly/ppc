package com.erp.service.mdm;

import com.erp.entity.mdm.MdmSupplierMergeLog;

import java.util.List;
import java.util.Map;

/**
 * 供应商合并去重业务能力契约（供方管理 1.4.2，流程五 FR-4.1-5-x）。
 */
public interface MdmSupplierMergeService {

    /** 疑似重复候选：税号精确 + 名称编辑距离 ≤3，排除自身与已合并（C-4.1-07 复用） */
    List<Map<String, Object>> candidates(String keyword, String excludeId);

    /** 差异对比并排（付款条件/税号/开户行/证照最早有效期）+ 方向建议 */
    Map<String, Object> compare(String sourceId, String targetId);

    /** 影响面：证照真实计数 + 四类单据迁移桩清单（采购/质量/财务域未接入明示） */
    Map<String, Object> impact(String sourceId);

    /**
     * 合并执行（单事务五步，design D3）：校验组 → 证照改挂 → 源 MERGED+MERGED_TO+PRE_STATUS
     * → 日志 INSERT → 双方 MERGE 快照 + MERGED 事件。
     */
    MdmSupplierMergeLog merge(String sourceId, String targetId, String reason);

    /**
     * 30 天回退（闭区间）：恢复 PRE_STATUS + 证照回迁 + 日志回填 + REVERT 快照 + 事件。
     * 超期/已回退/缺原因 → 422。
     */
    MdmSupplierMergeLog revert(String logId, String reason);

    /** 合并日志分页（keyword 匹配 LOG_NO/源/目标编码，联表补名称） */
    Map<String, Object> logPage(long current, long size, String keyword);
}
