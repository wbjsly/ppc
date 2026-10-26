package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrDeptDao;
import com.erp.dao.hr.HrPositionDao;
import com.erp.entity.hr.HrDept;
import com.erp.entity.hr.HrPosition;
import com.erp.service.hr.HrOrgService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** HR 轻量部门/岗位实现。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrOrgServiceImpl implements HrOrgService {

    private final HrDeptDao deptDao;
    private final HrPositionDao posDao;
    private final com.erp.dao.hr.HrEmployeeDao empDao;
    private final com.erp.dao.hr.HrGradeDao gradeDao;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrDept createDept(Map<String, Object> payload) {
        String name = str(payload.get("name"));
        require(name, "部门名称");
        HrDept d = new HrDept();
        d.setId(uuid());
        d.setDeptNo(nextNo("DEPT", deptDao::selectNosByPrefix));
        d.setName(name);
        d.setParentId(str(payload.get("parentId")));
        if (d.getParentId() != null) {
            HrDept parent = deptDao.selectById(d.getParentId());
            if (parent == null || !"1".equals(parent.getStatus())) {
                throw new ServiceException(422, "上级部门不存在或已停用");
            }
        }
        d.setStatus("1");
        d.setCreateBy(currentUser());
        deptDao.insert(d);
        log.info("hr dept created: {} {}", d.getDeptNo(), name);
        return d;
    }

    @Override
    public List<HrDept> listDepts() {
        return deptDao.selectList(new LambdaQueryWrapper<HrDept>()
                .eq(HrDept::getDelFlag, "0").orderByAsc(HrDept::getDeptNo));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrDept toggleDept(String deptId, String status) {
        HrDept d = requireDept(deptId);
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new ServiceException(422, "状态非法（0/1）");
        }
        if ("0".equals(status)) {
            // 停用前校验无启用岗位引用
            long used = posDao.selectCount(new LambdaQueryWrapper<HrPosition>()
                    .eq(HrPosition::getDelFlag, "0")
                    .eq(HrPosition::getDeptId, deptId)
                    .eq(HrPosition::getStatus, "1"));
            if (used > 0) {
                throw new ServiceException(422, "部门下存在启用岗位，先停用岗位");
            }
        }
        d.setStatus(status);
        d.setUpdateBy(currentUser());
        d.setUpdateDate(LocalDateTime.now());
        deptDao.updateById(d);
        return d;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPosition createPosition(Map<String, Object> payload) {
        String name = str(payload.get("name"));
        String deptId = str(payload.get("deptId"));
        require(name, "岗位名称");
        require(deptId, "所属部门");
        HrDept dept = requireDept(deptId);
        if (!"1".equals(dept.getStatus())) {
            throw new ServiceException(422, "所属部门已停用");
        }
        String rolesJson = str(payload.get("rolesJson"));
        if (rolesJson != null && !rolesJson.isBlank()) {
            try {
                Object parsed = MAPPER.readValue(rolesJson, Object.class);
                if (!(parsed instanceof List)) {
                    throw new IllegalArgumentException("not array");
                }
            } catch (Exception e) {
                throw new ServiceException(422, "岗位角色映射须为 JSON 数组");
            }
        }
        HrPosition p = new HrPosition();
        p.setId(uuid());
        p.setPosNo(nextNo("POS", posDao::selectNosByPrefix));
        p.setName(name);
        p.setDeptId(deptId);
        p.setRolesJson(rolesJson);
        Object hc = payload.get("headcount");
        if (hc != null && !String.valueOf(hc).isBlank()) {
            try {
                p.setHeadcount(Integer.parseInt(String.valueOf(hc).trim()));
            } catch (Exception e) {
                throw new ServiceException(422, "编制数须为整数");
            }
        }
        p.setStatus("1");
        p.setCreateBy(currentUser());
        posDao.insert(p);
        log.info("hr position created: {} {} roles {}", p.getPosNo(), name, rolesJson);
        return p;
    }

    @Override
    public List<HrPosition> listPositions(String deptId) {
        LambdaQueryWrapper<HrPosition> qw = new LambdaQueryWrapper<HrPosition>()
                .eq(HrPosition::getDelFlag, "0").orderByAsc(HrPosition::getPosNo);
        if (deptId != null && !deptId.isBlank()) {
            qw.eq(HrPosition::getDeptId, deptId);
        }
        return posDao.selectList(qw);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPosition togglePosition(String posId, String status) {
        HrPosition p = posDao.selectById(posId);
        if (p == null) {
            throw new ServiceException(404, "岗位不存在：" + posId);
        }
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new ServiceException(422, "状态非法（0/1）");
        }
        p.setStatus(status);
        p.setUpdateBy(currentUser());
        p.setUpdateDate(LocalDateTime.now());
        posDao.updateById(p);
        return p;
    }

    @Override
    public List<String> positionRoles(String posId) {
        HrPosition p = posDao.selectById(posId);
        if (p == null || !"1".equals(p.getStatus())) {
            return List.of(); // 停用/不存在：无角色（引用方阻断）
        }
        if (p.getRolesJson() == null || p.getRolesJson().isBlank()) {
            return List.of();
        }
        try {
            return new ArrayList<>(MAPPER.readValue(p.getRolesJson(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
        } catch (Exception e) {
            return List.of();
        }
    }

    // ================================================================
    // add-org-management（M10-2 原地升级）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrDept deactivateDept(String deptId) {
        HrDept d = requireDept(deptId);
        // 双重阻断（spec：在职员工 L1 / 启用子部门 L1）
        long active = empDao.selectCount(new LambdaQueryWrapper<com.erp.entity.hr.HrEmployee>()
                .eq(com.erp.entity.hr.HrEmployee::getDeptId, deptId)
                .eq(com.erp.entity.hr.HrEmployee::getDelFlag, "0")
                .in(com.erp.entity.hr.HrEmployee::getEmpStatus, "PROBATION", "REGULAR"));
        if (active > 0) {
            throw new ServiceException(422, "部门存在 " + active + " 名在职员工，不可停用（先迁移员工）");
        }
        long children = deptDao.selectCount(new LambdaQueryWrapper<HrDept>()
                .eq(HrDept::getParentId, deptId)
                .eq(HrDept::getDelFlag, "0")
                .eq(HrDept::getStatus, "1"));
        if (children > 0) {
            throw new ServiceException(422, "部门存在 " + children + " 个启用子部门，不可停用（先迁移子部门）");
        }
        return toggleDept(deptId, "0");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrDept setCostCenter(String deptId, String costCenter) {
        HrDept d = requireDept(deptId);
        d.setCostCenter(costCenter);
        d.setUpdateBy(currentUser());
        d.setUpdateDate(LocalDateTime.now());
        deptDao.updateById(d);
        return d;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPosition updatePositionMeta(String posId, String gradeId, String jdJson) {
        HrPosition p = posDao.selectById(posId);
        if (p == null) {
            throw new ServiceException(404, "岗位不存在：" + posId);
        }
        if (gradeId != null && !gradeId.isBlank()) {
            com.erp.entity.hr.HrGrade g = gradeDao.selectById(gradeId);
            if (g == null || !"1".equals(g.getStatus())) {
                throw new ServiceException(422, "关联职级不存在或已停用");
            }
            p.setGradeId(gradeId);
        }
        if (jdJson != null) {
            p.setJdJson(jdJson.isBlank() ? null : jdJson);
        }
        p.setUpdateBy(currentUser());
        p.setUpdateDate(LocalDateTime.now());
        posDao.updateById(p);
        return p;
    }

    // ================================================================
    // 内部
    // ================================================================

    private HrDept requireDept(String deptId) {
        HrDept d = deptDao.selectById(deptId);
        if (d == null) {
            throw new ServiceException(404, "部门不存在：" + deptId);
        }
        return d;
    }

    private String nextNo(String prefix, java.util.function.Function<String, List<String>> nosFn) {
        List<String> existing = nosFn.apply(prefix + "%");
        int max = 0;
        for (String no : existing) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignored) {
                // 非数字后缀跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private static void require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new ServiceException(422, field + "必填");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
