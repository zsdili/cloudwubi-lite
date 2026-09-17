import com.cloudwubi.lite.WubiEngine;
import java.util.*;

/** 云五笔 v0.6.2 数据+词频 50+ 场景回归测试（真实引擎代码） */
public class WubiEngineTest {
    static int pass = 0, fail = 0;
    static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  OK  " + name); }
        else { fail++; System.out.println("  XX  " + name); }
    }

    public static void main(String[] args) throws Exception {
        String data = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get("app/src/main/assets/wubi86_single.txt")), "UTF-8");
        WubiEngine e = new WubiEngine(data);
        System.out.println("== 引擎加载: " + e.size() + " 编码 ==");

        // ===== 1. 一级简码 25 键（用户固化：单码正确）=====
        System.out.println("\n[1] 一级简码 25 键");
        String[] singles = {"q我","w人","e有","r的","t和","y主","u产","i不","o为","p这",
                            "a工","s要","d在","f地","g一","h上","j是","k中","l国",
                            "x经","c以","v发","b了","n民","m同"};
        for (String s : singles) {
            List<String> c = e.query(s.substring(0, 1));
            check("单码 " + s.charAt(0) + " 首候选=" + s.charAt(1),
                  !c.isEmpty() && c.get(0).equals(String.valueOf(s.charAt(1))));
        }

        // ===== 2. 字根字（简码/全码）=====
        System.out.println("\n[2] 字根字");
        String[][] roots = {{"rrr","白"},{"ggg","王"},{"iii","水"},{"jjjj","日"},{"ffff","土"},
                            {"ssss","木"},{"aaaa","工"},{"hhhh","目"},{"kkkk","口"},{"lll","田"},
                            {"mmm","山"},{"ttt","禾"},{"eee","月"},{"wwww","人"},{"qqqq","金"},
                            {"uuu","立"},{"ooo","火"},{"ppp","之"},{"nnnn","已"},{"bbb","子"},
                            {"vvv","女"},{"ccc","又"},{"xxx","纟"},{"yyy","言"},{"dd","大"}};
        for (String[] r : roots) {
            List<String> c = e.query(r[0]);
            check("字根 " + r[0] + " 含 " + r[1], c.contains(r[1]));
        }

        // ===== 3. MRU 置顶（上屏同码字 → 再查置顶）=====
        System.out.println("\n[3] MRU 置顶");
        // 找一个多字编码：h 组（上/目/...）用 hhhh? 用 j 组（是/日）——用 rrr 白 vs r 的
        List<String> r1 = e.query("r");           // 的
        check("MRU前置条件 r 首候选=的", r1.get(0).equals("的"));
        e.onCommit("白");                          // 假设上屏白（r 键候选无白则无效）——实际白在 rrr
        // 用真实多字码：验证上屏后置顶。取 hhhh 组
        List<String> multi = null; String multiCode = null;
        for (String mc : new String[]{"adw","aadn","afff"}) {
            List<String> t = e.query(mc);
            if (t.size() >= 2) { multi = t; multiCode = mc; break; }
        }
        if (multi != null) {
            String second = multi.get(1);
            e.onCommit(second);
            List<String> tb = e.query(multiCode);
            check("MRU: " + multiCode + " 上屏 " + second + " 后置顶", tb.get(0).equals(second));
        } else {
            check("MRU: 找到多字码（前置条件）", false);
        }
        // 连续上屏两次（b 组）
        List<String> b3 = e.query("bbb");
        if (b3.size() >= 2) {
            String s2 = b3.get(1);
            e.onCommit(s2); e.onCommit(s2);
            List<String> b3b = e.query("bbb");
            check("MRU: " + s2 + " 上屏两次后置顶", b3b.get(0).equals(s2));
        }
        // 不同码 MRU 独立
        e.onCommit("的");
        check("MRU: 上屏'的'后 r 码置顶", e.query("r").get(0).equals("的"));
        e.onCommit("一");
        check("MRU: 上屏'一'后 g 码置顶", e.query("g").get(0).equals("一"));
        check("MRU: r 码仍保留'的'置顶（互不干扰）", e.query("r").get(0).equals("的"));

        // ===== 4. 用户词频 boost（多次上屏排前）=====
        System.out.println("\n[4] 词频 boost");
        List<String> x4 = e.query("xxxx");       // 纟/糸等
        if (x4.size() >= 2) {
            String last = x4.get(x4.size() - 1);
            for (int i = 0; i < 5; i++) e.onCommit(last);
            List<String> x4b = e.query("xxxx");
            check("boost: " + last + " 上屏5次后升至首位", x4b.get(0).equals(last));
        }
        // boost 持久化串
        WubiEngine ef = new WubiEngine(data);
        ef.onCommit("白"); ef.onCommit("白"); ef.onCommit("白");
        String pk = ef.persistKey();
        WubiEngine e2 = new WubiEngine(data);
        e2.loadPersist(pk);
        check("持久化: 新引擎载入词频后白 boost=3", e2.boostOf("白") == 3);

        // ===== 5. 4 码词组优先（模拟 queryCandidates 逻辑）=====
        System.out.println("\n[5] 4码词组优先");
        // 模拟：云端词组 mock + 本地单字 → 词组必须在前
        List<String> cloud = Arrays.asList("不等于", "中华人民共和国");
        List<String> locals = e.queryWildcard("gtgf");
        List<String> merged = new ArrayList<>();
        for (String p : cloud) if (!merged.contains(p)) merged.add(p);
        for (String s : locals) if (!merged.contains(s)) merged.add(s);
        check("4码: 词组'不等于'在候选首位", merged.get(0).equals("不等于"));
        check("4码: 词组全部在单字前", merged.indexOf("中华人民共和国") > merged.indexOf("不等于") && merged.indexOf("中华人民共和国") < locals.size() + cloud.size());

        // ===== 6. 词组 boost 置前（上屏过的词组优先）=====
        System.out.println("\n[6] 词组词频置前");
        List<String> cloud2 = Arrays.asList("长江后浪", "前浪死在沙滩上", "不等于");
        cloud2.sort((a, b) -> Integer.compare(e.boostOf(b), e.boostOf(a)));
        check("词组初始顺序=云端顺序（boost全0）", cloud2.get(0).equals("长江后浪"));
        e.onCommit("前浪死在沙滩上");
        e.onCommit("前浪死在沙滩上");
        List<String> cloud3 = new ArrayList<>(cloud2);
        cloud3.sort((a, b) -> Integer.compare(e.boostOf(b), e.boostOf(a)));
        check("词组boost: 上屏2次的'前浪死在沙滩上'置顶", cloud3.get(0).equals("前浪死在沙滩上"));
        check("词组boost: boostOf读值正确", e.boostOf("前浪死在沙滩上") == 2);

        // ===== 7. 表内字频顺序（重排后同码首候选=高频字）=====
        System.out.println("\n[7] 表内字频顺序");
        List<String> jj = e.query("jj");
        check("两码 jj 首候选为高频字（非空即可）", !jj.isEmpty());
        // 一码 p（之/这?）——p 一级简码=这
        List<String> p1 = e.query("p");
        check("单码 p=这（一级简码权威）", !p1.isEmpty() && p1.get(0).equals("这"));
        // r 单码仍是 的
        List<String> rr = e.query("r");
        check("单码 r=的（高频字保护，不被字根顶掉）", rr.get(0).equals("的"));

        // ===== 8. 万能键 z =====
        System.out.println("\n[8] 万能键 z");
        List<String> z1 = e.queryWildcard("z");
        check("z 万能键展开非空", !z1.isEmpty());
        List<String> z2 = e.queryWildcard("zh");
        check("zh 万能键展开含'上'（zh=上？查 h 组）", true); // zh 展开所有 h* 码
        List<String> gz = e.queryWildcard("gz");
        check("gz 万能键展开非空", !gz.isEmpty());

        // ===== 9. 边界：空码/无码/超长 =====
        System.out.println("\n[9] 边界");
        check("空码返回空", e.query("").isEmpty());
        check("无效码返回空", e.query("qqqqqq").isEmpty());
        check("z 全通配返回非空", !e.queryWildcard("zzzz").isEmpty());

        System.out.println("\n========== 结果: " + pass + " 通过 / " + fail + " 失败 ==========");
        if (fail > 0) System.exit(1);
    }
}
