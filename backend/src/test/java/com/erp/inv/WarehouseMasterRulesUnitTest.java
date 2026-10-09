package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.dao.inv.InvWarehouseDao;
import com.erp.dao.mdm.MdmItemDictDao;
import com.erp.dao.mdm.MdmOrgUnitDao;
import com.erp.entity.inv.InvWarehouse;
import com.erp.entity.mdm.MdmItemDict;
import com.erp.entity.mdm.MdmOrgUnit;
import com.erp.service.impl.inv.WarehouseServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 4.1.1 仓库档案补差距（change add-warehouse-zone-management，spec warehouse-master MODIFIED）：
 * 仓库类型字典校验（非法 422）、组织存在性/启用校验（DC-02，null 放行）、
 * 既有行为回归（编码锁定 / 默认仓禁停用 / 停用仓不进下拉）。
 */
class WarehouseMasterRulesUnitTest {

    private InvWarehouseDao warehouseDao;
    private MdmItemDictDao dictDao;
    private MdmOrgUnitDao orgUnitDao;
    private WarehouseServiceImpl service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "junit", "n/a", List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE"))));
        warehouseDao = mock(InvWarehouseDao.class);
        dictDao = mock(MdmItemDictDao.class);
        orgUnitDao = mock(MdmOrgUnitDao.class);
        service = new WarehouseServiceImpl(warehouseDao, dictDao, orgUnitDao);

        // WAREHOUSE_TYPE 启用字典：NORMAL / CHILLED
        MdmItemDict normal = new MdmItemDict();
        normal.setDictCode("NORMAL");
        MdmItemDict chilled = new MdmItemDict();
        chilled.setDictCode("CHILLED");
        when(dictDao.selectByType("WAREHOUSE_TYPE")).thenReturn(List.of(normal, chilled));
        when(warehouseDao.selectCodedWhs(anyString())).thenReturn(List.of("WH-MAIN", "WH-02"));
    }

    private InvWarehouse payload(String name, String whType, String orgUnit) {
        InvWarehouse wh = new InvWarehouse();
        wh.setWhName(name);
        wh.setWhType(whType);
        wh.setOrgUnit(orgUnit);
        return wh;
    }

    // ---- 任务 2.1：类型与组织校验四个用例 ----

    @Test
    void invalidWarehouseTypeRejected422() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(payload("测试仓", "NOT_A_TYPE", null)));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("仓库类型"));
        verify(warehouseDao, never()).insert(any());
    }

    @Test
    void validWarehouseTypeAccepted() {
        when(warehouseDao.insert(any(InvWarehouse.class))).thenReturn(1);
        InvWarehouse created = service.create(payload("测试仓", "CHILLED", null));
        assertNotNull(created.getWhCode());
        assertTrue(created.getWhCode().startsWith("WH-"));
        assertEquals("CHILLED", created.getWhType());
    }

    @Test
    void orgUnitNotFoundRejected422() {
        when(orgUnitDao.selectById("org-not-exist")).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(payload("测试仓", "NORMAL", "org-not-exist")));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void disabledOrgUnitRejected422() {
        MdmOrgUnit org = new MdmOrgUnit();
        org.setId("org-disabled");
        org.setOuName("停用组织");
        org.setStatus("0");
        when(orgUnitDao.selectById("org-disabled")).thenReturn(org);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(payload("测试仓", "NORMAL", "org-disabled")));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("停用"));
    }

    @Test
    void nullOrgUnitPasses() {
        when(warehouseDao.insert(any(InvWarehouse.class))).thenReturn(1);
        InvWarehouse created = service.create(payload("存量样式仓", "NORMAL", null));
        assertNotNull(created);
        verify(orgUnitDao, never()).selectById(anyString());
    }

    // ---- 任务 2.3：既有 4.1.1 行为回归 ----

    @Test
    void codeLockedAfterCreate() {
        InvWarehouse stored = new InvWarehouse();
        stored.setId("wh-id");
        stored.setWhCode("WH-0003");
        stored.setWhName("既有仓");
        when(warehouseDao.selectById("wh-id")).thenReturn(stored);

        InvWarehouse edit = payload("改名仓", "NORMAL", null);
        edit.setId("wh-id");
        edit.setWhCode("WH-9999"); // 尝试改码
        ServiceException ex = assertThrows(ServiceException.class, () -> service.update(edit));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("不可修改"));
    }

    @Test
    void defaultWarehouseCannotDisable() {
        InvWarehouse main = new InvWarehouse();
        main.setId("wh-main");
        main.setWhCode(InvWarehouse.DEFAULT_WH_CODE);
        main.setStatus("1");
        when(warehouseDao.selectById("wh-main")).thenReturn(main);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.disable("wh-main"));
        assertEquals(422, ex.getCode());
    }

    @Test
    void disabledWarehouseNotInEnabledDropdown() {
        // DAO 层 SQL 固定 WHERE STATUS = '1'（selectEnabled），停用仓不可能出现在下拉数据源
        InvWarehouse enabled = new InvWarehouse();
        enabled.setWhCode("WH-0003");
        enabled.setStatus("1");
        when(warehouseDao.selectEnabled()).thenReturn(List.of(enabled));
        List<InvWarehouse> options = service.listEnabled();
        assertTrue(options.stream().allMatch(w -> "1".equals(w.getStatus())),
                "下拉数据源必须全部为启用仓");
        verify(warehouseDao).selectEnabled();
    }
}
