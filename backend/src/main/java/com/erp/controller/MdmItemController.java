package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mdm.MdmItemVersion;
import com.erp.service.mdm.MdmItemService;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** 物料主数据 + 分类/字典参照（/api/mdm/items、/api/mdm/item-categories、/api/mdm/item-dicts） */
@RestController
@RequestMapping("/api/mdm")
public class MdmItemController {

    private final MdmItemService service;

    public MdmItemController(MdmItemService service) {
        this.service = service;
    }

    @GetMapping("/items")
    public R<Page<MdmItem>> page(@RequestParam(defaultValue = "1") long current,
                                 @RequestParam(defaultValue = "10") long size,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) String categoryCode,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) String hasSubstitute) {
        return R.ok(service.page(current, size, keyword, categoryCode, status, hasSubstitute));
    }

    @GetMapping("/items/similar")
    public R<List<MdmItem>> similar(@RequestParam String itemName) {
        return R.ok(service.checkSimilar(itemName));
    }

    @GetMapping("/items/options")
    public R<List<Map<String, String>>> options() {
        return R.ok(service.options());
    }

    @GetMapping("/items/{id}")
    public R<MdmItem> getOne(@PathVariable String id) {
        return R.ok(service.getById(id));
    }

    /** forceCreate=true + dupNote：查重命中时确认非重复继续（唯一性阻断不放行） */
    @PostMapping("/items")
    public R<MdmItem> create(@RequestBody MdmItem item,
                             @RequestParam(defaultValue = "false") boolean forceCreate) {
        return R.ok(service.create(item, forceCreate));
    }

    @PutMapping("/items")
    public R<MdmItem> update(@RequestBody MdmItem item) {
        return R.ok(service.update(item));
    }

    /** 停用（原因必填；替代引用 409 阻断） */
    @PutMapping("/items/{id}/disable")
    public R<Void> disable(@PathVariable String id, @RequestParam(required = false) String reason) {
        service.disable(id, reason);
        return R.ok();
    }

    /** 启用回退（仅停用态；替代指向目标失效 422） */
    @PutMapping("/items/{id}/enable")
    public R<Void> enable(@PathVariable String id, @RequestParam(required = false) String reason) {
        service.enable(id, reason);
        return R.ok();
    }

    /** 标记式归档（仅停用态无引用；归档为终态） */
    @PutMapping("/items/{id}/archive")
    public R<Void> archive(@PathVariable String id, @RequestParam(required = false) String reason) {
        service.archive(id, reason);
        return R.ok();
    }

    /** 影响分析（行 513）：替代引用真实清单 + 下游桩明示 */
    @GetMapping("/items/{id}/impact")
    public R<Map<String, Object>> impact(@PathVariable String id) {
        return R.ok(service.impact(id));
    }

    /** 批量停用：逐条结果分组 succeeded/failed，单条失败不回滚其余 */
    @PostMapping("/items/disable-batch")
    public R<Map<String, Object>> disableBatch(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("ids");
        Object reason = body.get("reason");
        if (ids == null || ids.isEmpty()) {
            throw new com.erp.common.ServiceException(422, "请选择要停用的物料");
        }
        Map<String, Object> result = service.disableBatch(ids, reason == null ? "" : String.valueOf(reason));
        return R.ok(result);
    }

    @GetMapping("/items/{id}/versions")
    public R<List<MdmItemVersion>> versions(@PathVariable String id) {
        return R.ok(service.versions(id));
    }

    @GetMapping("/items/{id}/diff")
    public R<Map<String, Object>> diff(@PathVariable String id,
                                       @RequestParam int from,
                                       @RequestParam int to) {
        return R.ok(service.diff(id, from, to));
    }

    /** 替代关系列表（direction: source 正向 / target 反查） */
    @GetMapping("/items/substitutes")
    public R<Map<String, Object>> substitutes(@RequestParam(defaultValue = "") String keyword,
                                              @RequestParam(defaultValue = "source") String direction,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "10") long size) {
        return R.ok(service.substituteList(keyword, direction, status, current, size));
    }

    /** 设置替代（已发布 + 非自身 + 间接环三校验） */
    @PutMapping("/items/{id}/substitute")
    public R<MdmItem> setSubstitute(@PathVariable String id,
                                    @RequestParam String substituteCode) {
        return R.ok(service.setSubstitute(id, substituteCode));
    }

    @DeleteMapping("/items/{id}/substitute")
    public R<MdmItem> clearSubstitute(@PathVariable String id) {
        return R.ok(service.clearSubstitute(id));
    }

    /** 替代三校验试算（dry-run，表单失焦实时提示） */
    @GetMapping("/items/substitute-check")
    public R<Void> checkSubstitute(@RequestParam String itemCode,
                                   @RequestParam String substituteCode) {
        service.checkSubstitute(itemCode, substituteCode);
        return R.ok();
    }

    @GetMapping("/item-categories")
    public R<List<Map<String, String>>> categories() {
        return R.ok(service.categories());
    }

    @GetMapping("/item-dicts")
    public R<List<Map<String, String>>> dicts(@RequestParam String type) {
        return R.ok(service.dicts(type));
    }
}
