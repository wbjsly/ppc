package com.erp.service.intf;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.intf.IntfEdimap;
import com.erp.entity.intf.IntfMessage;

import java.util.Map;

/**
 * EDI 报文四级校验与入库回执（spec edi-message-processing，design D4/D6）。
 * 四级：传输层（网关签名）→ 语法层（行/段/字段定位）→ 映射层（规则缺失 L1）→ 业务层（直调既有服务）。
 */
public interface EdiMessageService {

    /** 接收并处理报文（三入口共用），返回回执（60 秒内） */
    Map<String, Object> receive(String source, String partnerCode, String msgType, String rawJson);

    Page<IntfMessage> page(long current, long size, String msgType, String status, String partnerCode);

    Map<String, Object> detail(String id);

    /** 人工重放（保留原幂等键；成功行跳过、被拒行补入） */
    Map<String, Object> replay(String id);

    Page<IntfEdimap> mapRules(long current, long size, String partnerCode, String msgType);

    Map<String, Object> saveMapRule(Map<String, Object> payload);
}
