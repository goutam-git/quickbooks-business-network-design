package com.quickbooks.biznetwork.resolution.service;

/**
 * Deliberately simple, dependency-free normalized-Levenshtein similarity
 * (0.0-1.0) over lightly normalized display names. This is a V1
 * placeholder scoring method ("method" field on identity_resolution
 * records it as such) -- not a production entity-resolution model. It is
 * sufficient to demonstrate the MATCH / CONFIRM_REQUIRED / NO_MATCH
 * decision contract end-to-end.
 */
public final class NameSimilarity {

    private NameSimilarity() {
    }

    public static String normalize(String name) {
        if (name == null) return "";
        String s = name.toLowerCase().trim();
        s = s.replaceAll("[^a-z0-9 ]", " ");
        s = s.replaceAll("\\b(pvt|ltd|llc|inc|corp|co|limited|private|company)\\b", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    public static double similarity(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() && nb.isEmpty()) return 1.0;
        int dist = levenshtein(na, nb);
        int maxLen = Math.max(na.length(), nb.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - ((double) dist / maxLen);
    }

    private static int levenshtein(String a, String b) {
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
}
