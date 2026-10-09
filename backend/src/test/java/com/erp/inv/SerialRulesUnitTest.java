package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.dao.inv.InvSerialDao;
import com.erp.dao.inv.InvSerialLogDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.entity.inv.InvSerial;
import com.erp.entity.inv.InvSerialLog;
import com.erp.entity.mdm.MdmItem;
import com.erp.service.impl.inv.SerialServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 序列台账规则单测（4.2.2，spec serial-master，任务 4.2~4.5，design D3/D4）：
 * 全局唯一 409、编号锁定 422、状态机合法边/终态/跳态/解冻原因、流转留痕、判重三态、权限。
 */
class SerialRulesUnitTest {

    private InvSerialDao serialDao;
    private InvSerialLogDao logDao;
    private MdmItemDao itemDao;
    private SerialServiceImpl service;

    @BeforeEach
    void loginAsWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "junit", "n/a", List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE"))));
        serialDao = mock(InvSerialDao.class);
        logDao = mock(InvSerialLogDao.class);
        itemDao = mock(MdmItemDao.class);
        service = new SerialServiceImpl(serialDao, logDao, itemDao);
        when(serialDao.insert(any(InvSerial.class))).thenAnswer(inv -> 1);
        when(serialDao.updateById(any(InvSerial.class))).thenAnswer(inv -> 1);
        when(logDao.insert(any(InvSerialLog.class))).thenAnswer(inv -> 1);
        when(serialDao.selectCount(any())).thenReturn(0L);
        MdmItem item = new MdmItem();
        item.setItemCode("IT-001");
        item.setItemName("高值设备");
        when(itemDao.selectOne(any())).thenReturn(item);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    private InvSerial storedSerial(String id, String status) {
        InvSerial s = new InvSerial();
        s.setId(id);
        s.setSerialNo("SN0001");
        s.setItemCode("IT-001");
        s.setStatus(status);
        when(serialDao.selectById(id)).thenReturn(s);
        return s;
    }

    private InvSerial payload(String serialNo) {
        InvSerial s = new InvSerial();
        s.setSerialNo(serialNo);
        s.setItemCode("IT-001");
        return s;
    }

    // ---- 4.2 新建与锁定 ----

