package com.cloudwubi.lite;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.inputmethodservice.InputMethodService;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 云五笔 Lite（v0.6.5）
 * 设计基准：V12 设计稿（键面白 #FFF / 功能灰 #E8EAED / 选中蓝 #DCE1E7；
 *           文字主 #3A3F47 / 次 #9AA0A8；圆角 8 / 间隙 3 / 边距 12；总高 300）
 * 功能极简：王码86 单字（1/2/3/4 码 + 万能键 z）+ 云端词组（SCF）+ 词频/MRU 可调
 *           + 数字计算 + 符号 + 剪贴板 + APP 信息（版本/GitHub/微信）
 */
public class CloudWubiLiteIME extends InputMethodService {

    // ---------- V12 设计令牌 ----------
    private static final int COL_KEYS = Color.WHITE;        // 键面白
    private static final int COL_FUNC = Color.rgb(0xE8, 0xEA, 0xED); // 功能灰
    private static final int COL_SEL  = Color.rgb(0xDC, 0xE1, 0xE7); // 选中蓝
    private static final int COL_MAIN = Color.rgb(0x2B, 0x2F, 0x36); // 主文字（更深更清晰）
    private static final int COL_SUB  = Color.rgb(0x8A, 0x90, 0x99); // 次文字
    private static final int COL_LINK = 0xFF3366CC;                 // 链接（GitHub 反馈）
    private static final int D_TOOLBAR = 32, D_DIVIDER = 1, D_CAND = 28, D_KEYBOARD = 200;
    private static final int D_MARGIN = 12, D_GAP = 3, D_RADIUS = 6;
    // ---------- V12 字号令牌（统一规范） ----------
    private static final int FS_KEY = 20;    // 键盘字母/数字
    private static final int FS_ROOT = 9;    // 字根标注/数字子标
    private static final int FS_FUNC = 15;   // 功能键（shift/123/中英/回车）
    private static final int FS_CAND = 16;   // 备选栏（用户指定 16pt）
    private static final int FS_PANEL = 14;  // 其他界面（符号/剪贴板/信息/工具栏/状态栏——用户指定 14f）

    // ---------- 王码86 字根标注（键面次文字） ----------
    private static final String[][] ROOTS = {
        {"q","金 犬 儿 夕"},{"w","人 八 亻"},{"e","月 用 彡 乃"},{"r","白 手 扌 斤"},{"t","禾 竹 攵 夂"},
        {"y","言 讠 文 方"},{"u","立 辛 六 门"},{"i","水 氵 小 灬"},{"o","火 丷 米"},{"p","之 辶 冖 宀"},
        {"a","工 匚 七 戈"},{"s","木 西 丁"},{"d","大 三 厂 古"},{"f","土 士 二 干"},{"g","王 一 五 戋"},
        {"h","目 止 卜 丨"},{"j","日 曰 早 虫"},{"k","口 川"},{"l","田 甲 车 力"},
        {"z","万能键"},{"x","纟 幺 弓 匕"},{"c","又 巴 马"},{"v","女 刀 九 臼"},{"b","子 耳 阝 也"},{"n","已 尸 心 羽"},{"m","山 贝 冂 几"}
    };
    // 上档字符（V12）：Q-P 数字见 digitOf；A-L 行 ~@#$%&*()；Z-M 行 ,/-_:;、
    private static final String[][] SHIFTED = {
        {"a","~"},{"s","@"},{"d","#"},{"f","$"},{"g","%"},{"h","&"},{"j","*"},{"k","("},{"l",")"},
        {"z",","},{"x","/"},{"c","-"},{"v","_"},{"b",":"},{"n",";"},{"m","、"}
    };
    private static final char[][] DIGIT_SWIPE = {
        {'1','2','3','4','5','6','7','8','9','0'},
        {'@','#','$','%','&','*','(',')'},
        {'+','-','*','/','=','_',':',';',',','.','?','!','~','`','<','>','|'}
    };

    // ---------- 状态 ----------
    private WubiEngine engine;
    private final StringBuilder composing = new StringBuilder();
    private final List<String> candidates = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean chineseMode = true;
    private boolean shiftState = false;      // 英文大写锁定
    private int panelMode = 0;               // 0 中文 1 数字 2 符号 3 剪贴板 4 APP信息
    private final StringBuilder calcBuf = new StringBuilder();
    private boolean pendingCalcResult = false;
    private boolean infoPanel = false;
    private String latestVersion = null;
    private boolean checkingVersion = false;

