package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.TaxCalcLedger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaxCalcLedgerDao extends BaseMapper<TaxCalcLedger> {

    /** 某税码全部引用（合规报告：过期/临期扫描） */
    @Select("SELECT * FROM erp_fin_tax_calc_ledger WHERE DEL_FLAG = '0' AND TAX_CODE = #{taxCode}")
    List<TaxCalcLedger> selectByTaxCode(@Param("taxCode") String taxCode);

    /** 某期间已挂凭证（VOUCHED）且凭证已过账的行——勾稽取数（RECORDED/APPLYING 行不参与） */
    @Select("SELECT l.* FROM erp_fin_tax_calc_ledger l "
            + "JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID "
            + "WHERE l.DEL_FLAG = '0' AND l.PERIOD = #{period} AND l.STATUS = 'VOUCHED' "
            + "AND v.STATUS = 'POSTED'")
    List<TaxCalcLedger> selectPostedByPeriod(@Param("period") String period);

    /** SIMPLE（不得抵扣）已过账但凭证含 222101 进项行——抵扣违规检查 */
    @Select("SELECT l.* FROM erp_fin_tax_calc_ledger l "
            + "JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID "
            + "JOIN erp_fin_voucher_line vl ON vl.VOUCHER_ID = v.ID AND vl.ACCOUNT_CODE = '222101' "
            + "WHERE l.DEL_FLAG = '0' AND l.DEDUCTIBLE = 'N' AND l.STATUS = 'VOUCHED' "
            + "AND v.STATUS = 'POSTED'")
    List<TaxCalcLedger> selectPostedNonDeductible();
}
