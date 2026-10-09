package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.dao.inv.InvDocTypeDao;
import com.erp.entity.inv.InvDocType;
import com.erp.service.impl.inv.StockDocTypeServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 出入库类型配置规则单测（spec stock-doc-type，任务 2.1）：
 * 字段/取值域 422、TYPE_CODE 唯一 409 与创建后锁定 422、ADMIN 权限 403。
 */
class DocTypeRulesUnitTest {

    private StockDocTypeServiceImpl service;
    private InvDocTypeDao dao;

    @BeforeEach
    void setUp() {
        dao = mock(InvDocTypeDao.class);
        service = new StockDocTypeServiceImpl(dao);
        when(dao.insert(any(InvDocType.class))).thenAnswer(inv -> 1);
        when(dao.updateById(any(InvDocType.class))).thenReturn(1);
        login("admin1", "ROLE_ADMIN");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login(String user, String... roles) {
        var authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", authorities));
    }

    private InvDocType req(String code, String dir, String name, String alloc) {
        InvDocType t = new InvDocType();
        t.setTypeCode(code);
        t.setDirection(dir);
        t.setTypeName(name);
        t.setDefaultAlloc(alloc);
        return t;
    }

    @Test
    void createValidations() {
        // 字段缺失/取值域外 422
        assertThrows(ServiceException.class, () -> service.create(req(null, "IN", "n", "MANUAL")));
        assertThrows(ServiceException.class, () -> service.create(req("GOOD_CODE", "OTHER", "n", "MANUAL")));
        assertThrows(ServiceException.class, () -> service.create(req("GOOD_CODE", "IN", " ", "MANUAL")));
        assertThrows(ServiceException.class, () -> service.create(req("GOOD_CODE", "IN", "n", "WHATEVER")));
        // 类型码格式 422
        assertThrows(ServiceException.class, () -> service.create(req("bad code", "IN", "n", "MANUAL")));
        verify(dao, never()).insert(any(InvDocType.class));
    }

    @Test
    void duplicateCodeConflict() {
        when(dao.selectByCode("EXISTING")).thenReturn(req("EXISTING", "IN", "已有", "MANUAL"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(req("existing", "IN", "n", "MANUAL")));   // 小写归一后冲突
        assertEquals(409, ex.getCode());
    }

    @Test
    void typeCodeLockedOnUpdate() {
        InvDocType stored = req("PURCHASE_IN", "IN", "采购入库", "MANUAL");
        stored.setId("dt-1");
        when(dao.selectById("dt-1")).thenReturn(stored);
        InvDocType req = req("OTHER_CODE", "IN", "改名", "MANUAL");
        req.setId("dt-1");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.update(req));
        assertEquals(422, ex.getCode());
    }

    @Test
    void adminOnlyWrites() {
        login("wh1", "ROLE_WAREHOUSE");
        assertThrows(ServiceException.class, () -> service.create(req("X_CODE", "IN", "n", "MANUAL")));
        assertThrows(ServiceException.class, () -> service.setEnabled("dt-1", false));
        InvDocType upd = req("PURCHASE_IN", "IN", "n", "MANUAL");
        upd.setId("dt-1");
        assertThrows(ServiceException.class, () -> service.update(upd));
        verify(dao, never()).insert(any(InvDocType.class));
        verify(dao, never()).updateById(any(InvDocType.class));
    }

    @Test
    void enableDisableRoundtrip() {
        InvDocType stored = req("MATERIAL_OUT", "OUT", "领料出库", "AUTO_FIFO");
        stored.setId("dt-2");
        stored.setEnabled(1);
        when(dao.selectById("dt-2")).thenReturn(stored);
        service.setEnabled("dt-2", false);
        assertEquals(0, stored.getEnabled());
        verify(dao).updateById(stored);
        service.setEnabled("dt-2", true);
        assertEquals(1, stored.getEnabled());
    }
}
