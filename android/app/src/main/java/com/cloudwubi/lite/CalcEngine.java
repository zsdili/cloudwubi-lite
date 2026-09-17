package com.cloudwubi.lite;

/**
 * 精简计算引擎：四则 + 百分号 + 小数 + 括号
 * 实时计算（每输入一位刷新），支持连续运算（结果后接运算符继续）
 * 纯静态，无状态；解析失败返回 null
 */
public class CalcEngine {
    private static int pos = 0;
    private static String src = "";

    /** 计算表达式，返回结果字符串（失败 null） */
    public static String calc(String expr) {
        if (expr == null) return null;
        src = expr.replace("×", "*").replace("÷", "/").replace("－", "-").replace("＋", "+").trim();
        if (src.isEmpty()) return null;
        pos = 0;
        try {
            double v = exprValue();
            if (pos < src.length()) return null;
            if (Double.isNaN(v) || Double.isInfinite(v)) return null;
            return fmt(v);
        } catch (Exception e) { return null; }
    }

    private static double exprValue() { double v = termValue(); while (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) { char op = src.charAt(pos++); double r = termValue(); v = op == '+' ? v + r : v - r; } return v; }
    private static double termValue() { double v = factorValue(); while (pos < src.length() && (src.charAt(pos) == '*' || src.charAt(pos) == '/' || src.charAt(pos) == '%')) { char op = src.charAt(pos++); double r = factorValue(); v = op == '*' ? v * r : op == '/' ? v / r : v % r; } return v; }
    private static double factorValue() {
        if (pos < src.length() && src.charAt(pos) == '(') { pos++; double v = exprValue(); if (pos < src.length() && src.charAt(pos) == ')') pos++; return v; }
        if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) { char s = src.charAt(pos++); double v = number(); return s == '-' ? -v : v; }
        return number();
    }
    private static double number() {
        int start = pos;
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
        if (start == pos) throw new RuntimeException();
        return Double.parseDouble(src.substring(start, pos));
    }

    /** 格式化：整数去尾零，保留 8 位小数上限 */
    private static String fmt(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        String s = String.format("%.8f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 判断表达式是否可继续计算（以运算符结尾=等待下一数，实时刷新用） */
    public static boolean isComplete(String expr) {
        if (expr == null || expr.isEmpty()) return false;
        char c = expr.charAt(expr.length() - 1);
        return !(c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '(' || c == '.');
    }
}
