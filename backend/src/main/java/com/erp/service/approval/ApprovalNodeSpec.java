package com.erp.service.approval;

/**
 * 审批节点定义：外层 List 的下标 + 1 = SEQ，同 SEQ 的多个节点 = 并行（双签/会签）。
 * 例：双签 = 单个 SEQ 含两个 sign 节点（质量经理 + 技术负责人），全部 PASSED 才推进。
 *
 * <p>两种节点形态（add-permission-approval-flow design D3，spec approval-workflow 节点模型）：
 * <ul>
 *   <li><b>角色节点</b>：sign/joint(role, name)，按 ROLE_REQUIRED 匹配签署人（既有行为不变）</li>
 *   <li><b>按人节点</b>：person(userId, name)，提交时指定具体签署人（"直属主管"等按人语义），
 *       ROLE_REQUIRED 为空，仅指定用户可签</li>
 * </ul>
 */
public class ApprovalNodeSpec {

    /** SIGN 签署 / JOINT 会签 */
    private final String nodeType;
    private final String roleRequired;
    private final String nodeName;
    /** 按人节点的指定签署人（用户 ID）；角色节点为 null */
    private final String signerUserId;

    private ApprovalNodeSpec(String nodeType, String roleRequired, String nodeName, String signerUserId) {
        this.nodeType = nodeType;
        this.roleRequired = roleRequired;
        this.nodeName = nodeName;
        this.signerUserId = signerUserId;
    }

    public static ApprovalNodeSpec sign(String role, String name) {
        return new ApprovalNodeSpec("SIGN", role, name, null);
    }

    public static ApprovalNodeSpec joint(String role, String name) {
        return new ApprovalNodeSpec("JOINT", role, name, null);
    }

    /** 按人节点：仅 signerUserId 指定的用户可签（roleRequired 置空） */
    public static ApprovalNodeSpec person(String signerUserId, String name) {
        if (signerUserId == null || signerUserId.isBlank()) {
            throw new IllegalArgumentException("按人节点须指定签署人");
        }
        return new ApprovalNodeSpec("SIGN", null, name, signerUserId);
    }

    public String getNodeType() {
        return nodeType;
    }

    public String getRoleRequired() {
        return roleRequired;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getSignerUserId() {
        return signerUserId;
    }

    public boolean isPersonNode() {
        return signerUserId != null && !signerUserId.isBlank();
    }
}
