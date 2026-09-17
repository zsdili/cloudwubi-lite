package com.cloudwubi.lite;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 腾讯云 SCF 网关客户端（云端词库：词组/热词/成语，实时生效免发版）
 * 查询：{"code":"xxx","phrase":true} → {"phrases":[...]}
 * 失败静默降级为空列表；LRU 缓存 300 条提速
 */
public class CloudClient {
    public static final String GATEWAY_URL = "https://1251037126-bglnivgmaf.ap-guangzhou.tencentscf.com";
    private static final LinkedHashMap<String, List<String>> cache = new LinkedHashMap<>(300, 0.75f, true);
    private static final int CACHE_MAX = 300;

    public static List<String> queryPhrases(final String code) {
        synchronized (cache) {
            List<String> hit = cache.get(code);
            if (hit != null) return hit;
        }
        List<String> r = post("{\"code\":\"" + code + "\",\"phrase\":true}");
        if (r == null) r = Collections.emptyList();
        synchronized (cache) {
            cache.put(code, r);
            if (cache.size() > CACHE_MAX) { Iterator<String> it = cache.keySet().iterator(); it.next(); it.remove(); }
        }
        return r;
    }

    /** 词频上报（MRU 学习，尽力而为） */
    public static void report(final String phrase) {
        new Thread(() -> post("{\"report\":\"" + phrase + "\"}")).start();
    }

    /** 手动 JSON 解析（仅需 phrases 字段，避免引 JSON 库省体积） */
    private static List<String> post(String body) {
        List<String> out = new ArrayList<>();
        try {
            URL u = new URL(GATEWAY_URL);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    String json = sb.toString();
                    int i = json.indexOf("\"phrases\":[");
                    if (i < 0) return out;
                    int j = json.indexOf(']', i);
                    String seg = json.substring(i + 11, j);
                    for (String p : seg.split(",")) {
                        p = p.trim();
                        if (p.length() >= 2 && p.startsWith("\"") && p.endsWith("\"")) {
                            out.add(p.substring(1, p.length() - 1));
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 版本检测（GitHub 主 → Gitee 兜底），返回最新 tag 或 null */
    public static String checkLatest() {
        String[] urls = {
            "https://api.github.com/repos/zsdili/cloudwubi-client/releases/latest",
            "https://gitee.com/api/v5/repos/zsdili/cloudwubi-client/releases/latest"
        };
        for (String ustr : urls) {
            try {
                URL u = new URL(ustr);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "CloudWubi-IME");
                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) sb.append(line);
                String json = sb.toString();
                int i = json.indexOf("\"tag_name\":\"");
                if (i >= 0) return json.substring(i + 12, json.indexOf('"', i + 12));
            } catch (Exception ignored) { }
        }
        return null;
    }
}
