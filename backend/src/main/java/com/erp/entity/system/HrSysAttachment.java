package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 通用附件（最小能力：上传/查询/下载；本地盘 UUID 文件名，对象存储目标态留桩）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sys_attachment")
public class HrSysAttachment extends BaseEntity {

    /** 借调/外派三方协议扫描件 */
    public static final String BIZ_SECONDTM_AGREEMENT = "SECONDTM_AGREEMENT";
    /** 资格认证证书扫描件（add-training-certification） */
    public static final String BIZ_TRAIN_CERT = "TRAIN_CERT";
    /** 里程碑交付物（add-milestone-management：BIZ_ID=里程碑 ID，版本=序号派生） */
    public static final String BIZ_MILESTONE_DELIV = "MILESTONE_DELIV";

    /** 业务类型 */
    private String bizType;
    /** 业务主键 */
    private String bizId;
    /** 原始文件名 */
    private String fileName;
    /** 存储路径（本地盘 UUID 文件名） */
    private String storePath;
    /** 字节数 */
    private Long fileSize;
}
