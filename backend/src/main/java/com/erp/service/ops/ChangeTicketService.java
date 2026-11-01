package com.erp.service.ops;

import java.util.List;
import java.util.Map;

/**
 * 运维变更单（18.1，spec ops-sla）：申请→审批→执行→回滚→关闭五门禁。
 */
public interface ChangeTicketService {

    /** 提交变更申请（结账保障期校验 BR-5.8-14） */
    Map<String, Object> submit(Map<String, Object> body);

    /** 审批（预案三件套门禁 BR-5.8-01 + 窗口判定标记） */
    Map<String, Object> approve(String id);

    /** 启动执行（窗口剩余×1.5 门禁 BR-5.8-06） */
    Map<String, Object> start(String id);

    /** 回滚（记录回滚原因） */
    Map<String, Object> rollback(String id, String reason);

    /** 关闭（已回滚则原因必填 BR-5.8-04） */
    Map<String, Object> close(String id, String rollbackReason);

    /** 变更单台账 */
    List<Map<String, Object>> list(String status, int page, int size);
}
