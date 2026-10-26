package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.CashDoc;

import java.util.Map;

/**
 * 统一收付款中心（M8-4-1/8.4-2，spec payment-collection）：
 * 方向参数化（IN/OUT）一个内核：校验 → 余额双向维护 → 过账资金凭证 → 统一 FIFO 核销 → 跨期审批。
 */
public interface CashDocService {

    /** 创建收付款单（PENDING_POST）：IN=收款 / OUT=付款（可带申请、发票选择、SCAR 抵扣） */
    CashDoc create(Map<String, Object> payload);

    /** 过账（PENDING_POST → POSTED）：余额 + 资金凭证 + FIFO 核销（跨期挂审批）同事务 */
    Map<String, Object> post(String id);

    /** 作废（仅 PENDING_POST/DRAFT；POSTED 须红冲） */
    CashDoc voidDoc(String id, String reason);

    /** 分页（方向/状态/往来对象/关键字过滤） */
    Page<Map<String, Object>> page(long current, long size, String direction, String status,
                                   String partyId, String keyword, String dateFrom, String dateTo);

    /** 详情（含核销明细与跨期审批轨迹） */
    Map<String, Object> detail(String id);

    // ---- 供 PaymentServiceImpl 薄壳委托（保持既有 API 形态）----

    /** OUT 执行（含 SCAR 抵扣/申请累计/超审批阻断，payment-execution 口径），返回与旧 execute 同构 */
    Map<String, Object> executeOut(Map<String, Object> payload);
}
