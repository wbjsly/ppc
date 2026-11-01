package com.erp.service.bi;

import java.io.OutputStream;
import java.util.List;

/**
 * 导出文件生成器（add-export-console，spec bi-export-lifecycle）：
 * 三格式统一接口——xlsx（页眉页脚+背景半透明）/ pdf（右下角+斜置）/ csv（BOM+首行）。
 * 水印内容 = {用户名}|{时间戳}|{流水号}，由调用方拼好传入；水印失败 MUST 抛异常（任务 FAILED）。
 */
public interface ExportFileWriter {

    /** 文件扩展名（不含点） */
    String extension();

    /**
     * 写出文件。
     *
     * @param headers   表头（可空 = 无表头行）
     * @param rows      数据行
     * @param watermark 水印文本
     * @param out       输出流（调用方负责关闭）
     */
    void write(List<String> headers, List<List<Object>> rows, String watermark, OutputStream out)
            throws Exception;
}
