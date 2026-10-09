package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.dao.inv.InvBinDao;
import com.erp.dao.inv.InvZoneDao;
import com.erp.entity.inv.InvBin;
import com.erp.entity.inv.InvZone;
import com.erp.service.impl.inv.BinServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 仓位规则单测（4.1.2，spec warehouse-zone-planning，任务 3.3~3.7，design D2/D3/D4）：
 * 编号等宽补零字典序 / 自定义编号忽略 / 属性继承覆盖 / 停用区域 422 /
 * 批量冲突整体拒绝 / 超上限 422 / 权限。
 */
class BinRulesUnitTest {

    private InvBinDao binDao;
    private InvZoneDao zoneDao;
    private BinServiceImpl service;
    private InvZone activeZone;

    @BeforeEach
    void loginAsWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "junit", "n/a", List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE"))));
        binDao = mock(InvBinDao.class);
        zoneDao = mock(InvZoneDao.class);
        service = new BinServiceImpl(binDao, zoneDao);
        ReflectionTestUtils.setField(service, "batchMax", 5000);

        activeZone = new InvZone();
        activeZone.setWhCode("WH-0003");
        activeZone.setZoneCode("A");
        activeZone.setZoneName("常温存储区");
        activeZone.setStatus("1");
        activeZone.setDefBinType("STORE");
        activeZone.setDefTempLevel("AMBIENT");
        activeZone.setDefCapacityPallet(2);
        when(zoneDao.selectOne(any())).thenReturn(activeZone);
        when(binDao.insert(any(InvBin.class))).thenAnswer(inv -> 1);
        when(binDao.selectExistingCodes(anyList())).thenReturn(List.of());
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    private InvBin payload(int seq, int col, int layer) {
        InvBin b = new InvBin();
        b.setWhCode("WH-0003");
        b.setZoneCode("A");
        b.setBinSeq(seq);
        b.setColNo(col);
        b.setLayerNo(layer);
        return b;
    }

    private Map<String, Object> batch(int s1, int s2, int c1, int c2, int l1, int l2) {
        Map<String, Object> p = new HashMap<>();
        p.put("whCode", "WH-0003");
        p.put("zoneCode", "A");
        p.put("seqStart", s1);
        p.put("seqEnd", s2);
        p.put("colStart", c1);
        p.put("colEnd", c2);
        p.put("layerStart", l1);
        p.put("layerEnd", l2);
        return p;
    }

    // ---- 3.3 编号生成 ----

    @Test
    void binCodeZeroPaddedForLexicographicOrder() {
        InvBin low = service.create(payload(2, 1, 1));
        InvBin high = service.create(payload(10, 1, 1));
        assertEquals("A-02-01-01", low.getBinCode());
        assertEquals("A-10-01-01", high.getBinCode());
        // 字典序 = 物理行走序（排 02 必须排在排 10 前）
        assertTrue(low.getBinCode().compareTo(high.getBinCode()) < 0);
    }

    @Test
    void clientProvidedBinCodeIgnored() {
        InvBin b = payload(1, 1, 1);
        b.setBinCode("HACKED-CODE"); // 客户端伪造编号
        InvBin created = service.create(b);
        assertEquals("A-01-01-01", created.getBinCode());
        assertNotEquals("HACKED-CODE", created.getBinCode());
    }

    // ---- 3.4 属性继承与覆盖 ----

    @Test
    void newBinInheritsZoneDefaults() {
        InvBin created = service.create(payload(1, 1, 1));
        assertEquals("STORE", created.getBinType());
        assertEquals("AMBIENT", created.getTempLevel());
        assertEquals(Integer.valueOf(2), created.getCapacityPallet());
    }

    @Test
    void overrideBeatsZoneDefault() {
        InvBin b = payload(1, 1, 1);
        b.setTempLevel("FROZEN"); // 单独覆盖
        b.setCapacityPallet(99);
        InvBin created = service.create(b);
        assertEquals("FROZEN", created.getTempLevel());
        assertEquals(Integer.valueOf(99), created.getCapacityPallet());
        assertEquals("STORE", created.getBinType()); // 未覆盖项仍继承
    }

