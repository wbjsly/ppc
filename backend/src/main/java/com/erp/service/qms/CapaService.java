package com.erp.service.qms;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.qms.Capa;
import com.erp.entity.qms.CapaAction;
import com.erp.entity.qms.Ncr;

import java.util.List;
import java.util.Map;

/**
 * CAPA/8D（spec capa-management，tasks 9.1~9.5，6.6）。
 *
 * 触发（BR-4.12-31）：重大事件（Critical NCR）/ 3 月内 3 次重复不合格 / 审核问题
 *   → 强制生成 CAPA 绑定 NCR；复发（关闭 ≤1 年同料同供方，BR-4.12-36）自动新建关联原单并上调严重度。
 * 8D：D1~D8 顺序推进不可跳序；D2 5W2H 必填；D4 根本原因 + 验证证据缺一 422、浅层根因退回
 *   （BR-4.12-33）；D5/D6 措施全完成才进 D7、标准/SOP 变更走审批（BR-4.12-34）；
 *   D7 无效/部分有效 → 回 D4 重新分析 + 阻断 NCR 关闭（BR-4.12-35）→ D8 关闭沉淀。
 * Critical 24h 遏制倒计时超时升级质量总监（BR-4.12-32）。
 */
public interface CapaService {

    /**
     * NCR 生成时触发判定（tasks 9.1，BR-4.12-31）：命中则同事务立项（CAPA + 8 步骤 + 回绑 NCR），
     * 未命中返回 null。幂等：同 NCR 已有 CAPA 直接返回。
     */
    Capa maybeCreateFromNcr(Ncr ncr);

    /** 手工立项（QUALITY_ENG）：可关联 NCR 或独立（投诉/审核来源） */
    Capa createManual(Map<String, Object> body);

    /**
     * 8D 步骤推进（tasks 9.2~9.5）：body = {stepCode, ownerName, output, rootCause?,
     * rootCauseEvidence?, verifyResult?}。
     * 跳序 422；D2 缺 5W2H 422；D4 缺根因/证据 422 或浅层根因退回；
     * D6→D7 校验措施全 DONE；D7 按 verifyResult 分流（INVALID/PARTIAL → 回 D4 重新分析）。
     */
    Capa advanceStep(String capaId, Map<String, Object> body);

    /** 新增纠正/预防措施（D5/D6）；标准/SOP 变更自动挂变更审批（BR-4.12-34） */
    CapaAction addAction(String capaId, Map<String, Object> body);

    /** 措施完成（标准变更类须变更审批已通过，否则 422） */
    CapaAction completeAction(String actionId, String effectDesc);

    /** Critical 遏制倒计时扫描（BR-4.12-32：24h 未确认 → 升级质量总监，幂等） */
    int sweepContainment();

    Page<Map<String, Object>> page(long current, long size, String keyword, String status);

    /** 详情：CAPA + 8 步骤 + 措施 + 关联 NCR */
    Map<String, Object> detail(String id);

    List<CapaAction> actions(String capaId);
}
