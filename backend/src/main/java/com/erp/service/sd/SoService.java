package com.erp.service.sd;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.sd.So;
import com.erp.entity.sd.SoLine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 销售订单服务（tasks 7.2~7.10，spec sales-order）。
 *
 * 覆盖：头行录入与价格协议绑定（FR-4.3-4-1/2/3）、付款条件差异与交期确认（BR-4.3-25/28）、
 * 手动改价锁行（BR-4.3-26）、金额三档审批与链尾加签（FR-4.3-4-5）、
 * 状态机与挂起态（业务逻辑 6）、变更与已执行行阻断（FR-4.3-4-7/C-4.3-08）、订单关闭（3.5.4）。
 * 确认动作（预留+ATP+事件）在 {@code SoConfirmSupport}（7.7），审批回调与本服务共用。
 */
public interface SoService {

    /**
     * 录入 SO（7.2/7.3/7.4）：单价由价格协议自动带出（无协议 L1 阻断），
     * 付款条件差异需主管确认（否则提交审批时拦截），交期晚于客户期望标记待确认。
     *
     * @param header 头字段：customerId / orderType / paymentTerms / tradeTerms / oppId / remark 等
     * @param lines  行：itemCode / qty / warehouseCode / planShipDate / customerExpectDate / remark
     * @return 创建的 SO（含行与价格匹配记录）
     */
    Map<String, Object> create(Map<String, Object> header, List<Map<String, Object>> lines);

    /** 修改草稿（仅 DRAFT 可改；重新取价与卡控，同 create 口径） */
    Map<String, Object> update(String soId, Map<String, Object> header, List<Map<String, Object>> lines);

    /**
     * 手动改价（7.5）：锁行 + 生成价格变更审批任务（BIZ_TYPE=PriceChange），
     * 审批通过前 SO 不可确认（BR-4.3-26）。
     */
    SoLine changePrice(String lineId, BigDecimal newPrice, String reason);

    /**
     * 提交审批（7.6）：金额三档（自动 / 销售经理 / +销售总监）+ 两类链尾加签
     * （低毛利有特批 → 财务；改价行 → 价格变更复核）；小额高毛利免审直接确认。
     *
     * @return {so, mode: AUTO|APPROVAL, chain: 节点摘要}
     */
    Map<String, Object> submit(String soId);

    /** 付款条件差异的销售主管确认（BR-4.3-25）：记录确认人与原因 */
    So confirmPaymentTerms(String soId, String reason);

    /** 行交期客户已确认回执（BR-4.3-28）：清除「交期待确认」标记 */
    SoLine ackDelivery(String lineId);

    /**
     * SO 变更（7.9）：数量调整、交期变更与行取消；重跑信用（超限重新冻结）、
     * 增量重锁批次；已发货/已开票行 L1 阻断（C-4.3-08）。
     *
     * @param lineMaps 行变更：{id, qty?, planShipDate?, customerExpectDate?, cancel?}
     */
    Map<String, Object> change(String soId, List<Map<String, Object>> lineMaps, String reason);

    /**
     * 订单关闭（7.10）：手动关闭必填原因并经销售经理确认；释放未消耗预留；关闭仅可查询。
     */
    So close(String soId, String reason);

    /** 关闭单不可重启（7.8 场景）：恒 422 提示新建 SO 并关联原单号 */
    void reopen(String soId);

    Page<So> page(long current, long size, String keyword, String status, String customerId);

    /** 详情：头 + 行 + 价格匹配记录 + 付款条件差异 + 审批/冻结摘要 */
    Map<String, Object> detail(String soId);

    /** 审批日志（档位链与加签节点展示，3.5.2） */
    Map<String, Object> approvalLogs(String soId);
}