    @Test
    void disabledZoneRejectsCreate422() {
        activeZone.setStatus("0");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(payload(1, 1, 1)));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("停用"));
        verify(binDao, never()).insert(any(InvBin.class));
    }

    @Test
    void binCodeLockedAfterCreate() {
        InvBin stored = payload(1, 1, 1);
        stored.setId("bin-id");
        stored.setBinCode("A-01-01-01");
        when(binDao.selectById("bin-id")).thenReturn(stored);
        InvBin edit = payload(2, 2, 2);
        edit.setId("bin-id");
        edit.setBinCode("A-02-02-02"); // 尝试改码
        ServiceException ex = assertThrows(ServiceException.class, () -> service.update(edit));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("不可修改"));
    }

    // ---- 3.5 批量规划 ----

    @Test
    void batchGeneratesCartesianProduct() {
        Map<String, Object> result = service.batchCreate(batch(1, 4, 1, 6, 1, 2));
        assertEquals(48, result.get("created"));
        assertEquals("A-01-01-01", result.get("from"));
        assertEquals("A-04-06-02", result.get("to"));
        verify(binDao, org.mockito.Mockito.times(48)).insert(any(InvBin.class));
    }

    @Test
    void conflictRejectsWholeBatchWithZeroInsert() {
        when(binDao.selectExistingCodes(anyList()))
                .thenReturn(List.of("A-02-01-01", "A-03-05-02"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.batchCreate(batch(1, 4, 1, 6, 1, 2)));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("A-02-01-01"));
        assertTrue(ex.getMessage().contains("A-03-05-02"));
        verify(binDao, never()).insert(any(InvBin.class)); // 整体拒绝，0 插入
    }

    @Test
    void duplicateKeyMidwayRollsBackWholeBatch() {
        // 预检通过（无冲突），但第 3 条插入时唯一键撞并发 → 包装 409（事务回滚）
        when(binDao.insert(any(InvBin.class)))
                .thenAnswer(inv -> 1)
                .thenAnswer(inv -> 1)
                .thenAnswer(inv -> {
                    throw new org.springframework.dao.DuplicateKeyException("UK_INV_BIN_CODE");
                });
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.batchCreate(batch(1, 2, 1, 2, 1, 1))); // 4 条，第 3 条撞
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("回滚"));
    }

    @Test
    void overBatchLimitRejected422() {
        ReflectionTestUtils.setField(service, "batchMax", 10);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.batchCreate(batch(1, 4, 1, 6, 1, 2))); // 48 > 10
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("上限"));
        verify(binDao, never()).insert(any(InvBin.class));
    }

    @Test
    void resubmitSameRangeRejectedAsConflict() {
        // 首次成功后重复提交同区间 → 预检命中全部冲突（幂等拒绝而非追加）
        when(binDao.selectExistingCodes(anyList()))
                .thenAnswer(inv -> inv.getArgument(0)); // 全部已存在
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.batchCreate(batch(1, 2, 1, 2, 1, 1)));
        assertEquals(409, ex.getCode());
        verify(binDao, never()).insert(any(InvBin.class));
    }

    @Test
    void invalidRangeRejected422() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.batchCreate(batch(4, 1, 1, 6, 1, 2))); // 起 > 止
        assertEquals(422, ex.getCode());
    }

    // ---- 3.6 查询 ----

    @Test
    void queryCarriesZoneNameAndDefault() {
        InvBin bin = payload(1, 1, 1);
        bin.setBinCode("A-01-01-01");
        when(binDao.selectList(any())).thenReturn(List.of(bin));
        when(zoneDao.selectList(any())).thenReturn(List.of(activeZone));

        List<Map<String, Object>> rows = service.query("WH-0003", "A", null, null);
        assertEquals(1, rows.size());
        assertEquals("常温存储区", rows.get(0).get("zoneName"));
        assertNotNull(rows.get(0).get("zoneDefault"));
    }

    // ---- 3.7 权限 ----

    @Test
    void roleUserBatchForbidden403() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "junit", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.batchCreate(batch(1, 2, 1, 2, 1, 1)));
        assertEquals(403, ex.getCode());
        verify(binDao, never()).insert(any(InvBin.class));
    }
}
