package com.erp.service.iot;

import java.util.List;
import java.util.Map;

/**
 * 移动终端注册与签审双因子（C-4.11-18，spec iot-mobile）。
 */
public interface IotTerminalService {

    /** 注册终端（签发设备证书，绑定操作员） */
    Map<String, Object> register(Map<String, String> body);

    /** 绑定/解绑操作员 */
    Map<String, Object> bind(String deviceCode, String userId);

    Map<String, Object> unbind(String deviceCode);

    /**
     * 签审双因子校验：登录会话 + 二次输入密码 + 终端注册态。
     * 未注册/证书吊销 → 403；终端锁定 → 403；连续 3 次失败 → 告警 + 锁定。
     *
     * @return {pass:true} 或抛 ServiceException
     */
    Map<String, Object> verifySign(Map<String, String> body);

    /** 管理员解锁（留痕 unlockBy/unlockAt） */
    Map<String, Object> unlock(String deviceCode, String reason);

    /** 证书吊销 */
    Map<String, Object> revokeCert(String deviceCode);

    /** 终端列表 */
    List<Map<String, Object>> list(String keyword, String status);

    /** 终端详情 */
    Map<String, Object> detail(String deviceCode);

    /** 在线打点（登录/心跳） */
    void touchOnline(String deviceCode);
}
