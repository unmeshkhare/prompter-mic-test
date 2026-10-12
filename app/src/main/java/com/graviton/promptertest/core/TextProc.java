package com.graviton.promptertest.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text normalisation + fuzzy word similarity (English port of the PC app's textproc.py). */
public final class TextProc {
    private TextProc() {}

    public static final Pattern WORD_RE =
            Pattern.compile("[\\p{L}\\p{N}_](?:[\\p{L}\\p{N}_'\u2019]*[\\p{L}\\p{N}_])?");
    private static final Pattern NONWORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern COMBINING = Pattern.compile("\\p{M}+");
    private static final Pattern REPEATS = Pattern.compile("(.)\\1+");

    private static final Set<String> FILLERS = new HashSet<>(Arrays.asList(
            "um", "umm", "uh", "uhh", "uhm", "ah", "ahh", "er", "erm", "hmm", "hm",
            "mm", "mmm", "eh", "hamm", "ummm"));

    private static final String[][] PH = {
            {"chh", "c"}, {"ch", "c"}, {"sh", "s"}, {"kh", "k"}, {"gh", "g"}, {"th", "t"},
            {"dh", "d"}, {"bh", "b"}, {"ph", "f"}, {"jh", "j"}, {"ck", "k"}, {"q", "k"},
            {"x", "ks"}, {"w", "v"}, {"z", "j"}, {"c", "k"}, {"ai", "e"}, {"ay", "e"},
            {"au", "o"}, {"aw", "o"}, {"ee", "i"}, {"oo", "u"}, {"aa", "a"}, {"ii", "i"},
            {"uu", "u"}};

    private static final ConcurrentHashMap<String, Float> SIM_CACHE = new ConcurrentHashMap<>();

    public static String norm(String word) {
        String w = Normalizer.normalize(word, Normalizer.Form.NFKC).toLowerCase();
        w = w.replace("\u2019", "").replace("'", "");
        String d = Normalizer.normalize(w, Normalizer.Form.NFKD);
        w = COMBINING.matcher(d).replaceAll("");
        String w2 = NONWORD.matcher(w).replaceAll("");
        return w2.isEmpty() ? w : w2;
    }

    static String phon(String s) {
        for (String[] p : PH) s = s.replace(p[0], p[1]);
        s = REPEATS.matcher(s).replaceAll("$1");
        if (s.isEmpty()) return s;
        return s.charAt(0) + s.substring(1).replaceAll("[aeiouy]", "");
    }

    /** Normalised Indel similarity 0..1 (same as rapidfuzz fuzz.ratio / 100). */
    static double ratio(String a, String b) {
        int la = a.length(), lb = b.length();
        if (la + lb == 0) return 1.0;
        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int i = 1; i <= la; i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                if (ca == b.charAt(j - 1)) cur[j] = prev[j - 1] + 1;
                else cur[j] = Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return 2.0 * prev[lb] / (la + lb);
    }

    public static double wordSim(String spoken, String script) {
        if (spoken.equals(script)) return 1.0;
        if (spoken.isEmpty() || script.isEmpty()) return 0.0;
        String key = spoken + "\u0001" + script;
        Float c = SIM_CACHE.get(key);
        if (c != null) return c;
        double s = ratio(spoken, script);
        if (spoken.length() >= 3 && script.startsWith(spoken)) s = Math.max(s, 0.85);
        String pa = phon(spoken), pb = phon(script);
        if (pa.length() >= 2 && pb.length() >= 2) s = Math.max(s, 0.92 * ratio(pa, pb));
        if (SIM_CACHE.size() > 200000) SIM_CACHE.clear();
        SIM_CACHE.put(key, (float) s);
        return s;
    }

    /** Spoken text -> normalised tokens, fillers removed. */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD_RE.matcher(text);
        while (m.find()) {
            String t = norm(m.group());
            if (!t.isEmpty() && !FILLERS.contains(t)) out.add(t);
        }
        return out;
    }
}
