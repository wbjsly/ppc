package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.system.SysNotice;
import com.erp.service.system.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站内通知中心接口（change add-kitting-check design D4：端点自 OpportunityController 正名迁出）。
 * 服务层零改动——逻辑全部委托既有 NoticeService（按角色投递、未读幂等、可见性校验）；
 * 铃铛角标/下拉/通知中心页消费本控制器，open-api-gateway 未开放通知类目（无外部消费方）。
 */
@Tag(name = "站内通知")
@RestController
@RequestMapping("/api/notices")
public class NoticeController {

    private final NoticeService noticeService;

    public NoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    @GetMapping
    @Operation(summary = "我的通知", description = "按角色聚合，未读在前、时间倒序，上限 100 条")
    public R<List<SysNotice>> myNotices() {
        return R.ok(noticeService.myNotices());
    }

    @GetMapping("/unread-count")
    @Operation(summary = "未读数", description = "铃铛角标")
    public R<Long> unreadCount() {
        return R.ok(noticeService.unreadCount());
    }

    @PutMapping("/{id}/read")
    @Operation(summary = "标记已读", description = "仅可标记本人可见的通知（越权 403、不存在 404）")
    public R<Void> markRead(@PathVariable String id) {
        noticeService.markRead(id);
        return R.ok();
    }
}
