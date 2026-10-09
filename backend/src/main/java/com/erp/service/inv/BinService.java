package com.erp.service.inv;

import com.erp.entity.inv.InvBin;

import java.util.List;
import java.util.Map;

/**
 * 仓库仓位（4.1.2，spec warehouse-zone-planning）能力契约：
 * 编号系统生成（区域码-排2位-列2位-层2位，等宽补零，请求体 binCode 忽略）；
 * 属性继承区域默认可覆盖；批量规划单事务、冲突整体拒绝。
 */
public interface BinService {

    /** 仓位查询（仓库/区域/编号关键字/状态，编号字典序升序，带区域名称） */
    List<Map<String, Object>> query(String whCode, String zoneCode, String keyword, String status);

    /** 单个仓位 */
    InvBin get(String id);

    /** 单个新建（区域 + 排/列/层；属性继承区域默认可覆盖；停用区域 422） */
    InvBin create(InvBin bin);

    /** 变更属性（编号锁定；启停） */
    InvBin update(InvBin bin);

    /** 启用 */
    void enable(String id);

    /** 停用 */
    void disable(String id);

    /** 批量规划：区间 → 预检冲突（409 列全清单）→ 单事务插入；返回生成数量与编号范围 */
    Map<String, Object> batchCreate(Map<String, Object> payload);
}
