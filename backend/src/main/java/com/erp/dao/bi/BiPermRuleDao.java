package com.erp.dao.bi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.bi.BiPermRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BiPermRuleDao extends BaseMapper<BiPermRule> {

    /** 用户行级法人绑定（ROW_LE） */
    @Select("SELECT * FROM erp_bi_perm_rule WHERE RULE_TYPE = 'ROW_LE' AND USER_NAME = #{user} " +
            "AND STATUS = 'ENABLED' AND DEL_FLAG = '0'")
    List<BiPermRule> selectRowBindings(@Param("user") String user);

    /** 列级掩码规则（COL_MASK，命中即掩码；COL_SENSITIVE 未命中默认掩码） */
    @Select("SELECT * FROM erp_bi_perm_rule WHERE RULE_TYPE IN ('COL_MASK','COL_SENSITIVE') " +
            "AND STATUS = 'ENABLED' AND DEL_FLAG = '0'")
    List<BiPermRule> selectColRules();
}
