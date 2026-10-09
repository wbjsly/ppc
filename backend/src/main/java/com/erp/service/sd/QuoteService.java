package com.erp.service.sd;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.sd.SdQuote;
import com.erp.entity.sd.QuoteLine;
import com.erp.entity.sd.QuoteVersion;

import java.util.List;
import java.util.Map;

/**
 * 销售报价服务（spec sales-quote，FR-4.3-1-1~1-8 + BR-4.3-07~12）。
 */
public interface QuoteService {

    Page<SdQuote> page(long current, long size, String keyword, String status, String oppId);

    /**
     * 预检试算（不落库）：跑全部卡控与取价/毛利，返回 errors（L1 拒绝）/
     * warnings（L4 提示）/ 试算明细与《价格协议匹配记录》。create/update 内部复用同一评估。
     */
    Map<String, Object> precheck(SdQuote quote, List<QuoteLine> lines);

    /** 报价头（含毛利/有效期/审批在途状态） */
    SdQuote get(String id);

    List<QuoteLine> lines(String quoteId);

    List<QuoteVersion> versions(String quoteId);

    /**
     * 创建报价草稿（4.2~4.6 一体）：
     * 前置卡控（商机阶段 L1 / 客户冻结与证照过期 L1 / 地址不全 L4）、
     * 明细校验（SKU 启用、MOQ 拒存、样品单豁免、交期 L4）、
     * 三类协议取价（无协议 L1，返回《价格协议匹配记录》）、毛利测算。
     * 低毛利/负毛利只记录状态，处置在 submit。
     */
    SdQuote create(SdQuote quote, List<QuoteLine> lines);

    /** 草稿/驳回态修改（重取价、重算毛利；已发布不可改，须 revise） */
    SdQuote update(String id, SdQuote quote, List<QuoteLine> lines);

    /** 低毛利二次确认：0 ≤ 毛利率 < MIN_MARGIN_RATE 时提交前置条件（留痕确认人与时间） */
    SdQuote marginConfirm(String id);

    /**
     * 提交审批（4.7）：金额分档（自动 / 销售经理 / +销售总监）；
     * 小额高毛利自动审批直接发布；负毛利强制销售主管+财务会签（锁定至释放）。
     */
    SdQuote submit(String id);

    /** 开新版本（4.8）：旧版本置 SUPERSEDED 保留可查、不可转 SO；新草稿复制头行 */
    SdQuote revise(String id);

    /**
     * 转化 SO（4.9）：有效期校验、客户信用状态重检（冻结阻断）、未占用校验；
     * 生成 SO 草稿并回写报价，成功后商机自动推进「合同签订」（D12）。
     */
    SdQuote convert(String id);

    /** 转化预检（3.2.3 列表与按钮态）：返回可转化与不可转化原因 */
    Map<String, Object> convertCheck(String id);
}
