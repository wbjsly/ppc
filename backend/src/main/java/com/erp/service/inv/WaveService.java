package com.erp.service.inv;

import com.erp.entity.inv.InvWave;

import java.util.List;
import java.util.Map;

/**
 * 波次管理（4.8.1/4.8.2，spec wave-management）：
 * 聚类候选预览与确认建波次（手动触发+自动聚类，偏差 D1）、状态机与作废、
 * 波次级分配（共享预算、不足拆单）、C-4.4-08 改批审批挂接、
 * 分播复核、装车确认、逐单发运与失败重试。
 * 写限 ADMIN/WAREHOUSE（服务层二次校验）。
 */
public interface WaveService {

    /**
     * 按规则生成波次候选预览（不落库）：
     * 扫 DRAFT 销售发货单（仅 SALES_OUT，偏差 D2），按 配送线路 > 承运商 > 客户 聚类
     * （空线路独立降级分桶），超 WAVE_MAX_DOCS 拆块；每组标注聚类键与拆分原因。
     */
    Map<String, Object> preview();

    /**
     * 确认候选组建波次（逐单校验仍为 DRAFT，失效剔除并说明；全失效 422 不建）：
     * 建波次头 CREATED + 单据归属 BOUND。
     */
    Map<String, Object> createFromPreview(List<Map<String, Object>> groups);

    /** 波次分页：状态/关键词 */
    Map<String, Object> page(String status, String keyword, long current, long size);

    /** 波次详情：头 + 归属单据 + 分配行 + 改批记录 */
    Map<String, Object> detail(String waveId);

    /**
     * 状态迁移（服务内共用）：from 必须匹配当前状态（@Version 乐观锁），
     * 非法迁移 422；影响行数 0 → 409。
     */
    InvWave transition(String waveId, String fromStatus, String toStatus);

    /**
     * 作废（仅 CREATED/ALLOCATED=未开拣，原因必填）：
     * 解绑全部单据归属 + 作废 WAVE 任务 + 关闭未决改批审批（调整记录留痕）。
     */
    Map<String, Object> cancel(String waveId, String reason);

    /**
     * 波次级统一分配（BR-4.4-43 共享预算防抢批，spec「分配确认前波次 MUST 处于 CREATED」）：
     * 聚合需求 → FIFO+FEFO 切分（剔冻结/效期锁定，波次自身预留不占预算）→
     * 按单据创建序拆回 → 落分配行 + 回写发货行；订单拿不足 → 解绑拆出
     * （BR-4.4-47，全解绑 → 波次 CANCELLED 并提示）。**只计算落行，不改状态**
     * （改批审批锁行发生在本步与确认之间）；已有 LOCKED 行时 422 不可重算。
     */
    Map<String, Object> allocate(String waveId);

    /**
     * 确认分配（→ ALLOCATED，design D4 前置 = 无 LOCKED 行）：
     * 有 LOCKED 改批行未解锁 → 422（C-4.4-08 冻结拣货）；确认成功 → 生成
     * **恰好 1 张 WAVE 合并拣货任务**（FR-4.4-7-3，行按仓位+批次聚合、仓位号升序）。
     */
    Map<String, Object> confirmAllocate(String waveId);

    /**
     * 人工改批/改仓位（C-4.4-08 L2）：
     * 原因必填 → 调整记录 + 分配行 LOCKED + 提交审批底座单节点 ROLE_WAREHOUSE；
     * 发起人=ROLE_WAREHOUSE 唯一成员 → 422（禁同人闭环）。
     */
    Map<String, Object> adjust(String waveId, String lineId, String field,
                               String newValue, String reason);

    // ---------- 4.8.2 集货发运 ----------

    /**
     * 分播复核：按订单录实点（品种/数量/批次），不平 → WAVE_SORT 差异登记
     * （srcDocNo=订单号，门闩按订单拦截过账）；平 → SORT_STATUS=PASSED + 结果快照。
     * 全部 BOUND 单 PASSED → 波次 STAGING。
     */
    Map<String, Object> sortConfirm(String waveId, String shipId,
                                    List<Map<String, Object>> actualLines);

    /**
     * 装车确认：扫描件数/批次 vs 分播结果快照比对，不一致 422 阻断该单（C-4.4-06）；
     * 一致 → LOAD_STATUS=LOADED。全部 LOADED → 波次 SHIPPING。
     */
    Map<String, Object> loadConfirm(String waveId, String shipId,
                                    List<Map<String, Object>> scannedLines);

    /**
     * 发运确认（BR-4.4-46 逐单独立事务）：
     * 逐单调发货过账，失败记录 SHIP_ERR 支持单独重试；全部 POSTED → 波次 CLOSED。
     */
    Map<String, Object> shipConfirm(String waveId);

    /** 失败订单单独重试（BR-4.4-46） */
    Map<String, Object> retryShip(String waveId, String shipId);

    // ---------- 事件连带钩子（design D5，4.7/过账侧回调） ----------

    /** WAVE 任务状态连带：任务进 PICKING → 波次 PICKING；任务到 PICKED/DONE → 波次 SORTING */
    void onTaskStatus(String waveNo, String taskStatus);

    /** 单据过账 POSTED 连带：全 BOUND 单 POSTED → 波次 CLOSED */
    void onDocPosted(String shipNo);
}
