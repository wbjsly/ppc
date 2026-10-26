package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.ArInvoice;

import java.util.List;
import java.util.Map;

/** 应收确认（8.2.1，spec ar-management：确认标记=账龄起算锚点，未确认不计账龄） */
public interface ArConfirmService {

    /** 确认清单分页（confirmed=true 已确认 / false 未确认） */
    Page<ArInvoice> confirmPage(long current, long size, boolean confirmed, String keyword);

    /** 单笔确认（已确认不可撤销） */
    Map<String, Object> confirm(String arId);

    /** 批量确认（上限 200） */
    Map<String, Object> batchConfirm(List<String> arIds);
}
