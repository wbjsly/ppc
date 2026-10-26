package com.erp.eam;

import com.erp.entity.eam.EamAssetCategory;
import com.erp.service.eam.EamCategoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * add-asset-capitalization：资产分类主数据（spec asset-capitalization）。
 * 新增/重复编码 409/生效取数（转资带入默认折旧政策）/数值域校验。
 */
@SpringBootTest
class EamCategoryDbTest {

    @Autowired
    private EamCategoryService categoryService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void login() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_ADMIN"));
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_eam_asset_category WHERE CATEGORY_CODE LIKE 'CQT%'");
    }

    private EamAssetCategory mk(String code) {
        EamAssetCategory c = new EamAssetCategory();
        c.setCategoryCode(code);
        c.setName("CQT 测试分类");
        c.setCapitalizationFloor(new BigDecimal("5000"));
        c.setDefaultLifeYears(10);
        c.setDefaultResidualRate(new BigDecimal("0.05"));
        c.setLifeYearsMin(5);
        c.setLifeYearsMax(15);
        c.setAssetAccount("1601");
        return c;
    }

    @Test
    void createAndActiveFetch() {
        EamAssetCategory c = categoryService.create(mk("CQT-01"));
        assertNotNull(c.getId());
        EamAssetCategory got = categoryService.getActive("CQT-01");
        assertNotNull(got, "生效分类可按编码取到");
        assertEquals(10, got.getDefaultLifeYears(), "默认年限带入");
        assertEquals(0, got.getDefaultResidualRate().compareTo(new BigDecimal("0.05")),
                "默认残值率带入");
        assertEquals("1601", got.getAssetAccount(), "资产科目带入（凭证取数）");
    }

    @Test
    void duplicateCode409() {
        categoryService.create(mk("CQT-02"));
        var ex = assertThrows(com.erp.common.ServiceException.class,
                () -> categoryService.create(mk("CQT-02")));
        assertEquals(409, ex.getCode(), "编码唯一 409");
    }

    @Test
    void validation422() {
        EamAssetCategory noAccount = mk("CQT-03");
        noAccount.setAssetAccount(null);
        var ex1 = assertThrows(com.erp.common.ServiceException.class,
                () -> categoryService.create(noAccount));
        assertEquals(422, ex1.getCode(), "缺资产科目 422");

        EamAssetCategory badRate = mk("CQT-04");
        badRate.setDefaultResidualRate(new BigDecimal("1.5"));
        var ex2 = assertThrows(com.erp.common.ServiceException.class,
                () -> categoryService.create(badRate));
        assertEquals(422, ex2.getCode(), "残值率越界 422");
    }

    @Test
    void seedFiveCategories() {
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_eam_asset_category WHERE DEL_FLAG = '0'", Long.class);
        assertTrue(cnt >= 5, "种子五类存在（当前 " + cnt + "）");
        EamAssetCategory mach = categoryService.getActive("MACH");
        assertNotNull(mach, "机器设备种子可取");
        assertEquals(0, new BigDecimal("5000").compareTo(mach.getCapitalizationFloor()),
                "资本化下限 5000");
    }
}
