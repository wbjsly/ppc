package com.erp.dao.system;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.system.DashLayout;

/**
 * 工作台布局 DAO（change add-configurable-workbench，迁移 209）。
 * upsert 走 Service 层 selectOne + insert/update，不依赖数据库 ON DUPLICATE KEY
 * （保持与项目其它 admin 域表一致的 MyBatis-Plus 惯例）。
 */
public interface DashLayoutDao extends BaseMapper<DashLayout> {
}
