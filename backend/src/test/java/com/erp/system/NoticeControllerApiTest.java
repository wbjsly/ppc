package com.erp.system;

import com.erp.entity.system.SysNotice;
import com.erp.service.system.NoticeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.common.ServiceException;

/**
 * 站内通知中心（change add-kitting-check 任务 3.1，spec notification-center 三需求）：
 * 端点自 OpportunityController 正名迁出至 /api/notices；服务层语义 =
 * 角色聚合可见 / 未读幂等 / 越权标记已读 403、不存在 404 / 未登录 401。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NoticeControllerApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired
    private NoticeService noticeService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void login() {
        SystemAuth.login("notice-tester", "ROLE_PLANNER");
    }

    @AfterEach
    void cleanup() {
        SystemAuth.logout();
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'TEST_NOTICE%'");
    }

    @Test
    void allEndpointsRegisteredAtNewPath() {
        Set<String> paths = new HashSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            PathPatternsRequestCondition pc = e.getKey().getPathPatternsCondition();
            if (pc != null) {
                pc.getPatterns().forEach(p -> {
                    if (p.getPatternString().startsWith("/api/notices")) {
                        paths.add(p.getPatternString());
                    }
                });
            }
        }
        assertTrue(paths.containsAll(List.of(
                "/api/notices", "/api/notices/unread-count", "/api/notices/{id}/read"
        )), "三个端点在新路径注册，实际=" + paths);
        // 旧路径已迁出（OpportunityController 不再暴露 /api/crm/notices）
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            PathPatternsRequestCondition pc = e.getKey().getPathPatternsCondition();
            if (pc != null) {
                pc.getPatterns().forEach(p ->
                        assertFalse(p.getPatternString().startsWith("/api/crm/notices"),
                                "旧端点 /api/crm/notices 应已删除"));
            }
        }
    }

    @Test
    void unauthenticatedReturns401() throws Exception {
        SystemAuth.logout();
        mockMvc.perform(get("/api/notices")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notices/unread-count")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/notices/any-id/read")).andExpect(status().isUnauthorized());
    }

    /** 角色聚合可见（spec：按投递角色返回，未读优先） */
    @Test
    void roleAggregatedVisibility() {
        noticeService.push("ROLE_PLANNER", null, "测试通知-计划员", "内容",
                "TEST_NOTICE_A", "biz-1");
        List<SysNotice> mine = noticeService.myNotices();
        assertTrue(mine.stream().anyMatch(n -> "测试通知-计划员".equals(n.getTitle())),
                "计划员可见投递至本角色的通知");
        assertTrue(mine.stream().noneMatch(n -> "测试通知-他角色".equals(n.getTitle())),
                "不可见他角色通知");
        // 目标角色是 PLANNER，同角色 push 应可见；换用户角色不可见场景由 targetUser 覆盖
        noticeService.push(null, "other-user", "测试通知-他角色", "内容",
                "TEST_NOTICE_B", "biz-2");
        List<SysNotice> after = noticeService.myNotices();
        assertFalse(after.stream().anyMatch(n -> "测试通知-他角色".equals(n.getTitle())),
                "定向他人通知对当前用户不可见");
        long unread = noticeService.unreadCount();
        assertTrue(unread >= 1, "未读数至少 1");
    }

    /** 越权标记已读 403 / 不存在 404（spec：仅可标记本人可见通知） */
    @Test
    void markReadVisibilityGuard() {
        SysNotice other = noticeService.push(null, "another-user", "测试通知-越权", "内容",
                "TEST_NOTICE_C", "biz-3");
        assertNotNull(other, "推送成功");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> noticeService.markRead(other.getId()));
        assertEquals(403, ex.getCode(), "越权标记已读 403");
        ServiceException nf = assertThrows(ServiceException.class,
                () -> noticeService.markRead("nonexistent-notice-id"));
        assertEquals(404, nf.getCode(), "不存在 404");
        // 可见通知标记成功且幂等
        SysNotice mine = noticeService.push("ROLE_PLANNER", null, "测试通知-本人", "内容",
                "TEST_NOTICE_D", "biz-4");
        noticeService.markRead(mine.getId());
        noticeService.markRead(mine.getId()); // 二次幂等不抛
        assertEquals(0L, noticeService.myNotices().stream()
                .filter(n -> mine.getId().equals(n.getId()) && "0".equals(n.getReadFlag())).count(),
                "已读后 ReadFlag=1");
    }
}
