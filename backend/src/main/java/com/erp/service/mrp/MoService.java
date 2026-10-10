package com.erp.service.mrp;

import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoShortage;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生产工单服务（change add-work-order-management，spec work-order-management / 00-erp-spec 4.5-3）。
 * 五菜单入口：创建（手工/PMO 双入口）· 审批 · 释放 · 变更（挂起/取消/拆分）· 完工与关闭。
 * 权限：写操作 ROLE_PLANNER/ROLE_ADMIN；审批签署侧 ApprovalEngine 节点管（ROLE_PLAN_MGR）；
 * 读操作需登录（未认证 401）。
 */
public interface MoService {

    /** 审批业务类型（design D3：串行单节点计划主管签核） */
    String BIZ_APPROVE = "MoApprove";

    // ---------- 5.4.1 工单创建 ----------

    /** 可选 PMO 列表（5.3 已转正且未关联工单的生产建议，D6 对接） */
    List<Map<String, Object>> candidatePmos();

    /**
     * 手工新建（FR-4.5-3-2~6）：头校验（完工日期 L1/数量/唯一在途/超交 5%）+ BOM/路线双快照
     * + 缺料预检。返回 {mo, warnings}——无路线/无定额为 warning 不阻断。
     */
    Map<String, Object> create(MrpMo head);

    /**
     * PMO 入口建单（FR-4.5-3-1，D6 对接）：带出产品/数量/建议日期；
     * 同事务回写 erp_mrp_suggestion.MO_NO（WHERE MO_NO IS NULL 幂等，影响行数 0 → 422 已关联）。
     */
    Map<String, Object> createFromPmo(String suggestId, MrpMo overrides);

    /** 详情：头 + BOM 快照行 + 工序快照行 + 缺料清单 */
    Map<String, Object> detail(String moId);

    /** 列表（状态/产品/缺料过滤，带全部留痕字段） */
    List<MrpMo> list(String status, String productCode, String shortageFlag);

    /** 缺料清单（5.4.3 释放页） */
    List<MrpMoShortage> shortages(String moId);

    // ---------- 5.4.2 工单审批 ----------

    /** 提交审批：PLANNED→PENDING，挂审批底座单节点（重复提交 422） */
    MrpMo submit(String moId);

    // ---------- 5.4.3 工单释放 ----------

    /** 释放：CONFIRMED→RELEASED + 留痕 + 重跑齐套打缺料标记（缺料不阻断） */
    MrpMo release(String moId);

    // ---------- 5.4.4 工单变更 ----------

    /** 挂起（RELEASED→HOLD，原因必填） */
    MrpMo hold(String moId, String reason);

    /** 恢复（HOLD→RELEASED） */
    MrpMo resume(String moId);

    /** 取消（PLANNED/CONFIRMED/RELEASED/HOLD→CANCELLED，原因必填，终态） */
    MrpMo cancel(String moId, String reason);

    /** 拆分：子单合计=剩余量 L1（C-4.5-15），子单继承快照与交期（BR-4.5-05），返回 {parent, children} */
    Map<String, Object> split(String moId, List<BigDecimal> childQtys, String reason);

    // ---------- 5.4.5 完工与关闭 ----------

    /** 手动完工确认（过渡，5.7 报工落地后改自动）：RELEASED→COMPLETED + 合格产出留痕 */
    MrpMo complete(String moId, BigDecimal qualifiedQty);

    /** 关闭预检：状态 + 全部 MoCloseCheck 钩子结果（页面展示） */
    Map<String, Object> precheckClose(String moId);

    /** 关闭：状态=COMPLETED 硬校验 + 钩子（不满足 422），→ CLOSED 终态 + 留痕 */
    MrpMo close(String moId);

    // ---------- 回调入口（MoApprovalCallback 调用，无角色门） ----------

    /** 审批通过（PENDING→CONFIRMED）：回调专用 */
    void onApproved(String moId);

    /** 审批驳回（PENDING→PLANNED + 意见）：回调专用 */
    void onRejected(String moId, String reason);

    // ---------- 下游消费契约 ----------

    /** 在制供给：RELEASED/HOLD/未关闭 COMPLETED 的剩余量（spec「下游消费契约」②；1.3 核实结论） */
    Map<String, BigDecimal> inProcess(Set<String> itemCodes);
}
