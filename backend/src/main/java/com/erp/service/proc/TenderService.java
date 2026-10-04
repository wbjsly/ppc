package com.erp.service.proc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Map;

/**
 * 招标竞价能力契约（2.2.2，FR-4.2-10-1/2/3 + BR-4.2-44~48 + C-4.2-10）。
 * 查询前置执行懒 sweep（截止锁价，偏差 D5：截止即锁）；状态迁移经 TenderSupport.transition 单点。
 *
 * <p><b>本模块为内部端，不含供应商侧能力</b>（proposal 偏差表）：</p>
 * <ul>
 *   <li>偏差 D1 —— 不提供竞价看板对手报价脱敏（无外部看板）；</li>
 *   <li>偏差 D3 —— 不下发《中标通知书》、不向落标方开放得分与排名查询；</li>
 *   <li>偏差 D2 —— 报价由采购员在后台代录（见 TenderQuote.operator）；</li>
 *   <li>偏差 D4 —— 公示期异议由采购员在后台代录（见 TenderObjection）；</li>
 * </ul>
 * 另有 D5（截止即锁，无 30 分钟空窗）、D6（围标串标人工标记，不实现自动识别算法）、
 * D7（BR-4.2-44 口径以本年 PR 预估额近似采购额）。
 */
public interface TenderService {

    /** 分页（先 sweep）：状态/关键字筛选，行附投标方/报价轮次/进度统计 */
    Page<Map<String, Object>> page(long current, long size, String status, String keyword);

    /** 详情（先 sweep）：头 + 行 + 投标方（含资格状态）+ 报价轮次 + 评委 + 评分 */
    Map<String, Object> detail(String id);

    /** 立项（FR-4.2-10-1）：生成唯一编号、置 DRAFT，同时落行与投标方 */
    Map<String, Object> create(Map<String, Object> payload);

    /**
     * 更新立项要素（仅 DRAFT 可改）。
     * 招标编号创建后不可修改：payload 携带不同的 TENDER_NO 时 422（spec「编码不可改」）。
     */
    Map<String, Object> update(String id, Map<String, Object> payload);

    /** 开始报名（DRAFT → BIDDING） */
    Map<String, Object> start(String id);

    /** 资格审查：PASS / FAIL，重算合格投标方数（BR-4.2-45） */
    Map<String, Object> qualify(String id, String supplierId, String status, String note);

    /** 追加投标方（未锁价，来源 APPLY/INVITE） */
    Map<String, Object> addSuppliers(String id, java.util.List<String> supplierIds);

    /**
     * 多轮报价录入（BR-4.2-06）：
     * 轮次自增或指定；历史轮次只读；本轮价 MUST NOT 高于该投标方上一轮价。
     */
    Map<String, Object> saveQuote(String id, Map<String, Object> payload);

    /** 延长报价截止（仅锁价前，新截止须更晚 + 原因留痕） */
    Map<String, Object> postpone(String id, String newDeadline, String reason);

    /**
     * 开标（LOCKED → EVALUATING）。BR-4.2-45：合格投标方 < MIN_QUOTE_COUNT 时阻断，
     * 并提示「延长报名期」与「转邀请招标」两个入口；转邀请待审批同样阻断。
     */
    Map<String, Object> open(String id);

    /** 延长报名期（BR-4.2-45 入口一：新截止更大 + 原因留痕） */
    Map<String, Object> postponeReg(String id, String newDeadline, String reason);

    /** 转邀请招标（BR-4.2-45 入口二：升级采购总监审批，置 INVITE_PENDING） */
    Map<String, Object> toInvite(String id, String reason);

    /**
     * 转邀请招标审批结果（采购总监）：通过 → INVITE_PENDING=2；驳回 → 回退为公开招标。
     * 待审批期间开标被阻断，须先有明确结论，避免流程死锁。
     */
    Map<String, Object> inviteApproval(String id, boolean approved, String reason);

    /** 作废（锁价前各阶段可作废；已生成框架协议 422 拒绝） */
    void cancel(String id, String reason);

    /** 更新报价（历史轮次只读，一律 422，用于满足「不得覆盖」的可测行为） */
    Map<String, Object> updateQuote(String quoteId, Map<String, Object> payload);

    /** 指定评标委员（替换式；锁价后至评标开始前可调，ROLE_BID_JUDGE 名单来源，design D5） */
    Map<String, Object> setJudges(String id, java.util.List<String> judgeUserIds);

    /**
     * 评委提交/保存评分（FR-4.2-10-2）：4 维按招标权重快照加权；
     * 仅本人在评委名单内可提交；提交后锁定（BR-4.2-47）。
     */
    Map<String, Object> saveScore(String id, Map<String, Object> payload);

    /** 合规审批后修改评分（BR-4.2-47：阻断直接修改，改经本入口并留痕新旧值） */
    Map<String, Object> amendScore(String id, String scoreId, Map<String, Object> payload);

    /**
     * 评标汇总与定标（LOCKED→EVALUATING→PENDING_AWARD）：
     * 最低价法取最低有效报价；综合评分法取各评委加权分的算术平均，最高者中标。
     * 异常冻结（偏差 D6）期间阻断。
     */
    Map<String, Object> evaluate(String id);

    /** 围标/串标异常标记（偏差 D6：人工标记，冻结/解除评标） */
    Map<String, Object> setAnomaly(String id, boolean anomaly, String note);

    /**
     * 评委个人评标任务（4.2）：仅返回当前用户自己的评分任务，
     * 不含其他评委的任何数据；非本招标评委 403。
     */
    Map<String, Object> myTasks(String id);

    /** 定标审批待办（design D6 单节点）：status=PENDING_AWARD 的招标，含路由链 [采购总监] */
    java.util.List<Map<String, Object>> approvalTodo();

    /**
     * 定标审批（FR-4.2-10-3，采购总监单节点）：
     * 通过 → `AWAITING_PUBLICITY` 并按 `PUBLICITY_DAYS` 计算公示起止；
     * 驳回 → 回 `EVALUATING` 重新评标。
     */
    Map<String, Object> approveAward(String id, boolean approved, String note);

    /** 公示期异议登记（BR-4.2-48；valid=true 暂停协议生成并冻结定标） */
    Map<String, Object> raiseObjection(String id, String content, boolean valid);

    /** 异议复核裁定：MAINTAIN 恢复公示流程 / REBID 作废原定标（BR-4.2-48） */
    Map<String, Object> reviewObjection(String id, String objectionId, String verdict, String note);

    /** 框架协议查询（2.2.3）：按协议号/招标号关键字 + 状态 */
    java.util.List<Map<String, Object>> agreements(String keyword, String status);

    /** 协议详情（头 + 明细行） */
    Map<String, Object> agreementDetail(String id);

    /** 直接修改协议明细单价/份额 —— 一律 422（FR-4.2-10-3 价格份额锁定） */
    Map<String, Object> updateAgreementLine(String lineId, Map<String, Object> payload);

    /** 协议变更（经审批）：允许修改并留痕变更前后值、操作人与时间 */
    Map<String, Object> changeAgreementLine(String agreementId, String lineId,
                                            Map<String, Object> payload);
}
