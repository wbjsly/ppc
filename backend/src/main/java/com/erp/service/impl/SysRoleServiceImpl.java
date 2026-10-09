package com.erp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.system.SysRoleDao;
import com.erp.entity.system.SysRole;
import com.erp.service.SysRoleService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SysRoleServiceImpl implements SysRoleService {

    private final SysRoleDao roleDao;

    public SysRoleServiceImpl(SysRoleDao roleDao) {
        this.roleDao = roleDao;
    }

    @Override
    public List<SysRole> listAll() {
        return roleDao.selectList(
                new LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getStatus, "1")
                        .orderByAsc(SysRole::getSortOrder)
        );
    }
}
