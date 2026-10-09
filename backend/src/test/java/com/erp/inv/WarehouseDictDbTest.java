package com.erp.inv;

import com.erp.dao.mdm.MdmItemDictDao;
import com.erp.entity.mdm.MdmItemDict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字典查询 DB 侧验证（真实 MySQL，change add-warehouse-zone-management，任务 2.2）：
 * selectByType 按 DICT_TYPE 返回启用条目且排除停用条目——仓库类型/仓位类型校验的共同依据。
 */
@SpringBootTest
class WarehouseDictDbTest {

    @Autowired
    private MdmItemDictDao dictDao;

    private String tempId;

    @AfterEach
    void cleanup() {
        if (tempId != null) {
            dictDao.deleteById(tempId);
            tempId = null;
        }
    }

    @Test
    void selectByTypeReturnsActiveOnly() {
        // 造一个停用条目（独立 ID，不与种子冲突）
        tempId = "d-junit-disabled-" + System.nanoTime();
        MdmItemDict disabled = new MdmItemDict();
        disabled.setId(tempId);
        disabled.setDictType("WAREHOUSE_TYPE");
        disabled.setDictCode("JUNIT_OFF");
        disabled.setDictName("JUnit停用类型");
        disabled.setSortOrder(999);
        disabled.setStatus("0");
        dictDao.insert(disabled);

        List<MdmItemDict> actives = dictDao.selectByType("WAREHOUSE_TYPE");
        assertTrue(actives.stream().anyMatch(d -> "NORMAL".equals(d.getDictCode())),
                "应包含种子启用条目 NORMAL");
        assertFalse(actives.stream().anyMatch(d -> "JUNIT_OFF".equals(d.getDictCode())),
                "停用条目必须被排除");
        assertTrue(actives.stream().allMatch(d -> "1".equals(d.getStatus())),
                "返回结果必须全部为启用状态");
    }
}
