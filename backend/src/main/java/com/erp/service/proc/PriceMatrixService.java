package com.erp.service.proc;

import java.util.List;
import java.util.Map;

/**
 * 比价矩阵工作台读服务（2.2.4，change add-price-comparison-matrix design D3/D4）。
 * 全部为 GET 读端点：品类/供应商双透视实时聚合（不建物化表）+ 比价快照列表与回看。
 * 口径：报价为 RFQ 级，按 RFQ 行物料品类归组；跨品类 RFQ 计入各品类并标注。
 */
public interface PriceMatrixService {

    /** 可比价品类选项（有 RFQ 行的物料所属品类：code + name + RFQ 数），供工作台选择器 */
    List<Map<String, Object>> categories();

    /** 品类视角：该品类下按供应商聚合（最新报价、历史序列、参与 RFQ 数、中标次数、谈判让价均值） */
    Map<String, Object> categoryView(String categoryCode);

    /** 供应商视角：该供应商在该品类各 RFQ 的报价明细 + 汇总行 */
    Map<String, Object> supplierView(String categoryCode, String supplierId);

    /** 比价记录列表（快照冗余列，按更新时间倒序） */
    List<Map<String, Object>> snapshots();

    /** 快照回看（按 RFQ 解析 JSON；未定标 404） */
    Map<String, Object> snapshot(String rfqId);
}
