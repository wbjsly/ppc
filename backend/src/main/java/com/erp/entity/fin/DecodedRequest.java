package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数电开票请求队列（M9-2-3，spec invoice-management D5）。
 * PENDING→ISSUED：补开回填发票号+扣额度+幂等键核销（BR-4.14-37）；超限自动拆分（BR-4.14-15）。
 */
@Data
@TableName("erp_decoded_request")
public class DecodedRequest {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ISSUED = "ISSUED";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String reqNo;
    private String clientName;
    private BigDecimal amount;
    private String soNo;
    private String shipNo;
    private String idempotentKey;
    private String status;
    private String invoiceNos;
    private Integer splitCount;
    private LocalDateTime issueAt;
    private String archiveXml;
    private String archivePdf;
    private String remark;
    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
