package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 序列状态流转留痕（4.2.2，spec serial-master，design D1：纯追加日志，无状态语义）。
 */
@Getter
@Setter
@TableName("erp_inv_serial_log")
public class InvSerialLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String serialId;

    private String serialNo;

    private String fromStatus;

    private String toStatus;

    private String reason;

    private String createBy;

    private LocalDateTime createDate;
}
