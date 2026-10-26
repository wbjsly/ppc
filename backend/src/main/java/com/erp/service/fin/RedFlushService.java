package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.RedFlushApply;
import com.erp.entity.fin.RedInvoice;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 红字冲销服务（M9-4-1，spec red-invoice-void，design D1/D2/D3）。
 * 六步 SOP：申请 → 分级审批（ApprovalEngine）→ 红字信息单（填开方判定 BR-4.14-25）
 * → 信息表编号门禁 → 开红票（台账/AR/凭证联动）→ 终态。
 */
public interface RedFlushService {

    /** 红冲申请创建（DRAFT）：蓝票校验（C-4.14-12/BR-4.14-28）+ 金额校验（C-4.14-02）+ 跨期判定 */
    Map<String, Object> create(Map<String, Object> payload);

    /** 提交审批：金额分级（RED_FLUSH_APPROVAL_THRESHOLD）+ 频繁红冲升级（窗口/次数参数化） */
    Map<String, Object> submit(String applyId);

    /** 申请单分页（状态/客户筛选） */
    Page<RedFlushApply> page(long current, long size, String status, String customerId, String keyword);

    /** 详情：申请 + 审批轨迹 + 信息单 + 红票 + 凭证 */
    Map<String, Object> detail(String applyId);

    /** 信息表编号登记（INFO_ISSUED 态；开红票门禁） */
    RedFlushApply recordInfoSheetNo(String applyId, String infoSheetNo);

    /** 开红票（INFO_ISSUED + 编号非空门禁 → 台账/AR/凭证联动 → ISSUED） */
    RedInvoice issue(String applyId);

    /**
     * 红冲后重开（BR-4.14-29）：将原蓝票对应的开票申请从红字终态复活为 DRAFT，
     * 强制关联红冲单号（不可清空）+ 重开原因必填；提交时按金额/购方一致性二次校验。
     *
     * @param applyId   红冲申请单 ID（须已 ISSUED）
     * @param reopenNote 重开原因（必填）
     * @return 复活后的开票申请（含 redFlushApplyNo / reopenNote）
     */
    Map<String, Object> reopen(String applyId, String reopenNote);

    /**
     * 旧接口兼容重定向（design D3，deprecated）：校验同 create 后创建申请单并直接提交审批，
     * 返回 RedInvoice 视图（redNo=待开具占位，真红票在审批+编号+开票后落台账）。
     */
    RedInvoice redirectLegacy(String originInvoiceId, String returnId, String returnNo,
                              BigDecimal amount, String reason);
}
