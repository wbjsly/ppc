package com.erp.service.approval;

/**
 * 审批节点定义：外层 List 的下标 + 1 = SEQ，同 SEQ 的多个节点 = 并行（双签/会签）。
 * 例：双签 = 单个 SEQ 含两个 sign 节点（质量经理 + 技术负责人），全部 PASSED 才推进。
 */
public class ApprovalNodeSpec {

    /** SIGN 签署 / JOINT 会签 */
    private final String nodeType;
    private final String roleRequired;
    private final String nodeName;

    private ApprovalNodeSpec(String nodeType, String roleRequired, String nodeName) {
        this.nodeType = nodeType;
        this.roleRequired = roleRequired;
        this.nodeName = nodeName;
    }

    public static ApprovalNodeSpec sign(String role, String name) {
        return new ApprovalNodeSpec("SIGN", role, name);
    }

    public static ApprovalNodeSpec joint(String role, String name) {
        return new ApprovalNodeSpec("JOINT", role, name);
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
}
