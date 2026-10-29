package com.erp.service;

import java.util.List;
import java.util.Map;

/**
 * 权限矩阵（add-role-permission-console spec：权限矩阵与 override 覆盖层）。
 * 读 = 099 基线 ∪ override 合并视图（含每格来源标注）；
 * 写 = 仅落 override 差异格（绝对指令），永不改 erp_admin_menu.PERM 基线列。
 */
public interface PermMatrixService {

    /**
     * 指定角色的合并矩阵：每行含 id/parentId/title/path/icon/sortOrder/status/
     * baseline(基线是否含该角色) / effective(合并后是否可见) / source(BASE|OVERRIDE_ADD|OVERRIDE_REMOVE)。
     */
    List<Map<String, Object>> matrixForRole(String roleCode);

    /**
     * 勾选（checked=true）/ 取消（false）某角色对某菜单的授权。
     * 最小差异写入：恢复基线即删格；偏离基线才落 ADD/REMOVE 行；基线本就一致则无操作。
     */
    void setCell(String roleCode, String menuId, boolean checked);

    /** 清除该角色全部覆盖行（物理删除，回退 099 基线口径） */
    void clearRole(String roleCode);
}
