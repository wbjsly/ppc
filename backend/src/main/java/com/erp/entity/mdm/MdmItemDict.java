package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 物料字典（UNIT / MATERIAL_GROUP / STORAGE，开放集合加行不发版；采购类型为后端枚举）。
 */
@Data
@TableName("erp_mdm_item_dict")
public class MdmItemDict implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String dictType;

    private String dictCode;

    private String dictName;

    private Integer sortOrder;

    private String status;

    private String createBy;

    private LocalDateTime createDate;
}
