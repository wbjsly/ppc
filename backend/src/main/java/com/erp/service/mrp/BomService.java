package com.erp.service.mrp;

import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;

import java.util.List;
import java.util.Map;

/**
 * 物料清单（BOM）管理（change add-bom-management，spec bom-management，00-erp-spec 4.5-1）。
 * 对应菜单 5.1.1 清单创建 / 5.1.2 清单变更 / 5.1.3 版本发布 / 5.1.4 循环校验。
 * 审批底座 BIZ_TYPE：BomPublish（1 SEQ × 1 SIGN，签署角色 ROLE_PROCESS_MGR）。
 * 角色：ROLE_PROCESS_ENG 创建/变更/提交/校验；ROLE_PROCESS_MGR 审核发布/废止；写操作放行 ADMIN。
 */
public interface BomService {

    String BIZ_PUBLISH = "BomPublish";

    /** 新建 BOM 草稿：父项启用校验 + 行校验（用量/损耗率/子项启用）+ 行级替代校验（FR-4.5-1-1/2/3） */
    MrpBom create(MrpBom head, List<MrpBomItem> items);

    /** 覆盖式保存草稿（仅 DRAFT 可编辑；父项不可改；校验同 create） */
    MrpBom saveDraft(String bomId, MrpBom head, List<MrpBomItem> items);

    /** 复制任意版本为独立草稿：头行替代全量克隆，copy_from_id 留痕，版本号重分配（FR-4.5-1-1） */
    MrpBom copy(String sourceId);

    /**
     * 发起变更（菜单 5.1.2，任务 4.4）：仅已发布版本可发起（422），变更原因必填（422），
     * 克隆行为新草稿（change_from_id 变更链），版本默认次+1、勾选升级主版本则主+1次归零
     * （FR-4.5-1-5）；源版本数据不被修改（BR-4.5-09 只新增版本）。
     */
    MrpBom change(String sourceId, String changeReason, boolean upgradeMajor);

    /** 审批通过入口（5.1 回调调用，同事务）：PENDING→PUBLISHED + 同父项旧已发布→REVISED + 发布留痕 */
    void publishApproved(String bomId);

    /** 审批驳回入口（5.1 回调调用）：PENDING→DRAFT + 记录驳回意见 */
    void rejectBackToDraft(String bomId, String reason);

    /**
     * 提交审核（菜单 5.1.3，任务 5.2）：ROLE_PROCESS_ENG，仅草稿可提交（422）；
     * 同事务挂审批底座单节点（BIZ=BomPublish，签署角色 ROLE_PROCESS_MGR）+ DRAFT→PENDING；
     * 重复提交（已有 PENDING 实例）由审批底座 422 拒绝。
     */
    MrpBom submit(String bomId);

    /**
     * 手动废止（菜单 5.1.3，任务 5.3）：ROLE_PROCESS_MGR/ADMIN，
     * PUBLISHED|REVISED → OBSOLETE（白名单 + CAS）；失效日期不自动触发状态迁移。
     */
    void obsolete(String bomId);

    /** 详情：头 + 行 + 行级替代 */
    Map<String, Object> detail(String bomId);

    /** 版本列表：状态/父项/关键字筛选，按父项与版本倒序（版本历史留痕出口） */
    List<Map<String, Object>> query(String status, String parentItemCode, String keyword);

    /** 行替代候选带出：读取 MDM 物料替代关系（菜单 1.2.5）只读引用，不构成强耦合 */
    List<Map<String, Object>> substituteCandidates(String itemCode);

    /**
     * 循环校验工具页扫描（菜单 5.1.4，任务 3.3）：
     * parentItemCode 非空 = 按父项扫描其可达子图；空 = 全量扫描全部在用父项。
     * 口径 = 各父项 DRAFT∪PENDING∪PUBLISHED 版本行的并集（比保存校验更保守，报告用）。
     * 返回报告：scope / scannedRoots / scannedBoms / visitedNodes / maxDepth /
     * depthExceeded / cycles（环路径 "A→B→C→A" 列表）/ passed。
     */
    Map<String, Object> scan(String parentItemCode);
}
