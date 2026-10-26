package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.TaxRefundApply;

import java.util.Map;

/**
 * 增值税留抵退税服务（BR-4.14-33 / S-4.14-15，spec tax-filing）。
 * 四条件逐项校验 + 允许退还公式 + L2 审批 + 退回补正挂起 + 到账核销（一致自动/差异挂账）。
 */
public interface TaxRefundService {

    /** 申请创建：BR-4.14-33 四条件逐项校验（任一不满足 422 一次性列全）+ 公式计算 */
    Map<String, Object> apply(Map<String, Object> payload);

    /** 提交 L2 审批（税务主管单节点） */
    Map<String, Object> submit(String applyId);

    /** 税务机关状态登记（受理 ACCEPTED / 退回补正 RETURNED+清单 / 核准 APPROVED+核准额） */
    Map<String, Object> registerStatus(String applyId, Map<String, Object> payload);

    /** 退税款到账：一致自动核销+收款凭证；差异>容差挂账核查（BR-4.14-45） */
    Map<String, Object> receive(String applyId, Map<String, Object> payload);

    /** 分页 */
    Page<TaxRefundApply> page(long current, long size, String status, String period);

    /** 详情（含审批轨迹） */
    Map<String, Object> detail(String applyId);
}
