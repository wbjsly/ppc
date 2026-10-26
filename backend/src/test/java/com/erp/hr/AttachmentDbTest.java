package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.dao.system.HrSysAttachmentDao;
import com.erp.entity.system.HrSysAttachment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最小通用附件 DbTest（上传落盘/业务键查询/同名不覆盖 UUID 隔离）。
 * 控制器逻辑直测（无 HTTP 层），造数前缀 tatt-。
 */
@SpringBootTest
class AttachmentDbTest {

    @Autowired
    private com.erp.controller.CommonAttachmentController attachmentController;
    @Autowired
    private HrSysAttachmentDao attachmentDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "junit-hr", null,
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_HR"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        // 清测试附件文件与记录（bizId 前缀锚定）
        for (HrSysAttachment a : attachmentDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HrSysAttachment>()
                        .likeRight(HrSysAttachment::getBizId, "tatt-"))) {
            new File("./uploads", a.getStorePath()).delete();
        }
        jdbc.update("DELETE FROM erp_sys_attachment WHERE BIZ_ID LIKE 'tatt-%'");
    }

    private MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "fake-pdf-bytes".getBytes());
    }

    @Test
    void uploadListAndSameNameIsolated() throws Exception {
        // 上传
        HrSysAttachment a = attachmentController.upload(file("三方协议.pdf"),
                HrSysAttachment.BIZ_SECONDTM_AGREEMENT, "tatt-sec-1").getData();
        assertEquals("三方协议.pdf", a.getFileName());
        assertTrue(a.getStorePath().endsWith(".pdf"));
        // 落盘
        File stored = new File("./uploads", a.getStorePath());
        assertTrue(stored.exists(), "文件已落本地盘");
        assertEquals(Files.size(stored.toPath()), a.getFileSize().intValue());
        // 业务键查询
        assertEquals(1, attachmentController.list(HrSysAttachment.BIZ_SECONDTM_AGREEMENT, "tatt-sec-1")
                .getData().size());
        // 同名不覆盖（UK + UUID 隔离）→ 409
        ServiceException e = assertThrows(ServiceException.class,
                () -> attachmentController.upload(file("三方协议.pdf"),
                        HrSysAttachment.BIZ_SECONDTM_AGREEMENT, "tatt-sec-1"));
        assertTrue(e.getMessage().contains("同名"), e.getMessage());
        // 不同业务对象同名互不影响
        HrSysAttachment b = attachmentController.upload(file("三方协议.pdf"),
                HrSysAttachment.BIZ_SECONDTM_AGREEMENT, "tatt-sec-2").getData();
        assertTrue(!a.getStorePath().equals(b.getStorePath()), "UUID 隔离不覆盖");
        assertTrue(new File("./uploads", b.getStorePath()).exists());
    }
}
