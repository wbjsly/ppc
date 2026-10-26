package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发票交付记录（M9-2-4，FR-4.14-2-4，D6 双路：数电自动 PENDING + 销项手工登记）。
 */
@Data
@TableName("erp_fin_invoice_delivery")
public class InvoiceDelivery {

    public static final String SRC_DIGITAL = "DIGITAL";
    public static final String SRC_SALES = "SALES";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String invoiceNo;
    private String source;
    private String sourceId;
    private String method;
    private String contact;
    private String status;
    private LocalDateTime sentAt;
    private Integer retryCount;
    private String remark;
    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
