package com.erp.service.bi;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HeaderFooter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * XLSX 导出（add-export-console，spec bi-export-lifecycle，FR-4.10-4-4②）：
 * 页眉页脚水印文本 + 背景半透明斜置水印图层（预渲染透明 PNG 单图层，design D1）。
 */
public class XlsxFileWriter implements ExportFileWriter {

    @Override
    public String extension() {
        return "xlsx";
    }

    @Override
    public void write(List<String> headers, List<List<Object>> rows, String watermark,
                      OutputStream out) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("导出");
            // 页眉页脚水印（规格下限，必成）
            HeaderFooter hf = sheet.getHeader();
            hf.setCenter(watermark);
            HeaderFooter footer = sheet.getFooter();
            footer.setCenter(watermark);

            // 表头
            int r = 0;
            CellStyle head = headStyle(wb);
            if (headers != null && !headers.isEmpty()) {
                Row hr = sheet.createRow(r++);
                for (int i = 0; i < headers.size(); i++) {
                    Cell c = hr.createCell(i);
                    c.setCellValue(headers.get(i) == null ? "" : headers.get(i));
                    c.setCellStyle(head);
                }
            }
            // 数据
            if (rows != null) {
                for (List<Object> data : rows) {
                    Row dr = sheet.createRow(r++);
                    for (int i = 0; i < data.size(); i++) {
                        Cell c = dr.createCell(i);
                        Object v = data.get(i);
                        if (v instanceof Number n) {
                            c.setCellValue(n.doubleValue());
                        } else {
                            c.setCellValue(v == null ? "" : String.valueOf(v));
                        }
                    }
                }
            }
            // 列宽自适应（粗略）
            if (headers != null) {
                for (int i = 0; i < headers.size(); i++) {
                    sheet.setColumnWidth(i, 18 * 256);
                }
            }
            // 背景半透明水印图层（失败降级仅页眉——页眉已是规格下限）
            try {
                byte[] png = renderWatermarkPng(watermark);
                int picIdx = wb.addPicture(png, XSSFWorkbook.PICTURE_TYPE_PNG);
                var drawing = sheet.createDrawingPatriarch();
                var anchor = new org.apache.poi.xssf.usermodel.XSSFClientAnchor(
                        0, 0, 0, 0, 0, 2, 12, 18);
                var picture = drawing.createPicture(anchor, picIdx);
                picture.resize(1.0);
            } catch (Throwable ignored) {
                // 背景水印为增强项；页眉页脚已满足规格下限
            }
            wb.write(out);
        }
    }

    private CellStyle headStyle(XSSFWorkbook wb) {
        CellStyle st = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        st.setFont(f);
        st.setBorderBottom(BorderStyle.THIN);
        return st;
    }

    /** 渲染斜置半透明水印 PNG（40° 旋转、30% 透明度灰字平铺） */
    private byte[] renderWatermarkPng(String text) throws Exception {
        int w = 480;
        int h = 320;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.30f));
            g.setColor(Color.GRAY);
            g.rotate(Math.toRadians(-40), w / 2.0, h / 2.0);
            java.awt.Font font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 18);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(text);
            g.drawString(text, (w - tw) / 2, h / 2);
        } finally {
            g.dispose();
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }
}
