package com.erp.service.intf;

import com.erp.entity.intf.IntfTierChangeLog;

import java.util.List;
import java.util.Map;

/**
 * 限流档位变更历史（spec 12.3.2：降档与调档历史留痕，design D1 单入口）。
 * 历史只增不改不删：本接口仅暴露 record（INSERT）与只读查询，无 update/delete 方法。
 */
public interface TierChangeLogService {

    /**
     * 记录一次档位变更（唯一写入口，三处写入点共用）。
     *
     * @param changeType {@link IntfTierChangeLog#TYPE_AUTO} / {@link IntfTierChangeLog#TYPE_MANUAL}
     * @param operator   自动=SYSTEM，手动=操作者用户 ID
     */
    void record(String caller, String partnerCode, String changeType,
                String tierFrom, String tierTo, Integer limitFrom, Integer limitTo,
                String triggerReason, String operator);

    /** 历史分页（caller 可空=全部），返回 rows + total */
    Map<String, Object> history(String caller, long current, long size);
}
