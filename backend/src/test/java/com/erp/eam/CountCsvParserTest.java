package com.erp.eam;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盘点 CSV 解析纯单测（add-asset-count 任务 6.1：BOM/表头行号/列拆分/空卡号，无 Spring 上下文）。
 */
class CountCsvParserTest {

    @Test
    void bomAndHeaderStrippedAndDataRowNumbering() {
        String csv = "﻿cardNo,actualStatus,actualNote,refreshLastUsed\n"
                + "ZC-0001,IN_USE,,0\n"
                + "ZC-0002,IDLE,闲置于仓库,1\n";
        List<CountCsvParser.Row> rows = CountCsvParser.parse(csv);
        assertEquals(2, rows.size(), "BOM 剥离 + 表头跳过 = 2 数据行");
        assertEquals(1, rows.get(0).rowNo(), "数据行号从 1 起（表头不计）");
        assertEquals(2, rows.get(1).rowNo());
        assertEquals("ZC-0001", rows.get(0).cardNo());
        assertEquals("IN_USE", rows.get(0).status());
        assertFalse(rows.get(0).refresh());
        assertEquals("IDLE", rows.get(1).status());
        assertEquals("闲置于仓库", rows.get(1).note());
        assertTrue(rows.get(1).refresh(), "第 4 列 1 → 刷新闲置锚");
        assertNull(rows.get(0).note(), "空备注列 → null");
        assertFalse(rows.get(0).broken());
    }

    @Test
    void emptyCardNoRowMarkedBrokenWithRowNo() {
        String csv = "cardNo,actualStatus,actualNote,refreshLastUsed\n"
                + "ZC-0010,IN_USE,,0\n"
                + ",IN_USE,缺卡号行,0\n"
                + "ZC-0011,MISSING,未找到,0\n";
        List<CountCsvParser.Row> rows = CountCsvParser.parse(csv);
        assertEquals(3, rows.size());
        assertTrue(rows.get(1).broken(), "空卡号行结构性错误");
        assertEquals("卡号为空", rows.get(1).parseError());
        assertEquals(2, rows.get(1).rowNo(), "错误行号=数据行第 2 条");
        assertEquals(3, rows.get(2).rowNo(), "错误行不影响后续编号");
        assertFalse(rows.get(2).broken());
    }

    @Test
    void crlfAndBlankLinesAndMissingOptionalColumns() {
        String csv = "cardNo,actualStatus\r\n"
                + "ZC-0020,IN_USE\r\n"
                + "\r\n"
                + "ZC-0021,DAMAGED\r\n";
        List<CountCsvParser.Row> rows = CountCsvParser.parse(csv);
        assertEquals(2, rows.size(), "空行跳过 + CRLF 兼容");
        assertEquals("ZC-0021", rows.get(1).cardNo());
        assertNull(rows.get(1).note(), "缺省列 → note null");
        assertFalse(rows.get(1).refresh(), "缺省列 → 不刷新");
    }

    @Test
    void blankContentThrows() {
        assertThrows(IllegalArgumentException.class, () -> CountCsvParser.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> CountCsvParser.parse(null));
    }
}
