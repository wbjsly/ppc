package com.erp.service.bi;

import java.util.List;
import java.util.Map;

/**
 * 1.7 通用版本中心：13 类主数据实体类型无关的版本列表/对比/回滚（C-4.1-06）。
 */
public interface MdmVersionCenterService {

    /** 实体选择器：按类型查主表编码+名称（分页） */
    List<Map<String, Object>> entities(String bizType, String keyword, int page, int size);

    /** 版本列表（版本号倒序，含变更原因/分类/操作人/时间/快照摘要） */
    List<Map<String, Object>> versions(String bizType, String entityId);

    /** 任一版本完整快照 JSON */
    Map<String, Object> snapshot(String bizType, String entityId, int versionNo);

    /**
     * 两版本逐字段对比（快照 JSON 键并集三态：ADDED/REMOVED/CHANGED）。
     * 返回 {from, to, fields: [{field, oldVal, newVal, state}]}
     */
    Map<String, Object> diff(String bizType, String entityId, int from, int to);

    /**
     * 一键回滚：以源版本快照生成 V(N+1) 直接生效（写主表+新版本行+审计+事件）。
     * 返回 {newVersionNo, sourceVersionNo}
     */
    Map<String, Object> rollback(String bizType, String entityId, int sourceVersionNo, String reason);
}
