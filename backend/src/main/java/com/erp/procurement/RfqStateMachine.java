package com.erp.procurement;

import com.erp.common.ServiceException;

import java.util.Map;
import java.util.Set;

/**
 * 询价单状态机单点守卫（design add-rfq-comparison D2）。
 * 非法迁移 422 带当前态与可达集合。
 */
public final class RfqStateMachine {

    public static final String DRAFT = "DRAFT";
    public static final String SENT = "SENT";
    public static final String QUOTING = "QUOTING";
    public static final String QUOTED_CLOSED = "QUOTED_CLOSED";
    public static final String AWARDED = "AWARDED";
    public static final String CLOSED = "CLOSED";

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            // 截止日到期即锁价（sweep 对 DRAFT/SENT/QUOTING 一体处理，未发出/无报价同样锁→不足处置）
            DRAFT, Set.of(SENT, QUOTED_CLOSED, CLOSED),
            SENT, Set.of(QUOTING, QUOTED_CLOSED, CLOSED),
            QUOTING, Set.of(QUOTED_CLOSED, CLOSED),
            // 锁价后：定标 或 作废重询（BR-4.2-13 不足处置唯一出口）
            QUOTED_CLOSED, Set.of(AWARDED, CLOSED),
            AWARDED, Set.of(),
            CLOSED, Set.of()
    );

    private RfqStateMachine() {
    }

    public static void require(String from, String to) {
        Set<String> ok = ALLOWED.get(from);
        if (ok == null) {
            throw new ServiceException(422, "未知询价单状态：" + from);
        }
        if (!ok.contains(to)) {
            throw new ServiceException(422, "状态不允许从 " + from + " 迁移至 " + to
                    + (ok.isEmpty() ? "（已到终态）" : "，可达状态：" + ok));
        }
    }
}
