package com.erp.service.mrp;

import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;

import java.util.List;
import java.util.Map;

/**
 * 工艺路线管理（change add-routing-management，spec routing-management，00-erp-spec 4.5 消费方反推）。
 * 对应菜单 5.2.4 路线装配（5.2.1/5.2.2/5.2.3 由 OperationService/WorkCenterService/OpWcStandardService 承担）。
 * 审批底座 BIZ_TYPE：RoutingPublish（1 SEQ × 1 SIGN，签署角色 ROLE_PROCESS_MGR）。
 * 角色：ROLE_PROCESS_ENG / ROLE_PROCESS_MGR 装配与提交；ROLE_PROCESS_MGR 审核发布/废止；写操作放行 ADMIN。
 */
public interface RoutingService {

    String BIZ_PUBLISH = "RoutingPublish";
    /** 试产特别审批 BIZ_TYPE（change add-trial-production-management，bizId = routingId） */
    String BIZ_TRIAL = "TrialRoute";

    /**
     * 新建路线草稿（菜单 5.2.4）：产品启用校验 + 工序行校验（空行 422、工序/工作中心启用、
     * 定额组合存在 L1）+ 同产品唯一在途 + 版本号自动分配。
     */
    MrpRouting create(MrpRouting head, List<MrpRoutingOp> ops);

    /** 覆盖式保存草稿（仅 DRAFT 可编辑；产品不可改；行校验同 create；op_seq 按载荷序重排） */
    MrpRouting saveDraft(String routingId, MrpRouting head, List<MrpRoutingOp> ops);

    /**
     * 发起变更（任务 4.4）：仅已发布版本可发起（422）、变更原因必填（422）、
     * 克隆行为新草稿（change_from_id 变更链），版本默认次+1、勾选升级主版本则主+1次归零；源版本不被修改。
     */
    MrpRouting change(String sourceId, String changeReason, boolean upgradeMajor);

    /** 审批通过入口（5.1 回调调用，同事务）：PENDING→PUBLISHED + 同产品旧已发布→REVISED + 发布留痕 */
    void publishApproved(String routingId);

    /** 审批驳回入口（5.1 回调调用）：PENDING→DRAFT + 记录驳回意见 */
    void rejectBackToDraft(String routingId, String reason);

    /**
     * 提交试产特别审批（change add-trial-production-management，spec routing-management delta，
     * FR-4.5-8-2）：仅带 TRIAL_STAGE 的草稿可提交（否则 422）；挂审批底座单节点
     * （BIZ=TrialRoute，签署角色 ROLE_PROCESS_MGR）；审批期间状态保持 DRAFT，
     * 通过回调 onTrialApproved 同事务 DRAFT→TRIAL。
     */
    MrpRouting submitTrial(String routingId);

    /** 试产审批通过入口（TrialRoute 回调调用，同事务）：DRAFT→TRIAL + 同产品旧 TRIAL→REVISED */
    void onTrialApproved(String routingId);

    /** 试产审批驳回入口（TrialRoute 回调调用）：状态保持 DRAFT + 记录驳回意见 */
    void onTrialRejected(String routingId, String reason);

    /**
     * 转量产复制（change add-trial-production-management，design D11）：
     * 克隆试产路线为量产 DRAFT（TRIAL_STAGE=NULL、SRC_TRIAL_NO=TP 单号留痕），
     * 原试产版零改动；发布两处校验来源评审 APPROVED。
     */
    MrpRouting promoteFromTrial(String sourceId, String trialMoNo);

    /**
     * 提交审核（任务 5.2）：仅草稿可提交（422）；同事务挂审批底座单节点
     * （BIZ=RoutingPublish，签署角色 ROLE_PROCESS_MGR）+ DRAFT→PENDING；重复提交由底座 422 拒绝。
     */
    MrpRouting submit(String routingId);

    /**
     * 手动废止（任务 5.3，ROLE_PROCESS_MGR/ADMIN）：
     * PUBLISHED|REVISED → OBSOLETE（白名单 + CAS）。
     */
    void obsolete(String routingId);

    /** 详情：头 + 有序工序行 */
    Map<String, Object> detail(String routingId);

    /** 版本列表：状态/产品/关键字筛选，按产品与版本倒序（版本历史留痕出口） */
    List<Map<String, Object>> query(String status, String itemCode, String keyword);

    /** 列表按路线类型过滤（change add-rework-management：NORMAL/REWORK 分池展示；routeType 空=全部） */
    List<Map<String, Object>> query(String status, String itemCode, String keyword, String routeType);

    /**
     * 下游消费契约（spec「下游消费数据契约」）：按产品取最新 PUBLISHED 版本的
     * 有序工序行 + 每行四类工时（定额） + 工作中心产能三要素与状态；
     * 无已发布版本 → 返回空 Map（不返回未发布数据）。
     */
    Map<String, Object> publishedRoute(String itemCode);

    /** 下游契约按类型取已发布路线（rework-order 选 REWORK 版；routeType 空=NORMAL 缺省口径） */
    Map<String, Object> publishedRoute(String itemCode, String routeType);
}
