package com.erp.procurement;

import com.erp.common.ServiceException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * PR 状态机单点守卫（design add-purchase-requisition D2）。
 * 全部状态迁移必须经 require/from 校验，非法迁移 422 带可操作口径；
 * 行级状态独立矩阵（OPEN→CLOSED）。
 * 附工作日工具：跳过周六日的近似工作日（无节假日日历，偏差表记口径）。
 */
public final class RequisitionStateMachine {

    /** PR 头状态全集 */
    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String PENDING_BUDGET = "PENDING_BUDGET";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String PENDING_MODIFY = "PENDING_MODIFY";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String APPROVING = "APPROVING";
    public static final String APPROVED = "APPROVED";
    public static final String PENDING_RFQ = "PENDING_RFQ";
    public static final String CLOSED = "CLOSED";

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            // MRP：待确认 → 确认 / 关闭
            PENDING_CONFIRM, Set.of(CONFIRMED, CLOSED),
            // 手工：待预算确认 → 补预算进待审批 / 关闭；待审批 → 提交审批 / 清预算回落 / 关闭
            PENDING_BUDGET, Set.of(PENDING_APPROVAL, CLOSED),
            PENDING_APPROVAL, Set.of(APPROVING, PENDING_BUDGET, CLOSED),
            // 已驳回待修改 → 重提审批 / 关闭
            PENDING_MODIFY, Set.of(APPROVING, CLOSED),
            // 已确认（MRP）→ 提交审批 / 关闭
            CONFIRMED, Set.of(APPROVING, CLOSED),
            // 审批中 → 批准 / 驳回 / 关闭
            APPROVING, Set.of(APPROVED, PENDING_MODIFY, CLOSED),
            // 已批准 → 补供应商流转 / 关闭
            APPROVED, Set.of(PENDING_RFQ, CLOSED),
            // 待询价（本变更终点态）→ 关闭（2.2 接收前的例外关闭）
            PENDING_RFQ, Set.of(CLOSED),
            CLOSED, Set.of()
    );

    private static final Set<String> LINE_ALLOWED = Set.of("CLOSED");

    private RequisitionStateMachine() {
    }

    /** 头状态迁移校验；非法 422 带当前态与可达集合 */
    public static void require(String from, String to) {
        Set<String> ok = ALLOWED.get(from);
        if (ok == null) {
            throw new ServiceException(422, "未知 PR 状态：" + from);
        }
        if (!ok.contains(to)) {
            throw new ServiceException(422, "状态不允许从 " + from + " 迁移至 " + to
                    + (ok.isEmpty() ? "（已关闭为终态）" : "，可达状态：" + ok));
        }
    }

    /** 行状态迁移校验 */
    public static void requireLine(String from, String to) {
        if (!"OPEN".equals(from)) {
            throw new ServiceException(422, "行状态不允许从 " + from + " 迁移至 " + to);
        }
        if (!LINE_ALLOWED.contains(to)) {
            throw new ServiceException(422, "行状态不可迁移至 " + to);
        }
    }

    // ---------- 工作日近似（跳周六日；无节假日日历，design D5 口径） ----------

    /** startDate 起经过 businessDays 个工作日后的日期（不含起始日） */
    public static LocalDate plusBusinessDays(LocalDate start, int businessDays) {
        LocalDate d = start;
        int left = businessDays;
        while (left > 0) {
            d = d.plusDays(1);
            DayOfWeek w = d.getDayOfWeek();
            if (w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY) {
                left--;
            }
        }
        return d;
    }

    /** start 向前回退 businessDays 个工作日后的日期（跳周六日，默认截止日用） */
    public static LocalDate minusBusinessDays(LocalDate start, int businessDays) {
        LocalDate d = start;
        int left = businessDays;
        while (left > 0) {
            d = d.minusDays(1);
            DayOfWeek w = d.getDayOfWeek();
            if (w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY) {
                left--;
            }
        }
        return d;
    }

    /** from 至 now 已过去的工作日数（不含 from 当日） */
    public static long businessDaysBetween(LocalDate from, LocalDateTime now) {
        if (from == null || !now.toLocalDate().isAfter(from)) {
            return 0;
        }
        long count = 0;
        LocalDate d = from;
        LocalDate end = now.toLocalDate();
        while (d.isBefore(end)) {
            d = d.plusDays(1);
            DayOfWeek w = d.getDayOfWeek();
            if (w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }
}
