package com.erp.mo;

import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoShortage;
import com.erp.dao.mrp.MrpMoShortageDao;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.KittingService;
import com.erp.service.mrp.MoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 齐套计算库测（task 4.2）：缺料清单与标记、齐套恢复自动清除、创建与释放同一算法。
 */
@SpringBootTest
class MoKittingDbTest {

    @Autowired
    private MoService moService;
    @Autowired
    private KittingService kittingService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private BomService bomService;
    @Autowired
    private MrpMoShortageDao shortageDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("mo-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        MoSeed.cleanup(jdbc);
    }

    private MrpMo createMo(MdmItem product, String qty) {
        MrpMo h = new MrpMo();
        h.setProductCode(product.getItemCode());
        h.setQty(new BigDecimal(qty));
        h.setPlanStartDate(LocalDate.now());
        h.setPlanEndDate(LocalDate.now().plusDays(7));
        return (MrpMo) moService.create(h).get("mo");
    }

    @Test
    void shortageRowsAndFlagGenerated() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "齐套产品一", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "齐套子件一", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0.05");
        // 无库存：需求 = 10 × 2 × 1.05 = 21
        MrpMo mo = createMo(product, "10");
        assertEquals("1", mo.getShortageFlag(), "缺料置标记");

        List<MrpMoShortage> rows = kittingService.shortages(mo.getId());
        assertEquals(1, rows.size(), "缺料清单一行");
        assertEquals(0, rows.get(0).getReqQty().compareTo(new BigDecimal("21.0000")),
                "需求数量 = QTY×用量×(1+损耗)：" + rows.get(0).getReqQty());
        assertEquals(0, rows.get(0).getShortQty().compareTo(new BigDecimal("21.0000")), "缺料 = 需求 − 0");
        assertEquals(child.getItemCode(), rows.get(0).getItemCode(), "子项编码");
    }

    @Test
    void kittingRecoveryClearsFlag() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "齐套产品二", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "齐套子件二", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0");
        MrpMo mo = createMo(product, "10"); // 需求 20，无库存 → flag=1
        assertEquals("1", mo.getShortageFlag(), "初始缺料");
        // 在途到货（补库存 20）→ 重算 → 齐套恢复 100%
        MoSeed.mkStock(jdbc, child.getItemCode(), "20");
        BigDecimal rate = kittingService.check(mo.getId());
        assertEquals(0, rate.compareTo(BigDecimal.valueOf(100).setScale(1)), "齐套率 100");
        assertTrue(shortageDao.selectList(new LambdaQueryWrapper<MrpMoShortage>()
                .eq(MrpMoShortage::getMoId, mo.getId())).isEmpty(), "清单清空");
        MrpMo after = moService.list(null, product.getItemCode(), null).get(0);
        assertEquals("0", after.getShortageFlag(), "缺料标记自动清除（spec 场景）");
    }

    @Test
    void releaseRerunsSameKittingAlgorithm() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "齐套产品三", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "齐套子件三", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "10"); // flag=1
        // 补料后再释放：释放时重跑同一算法 → 标记清除
        MoSeed.mkStock(jdbc, child.getItemCode(), "10");
        moService.submit(mo.getId());
        moService.onApproved(mo.getId());
        MrpMo released = moService.release(mo.getId());
        assertEquals(MrpMo.ST_RELEASED, released.getStatus(), "释放成功（缺料不阻断的反面：已齐套）");
        assertEquals("0", released.getShortageFlag(), "释放时重跑算法清除标记");
        assertNotNull(released.getReleaseBy(), "释放留痕");
    }

    @Test
    void shortageDoesNotBlockRelease() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "齐套产品四", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "齐套子件四", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "10"); // 缺料
        moService.submit(mo.getId());
        moService.onApproved(mo.getId());
        MrpMo released = moService.release(mo.getId()); // 缺料仍可释放（L2496 场景）
        assertEquals("1", released.getShortageFlag(), "缺料释放后标「缺料待料」");
        assertEquals(1, kittingService.shortages(released.getId()).size(), "缺料清单可见");
    }
}
