package com.erp.service.i18n;

import java.util.List;
import java.util.Map;

/**
 * 本地合规字段包引擎（17.5，BR-5.7-13 规则集中配置禁硬编码）。
 * 字段挂既有列（税域等），引擎只管包定义/规则/注册地映射。
 */
public interface ComplianceFieldService {

    /** 字段包列表（按 packCode/注册地过滤） */
    List<Map<String, Object>> list(String packCode, String regPlace);

    /** 保存字段包定义（包/注册地/字段/格式规则） */
    Map<String, Object> save(Map<String, Object> body);

    /**
     * 按法人主体注册地加载字段清单（17.5.3：无关法人不出现冗余字段）。
     *
     * @param regPlace JP/EU/CN（法人主体注册地）
     * @return 该注册地启用的字段包与字段
     */
    List<Map<String, Object>> loadByRegPlace(String regPlace);

    /**
     * 单据保存前合规校验（C-5.7-09）：按注册地取包 → 对既有列值做正则/必填校验。
     *
     * @return {pass, errors:[{field, hint}]}
     */
    Map<String, Object> validate(String regPlace, Map<String, String> values);
}
