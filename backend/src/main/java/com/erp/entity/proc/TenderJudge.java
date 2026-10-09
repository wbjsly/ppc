package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 招标评委名单（design D5：ROLE_BID_JUDGE）。TENDER_ID × JUDGE_USER_ID 唯一。 */
@Data
@TableName("erp_proc_tender_judge")
public class TenderJudge implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenderId;

    private String judgeUserId;

    private String judgeName;

    private String createBy;

    private LocalDateTime createDate;
}
