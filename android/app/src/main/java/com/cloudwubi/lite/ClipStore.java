package com.cloudwubi.lite;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 剪贴板历史（持久化 SharedPreferences，最多 50 条，去重置顶）
 * 点选上屏；长按删除（可撤销：标记删除态，点"恢复"还原，点其他处确认）
 */
public class ClipStore {
    private static final String PREFS = "cloudwubi_clip";
    private static final int MAX = 50;
    private static SharedPreferences sp;

    public static void init(Context c) { if (sp == null) sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static List<String> list() {
        List<String> out = new ArrayList<>();
        if (sp == null) return out;
        Set<String> s = sp.getStringSet("items", null);
        if (s != null) out.addAll(s);
        return out;
    }

    public static void add(String text) {
        if (sp == null || text == null || text.trim().isEmpty()) return;
        LinkedHashSet<String> s = new LinkedHashSet<>();
        s.add(text.trim());
        s.addAll(list());
        while (s.size() > MAX) { String first = s.iterator().next(); s.remove(first); }
        sp.edit().putStringSet("items", s).apply();
    }

    public static void remove(String text) {
        if (sp == null || text == null) return;
        Set<String> s = sp.getStringSet("items", null);
        if (s == null) return;
        s = new LinkedHashSet<>(s);
        s.remove(text);
        sp.edit().putStringSet("items", s).apply();
    }
}
