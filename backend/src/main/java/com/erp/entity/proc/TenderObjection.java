package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 招标异议登记（BR-4.2-48：公示期内有效异议暂停协议生成）。
 * 内部端由采购员代录（偏差 D4）。
 */
@Data
@TableName("erp_proc_tender_objection")
public class TenderObjection implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenderId;

    private String objectionNo;

    /** 形式审查有效：1 有效（触发暂停）/ 0 无效（不阻断） */
    private String validFlag;

    private String objectioner;

    private String content;

    private LocalDateTime submitDate;

    /** PENDING 待复核 / MAINTAIN 维持原定标 / REBID 重新招标 */
    private String reviewStatus;

    private String reviewResult;

    private String reviewBy;

    private LocalDateTime reviewDate;

    private String createBy;

    private LocalDateTime createDate;
}
