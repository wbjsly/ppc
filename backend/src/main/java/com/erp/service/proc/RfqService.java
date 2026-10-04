package com.erp.service.proc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Map;

/**
 * 询价比价能力契约（2.2.1，FR-4.2-2-1/2 + C-4.2-01 + BR-4.2-12/13/14）。
 * 查询前置执行懒 sweep（截止锁价 + 不足标记）；状态迁移经 RfqSupport.transition 单点。
 */
public interface RfqService {

    /** 分页（先 sweep）：状态/关键字筛选，行附 报价进度 x/N 与紧急/不足标记 */
    Page<Map<String, Object>> page(long current, long size, String status, String keyword);

    /** 详情：头 + 行快照 + 供应商（含名称）+ 报价（含名称/谈判/异常状态） */
    Map<String, Object> detail(String id);

    /** PR 候选（PENDING_RFQ）：含已有未关闭 RFQ 标记与紧急放行状态 */
    Map<String, Object> prCandidates();

    /**
     * 创建：PR=PENDING_RFQ、同 PR 防重、供应商全 QUALIFIED、
     * C-4.2-01 数量卡控（clearance 有效 → 最低 1 家 + EMERGENCY_FLAG；否则 MIN）；
     * 截止日默认 = 最早需求日 − 5 个工作日（可覆盖）；行快照复制 PR 行。
     */
    Map<String, Object> create(Map<String, Object> payload);

    /** 发出（OFFLINE 确认 / ONLINE 状态桩）：DRAFT→SENT */
    Map<String, Object> send(String id, String sendMode);

    /** 报价录入/覆盖（SENT/QUOTING；首录 SENT→QUOTING；锁价/草稿 422） */
    Map<String, Object> saveQuote(String rfqId, Map<String, Object> payload);

    /** 延期截止日（未锁价、新日更大、原因≥2 留痕） */
    Map<String, Object> postpone(String id, String newDeadline, String reason);

    /** 追加供应商（未锁价、全 QUALIFIED、并集≥1、重新发出标记） */
    Map<String, Object> addSuppliers(String id, java.util.List<String> supplierIds);

    /** 作废重询/人工关闭（非 AWARDED/CLOSED，原因必填 → CLOSED；PR 可重建 RFQ） */
    void closeRfq(String id, String reason);

    /**
     * 比价矩阵：均值与 ±20% 异常（持久化 ANOMALY_FLAG）、权重和=100 校验、
     * 加权总分、含税单价按 TAX_CODE_NO+RFQ 创建日税码试算（未填/未命中「待税率」）、履约桩列。
     */
    Map<String, Object> matrix(String id, Integer weightPrice, Integer weightDelivery);

    /** 异常确认保留（记确认人/时间） */
    Map<String, Object> confirmAnomaly(String quoteId);

    /** 剔除报价（原因必填，置无效不参与均值/加权/定标） */
    Map<String, Object> excludeQuote(String quoteId, String reason);

    /** 谈判双轨：谈判后单价+说明，原始保留（BR-4.2-14） */
    Map<String, Object> negotiate(String quoteId, java.math.BigDecimal price, String note);

    /** 定标（六前置 → AWARDED + 事件） */
    Map<String, Object> award(String id, Map<String, Object> payload);

    /** 中选桩：按 PR 号查询（供 2.3.1），无 → 明示结构 */
    Map<String, Object> awarded(String prNo);

}