    // ---------- 视图 ----------
    private LinearLayout root;
    private LinearLayout tabBar;                 // V12 顶部标签栏
    private TextView[] tabs = new TextView[5];   // 中文/数字/符号/表情/剪贴板
    private LinearLayout toolbar;
    private TextView statusInfo;             // 左侧"云五笔"（编码/计算显示）
    private java.util.Map<String, java.util.List<String>> phraseMap = new java.util.HashMap<>();
    private View redDot;                     // 版本红点
    private HorizontalScrollView candScroll;
    private LinearLayout candBar;
    private LinearLayout keyArea;
    private View prevKeyArea;

    // ---------- 生命周期 ----------
    @Override public void onCreate() {
        super.onCreate();
        ClipStore.init(this);
        try {
            InputStream is = getAssets().open("wubi86_single.txt");
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            engine = new WubiEngine(sb.toString());
            engine.loadPersist(getSharedPreferences("cloudwubi_pref", MODE_PRIVATE).getString("freq", ""));
            try {
                InputStream ip = getAssets().open("wubi86_phrases_lite.txt");
                BufferedReader pr = new BufferedReader(new InputStreamReader(ip, "UTF-8"));
                String pl;
                while ((pl = pr.readLine()) != null) {
                    pl = pl.trim();
                    int sp = pl.indexOf(' ');
                    if (sp < 1 || pl.length() < sp + 2) continue;
                    phraseMap.computeIfAbsent(pl.substring(0, sp), k -> new java.util.ArrayList<>()).add(pl.substring(sp + 1).trim());
                }
                pr.close();
            } catch (Exception pe) { /* 词表缺失不影响启动 */ }
        } catch (Exception e) {
            engine = new WubiEngine("g一\nr的\nt和\n");
        }
        checkVersion();
    }

