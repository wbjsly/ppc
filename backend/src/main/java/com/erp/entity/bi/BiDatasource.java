package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 逻辑数据源登记（add-data-extract-console design D1，spec bi-extract-console）。
 * 连接信息加密存储（FR-4.10-2-1）；schema 快照为变更检测基准（BR-4.10-15）。
 * API/FILE 仅登记形态，不建立真实外连（偏差表）。
 */
@Getter
@Setter
@TableName("erp_bi_datasource")
public class BiDatasource extends BaseEntity {

    public static final String ST_ACTIVE = "ACTIVE";
    /** 结构已变更：暂停其下配置自动执行（BR-4.10-15） */
    public static final String ST_SCHEMA_CHANGED = "SCHEMA_CHANGED";
    public static final String ST_DISABLED = "DISABLED";

    public static final String TYPE_DB = "DB";
    public static final String TYPE_API = "API";
    public static final String TYPE_FILE = "FILE";

    private String name;
    private String type;
    private String dbName;
    /** 连接信息加密存储（AES，明文不出接口） */
    private String connEnc;
    /** schema 快照 JSON（information_schema 列清单） */
    private String schemaSnapshot;
    private LocalDateTime snapshotAt;
    private String status;
    private String remark;
}
