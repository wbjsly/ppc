package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.InvoiceVoidApply;

import java.util.Map;

/**
 * 发票作废服务（M9-4-2，spec red-invoice-void）。
 * 三条件（BR-4.14-24：当月开具 + 未交付 + 购方未确认）申请时逐项校验，
 * 任一不满足 422 阻断并引导红冲；审批通过后执行作废置 VOID（终态不可恢复）。
 */
public interface RedVoidService {

    /** 作废申请：三条件校验（一次性返回全部未满足项）+ 建单 + 提交审批 */
    Map<String, Object> apply(Map<String, Object> payload);

    /** 申请分页 */
    Page<InvoiceVoidApply> page(long current, long size, String status, String keyword);

    /** 详情（含审批轨迹） */
    Map<String, Object> detail(String applyId);
}
