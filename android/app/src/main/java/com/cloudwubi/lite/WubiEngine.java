package com.cloudwubi.lite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 王码86 单字引擎（云端词库精简版）
 * 数据：assets/wubi86_single.txt，每行「编码字」（同码多行按使用频率降序）
 * 覆盖：一级简码(25) + 二级简码(615) + 三级简码(4349) + 全码(5710)
 * 词频可调：MRU（最近上屏前移）+ 用户词频加成（持久化），满足"高频字/上屏字优先"
 */
public class WubiEngine {
    private static final String[] LETTERS = {"q","w","e","r","t","y","u","i","o","p","a","s","d","f","g","h","j","k","l","z","x","c","v","b","n","m"};
    private final HashMap<String, ArrayList<String>> map = new HashMap<>();
    private final HashMap<String, Integer> boost = new HashMap<>();      // 用户词频加成
    private final LinkedHashMap<String, String> mru = new LinkedHashMap<>(32, 0.75f, true);
    private static final int MRU_MAX = 20;
    private final StringBuilder persist = new StringBuilder();           // 词频持久化串（字:频次,字:频次）

    public WubiEngine(String data) { load(data); }

    private void load(String data) {
        for (String line : data.split("\n")) {
            line = line.trim();
            if (line.length() < 2) continue;
            String code = line.substring(0, line.length() - 1);
            String ch = line.substring(line.length() - 1);
            map.computeIfAbsent(code, k -> new ArrayList<>()).add(ch);
        }
    }

    /** 单字/简码候选（MRU 置顶 + 用户词频前移） */
    public List<String> query(String code) {
        ArrayList<String> base = map.get(code);
        if (base == null) return new ArrayList<>();
        ArrayList<String> out = new ArrayList<>(base);
        // MRU：最近上屏的同码字置顶（用户固化：上过屏的字优先排前）
        for (int i = out.size() - 1; i >= 0; i--) {
            if (mru.containsKey(out.get(i))) { String c = out.remove(i); out.add(0, c); }
        }
        // 用户词频加成（稳定降序，同频保持原序）
        out.sort((a, b) -> Integer.compare(boost.getOrDefault(b, 0), boost.getOrDefault(a, 0)));
        return out;
    }

    /** 万能键 z：展开为 25 键查询并合并（王码86 Z 键特性） */
    public List<String> queryWildcard(String code) {
        if (!code.contains("z")) return query(code);
        List<String> all = new ArrayList<>();
        expand(code, 0, new StringBuilder(), all);
        return all;
    }

    private void expand(String code, int pos, StringBuilder cur, List<String> out) {
        if (pos == code.length()) {
            for (String s : query(cur.toString())) if (!out.contains(s)) out.add(s);
            return;
        }
        char c = code.charAt(pos);
        if (c == 'z') {
            for (String l : LETTERS) { cur.append(l); expand(code, pos + 1, cur, out); cur.setLength(cur.length() - 1); }
        } else {
            cur.append(c); expand(code, pos + 1, cur, out); cur.setLength(cur.length() - 1);
        }
    }

    /** 上屏回调：词频 +1 并记入 MRU */
    public void onCommit(String ch) {
        boost.merge(ch, 1, Integer::sum);
        mru.put(ch, ch);
        if (mru.size() > MRU_MAX) { Iterator<String> it = mru.keySet().iterator(); it.next(); it.remove(); }
        if (persist.length() > 4000) persist.setLength(0);
        persist.append(ch).append(':').append(boost.get(ch)).append(',');
    }

    /** 词频快照（持久化用） */
    public String persistKey() { return persist.toString(); }

    /** 载入持久化词频（格式 字:频,字:频） */
    public void loadPersist(String data) {
        if (data == null) return;
        for (String pair : data.split(",")) {
            int i = pair.lastIndexOf(':');
            if (i > 0 && i < pair.length() - 1) {
                try { boost.put(pair.substring(0, i), Integer.parseInt(pair.substring(i + 1))); } catch (Exception ignored) { }
            }
        }
    }

    public boolean has(String code) { return map.containsKey(code); }
    public int size() { return map.size(); }
    public Map<String, ArrayList<String>> rawMap() { return map; }
}
