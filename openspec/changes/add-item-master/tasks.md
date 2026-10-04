# Tasks

## 1. 后端数据层

- [x] 1.1 迁移 `015-mdm-item-category.sql`：`erp_mdm_item_category`（`CATEGORY_CODE` 4位唯一、`ITEM_PREFIX`、`PARENT_ID`/`LEVEL`）+ `erp_mdm_item_dict`（`DICT_TYPE`+`DICT_CODE` 唯一，种子：单位/物料组/存储条件）；三大类种子 FG0001/RM0001/WIP0001 + 示例二级分类；重启验证 marker 015 与种子数据
- [x] 1.2 迁移 `016-mdm-item.sql`：`erp_mdm_item`（`ITEM_CODE` 唯一、分类/单位/物料组/采购类型/存储条件、选填4项、差异化列 `SHELF_LIFE_DAYS`/`PACKING_SPEC`/`BARCODE`、BaseEntity）+ `erp_mdm_item_version`（`ENTITY_ID+VERSION_NO` 唯一）+ 示例物料 `RM0001000001`；重启验证两表与种子
- [x] 1.3 实体 `MdmItem`/`MdmItemVersion`/`MdmItemCategory`/`MdmItemDict` + DAO ×4（含 `selectMaxCodeByCategory`、分类/字典查询）；编译通过（JDK 17）

## 2. 后端业务层

- [x] 2.1 Service 接口 + Impl：page/create/update/disable/options/categories/dict/versions/diff；**三段式编码生成**（前缀+4位分类码+6位流水按分类独立、唯一索引冲突重试）+ 手输格式正则与前缀-分类一致校验（L1 + 近3条相似）
- [x] 2.2 校验组：必填、参照完整性（分类/单位/物料组/存储条件存在）、名称+规格 **Levenshtein ≤3 查重**（近3条 + `forceCreate`/`dupNote` 绕过）、批次保质期必填（BR-4.1-08）、自制 BOM L4 提示（BR-4.1-09）、特殊字符过滤（BR-4.1-10）、按分类校验差异化字段必填
- [x] 2.3 发布与变更：立即 Active + V1 快照；变更非编码字段 + 乐观锁 + V(N+1) 快照 diff；停用软删 + 下游引用桩 TODO + `MDM.ITEM.PUBLISHED` 事件桩 TODO；编码不可改（BR-4.1-13）
- [x] 2.4 Controller `/api/mdm/items` + `/api/mdm/item-categories` + `/api/mdm/item-dicts` + `SecurityConfig` 写接口 ADMIN；编译打包通过
- [x] 2.5 接口冒烟：自动编码 `RM0001000001` 递增 → 手输错前缀 422+近3条 → 格式非法 422 → 名称相似 409 + forceCreate 放行 → 批次缺保质期 422 → 自制无 BOM 200+提示 → 参照无效 422 → 编码不可改 422 → 变更 verNo 递增+V2 → 旧 verNo 409 → 停用 200 → options/分类/字典接口校验 → 非法类型 422

## 3. 前端

- [x] 3.1 API 层 `api/mdm/item.js`；`views/mdm/item/index.vue`：关键字/分类/状态筛选列表（分页）+ 新建/变更弹窗（必填7+选填4、分类联动前缀提示与**差异化字段组**、自动/手输编码切换、查重近3条确认非重复弹窗）+ 详情抽屉 + 版本历史与逐字段对比 + 停用确认
- [x] 3.2 `router/index.js` 注册 `/m/1.2.1` → `views/mdm/item/index.vue`

## 4. 端到端验证

- [x] 4.1 浏览器全流程：新建（自动编码+差异化字段切换+查重确认）→ 列表筛选 → 详情版本对比 → 变更 V2 → 停用阻断回显；分类/字典下拉数据正确
- [x] 4.2 权限：`ROLE_USER` 写 403 / 读 200；未认证 401
- [x] 4.3 回归：组织管理四模块 + 关联回填页面与 API 无损；测试数据清理仅留种子
- [x] 4.4 `openspec validate add-item-master --strict --no-interactive` 通过，全部任务勾选
