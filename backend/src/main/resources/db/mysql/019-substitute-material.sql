-- 019 替代物料（物料管理 1.2.5，反查 ALT_ITEM_CODE 用；marker 保证单次执行）

ALTER TABLE erp_mdm_item
    ADD INDEX IDX_MDM_ITEM_ALT (ALT_ITEM_CODE);