    @Override public View onCreateInputView() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF0F1F3);
        root.setPadding(dp(D_MARGIN), 0, dp(D_MARGIN), 0);
        buildTabBar();
        buildToolbar();
        View divider = new View(this);
        divider.setBackgroundColor(COL_FUNC);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(D_DIVIDER)));
        buildCandBar();
        keyArea = new LinearLayout(this);
        keyArea.setOrientation(LinearLayout.VERTICAL);
        root.addView(keyArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(D_KEYBOARD)));
        showPanel(0);
        return root;
    }

    @Override public void onStartInputView(android.view.inputmethod.EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        composing.setLength(0);
        candidates.clear();
        updateCandBar();
        refreshStatus();
    }

    // ---------- V12 顶部标签栏：中文 | 数字 | 符号 | 表情 | 剪贴板 ----------
    private static final int[] TAB_MODES = {0, 1, 2, 5, 3};
    private void buildTabBar() {
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER);
        tabBar.setBackgroundColor(0xFFE9EAEC);
        String[] names = {"中文", "数字", "符号", "表情", "剪贴板"};
        for (int i = 0; i < 5; i++) {
            final int m = TAB_MODES[i];
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(FS_PANEL);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(8), 0, dp(8), 0);
            t.setOnClickListener(v -> showPanel(m));
            tabBar.addView(t, new LinearLayout.LayoutParams(0, dp(20), 1));
            tabs[i] = t;
        }
        root.addView(tabBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));
    }
    private void updateTabHighlight() {
        for (int i = 0; i < 5; i++) {
            boolean sel = TAB_MODES[i] == panelMode;
            tabs[i].setTextColor(sel ? COL_LINK : COL_SUB);
            tabs[i].setBackgroundColor(sel ? COL_KEYS : COL_FUNC);
            tabs[i].setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    // ---------- 工具栏 ----------
    private void buildToolbar() {
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(0xFFE9EAEC);
        toolbar.setPadding(0, 0, 0, 0);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(D_TOOLBAR)));

        // 左侧：云五笔（APP 信息入口 + 版本红点）
        LinearLayout brandBox = new LinearLayout(this);
        brandBox.setOrientation(LinearLayout.HORIZONTAL);
        brandBox.setGravity(Gravity.CENTER_VERTICAL);
        brandBox.setPadding(dp(4), 0, dp(4), 0);
        statusInfo = new TextView(this);
        statusInfo.setText("云五笔");
        statusInfo.setTextColor(COL_MAIN);
        statusInfo.setTextSize(FS_PANEL);
        statusInfo.setGravity(Gravity.CENTER_VERTICAL);
        brandBox.addView(statusInfo);
        redDot = new View(this);
        redDot.setBackgroundColor(Color.RED);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(2), dp(2));
        rp.leftMargin = dp(2); rp.topMargin = dp(1);
        redDot.setVisibility(View.GONE);
        brandBox.addView(redDot, rp);
        brandBox.setOnClickListener(v -> toggleInfoPanel());
        toolbar.addView(brandBox, new LinearLayout.LayoutParams(0, dp(D_TOOLBAR), 1));

        // 右侧工具（固定宽度、统一圆圈细线图标）
        String[][] tools = {{"✓","全选"},{"↺","取消"},{"↻","重做"},{"🎤","语音"},{"▤","剪贴板"},{"▾","收起"}};
        for (String[] t : tools) toolbar.addView(makeToolIcon(t[0], t[1]));
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(D_TOOLBAR)));
    }

    private View makeToolIcon(String glyph, final String action) {
        TextView b = new TextView(this);
        b.setText(glyph);
        b.setTextColor(COL_MAIN);
        b.setTextSize(FS_PANEL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(roundBg(COL_KEYS));
        int s = dp(24);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> toolAction(action));
        return b;
    }

    private void toolAction(String a) {
        switch (a) {
            case "全选": InputConnection ic = getCurrentInputConnection(); if (ic != null) ic.performContextMenuAction(android.R.id.selectAll); break;
            case "取消": undo(); break;
            case "重做": redo(); break;
            case "语音": startVoice(); break;
            case "sym1": if (chineseMode) commit("！"); else commitAscii("！"); break;
            case "sym2": if (chineseMode) commit("？"); else commitAscii("？"); break;
            case "剪贴板": showPanel(3); break;
            case "收起": requestHideSelf(0); break;
        }
    }

    // ---------- 备选栏 ----------
    private void buildCandBar() {
        candScroll = new HorizontalScrollView(this);
        candScroll.setHorizontalScrollBarEnabled(false);
        candScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(D_CAND)));
        candBar = new LinearLayout(this);
        candBar.setOrientation(LinearLayout.HORIZONTAL);
        candBar.setGravity(Gravity.CENTER_VERTICAL);
        candScroll.addView(candBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(D_CAND)));
        root.addView(candScroll);
    }

    private void updateCandBar() {
        candBar.removeAllViews();
        if (candidates.isEmpty()) return;
        for (final String c : candidates) {
            TextView tv = new TextView(this);
            tv.setText(c);
            tv.setTextColor(COL_MAIN);
            tv.setTextSize(FS_CAND);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(6), 0, dp(6), 0);
            tv.setOnClickListener(v -> commit(c));
            candBar.addView(tv);
        }
        candScroll.post(() -> candScroll.scrollTo(0, 0));
    }

    // ---------- 面板切换 ----------
    private void showPanel(int mode) {
        panelMode = mode;
        infoPanel = (mode == 4);
        keyArea.removeAllViews();
        if (mode == 0) buildChineseKey();
        else if (mode == 1) buildNumberKey();
        else if (mode == 2) buildSymbolKey();
        else if (mode == 5) buildEmojiKey();
        else if (mode == 3) buildClipKey();
        else buildInfoPanel();
        updateTabHighlight();
        refreshStatus();
    }

    // ---------- 中文键盘 ----------
    private void buildChineseKey() {
        // 3 行字母 + 1 行底行（200dp：3×46 + 底行 56）
        int rowH = (dp(D_KEYBOARD) - dp(56) - dp(2) * D_GAP) / 3;
        String[][] rows = {
            {"q","w","e","r","t","y","u","i","o","p"},
            {"a","s","d","f","g","h","j","k","l"},
            {"z","x","c","v","b","n","m"}
        };
        for (String[] row : rows) {
            LinearLayout rl = new LinearLayout(this);
            rl.setOrientation(LinearLayout.HORIZONTAL);
            rl.setGravity(Gravity.CENTER);
            // V12 Z 行 = ↑ + z x c v b n m + ⌫（左 Shift、右退格删除）
            if (row[0].equals("z")) {
                rl.addView(makeFuncKey("↑", "shift", rowH, rowH, 0));
            }
            for (String k : row) {
                View mk = makeKey(k, rowH, 0);
                rl.addView(mk, new LinearLayout.LayoutParams(0, rowH, 1));
            }
            if (row[0].equals("z")) {
                TextView del = new TextView(this);
                del.setText("×");
                del.setTextColor(COL_MAIN);
                del.setTextSize(FS_PANEL);
                del.setGravity(Gravity.CENTER);
                del.setBackground(keyBg(true));
                del.setOnClickListener(v -> {
                    if (composing.length() > 0) {
                        composing.setLength(composing.length() - 1);
                        queryCandidates();
                    } else {
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) ic.deleteSurroundingText(1, 0);
                    }
                    refreshStatus();
                });
                rl.addView(del, new LinearLayout.LayoutParams(rowH, rowH));
            }
            keyArea.addView(rl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH));
        }
        // 底行：中文=V12 7 键（123|中|！，|空格|？。|符|⏎）；英文=shift|123|EN|空格|⏎
        LinearLayout bl = new LinearLayout(this);
        bl.setOrientation(LinearLayout.HORIZONTAL);
        bl.setGravity(Gravity.CENTER);
        if (chineseMode) {
            bl.addView(makeFuncKey("123", "num", dp(38), dp(38), 0));
            bl.addView(makeFuncKey("中/英", "lang", dp(38), dp(38), 0));
            bl.addView(makeFuncKey("！，", "sym1", dp(38), dp(38), 0));
            TextView sp1 = new TextView(this);
            sp1.setText("空格");
            sp1.setTextColor(COL_SUB); sp1.setTextSize(FS_PANEL);
            sp1.setGravity(Gravity.CENTER);
            sp1.setBackground(keyBg(false));
            sp1.setOnClickListener(v -> onSpace());
            LinearLayout.LayoutParams slp1 = new LinearLayout.LayoutParams(0, dp(56), 2);
            slp1.setMargins(dp(D_GAP), 0, dp(D_GAP), 0);
            bl.addView(sp1, slp1);
            bl.addView(makeFuncKey("🎤", "voice", dp(38), dp(38), 0));
            bl.addView(makeFuncKey("？。", "sym2", dp(38), dp(38), 0));
            bl.addView(makeFuncKey("↵", "enter", dp(38), dp(38), 0));
        } else {
            bl.addView(makeFuncKey(shiftState ? "A" : "a", "shift", dp(38), dp(38), 0));
            bl.addView(makeFuncKey("123", "num", dp(38), dp(38), 0));
            bl.addView(makeFuncKey("EN", "lang", dp(38), dp(38), 0));
            TextView sp2 = new TextView(this);
            sp2.setText("空格");
            sp2.setTextColor(COL_SUB); sp2.setTextSize(FS_PANEL);
            sp2.setGravity(Gravity.CENTER);
            sp2.setBackground(keyBg(false));
            sp2.setOnClickListener(v -> onSpace());
            LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(0, dp(56), 1);
            slp2.setMargins(dp(D_GAP), 0, dp(D_GAP), 0);
            bl.addView(sp2, slp2);
            bl.addView(makeFuncKey("↵", "enter", dp(38), dp(38), 0));
        }
        keyArea.addView(bl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
    }

    private View makeKey(final String letter, int h, int cols) {
        android.widget.FrameLayout k = new android.widget.FrameLayout(this);
        k.setBackground(keyBg(false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1);
        lp.setMargins(dp(D_GAP), dp(D_GAP) / 2, dp(D_GAP), dp(D_GAP) / 2);
        k.setLayoutParams(lp);
        // 上档常显：顶部小字（数字/符号）
        String shifted = shiftedOf(letter);
        if (shifted != null) {
            TextView up = new TextView(this);
            up.setText(shifted);
            up.setTextColor(COL_SUB);
            up.setTextSize(FS_ROOT);
            up.setGravity(Gravity.CENTER_HORIZONTAL);
            android.widget.FrameLayout.LayoutParams ulp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ulp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            ulp.bottomMargin = h / 2 + dp(4);
            k.addView(up, ulp);
        }
        // 主字母：大写居中（国际标准，键面无中文）
        TextView main = new TextView(this);
        main.setText(letter.toUpperCase());
        main.setTextColor(COL_MAIN);
        main.setTextSize(FS_KEY);
        main.setTypeface(null, android.graphics.Typeface.BOLD);
        main.setGravity(Gravity.CENTER);
        k.addView(main, new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 点击输入
        k.setOnClickListener(v -> onLetterKey(letter));
        // 上滑出数字/符号
        k.setOnTouchListener(new View.OnTouchListener() {
            private float y0 = 0;
            private boolean swiped = false;
            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getAction()) {
                    case MotionEvent.ACTION_DOWN: y0 = ev.getY(); swiped = false; return false;
                    case MotionEvent.ACTION_UP:
                        if (swiped) return true;
                        if (ev.getY() - y0 < -dp(18)) { swiped = true; onSwipeUp(letter); return true; }
                        return false;
                }
                return false;
            }
        });
        return k;
    }

    private View makeFuncKey(String label, final String action, int w, int h, int weight) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(COL_MAIN);
        b.setTextSize(FS_FUNC);
        b.setGravity(Gravity.CENTER);
        b.setBackground(keyBg(true));
        b.setOnClickListener(v -> funcAction(action));
        LinearLayout.LayoutParams lp = weight > 0 ? new LinearLayout.LayoutParams(0, h, weight)
                                                  : new LinearLayout.LayoutParams(w, h);
        lp.setMargins(dp(D_GAP), 0, dp(D_GAP), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void funcAction(String a) {
        switch (a) {
            case "voice": startVoice(); break;
            case "shift": shiftState = !shiftState; rebuildPanel(0); break;
            case "num": showPanel(1); break;
            case "lang":
                chineseMode = !chineseMode;
                composing.setLength(0); candidates.clear(); updateCandBar();
                rebuildPanel(0);
                break;
            case "enter":
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                if (ic != null) ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                break;
            case "del":
                if (calcBuf.length() > 0) calcBuf.setLength(calcBuf.length() - 1);
                refreshStatus();
                break;
            case "clear":
                calcBuf.setLength(0);
                candidates.clear();
                updateCandBar();
                refreshStatus();
                break;
        }
    }

    // ---------- 数字键盘 ----------
    private void buildNumberKey() {
        String[][] grid = {
            {"7","8","9","÷"},
            {"4","5","6","×"},
            {"1","2","3","-"},
            {"0",".","=","+"}
        };
        for (String[] row : grid) {
            LinearLayout rl = new LinearLayout(this);
            rl.setOrientation(LinearLayout.HORIZONTAL);
            for (String k : row) {
                TextView b = new TextView(this);
                b.setText(k);
                b.setTextColor(COL_MAIN); b.setTextSize(FS_KEY);
                b.setGravity(Gravity.CENTER);
                b.setBackground(roundBg(COL_KEYS));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
                lp.setMargins(dp(D_GAP), dp(D_GAP), dp(D_GAP), dp(D_GAP));
                b.setLayoutParams(lp);
                b.setOnClickListener(v -> onCalcKey(k));
                rl.addView(b);
            }
            keyArea.addView(rl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        }
        // 底行：返回 | 清空 | 退格
        LinearLayout bl = new LinearLayout(this);
        bl.setOrientation(LinearLayout.HORIZONTAL);
        bl.setGravity(Gravity.CENTER_VERTICAL);
        bl.addView(makeFuncKey("返回", "back", dp(40), dp(40), 0));
        bl.addView(makeFuncKey("清空", "clear", dp(40), dp(40), 0));
        bl.addView(makeFuncKey("⌫", "del", 0, dp(40), 1));
        keyArea.addView(bl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(55)));
    }

    private void onCalcKey(String k) {
        switch (k) {
            case "=":
                String res = CalcEngine.calc(calcBuf.toString());
                if (res != null) {
                    commit(res);
                    calcBuf.setLength(0);
                }
                break;
            case "÷": calcBuf.append("/"); break;
            case "×": calcBuf.append("*"); break;
            default: calcBuf.append(k); break;
        }
        refreshStatus();
    }

    // ---------- 符号键盘 ----------
    private void buildSymbolKey() {
        String[] syms = {"，","。","！","？","；","：","、","·","…","—","“","”","‘","’","（","）","《","》","【","】",
            "～","@","#","$","%","^","&","*","-","_","+","=","<",">","/","\\","|","[","]","{","}",
            "￥","€","£","℃","℉","§","№","①","②","③","④","⑤","⑥","⑦","⑧","⑨","⑩","☆","★","○","●","◇","◆","△","▲","□","■","♂","♀","√","×","÷","±","≈","≠","≤","≥","∞","→","←","↑","↓","「","」","『","』","〝","〞"};
        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        GridLayout gl = new GridLayout(this);
        gl.setColumnCount(6);
        for (String s : syms) {
            TextView b = new TextView(this);
            b.setText(s);
            b.setTextColor(COL_MAIN); b.setTextSize(FS_PANEL);
            b.setGravity(Gravity.CENTER);
            b.setBackground(roundBg(COL_KEYS));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0; lp.height = dp(40);
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            b.setLayoutParams(lp);
            final String c = s;
            b.setOnClickListener(v -> { if (chineseMode) commit(c); else commitAscii(c); });
            gl.addView(b);
        }
        sv.addView(gl);
        // 底行：返回
        LinearLayout bl = new LinearLayout(this);
        bl.setOrientation(LinearLayout.HORIZONTAL);
        bl.addView(makeFuncKey("返回", "back", 0, dp(40), 1));
        keyArea.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        keyArea.addView(bl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
    }

    // ---------- 表情面板（V12 标签：表情） ----------
    private void buildEmojiKey() {
        String[] emojis = {"😀","😁","😂","🤣","😊","😍","🥰","😘","😜","🤪","😎","🤩","🥳","😏","😒","😔","😢","😭","😤","😡","🤔","🤗","🤫","🤭","😴","🤤","😱","🤯","😇","🙃","👍","👎","👏","🙏","💪","🤝","✌️","🤞","👌","🤟","🖐️","❤️","💔","💯","🔥","✨","⭐","🎉","🎂","🌸","🍀","🌹","☀️","🌈","⚡","❄️","🎵","🎮","💰","🚀"};
        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        GridLayout gl = new GridLayout(this);
        gl.setColumnCount(6);
        for (String e : emojis) {
            TextView b = new TextView(this);
            b.setText(e);
            b.setTextSize(FS_PANEL);
            b.setGravity(Gravity.CENTER);
            b.setBackground(roundBg(COL_KEYS));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0; lp.height = dp(40);
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            b.setLayoutParams(lp);
            final String c = e;
            b.setOnClickListener(v -> { if (chineseMode) commit(c); else commitAscii(c); });
            gl.addView(b);
        }
        sv.addView(gl);
        keyArea.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout bl = new LinearLayout(this);
        bl.setOrientation(LinearLayout.HORIZONTAL);
        bl.addView(makeFuncKey("返回", "back", 0, dp(40), 1));
        keyArea.addView(bl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
    }

    // ---------- 剪贴板面板 ----------
    private void buildClipKey() {
        List<String> clips = ClipStore.list();
        TextView tip = new TextView(this);
        tip.setText("◀返回（长按可删除）");
        tip.setTextColor(COL_SUB); tip.setTextSize(FS_PANEL);
        tip.setPadding(dp(4), dp(4), dp(4), dp(4));
        keyArea.addView(tip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (clips.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("剪贴板为空");
            empty.setTextColor(COL_SUB); empty.setGravity(Gravity.CENTER);
            keyArea.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            ScrollView sv = new ScrollView(this);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (final String item : clips) {
                TextView tv = new TextView(this);
                tv.setText(item);
                tv.setTextColor(COL_MAIN); tv.setTextSize(FS_PANEL);
                tv.setPadding(dp(4), dp(6), dp(4), dp(6));
                tv.setGravity(Gravity.CENTER_VERTICAL);
                tv.setBackground(roundBg(COL_KEYS));
                tv.setOnClickListener(v -> commit(item));
                tv.setOnLongClickListener(v -> { ClipStore.remove(item); showPanel(3); return true; });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, dp(2), 0, dp(2));
                tv.setLayoutParams(lp);
                list.addView(tv);
            }
            sv.addView(list);
            keyArea.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        LinearLayout bl = new LinearLayout(this);
        bl.setOrientation(LinearLayout.HORIZONTAL);
        bl.addView(makeFuncKey("返回", "back", 0, dp(40), 1));
        keyArea.addView(bl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
    }

    // ---------- APP 信息面板 ----------
    private void buildInfoPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        String[] lines = {
            "云五笔 v" + verName(),
            "GitHub 反馈：github.com/zsdili",
            "微信反馈：添加好友后留言",
            "空格=选首词 | 点选=上屏 | 上滑=数字",
            "剪贴板长按=删除 | 编码不足自动查词",
            latestVersion != null && !latestVersion.equals("v" + verName()) ? "新版本：" + latestVersion : "已是最新版本"
        };
        for (String s : lines) {
            TextView tv = new TextView(this);
            tv.setText(s);
            tv.setTextColor(COL_MAIN); tv.setTextSize(FS_PANEL);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(6), 0, dp(6));
            box.addView(tv);
            if (s.startsWith("GitHub")) tv.setTextColor(COL_LINK);
            if (s.startsWith("GitHub")) tv.setOnClickListener(v -> openUrl("https://github.com/zsdili"));
        }
        keyArea.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout bl = new LinearLayout(this);
        bl.setOrientation(LinearLayout.HORIZONTAL);
        bl.addView(makeFuncKey("返回", "back", 0, dp(40), 1));
        keyArea.addView(bl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
    }

    private void toggleInfoPanel() {
        if (panelMode == 4) { showPanel(0); return; }
        // 点云五笔：检测版本 + 显示 APP 信息
        checkVersion();
        showPanel(4);
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
    }

    // ---------- 输入处理 ----------
    private void onLetterKey(String letter) {
        if (!chineseMode) { commitAscii(shiftState ? letter.toUpperCase() : letter); return; }
        if (shiftState) {
            String sh = shiftedOf(letter);
            if (sh != null) { commitAscii(sh); shiftState = false; rebuildPanel(0); return; }
        }
        if (composing.length() >= 4) { composing.setLength(0); candidates.clear(); }
        composing.append(letter);
        queryCandidates();
        refreshStatus();
    }

    private void queryCandidates() {
        candidates.clear();
        String code = composing.toString();
        if (code.isEmpty()) { updateCandBar(); return; }
        // 本地单字（1-4 码 + 万能键）
        List<String> locals = engine.queryWildcard(code);
        // 4 码：本地内置词优先 → 云端词组 → 单字殿后
        if (code.length() == 4) {
            java.util.List<String> localPh = phraseMap.get(code);
            if (localPh != null) for (String lp : localPh) if (!candidates.contains(lp)) candidates.add(lp);
            List<String> cloud = CloudClient.queryPhrases(code);
            // 词组按用户词频降序（上屏过的词组置前；稳定排序保持云端默认顺序）
            cloud.sort((a, b) -> Integer.compare(engine.boostOf(b), engine.boostOf(a)));
            for (String p : cloud) if (!candidates.contains(p)) candidates.add(p);
        }
        for (String s : locals) if (!candidates.contains(s)) candidates.add(s);
        updateCandBar();
    }

    private void onSwipeUp(String letter) {
        String sh = shiftedOf(letter);
        if (sh == null) return;
        if (chineseMode) commit(sh); else commitAscii(sh);
    }

    private void onSpace() {
        if (!candidates.isEmpty()) { commit(candidates.get(0)); return; }
        if (!composing.isEmpty()) { commit(composing.toString()); return; }
        if (chineseMode) commit("　"); else commitAscii(" ");
    }

    /** 上屏：清空编码/候选 + 词频/MRU + 剪贴板保存 */
    private void commit(String text) {
        if (text == null || text.isEmpty()) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            if (pendingCalcResult) { /* 计算上屏带公式走 deleteSurrounding 无 */ }
            ic.commitText(text, 1);
        }
        if (chineseMode && engine != null && isCjk(text)) {
            engine.onCommit(text);   // 单字与词组均记词频/MRU（v0.6.2：词组上屏也前移）
            saveFreq();
        }
        CloudClient.report(text);
        if (text.trim().length() >= 2) ClipStore.add(text);
        composing.setLength(0);
        candidates.clear();
        pendingCalcResult = false;
        updateCandBar();
        refreshStatus();
    }

    private void commitAscii(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
        composing.setLength(0);
        candidates.clear();
        updateCandBar();
    }

    private boolean isCjk(String s) {
        char c = s.charAt(0);
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private void saveFreq() {
        if (engine != null) getSharedPreferences("cloudwubi_pref", MODE_PRIVATE).edit().putString("freq", engine.persistKey()).apply();
    }

    private void refreshStatus() {
        if (statusInfo == null) return;
        String txt;
        if (panelMode == 1) {
            String calc = calcBuf.toString();
            String res = CalcEngine.calc(calc);
            txt = calc.isEmpty() ? "云五笔" : (calc + (res != null ? "=" + res : ""));
        } else if (panelMode == 4) {
            txt = "云五笔";
        } else {
            txt = composing.length() > 0 ? "编码:" + composing.toString() : "云五笔";
        }
        statusInfo.setText(txt);
        if (redDot != null) {
            boolean hasNew = latestVersion != null && !latestVersion.equals("v" + verName());
            redDot.setVisibility(hasNew ? View.VISIBLE : View.GONE);
        }
    }

    // ---------- 语音输入（系统 SpeechRecognizer，免费） ----------
    private SpeechRecognizer sr;
    private boolean listening = false;

    private void startVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusInfo.setText("系统无语音识别服务");
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusInfo.setText("请开启麦克风权限");
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
            return;
        }
        if (listening) { if (sr != null) { sr.stopListening(); listening = false; } return; }
        if (sr != null) { sr.destroy(); sr = null; }
        sr = SpeechRecognizer.createSpeechRecognizer(this);
        sr.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { listening = true; statusInfo.setText("聆听中…"); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { statusInfo.setText("识别中…"); }
            @Override public void onError(int e) {
                listening = false;
                statusInfo.setText(voiceError(e));
                if (sr != null) { sr.destroy(); sr = null; }
            }
            @Override public void onResults(Bundle r) {
                listening = false;
                ArrayList<String> ms = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (ms != null && !ms.isEmpty()) commit(ms.get(0));
                else statusInfo.setText("未听清，再试一次");
                if (sr != null) { sr.destroy(); sr = null; }
                statusInfo.setText("云五笔");
            }
            @Override public void onPartialResults(Bundle p) { }
            @Override public void onEvent(int t, Bundle b) { }
        });
        try {
            Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            sr.startListening(it);
        } catch (Exception ex) {
            statusInfo.setText("语音启动失败");
            if (sr != null) { sr.destroy(); sr = null; }
        }
    }

    private String voiceError(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_NO_MATCH: return "未听清，再试一次";
            case SpeechRecognizer.ERROR_NETWORK: return "网络不可用";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "网络超时，再试一次";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "没听到声音";
            default: return "语音失败，再试一次";
        }
    }

    private String verName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "0.6.5"; }
    }

    @Override public void onDestroy() {
        if (sr != null) { sr.destroy(); sr = null; }
        super.onDestroy();
    }

    // ---------- 版本检测 ----------
    private void checkVersion() {
        if (checkingVersion) return;
        checkingVersion = true;
        new Thread(() -> {
            final String v = CloudClient.checkLatest();
            ui.post(() -> { latestVersion = v; checkingVersion = false; if (statusInfo != null) refreshStatus(); });
        }).start();
    }

    // ---------- 撤销/重做 ----------
    private final List<String> undoStack = new ArrayList<>();
    private final List<String> redoStack = new ArrayList<>();
    private void undo() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence before = ic.getTextBeforeCursor(50, 0);
        if (before != null && before.length() > 0) {
            String s = before.toString();
            char last = s.charAt(s.length() - 1);
            ic.deleteSurroundingText(1, 0);
            undoStack.add(String.valueOf(last));
        }
    }
    private void redo() {
        if (redoStack.isEmpty()) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) { String s = redoStack.remove(redoStack.size() - 1); ic.commitText(s, 1); }
    }

    // ---------- 工具 ----------
    private void rebuildPanel(int m) { showPanel(m); }
    private android.graphics.drawable.Drawable roundBg(int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(D_RADIUS));
        g.setStroke(dp(1), 0xFFD8D8D8);
        return g;
    }
    // 键面质感：上下渐变 + 底部深色描边（模拟投影，国际标准）
    private android.graphics.drawable.Drawable keyBg(boolean func) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            func ? new int[]{0xFFF0F1F3, 0xFFE3E5E8} : new int[]{0xFFFFFFFF, 0xFFF5F6F8});
        g.setCornerRadius(dp(D_RADIUS));
        g.setStroke(dp(1), func ? 0xFFC9CDD3 : 0xFFD9DCE0);
        return g;
    }
    private int dp(int v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }
    private String rootLabel(String letter) {
        for (String[] r : ROOTS) if (r[0].equals(letter)) {
            String[] parts = r[1].split(" ");
            return parts.length > 0 ? parts[0] : "";
        }
        return "";
    }
    private int digitOf(String letter) {
        String row = "qwertyuiop";
        int i = row.indexOf(letter);
        return i >= 0 ? i + 1 : -1;
    }
    private String shiftedOf(String letter) {
        int d = digitOf(letter);
        if (d >= 0) return String.valueOf(d);
        for (String[] sp : SHIFTED) if (sp[0].equals(letter)) return sp[1];
        return null;
    }
}
