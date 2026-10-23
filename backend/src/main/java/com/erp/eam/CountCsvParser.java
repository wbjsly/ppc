package com.erp.eam;

import java.util.ArrayList;
import java.util.List;

/**
 * 盘点实盘 CSV 解析（纯函数，add-asset-count 任务 6.1 纯单测锚）。
 *
 * <p>约定：UTF-8 BOM 容错；首行 cardNo... 为表头不计行号；行号按数据行编号（第 N 条数据）；
 * 列序 cardNo,actualStatus,actualNote,refreshLastUsed（后两列可缺省）；空卡号行记入错误行不整批回滚。</p>
 */
public final class CountCsvParser {

    private CountCsvParser() {
    }

    /** 单数据行（parseError 非空 = 该行结构性错误，不进入业务校验） */
    public record Row(int rowNo, String cardNo, String status, String note,
                      boolean refresh, String raw, String parseError) {

        public boolean broken() {
            return parseError != null;
        }
    }

    /** 解析 CSV 文本为数据行序列（表头/空行已跳过，rowNo 从 1 起连续编号） */
    public static List<Row> parse(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            throw new IllegalArgumentException("CSV 内容为空");
        }
        String content = csvContent;
        if (content.startsWith("﻿")) {
            content = content.substring(1);   // UTF-8 BOM 容错
        }
        List<Row> rows = new ArrayList<>();
        int rowNo = 0;
        int lineNo = 0;
        for (String raw : content.split("\\r?\\n")) {
            lineNo++;
            if (lineNo == 1 && raw.toLowerCase().startsWith("cardno")) {
                continue;   // 表头不计行号
            }
            if (raw.isBlank()) {
                continue;
            }
            rowNo++;
            String[] parts = raw.split(",", -1);
            String cno = parts.length > 0 ? parts[0].trim() : "";
            String st = parts.length > 1 ? parts[1].trim().toUpperCase() : "";
            String note = parts.length > 2 && !parts[2].isBlank() ? parts[2].trim() : null;
            boolean refresh = parts.length > 3 && "1".equalsIgnoreCase(parts[3].trim());
            if (cno.isEmpty()) {
                rows.add(new Row(rowNo, "", st, note, refresh, raw, "卡号为空"));
                continue;
            }
            rows.add(new Row(rowNo, cno, st, note, refresh, raw, null));
        }
        return rows;
    }
}
