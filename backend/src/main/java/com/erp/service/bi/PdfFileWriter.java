package com.erp.service.bi;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.OutputStream;
import java.util.List;

/**
 * PDF 导出（add-export-console，spec bi-export-lifecycle，FR-4.10-4-4③）：
 * 每页右下角水印文本 + 背景斜置水印（openpdf setTextMatrix 45°，design D1）。
 * 中文用内置 CJK 字体（STSong-Light）。
 */
public class PdfFileWriter implements ExportFileWriter {

    @Override
    public String extension() {
        return "pdf";
    }

    @Override
    public void write(List<String> headers, List<List<Object>> rows, String watermark,
                      OutputStream out) throws Exception {
        BaseFont bf = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
        com.lowagie.text.Font dataFont = new com.lowagie.text.Font(bf, 10);
        com.lowagie.text.Font headFont = new com.lowagie.text.Font(bf, 10,
                com.lowagie.text.Font.BOLD);
        com.lowagie.text.Font wmFont = new com.lowagie.text.Font(bf, 26);

        Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        // 每页水印（斜置背景 + 右下角）
        writer.setPageEvent(new com.lowagie.text.pdf.PdfPageEventHelper() {
            @Override
            public void onEndPage(PdfWriter pdfWriter, Document document) {
                PdfContentByte cb = pdfWriter.getDirectContent();
                cb.saveState();
                // 斜置背景水印
                cb.beginText();
                cb.setFontAndSize(wmFont.getBaseFont(), 26);
                cb.setColorFill(Color.GRAY);
                cb.setTextMatrix(30, 200);
                cb.showText(watermark);
                cb.setTextMatrix(180, 420);
                cb.showText(watermark);
                cb.endText();
                cb.restoreState();
                // 右下角常规水印
                ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT,
                        new Phrase(watermark, new com.lowagie.text.Font(bf, 8)),
                        document.right(), document.bottom() - 18, 0);
            }
        });
        doc.open();
        // 表头
        if (headers != null && !headers.isEmpty()) {
            Paragraph p = new Paragraph(String.join("  |  ", headers), headFont);
            doc.add(p);
        }
        // 数据行
        if (rows != null) {
            for (List<Object> r : rows) {
                StringBuilder sb = new StringBuilder();
                for (Object v : r) {
                    if (sb.length() > 0) {
                        sb.append("  |  ");
                    }
                    sb.append(v == null ? "" : String.valueOf(v));
                }
                doc.add(new Paragraph(sb.toString(), dataFont));
            }
        }
        doc.close();
    }
}
