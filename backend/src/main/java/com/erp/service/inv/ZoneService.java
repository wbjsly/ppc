package com.erp.service.inv;

import com.erp.entity.inv.InvZone;

import java.util.List;
import java.util.Map;

/**
 * 仓库区域（4.1.2，spec warehouse-zone-planning）能力契约：
 * 编码仓库内唯一且创建后不可改；变更走版本快照 + 乐观锁；停用后禁建仓位、不级联既有仓位。
 */
public interface ZoneService {

    /** 区域列表（仓库/关键字/状态筛选，按排序与编码升序） */
    List<InvZone> list(String whCode, String keyword, String status);

    /** 单个区域 */
    InvZone get(String id);

    /** 新建（编码创建后不可改；停用仓库禁建 422；生成 V1 快照） */
    InvZone create(InvZone zone);

    /** 变更（编码锁定 422；乐观锁并发 409；写 N+1 版本快照） */
    InvZone update(InvZone zone);

    /** 启用 */
    void enable(String id);

    /** 停用（不级联仓位） */
    void disable(String id);

    /** 区域 + 下属仓位数汇总（规划页左栏） */
    List<Map<String, Object>> listWithBinCount(String whCode, String keyword, String status);
}
