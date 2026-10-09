package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmTaxPolicy;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MdmTaxPolicyDao extends BaseMapper<MdmTaxPolicy> {

    /** 政策台账硬删（spec：无区间/历史语义，未被引用即物理删除并记日志） */
    @Delete("DELETE FROM erp_mdm_tax_policy WHERE ID = #{id}")
    int hardDelete(@Param("id") String id);
}
