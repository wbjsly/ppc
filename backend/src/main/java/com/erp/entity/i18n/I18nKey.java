package com.erp.entity.i18n;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 翻译键值（BR-5.7-03 占位符校验载体） */
@Data
@TableName("erp_i18n_key")
public class I18nKey implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String keyName;

    private String module;

    /** P0/P1/P2/P3 */
    private String priority;

    private String contextNote;

    private Integer charLimit;

    private String sourceText;

    private String targetText;

    private String locale;

    private String packNo;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;
}
