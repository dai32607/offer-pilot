package com.offerpilot.agent;

import java.util.Locale;

/**
 * 证据校验器（约束 C2 的判定核心）：
 * 判断模型给出的引用片段（quote）是否为对应原文的子串，防止模型改写、拼接或编造证据。
 * <p>
 * 匹配前统一做「无关差异归一化」：忽略所有空白字符、全角/半角差异、中英文标点差异与英文大小写，
 * 但严格保留字词内容本身——改写、概括、补字的片段无法通过校验。
 */
public final class EvidenceValidator {

    private EvidenceValidator() {
    }

    /**
     * 校验引用是否为原文子串。
     *
     * @param quote      模型返回的引用片段
     * @param source     来源标记（jd / resume，可为 null，为 null 时在两份原文中查找）
     * @param jdRaw      JD 原文
     * @param resumeRaw  简历原文
     * @return true 表示该引用可在对应原文中原样定位
     */
    public static boolean supports(String quote, String source, String jdRaw, String resumeRaw) {
        String normalizedQuote = normalize(quote);
        if (normalizedQuote.isEmpty()) {
            return false;
        }
        String label = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        boolean fromJd = label.contains("jd") || label.contains("岗位");
        boolean fromResume = label.contains("resume") || label.contains("简历") || label.contains("cv");
        if (fromJd && !fromResume) {
            return normalize(jdRaw).contains(normalizedQuote);
        }
        if (fromResume && !fromJd) {
            return normalize(resumeRaw).contains(normalizedQuote);
        }
        return normalize(jdRaw).contains(normalizedQuote)
                || normalize(resumeRaw).contains(normalizedQuote);
    }

    /** 归一化：去除空白、全角转半角、统一中英文标点、英文小写。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (Character.isWhitespace(ch) || ch == '\u00A0' || ch == '\u3000') {
                continue; // 丢弃所有空白（含全角空格）
            }
            if (ch >= 0xFF01 && ch <= 0xFF5E) {
                ch = (char) (ch - 0xFEE0); // 全角 ASCII → 半角
            }
            switch (ch) {
                case '\u3002' -> ch = '.';   // 。
                case '\uFF0C' -> ch = ',';   // ，
                case '\uFF1B' -> ch = ';';   // ；
                case '\uFF1A' -> ch = ':';   // ：
                case '\uFF08' -> ch = '(';   // （
                case '\uFF09' -> ch = ')';   // ）
                case '\u201C', '\u201D' -> ch = '"'; // “ ”
                case '\u2018', '\u2019' -> ch = '\''; // ‘ ’
                case '\uFF01' -> ch = '!';   // ！
                case '\uFF1F' -> ch = '?';   // ？
                case '\u2014', '\u2013', '\uFF0D' -> ch = '-'; // — – －
                case '\u3001' -> ch = ',';   // 、
                default -> {
                }
            }
            sb.append(ch);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
