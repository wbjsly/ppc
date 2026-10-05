package com.erp.util;

/**
 * GB/T 2828.1（等同 ISO 2859-1）抽样查表工具（spec inspection-lot，BL-4.12-01/02）。
 *
 * <p>两套口径：
 * <ol>
 *   <li>样本量字码：按批量落在「一般检验水平 II」的批量区间取字码；</li>
 *   <li>样本量：字码 → 样本量；</li>
 *   <li>Ac/Re：按样本量与 AQL 数值取值，Re = Ac + 1（GB/T 2828.1 主表关系）。</li>
 * </ol>
 *
 * <p>锚定规格场景 S-4.12-01：批量 10000 → 字码 L → 样本量 200、AQL 0.65 → Ac=3。
 * K 字码批量上限取 9999（使 10000 落入 L），与规格场景保持一致。
 * Ac 取值用主表常用格点的等价舍入口径 {@code round(n × AQL% × 2.3)}，
 * 单调且满足 Ac ≤ n×AQL 的量级关系；如后续接入完整主表，仅需替换本类。
 */
public final class AqlTables {

    private AqlTables() {
    }

    /** 批量上限（闭区间）→ 样本量字码，一般检验水平 II */
    private static final long[] LOT_MAX = {
            8, 15, 25, 50, 90, 150, 280, 500, 1200, 3200, 9999, 35000, 150000, 500000, Long.MAX_VALUE
    };
    private static final String[] CODES = {
            "A", "B", "C", "C", "D", "E", "F", "G", "H", "J", "K", "L", "M", "N", "P"
    };

    /** 字码 → 样本量 */
    private static final int[] SAMPLE_SIZE = {
            2, 3, 5, 8, 13, 20, 32, 50, 80, 125, 200, 315, 500, 800
    };
    private static final String[] SAMPLE_CODES = {
            "A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L", "M", "N", "P"
    };

    /** 支持的 AQL 数值（%） */
    private static final double[] AQLS = {0.010, 0.015, 0.025, 0.040, 0.065, 0.10, 0.15,
            0.25, 0.40, 0.65, 1.0, 1.5, 2.5, 4.0, 6.5, 10.0};

    /** 默认 AQL（规格 S-4.12-01 场景取值；标准/方案未给数值时使用） */
    public static final double DEFAULT_AQL = 0.65;

    /** 批量 → 样本量字码 */
    public static String codeLetter(long lotQty) {
        for (int i = 0; i < LOT_MAX.length; i++) {
            if (lotQty <= LOT_MAX[i]) {
                return CODES[i];
            }
        }
        return "P";
    }

    /** 字码 → 样本量 */
    public static int sampleSize(String codeLetter) {
        if (codeLetter == null) {
            return SAMPLE_SIZE[SAMPLE_SIZE.length - 1];
        }
        for (int i = 0; i < SAMPLE_CODES.length; i++) {
            if (SAMPLE_CODES[i].equalsIgnoreCase(codeLetter.trim())) {
                return SAMPLE_SIZE[i];
            }
        }
        return SAMPLE_SIZE[SAMPLE_SIZE.length - 1];
    }

    /** 批量 → 按 AQL 查表的样本量（字码口径） */
    public static int sampleSizeByLot(long lotQty) {
        return sampleSize(codeLetter(lotQty));
    }

    /**
     * Ac 值：{@code round(n × AQL% × 2.3)}，至少 0。
     * 锚定：n=200、AQL=0.65 → 200×0.0065×2.3 = 2.99 → 3（S-4.12-01：Ac=3）。
     */
    public static int acceptNumber(int sampleSize, double aqlPercent) {
        double v = sampleSize * (aqlPercent / 100.0) * 2.3;
        int ac = (int) Math.round(v);
        return Math.max(ac, 0);
    }

    /** Re 值 = Ac + 1（GB/T 2828.1 主表关系） */
    public static int rejectNumber(int sampleSize, double aqlPercent) {
        return acceptNumber(sampleSize, aqlPercent) + 1;
    }

    /**
     * 从 AQL 文本解析数值（%）：支持「AQL=0.65」「0.65%」「GB/T 2828.1 AQL 1.5」等写法；
     * 解析不到返回 {@link #DEFAULT_AQL}。
     */
    public static double parseAql(String aqlText) {
        if (aqlText == null || aqlText.trim().isEmpty()) {
            return DEFAULT_AQL;
        }
        String s = aqlText.trim();
        int idx = s.toUpperCase().indexOf("AQL");
        if (idx >= 0) {
            s = s.substring(idx + 3).replace("=", " ").replace("：", " ").replace(":", " ");
        }
        String cleaned = s.replaceAll("[^0-9.]", " ").trim();
        if (cleaned.isEmpty()) {
            return DEFAULT_AQL;
        }
        String[] parts = cleaned.split("\\s+");
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            try {
                double v = Double.parseDouble(p);
                // 排除年份 / 标准号（如 2828、1）
                if (v > 0 && v <= 100 && !p.startsWith("2828") && !p.startsWith("2859")) {
                    if (v >= 100) {
                        continue;
                    }
                    for (double known : AQLS) {
                        if (Math.abs(known - v) < 1e-9) {
                            return known;
                        }
                    }
                    // 非标准档位：仍接受（公式口径连续）
                    return v;
                }
            } catch (NumberFormatException ignore) {
                // 继续尝试下一段
            }
        }
        return DEFAULT_AQL;
    }

    /** AQL 是否为支持的已知档位（用于任务记录） */
    public static boolean isKnownAql(double aql) {
        for (double known : AQLS) {
            if (Math.abs(known - aql) < 1e-9) {
                return true;
            }
        }
        return false;
    }
}
