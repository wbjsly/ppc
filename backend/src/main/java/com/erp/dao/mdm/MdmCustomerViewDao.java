package com.erp.dao.mdm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mdm.MdmCustomerView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface MdmCustomerViewDao extends BaseMapper<MdmCustomerView> {

    /** 集团下法人视图额度合计（BR-4.1-31 求和基准；含停用/冻结——额度是分配事实，停用不释放） */
    @Select("SELECT IFNULL(SUM(CREDIT_LIMIT), 0) FROM erp_mdm_customer_view " +
            "WHERE DEL_FLAG = '0' AND GROUP_ID = #{groupId}")
    BigDecimal sumCreditLimit(@Param("groupId") String groupId);

    /** 集团下法人视图数（合并影响面） */
    @Select("SELECT COUNT(1) FROM erp_mdm_customer_view WHERE DEL_FLAG = '0' AND GROUP_ID = #{groupId}")
    int countByGroup(@Param("groupId") String groupId);

    /** 到期待回滚的临时额度行（BR-4.1-33，含今天到期；幂等由 UPDATE 清列保证） */
    @Select("SELECT * FROM erp_mdm_customer_view WHERE DEL_FLAG = '0' " +
            "AND TEMP_CREDIT_LIMIT IS NOT NULL AND TEMP_EXPIRE_DATE <= CURDATE() LIMIT 200")
    java.util.List<MdmCustomerView> findExpiredTempLimits();

    /**
     * 复审超期候选（C-4.3-13 懒压缩前置筛选）：已复审且距今 >12 个月，或从未复审且建档 >12 个月；
     * 且尚未压缩（COMPRESSED_LIMIT IS NULL）。精确月判定在服务层做。
     */
    @Select("SELECT * FROM erp_mdm_customer_view WHERE DEL_FLAG = '0' " +
            "AND COMPRESSED_LIMIT IS NULL AND CREDIT_LIMIT IS NOT NULL AND (" +
            "  (LAST_REVIEW_DATE IS NOT NULL AND LAST_REVIEW_DATE < DATE_SUB(CURDATE(), INTERVAL 12 MONTH))" +
            "  OR (LAST_REVIEW_DATE IS NULL AND CREATE_DATE < DATE_SUB(CURDATE(), INTERVAL 12 MONTH))" +
            ") LIMIT 200")
    java.util.List<MdmCustomerView> findReviewOverdueCandidates();
}
