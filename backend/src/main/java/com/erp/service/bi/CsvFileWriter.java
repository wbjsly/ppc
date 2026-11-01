package com.erp.service.bi;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV 导出（add-export-console，spec bi-export-lifecycle）：
 * UTF-8 BOM 开头 + 首行水印注释（FR-4.10-4-4③）。
 */
public class CsvFileWriter implements ExportFileWriter {

    @Override
    public String extension() {
        return "csv";
    }

    @Override
    public void write(List<String> headers, List<List<Object>> rows, String watermark,
                      OutputStream out) throws Exception {
        // UTF-8 BOM（Excel 中文兼容）
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            w.println("# watermark: " + watermark);   // 首行水印注释
            if (headers != null && !headers.isEmpty()) {
                w.println(line(headers));
            }
            if (rows != null) {
                for (List<Object> r : rows) {
                    w.println(line(r));
                }
            }
        }
    }

    private static String line(List<?> row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = row.get(i) == null ? "" : String.valueOf(row.get(i));
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }
}
