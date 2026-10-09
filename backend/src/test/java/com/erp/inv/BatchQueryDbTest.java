package com.erp.inv;

import com.erp.dao.inv.InvBatchDao;
import com.erp.entity.inv.InvBatch;
import com.erp.service.inv.BatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次查询 DB 侧语义（真实 MySQL，任务 2.2：效期区间筛选 + 效期升序，spec batch-master）。
 */
@SpringBootTest
class BatchQueryDbTest {

    @Autowired
    private InvBatchDao batchDao;
    @Autowired
    private BatchService batchService;

    private String idA;
    private String idB;

    @AfterEach
    void cleanup() {
        if (idA != null) batchDao.deleteById(idA);
        if (idB != null) batchDao.deleteById(idB);
        idA = idB = null;
    }

    private InvBatch insert(String suffix, LocalDate expiry) {
        InvBatch b = new InvBatch();
        b.setId("junit-batch-" + suffix + "-" + System.nanoTime());
        b.setBatchNo("JT" + suffix + System.nanoTime() % 100000);
        b.setItemCode("IT-QUERY-TEST");
        b.setItemName("查询测试物料");
        b.setExpiryDate(expiry);
        b.setStatus("1");
        batchDao.insert(b);
        return b;
    }

    @Test
    void expiryRangeFilterAndAscendingOrder() {
        InvBatch a = insert("A", LocalDate.of(2026, 11, 30)); // 近效期
        InvBatch b = insert("B", LocalDate.of(2027, 3, 31));  // 远效期
        idA = a.getId();
        idB = b.getId();

        // 区间：>= 2027-01-01 → 只命中 B（走服务层 query 组装）
        List<InvBatch> ranged = batchService.query("IT-QUERY-TEST", null, null,
                LocalDate.of(2027, 1, 1), null, null);
        assertEquals(1, ranged.size());
        assertEquals(b.getId(), ranged.get(0).getId());

        // 全量（服务层固定效期升序）：A(2026-11-30) 在 B(2027-03-31) 前
        List<InvBatch> all = batchService.query("IT-QUERY-TEST", null, null, null, null, null);
        List<String> ids = all.stream().map(InvBatch::getId).collect(Collectors.toList());
        assertTrue(ids.indexOf(a.getId()) < ids.indexOf(b.getId()), "效期应升序排列");
    }
}
