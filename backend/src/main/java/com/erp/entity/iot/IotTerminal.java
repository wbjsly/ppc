package com.erp.entity.iot;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 移动终端注册与证书（C-4.11-18 未注册拒绝） */
@Data
@TableName("erp_iot_terminal")
public class IotTerminal implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String CERT_ACTIVE = "ACTIVE";
    public static final String CERT_REVOKED = "REVOKED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 终端唯一编码（业务键） */
    private String deviceCode;

    /** PDA/TABLET/PHONE */
    private String deviceType;

    private String imeiMac;

    /** 绑定操作人员账号 */
    private String bindUserId;

    private String certSn;

    /** ACTIVE/REVOKED */
    private String certStatus;

    private LocalDateTime lastOnlineAt;

    /** 签审密码连续失败次数 */
    private Integer failCnt;

    /** 连续 3 次失败临时锁定 */
    private String lockedFlag;

    private String lockReason;

    private LocalDateTime lockAt;

    private String unlockBy;

    private LocalDateTime unlockAt;

    private String remark;

    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
}
