package com.erp.service.sd;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.sd.CreditCheck;
import com.erp.entity.sd.CreditFreeze;
import com.erp.entity.sd.PrepaymentNotice;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 客户信用管控（spec customer-credit-control，D5 建单即检、D6 特批上限）。
 */
public interface CreditControlService {

    /**
     * 实时信用检查（6.2~6.4）：四因子可用额度、账龄超比（BR-4.3-14 → NEED_APPROVAL）、
     * 付款及时率下调评级（BR-4.3-15）、连续 3 次逾期临时降额 50%（BR-4.3-16）。
     * persist=true 落检查记录。
     *
     * @return {result: PASS/FROZEN/NEED_APPROVAL, factors:{...}, gap, aging:{...}, rate, actions:[...]}
     */
    Map<String, Object> check(String customerId, BigDecimal orderAmount,
                              String soId, String soNo, boolean persist);

    /**
     * 建单即检（6.5）：SO 创建后调用；未过 → SO 置 CREDIT_FREEZE 挂起、
     * 生成冻结单（PREV_STATUS=建单时状态）与《预收款通知单》；账龄超比 → 通知信用管理员。
     * 返回检查结论。
     */
    Map<String, Object> checkOnSoCreate(String soId);

    /** 对既有 SO 重新执行信用检查并按结论标记（变更重跑 BR-4.3-30 用） */
    Map<String, Object> recheckSo(String soId);

    // ---------- 冻结看板（3.3.2） ----------

    Page<CreditFreeze> freezePage(long current, long size, String status, String keyword);

    CreditFreeze getFreeze(String id);

    /**
     * 解冻（6.6）：SO 状态回 PREV_STATUS（审批前→待审批；已确认→已确认），
     * 不重跑审批、不释放预留；生成《SO 解冻确认单》编号。
     */
    CreditFreeze unfreeze(String freezeId, String method, String remark);

    // ---------- 预收（6.7 / 3.3.3） ----------

    Page<PrepaymentNotice> noticePage(long current, long size, String status, String keyword);

    /** 通知销售与客户（留痕时间戳） */
    PrepaymentNotice notify(String noticeId);

    /** 到账登记（销售/客服可登记，累计金额） */
    PrepaymentNotice registerReceived(String noticeId, BigDecimal amount, String remark);

    /**
     * 财务确认到账（仅 FINANCE_MGR/ADMIN）：到账 ≥ 缺口 → 自动解冻 + 解冻确认单；
     * 不足 → 保持冻结并返回尚差金额。
     */
    Map<String, Object> confirmReceived(String noticeId);

    // ---------- 信用特批（6.8，BR-4.3-18） ----------

    /**
     * 信用特批：校验金额 ≤ CREDIT_SPECIAL_LIMIT 且理由必填 → 解冻对应 SO（method=SPECIAL），
     * 永久留痕（操作人、时间、金额、理由）。
     */
    CreditFreeze specialApprove(String freezeId, BigDecimal amount, String reason);

    // ---------- 信用检查记录（3.3.1） ----------

    Page<CreditCheck> checkPage(long current, long size, String customerId, String result);
}