    @Test
    void duplicateSerialNoGlobally409() {
        when(serialDao.selectCount(any())).thenReturn(1L);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(payload("SN0001")));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("串码"));
        verify(serialDao, never()).insert(any(InvSerial.class));
    }

    @Test
    void serialCreatedInStockWithLog() {
        InvSerial created = service.create(payload("SN0001"));
        assertEquals(InvSerial.ST_IN_STOCK, created.getStatus());
        ArgumentCaptor<InvSerialLog> cap = ArgumentCaptor.forClass(InvSerialLog.class);
        verify(logDao).insert(cap.capture());
        assertEquals("建档登记", cap.getValue().getReason());
    }

    @Test
    void serialNoLockedAfterCreate() {
        storedSerial("sid", InvSerial.ST_IN_STOCK);
        InvSerial edit = payload("SN-HACKED");
        edit.setId("sid");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.update(edit));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("不可修改"));
    }

    // ---- 4.3 状态机 ----

    @Test
    void freezeThenUnfreezeLegalPath() {
        storedSerial("sid", InvSerial.ST_IN_STOCK);
        service.transition("sid", InvSerial.ST_FROZEN, "外观异常", null);
        // 第二次流转：重新 selectById 返回同对象（status 已被改为 FROZEN）
        InvSerial after = service.transition("sid", InvSerial.ST_IN_STOCK, "质量放行", null);
        assertEquals(InvSerial.ST_IN_STOCK, after.getStatus());
        verify(logDao, times(2)).insert(any(InvSerialLog.class));
    }

    @Test
    void shipAndScrapLegalFromInStock() {
        storedSerial("sid", InvSerial.ST_IN_STOCK);
        assertEquals(InvSerial.ST_OUT,
                service.transition("sid", InvSerial.ST_OUT, null, "客户A").getStatus());
        verify(logDao, times(1)).insert(any(InvSerialLog.class)); // 本例仅 1 次流转留痕
    }

    @Test
    void terminalStateNoExit422() {
        storedSerial("sid", InvSerial.ST_OUT);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.transition("sid", InvSerial.ST_IN_STOCK, "退货回库", null));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("终态"));
    }

    @Test
    void scrapThenUnfreezeRejected422() {
        storedSerial("sid", InvSerial.ST_SCRAPPED);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.transition("sid", InvSerial.ST_FROZEN, null, null));
        assertEquals(422, ex.getCode()); // 终态无出边
    }

    @Test
    void jumpStateRejected422() {
        storedSerial("sid", InvSerial.ST_FROZEN);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.transition("sid", InvSerial.ST_OUT, "直接出库", null));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("非法状态流转"));
    }

    @Test
    void unfreezeWithoutReason422() {
        storedSerial("sid", InvSerial.ST_FROZEN);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.transition("sid", InvSerial.ST_IN_STOCK, null, null));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("解冻"));
    }

    @Test
    void transitionLogCarriesFromTo() {
        storedSerial("sid", InvSerial.ST_IN_STOCK);
        service.transition("sid", InvSerial.ST_SCRAPPED, "物理损毁", null);
        ArgumentCaptor<InvSerialLog> cap = ArgumentCaptor.forClass(InvSerialLog.class);
        verify(logDao).insert(cap.capture());
        InvSerialLog l = cap.getValue();
        assertEquals(InvSerial.ST_IN_STOCK, l.getFromStatus());
        assertEquals(InvSerial.ST_SCRAPPED, l.getToStatus());
        assertEquals("物理损毁", l.getReason());
        assertEquals("SN0001", l.getSerialNo());
    }

    @Test
    void concurrentTransition409() {
        storedSerial("sid", InvSerial.ST_IN_STOCK);
        when(serialDao.updateById(any(InvSerial.class))).thenReturn(0); // 乐观锁落败
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.transition("sid", InvSerial.ST_OUT, null, null));
        assertEquals(409, ex.getCode());
        verify(logDao, never()).insert(any(InvSerialLog.class));
    }

    // ---- 4.4 判重查询（三态只读） ----

    @Test
    void checkHitsInStock() {
        InvSerial s = storedSerial("sid", InvSerial.ST_IN_STOCK);
        when(serialDao.selectOne(any())).thenReturn(s);
        var r = service.check("IT-001", "SN0001");
        assertEquals(Boolean.TRUE, r.get("exists"));
        assertEquals(InvSerial.ST_IN_STOCK, r.get("status"));
        verify(logDao, never()).insert(any(InvSerialLog.class));
        verify(serialDao, never()).insert(any(InvSerial.class));
        verify(serialDao, never()).updateById(any(InvSerial.class));
    }

    @Test
    void checkHitsHistory() {
        InvSerial s = storedSerial("sid", InvSerial.ST_OUT);
        when(serialDao.selectOne(any())).thenReturn(s);
        var r = service.check(null, "SN0001");
        assertEquals(Boolean.TRUE, r.get("exists"));
        assertEquals(InvSerial.ST_OUT, r.get("status"));
        assertFalse((Boolean) r.getOrDefault("itemMismatch", false));
    }

    @Test
    void checkMissReturnsNotExists() {
        when(serialDao.selectOne(any())).thenReturn(null);
        var r = service.check("IT-001", "SN-NEW");
        assertEquals(Boolean.FALSE, r.get("exists"));
        verify(serialDao, never()).insert(any(InvSerial.class));
    }

    // ---- 4.5 权限 ----

    @Test
    void roleUserWriteForbidden403() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "junit", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(payload("SN0001")));
        assertEquals(403, ex.getCode());
        verify(serialDao, never()).insert(any(InvSerial.class));
    }

    @Test
    void checkOnlyNeedsAuthentication() {
        // 判重不走 requireRole：ROLE_USER 可调用（D4 供写入点复用）
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "junit", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(serialDao.selectOne(any())).thenReturn(null);
        assertNotNull(service.check("IT-001", "SN-ANY"));
    }
}
