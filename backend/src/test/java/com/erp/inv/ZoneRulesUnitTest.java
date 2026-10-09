package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.dao.inv.InvBinDao;
import com.erp.dao.inv.InvWarehouseDao;
import com.erp.dao.inv.InvZoneDao;
import com.erp.dao.inv.InvZoneVersionDao;
import com.erp.entity.inv.InvBin;
import com.erp.entity.inv.InvWarehouse;
import com.erp.entity.inv.InvZone;
import com.erp.entity.inv.InvZoneVersion;
import com.erp.service.impl.inv.ZoneServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 区域规则单测（4.1.2，spec warehouse-zone-planning，任务 3.1/3.2/3.7）：
 * 编码唯一 409 / 编码锁定 422 / 停用仓禁建 422 / 版本快照 V1V2 / 乐观锁 409 / 权限 403。
 */
class ZoneRulesUnitTest {

    private InvZoneDao zoneDao;
    private InvZoneVersionDao versionDao;
    private InvBinDao binDao;
    private InvWarehouseDao warehouseDao;
    private ZoneServiceImpl service;

    @BeforeEach
    void loginAsWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "junit", "n/a", List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE"))));
        zoneDao = mock(InvZoneDao.class);
        versionDao = mock(InvZoneVersionDao.class);
        binDao = mock(InvBinDao.class);
        warehouseDao = mock(InvWarehouseDao.class);
        service = new ZoneServiceImpl(zoneDao, versionDao, binDao, warehouseDao, new ObjectMapper());

        InvWarehouse wh = new InvWarehouse();
        wh.setWhCode("WH-0003");
        wh.setWhName("启用仓");
        wh.setStatus("1");
        when(warehouseDao.selectOne(any())).thenReturn(wh);
        when(zoneDao.selectCount(any())).thenReturn(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        when(versionDao.selectCount(any())).thenReturn(0L, 1L);
        when(zoneDao.insert(any(InvZone.class))).thenAnswer(inv -> 1);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    private InvZone payload(String whCode, String zoneCode, String name) {
        InvZone z = new InvZone();
        z.setWhCode(whCode);
        z.setZoneCode(zoneCode);
        z.setZoneName(name);
        return z;
    }

    // ---- 3.1 区域档案维护 ----

    @Test
    void duplicateZoneCodeInSameWarehouse409() {
        when(zoneDao.selectCount(any())).thenReturn(1L);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(payload("WH-0003", "A", "重复区域")));
        assertEquals(409, ex.getCode());
        verify(zoneDao, never()).insert(any(InvZone.class));
    }

    @Test
    void zoneCodeLockedAfterCreate() {
        InvZone stored = payload("WH-0003", "A", "既有区域");
        stored.setId("zone-id");
        when(zoneDao.selectById("zone-id")).thenReturn(stored);
        InvZone edit = payload("WH-0003", "Z", "改码");
        edit.setId("zone-id");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.update(edit));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("不可修改"));
    }

    @Test
    void disabledWarehouseCannotCreateZone422() {
        InvWarehouse disabled = new InvWarehouse();
        disabled.setWhCode("WH-0009");
        disabled.setWhName("停用仓");
        disabled.setStatus("0");
        when(warehouseDao.selectOne(any())).thenReturn(disabled);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(payload("WH-0009", "A", "新区域")));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("停用"));
    }

    // ---- 3.2 版本快照与乐观锁 ----

    @Test
    void consecutiveUpdatesWriteV1AndV2Snapshots() {
        // 第一次 selectCount=0 → V1；第二次=1 → V2（setUp 的 thenReturn 链）
        InvZone stored = payload("WH-0003", "A", "区域A");
        stored.setId("zone-id");
        when(zoneDao.selectById("zone-id")).thenReturn(stored);
        when(zoneDao.updateById(any(InvZone.class))).thenReturn(1);

        InvZone edit1 = payload("WH-0003", "A", "改名一");
        edit1.setId("zone-id");
        service.update(edit1);
        InvZone edit2 = payload("WH-0003", "A", "改名二");
        edit2.setId("zone-id");
        service.update(edit2);

        verify(versionDao).insert(argWhereVersionNo(1));
        verify(versionDao).insert(argWhereVersionNo(2));
    }

    private InvZoneVersion argWhereVersionNo(int versionNo) {
        return org.mockito.ArgumentMatchers.argThat(v ->
                v instanceof InvZoneVersion && Integer.valueOf(versionNo).equals(v.getVersionNo()));
    }

    @Test
    void optimisticLockConflict409() {
        InvZone stored = payload("WH-0003", "A", "区域A");
        stored.setId("zone-id");
        when(zoneDao.selectById("zone-id")).thenReturn(stored);
        when(zoneDao.updateById(any(InvZone.class))).thenReturn(0); // 乐观锁落败
        InvZone edit = payload("WH-0003", "A", "并发改");
        edit.setId("zone-id");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.update(edit));
        assertEquals(409, ex.getCode());
        verify(versionDao, never()).insert(any(InvZoneVersion.class));
    }

    // ---- 3.7 权限 ----

    @Test
    void disableZoneDoesNotCascadeToBins() {
        InvZone stored = payload("WH-0003", "A", "区域A");
        stored.setId("zone-id");
        stored.setStatus("1");
        when(zoneDao.selectById("zone-id")).thenReturn(stored);
        when(zoneDao.updateById(any(InvZone.class))).thenReturn(1);

        service.disable("zone-id");

        // 只改区域状态，不触碰 bin 表（spec「仓位启停与约束」scenario）
        verify(zoneDao).updateById(argWhereStatus("0"));
        verify(binDao, never()).updateById(any());
        verify(binDao, never()).insert(any(InvBin.class));
    }

    private InvZone argWhereStatus(String status) {
        return org.mockito.ArgumentMatchers.argThat(z ->
                z instanceof InvZone && status.equals(z.getStatus()));
    }

    @Test
    void roleUserWriteForbidden403() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "junit", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(payload("WH-0003", "A", "越权区域")));
        assertEquals(403, ex.getCode());
        verify(zoneDao, never()).insert(any(InvZone.class));
    }

    @Test
    void adminWriteAllowed() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "junit", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        InvZone created = service.create(payload("WH-0003", "B", "管理员建区"));
        assertNotNull(created);
        verify(zoneDao).insert(any(InvZone.class));
    }

    @Test
    void unauthenticated401() {
        SecurityContextHolder.clearContext();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(payload("WH-0003", "A", "未登录")));
        assertEquals(401, ex.getCode());
    }
}
