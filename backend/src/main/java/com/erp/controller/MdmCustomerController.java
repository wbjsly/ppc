package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.mdm.MdmCustomerGroup;
import com.erp.entity.mdm.MdmCustomerGroupVersion;
import com.erp.entity.mdm.MdmCustomerView;
import com.erp.entity.mdm.MdmCustomerViewVersion;
import com.erp.service.mdm.MdmCustomerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 客户准入（客户管理 1.3.1）：集团/法人视图 + 状态机 + 合并（/api/mdm/customer-groups、/api/mdm/customer-views） */
@RestController
@RequestMapping("/api/mdm")
public class MdmCustomerController {

    private final MdmCustomerService service;

    public MdmCustomerController(MdmCustomerService service) {
        this.service = service;
    }

    // ---------- 集团视图 ----------

    @GetMapping("/customer-groups")
    public R<Page<MdmCustomerGroup>> groupPage(@RequestParam(defaultValue = "1") long current,
                                               @RequestParam(defaultValue = "10") long size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status) {
        return R.ok(service.groupPage(current, size, keyword, status));
    }

    @GetMapping("/customer-groups/{id}")
    public R<MdmCustomerGroup> getGroup(@PathVariable String id) {
        return R.ok(service.getGroup(id));
    }

    @PostMapping("/customer-groups")
    public R<MdmCustomerGroup> createGroup(@RequestBody MdmCustomerGroup group,
                                           @RequestParam(defaultValue = "false") boolean forceCreate) {
        return R.ok(service.createGroup(group, forceCreate));
    }

    @PutMapping("/customer-groups")
    public R<MdmCustomerGroup> updateGroup(@RequestBody MdmCustomerGroup group) {
        return R.ok(service.updateGroup(group));
    }

    @GetMapping("/customer-groups/{id}/versions")
    public R<List<MdmCustomerGroupVersion>> groupVersions(@PathVariable String id) {
        return R.ok(service.groupVersions(id));
    }

    @GetMapping("/customer-groups/{id}/diff")
    public R<Map<String, Object>> groupDiff(@PathVariable String id,
                                            @RequestParam int from, @RequestParam int to) {
        return R.ok(service.groupDiff(id, from, to));
    }

    // ---------- 状态机 ----------

    /** toStatus: 1 启用 / 0 停用 */
    @PutMapping("/customer-groups/{id}/status")
    public R<Void> changeStatus(@PathVariable String id,
                                @RequestParam String toStatus,
                                @RequestParam(required = false) String reason) {
        service.changeGroupStatus(id, toStatus, reason);
        return R.ok();
    }

    @PutMapping("/customer-groups/{id}/freeze")
    public R<Void> freeze(@PathVariable String id, @RequestParam(required = false) String reason) {
        service.freezeGroup(id, reason);
        return R.ok();
    }

    @PutMapping("/customer-groups/{id}/unfreeze")
    public R<Void> unfreeze(@PathVariable String id, @RequestParam(required = false) String reason) {
        service.unfreezeGroup(id, reason);
        return R.ok();
    }

    @GetMapping("/customer-groups/{id}/impact")
    public R<Map<String, Object>> impact(@PathVariable String id) {
        return R.ok(service.impact(id));
    }

    // ---------- 合并 ----------

    @GetMapping("/customer-groups/merge-candidates")
    public R<List<MdmCustomerGroup>> mergeCandidates(@RequestParam(defaultValue = "") String keyword,
                                                     @RequestParam(required = false) String excludeId) {
        return R.ok(service.mergeCandidates(keyword, excludeId));
    }

    @PostMapping("/customer-groups/merge")
    public R<Void> merge(@RequestBody Map<String, String> body) {
        service.merge(body.get("sourceId"), body.get("targetId"), body.get("reason"));
        return R.ok();
    }

    // ---------- 法人视图 ----------

    @GetMapping("/customer-views")
    public R<List<MdmCustomerView>> viewsByGroup(@RequestParam String groupId) {
        return R.ok(service.viewsByGroup(groupId));
    }

    @PostMapping("/customer-views")
    public R<MdmCustomerView> saveView(@RequestBody MdmCustomerView view) {
        return R.ok(service.saveView(view));
    }

    @GetMapping("/customer-views/{id}/versions")
    public R<List<MdmCustomerViewVersion>> viewVersions(@PathVariable String id) {
        return R.ok(service.viewVersions(id));
    }
}
