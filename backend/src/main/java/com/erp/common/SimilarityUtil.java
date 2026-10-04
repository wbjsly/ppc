package com.erp.common;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 名称相似度工具（C-4.1-07 语义共享）：编辑距离 ≤N 查重，物料域与客户域共用。
 */
public final class SimilarityUtil {

    private SimilarityUtil() {
    }

    /** Levenshtein 编辑距离 */
    public static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    /**
     * 从候选池中取名称编辑距离 ≤maxDistance 的前 limit 条。
     * 候选池由调用方决定（存量 ≤5000 全量 / 超出 LIKE 预筛）。
     */
    public static <T> List<T> similarWithin(List<T> pool, String name, int maxDistance,
                                            int limit, Function<T, String> nameGetter) {
        List<T> hits = new ArrayList<>();
        if (name == null || name.isBlank()) {
            return hits;
        }
        for (T c : pool) {
            String other = nameGetter.apply(c);
            if (other == null) {
                continue;
            }
            if (levenshtein(name, other) <= maxDistance) {
                hits.add(c);
            }
            if (hits.size() >= limit) {
                break;
            }
        }
        return hits;
    }
}
