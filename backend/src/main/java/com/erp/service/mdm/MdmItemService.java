package com.erp.service.mdm;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mdm.MdmItemVersion;

import java.util.List;
import java.util.Map;

/**
 * 物料主数据业务能力契约（基础数据-物料管理 1.2.1 物料新建）。
 */
public interface MdmItemService {

    /** 分页查询，keyword 匹配编码/名称，categoryCode/status 精确匹配（可空）；hasSubstitute: 1 有替代 / 0 无替代 / 空 全部 */
    Page<MdmItem> page(long current, long size, String keyword, String categoryCode, String status,
                       String hasSubstitute);

    /** 按 id 查询详情 */
    MdmItem getById(String id);

    /**
     * 新建（流程一第 1/2/4 步）：itemCode 为空 → 按分类自动生成三段式编码；
     * 非空 → 格式/前缀-分类一致校验。查重命中时若 forceCreate=false 返回409+近3条，
     * 带 forceCreate+dupNote 放行（唯一性不放行）。成功立即 Active + V1 快照。
     */
    MdmItem create(MdmItem item, boolean forceCreate);

    /** 变更非编码字段：编码不可改（BR-4.1-13），乐观锁 + V(N+1) 快照 */
    MdmItem update(MdmItem item);

    /** 停用软删（C-4.1-03）：仅 1→0，原因必填，替代引用 409 阻断；快照 DISABLE；事件桩 MDM.ITEM.DISABLED */
    void disable(String id, String reason);

    /** 启用回退（规格空白补强）：仅 0→1，原因必填，替代指向目标非启用 422；快照 ENABLE */
    void enable(String id, String reason);

    /** 标记式归档（行 511 降级实现）：仅 0→2，原因必填，被替代引用 409；快照 ARCHIVE；归档为终态 */
    void archive(String id, String reason);

    /** 影响分析（行 513）：替代引用真实清单 + 下游桩（downstreamStub=true 明示未接入） */
    Map<String, Object> impact(String id);

    /** 批量停用：逐条执行单条语义，单条失败不回滚其余，返回 succeeded/failed 分组 */
    Map<String, Object> disableBatch(List<String> ids, String reason);

    /** 查重候选：名称编辑距离 ≤3 的最近 3 条（供表单实时提示） */
    List<MdmItem> checkSimilar(String itemName);

    /** 分类选项（编码校验与表单共用） */
    List<Map<String, String>> categories();

    /** 字典项：type = UNIT / MATERIAL_GROUP / STORAGE */
    List<Map<String, String>> dicts(String type);

    /** 启用物料选项，供下游引用 */
    List<Map<String, String>> options();

    List<MdmItemVersion> versions(String entityId);

    Map<String, Object> diff(String entityId, int from, int to);

    /** 替代关系列表（自 join，direction: source 正向 / target 反查） */
    Map<String, Object> substituteList(String keyword, String direction, String status,
                                       long current, long size);

    /** 设置替代：已发布 + 非自身 + 间接环三校验，入版本快照（changeReason=设置替代） */
    MdmItem setSubstitute(String id, String substituteCode);

    /** 清除替代：置空 altItemCode，入版本快照（changeReason=清除替代） */
    MdmItem clearSubstitute(String id);

    /** 替代三校验试算（dry-run，供表单失焦实时提示；不产生任何写入） */
    void checkSubstitute(String itemCode, String substituteCode);
}
