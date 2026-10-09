package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemDictDao;
import com.erp.entity.mdm.MdmItemDict;
import com.erp.service.impl.inv.InvDictServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 仓库属性字典规则单测（4.1.3，spec warehouse-attribute-config，任务 4.1/4.2）：
 * 类型白名单 422 / 编码锁定 422 / 类型内唯一 409 / 停用条目退出启用查询 / 权限 403。
 */
class InvDictRulesUnitTest {

    private MdmItemDictDao dictDao;
    private InvDictServiceImpl service;

    @BeforeEach
    void loginAsWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "junit", "n/a", List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE"))));
        dictDao = mock(MdmItemDictDao.class);
        service = new InvDictServiceImpl(dictDao);
        when(dictDao.selectCount(any())).thenReturn(0L);
        when(dictDao.insert(any(MdmItemDict.class))).thenAnswer(inv -> 1);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    private MdmItemDict payload(String type, String code, String name) {
        MdmItemDict d = new MdmItemDict();
        d.setDictType(type);
        d.setDictCode(code);
        d.setDictName(name);
        return d;
    }

    @Test
    void whitelistRejectsForeignDictType422() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(payload("CUSTOMER_CHANNEL", "X", "跨域条目")));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("非仓库域"));
        verify(dictDao, never()).insert(any(MdmItemDict.class));
    }

    @Test
    void listRejectsForeignDictType422() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.list("UNIT", null));
        assertEquals(422, ex.getCode());
    }

    @Test
    void duplicateCodeInSameType409() {
        when(dictDao.selectCount(any())).thenReturn(1L);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(payload("TEMP_LEVEL", "AMBIENT", "重复码")));
        assertEquals(409, ex.getCode());
        verify(dictDao, never()).insert(any(MdmItemDict.class));
    }

    @Test
    void codeLockedAfterCreate() {
        MdmItemDict stored = payload("TEMP_LEVEL", "AMBIENT", "常温");
        stored.setId("dict-id");
        when(dictDao.selectById("dict-id")).thenReturn(stored);
        MdmItemDict edit = payload("TEMP_LEVEL", "AMB", "改码");
        edit.setId("dict-id");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.update(edit));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("不可修改"));
    }

    @Test
    void disableExitsActiveQuery() {
        MdmItemDict stored = payload("TEMP_LEVEL", "COOL", "阴凉");
        stored.setId("dict-id");
        stored.setStatus("1");
        when(dictDao.selectById("dict-id")).thenReturn(stored);
        service.disable("dict-id");
        // 停用后 selectByType（SQL 固定 STATUS='1'）不再返回该条目
        verify(dictDao).updateById(argWhereStatus("0"));
    }

    private MdmItemDict argWhereStatus(String status) {
        return org.mockito.ArgumentMatchers.argThat(d ->
                d instanceof MdmItemDict && status.equals(d.getStatus()));
    }

    @Test
    void activeQueryExcludesDisabled() {
        MdmItemDict on = payload("TEMP_LEVEL", "AMBIENT", "常温");
        on.setStatus("1");
        MdmItemDict off = payload("TEMP_LEVEL", "COOL", "阴凉");
        off.setStatus("0");
        when(dictDao.selectByType("TEMP_LEVEL")).thenReturn(List.of(on));
        List<MdmItemDict> actives = service.listActive("TEMP_LEVEL");
        assertTrue(actives.stream().allMatch(d -> "1".equals(d.getStatus())));
        assertFalse(actives.stream().anyMatch(d -> "COOL".equals(d.getDictCode())));
    }

    @Test
    void roleUserWriteForbidden403() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "junit", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.create(payload("TEMP_LEVEL", "NEW", "越权")));
        assertEquals(403, ex.getCode());
        verify(dictDao, never()).insert(any(MdmItemDict.class));
    }
}
