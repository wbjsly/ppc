package com.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.dao.system.HrSysAttachmentDao;
import com.erp.entity.system.HrSysAttachment;
import com.erp.service.SysParamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 最小通用附件能力（add-employee-secondment）：上传/按业务键查询/下载三动作。
 * 本地盘存储（ATTACHMENT_STORE_DIR，默认 ./uploads），UUID 文件名防同名覆盖；
 * 对象存储为目标态留桩。不做预览/版本/ACL（等第二消费方）。
 */
@Slf4j
@Tag(name = "通用附件")
@RestController
@RequestMapping("/api/common/attachments")
@RequiredArgsConstructor
public class CommonAttachmentController {

    private final HrSysAttachmentDao attachmentDao;
    private final SysParamService sysParamService;

    @PostMapping
    @Operation(summary = "上传（multipart file + bizType + bizId）")
    public R<HrSysAttachment> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam String bizType,
                                     @RequestParam String bizId) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException(422, "文件为空");
        }
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.')) : "";
        String stored = UUID.randomUUID().toString().replace("-", "") + ext;
        File dir = new File(storeDir());
        if (!dir.exists() && !dir.mkdirs()) {
            throw new ServiceException(500, "附件目录创建失败：" + dir.getAbsolutePath());
        }
        File dest = new File(dir, stored);
        try {
            file.transferTo(dest.getAbsoluteFile());
        } catch (Exception e) {
            throw new ServiceException(500, "附件保存失败：" + e.getMessage());
        }
        HrSysAttachment a = new HrSysAttachment();
        a.setId(UUID.randomUUID().toString().replace("-", ""));
        a.setBizType(bizType);
        a.setBizId(bizId);
        a.setFileName(original);
        a.setStorePath(stored);
        a.setFileSize(dest.length());
        a.setCreateBy(currentUser());
        a.setCreateDate(LocalDateTime.now());
        try {
            attachmentDao.insert(a);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // UK(BIZ_TYPE,BIZ_ID,FILE_NAME)：同名不覆盖（UUID 隔离），提示改名或删除旧件
            dest.delete();
            throw new ServiceException(409, "该业务对象已存在同名附件：" + original);
        }
        log.info("attachment uploaded: {} {} -> {}", bizType, bizId, stored);
        return R.ok(a);
    }

    @GetMapping
    @Operation(summary = "按业务键查询附件列表")
    public R<List<HrSysAttachment>> list(@RequestParam String bizType, @RequestParam String bizId) {
        return R.ok(attachmentDao.selectList(new LambdaQueryWrapper<HrSysAttachment>()
                .eq(HrSysAttachment::getBizType, bizType)
                .eq(HrSysAttachment::getBizId, bizId)
                .eq(HrSysAttachment::getDelFlag, "0")
                .orderByDesc(HrSysAttachment::getCreateDate)));
    }

    @GetMapping("/download")
    @Operation(summary = "下载（按附件 ID）")
    public ResponseEntity<FileSystemResource> download(@RequestParam String id) {
        HrSysAttachment a = attachmentDao.selectById(id);
        if (a == null || "1".equals(a.getDelFlag())) {
            throw new ServiceException(404, "附件不存在：" + id);
        }
        File f = new File(storeDir(), a.getStorePath());
        if (!f.exists()) {
            throw new ServiceException(404, "附件文件缺失：" + a.getFileName());
        }
        String encoded = URLEncoder.encode(a.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(f));
    }

    private String storeDir() {
        try {
            String dir = sysParamService.getValue("ATTACHMENT_STORE_DIR");
            return dir == null || dir.isBlank() ? "./uploads" : dir.trim();
        } catch (Exception e) {
            return "./uploads";
        }
    }

    private static String currentUser() {
        try {
            return SecurityContextHolder.getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }
}
