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
            Pattern.compile("[\\p{L}\\p{N}_](?:[\\p{L}\\p{M}\\p{N}_'\u2019\u200c\u200d]*[\\p{L}\\p{M}\\p{N}_])?");
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

    // ------------------------------------------------------------------ Hindi / Hinglish

    private static final Set<String> FILLERS_HI = new HashSet<>(Arrays.asList(
            "हम्म", "उम्म", "अह", "आह"));

    private static String devaConsonant(char c) {
        switch (c) {
            case 'क': return "k";   case 'ख': return "kh";  case 'ग': return "g";
            case 'घ': return "gh";  case 'ङ': return "n";   case 'च': return "ch";
            case 'छ': return "chh"; case 'ज': return "j";   case 'झ': return "jh";
            case 'ञ': return "n";   case 'ट': return "t";   case 'ठ': return "th";
            case 'ड': return "d";   case 'ढ': return "dh";  case 'ण': return "n";
            case 'त': return "t";   case 'थ': return "th";  case 'द': return "d";
            case 'ध': return "dh";  case 'न': return "n";   case 'प': return "p";
            case 'फ': return "ph";  case 'ब': return "b";   case 'भ': return "bh";
            case 'म': return "m";   case 'य': return "y";   case 'र': return "r";
            case 'ल': return "l";   case 'ळ': return "l";   case 'व': return "v";
            case 'श': return "sh";  case 'ष': return "sh";  case 'स': return "s";
            case 'ह': return "h";
            // precomposed nukta letters
            case 'क़': return "k";   case 'ख़': return "kh";  case 'ग़': return "g";
            case 'ज़': return "j";   case 'ड़': return "d";   case 'ढ़': return "d";
            case 'फ़': return "ph";  case 'य़': return "y";
            default: return null;
        }
    }

    private static String devaIndependentVowel(char c) {
        switch (c) {
            case 'ऄ': case 'अ': return "a";
            case 'आ': return "aa";
            case 'इ': return "i";   case 'ई': return "ii";
            case 'उ': return "u";   case 'ऊ': return "uu";
            case 'ऋ': return "ri";
            case 'ऍ': case 'ऎ': case 'ए': return "e";
            case 'ऐ': return "ai";
            case 'ऑ': case 'ऒ': case 'ओ': return "o";
            case 'औ': return "au";
            default: return null;
        }
    }

    private static String devaMatra(char c) {
        switch (c) {
            case 'ा': return "a";   case 'ि': case 'ी': return "i";
            case 'ु': case 'ू': return "u";
            case 'ृ': case 'ॄ': return "ri";
            case 'ॅ': case 'ॆ': case 'े': return "e";
            case 'ै': return "ai";
            case 'ॉ': case 'ॊ': case 'ो': return "o";
            case 'ौ': return "au";
            default: return null;
        }
    }

    /** Devanagari -> rough Roman letters (other characters pass through). Word-final schwa is dropped. */
    static String devaToRoman(String w) {
        w = w.replace("ज्ञ", "gy");
        StringBuilder sb = new StringBuilder();
        boolean pend = false; // a consonant was written, inherent 'a' not decided yet
        int n = w.length();
        for (int i = 0; i < n; i++) {
            char c = w.charAt(i);
            String cons = devaConsonant(c);
            if (cons != null) {
                if (pend) sb.append('a');
                if (i + 1 < n && w.charAt(i + 1) == '़') { // nukta
                    if (c == 'ड' || c == 'ढ') cons = "d";
                    i++;
                }
                sb.append(cons);
                pend = true;
                continue;
            }
            String m = devaMatra(c);
            if (m != null) { sb.append(m); pend = false; continue; }
            if (c == '्') { pend = false; continue; }          // virama
            if (c == '़' || c == '‌' || c == '‍' || c == 'ऽ') continue;
            String v = devaIndependentVowel(c);
            if (v != null) { if (pend) sb.append('a'); sb.append(v); pend = false; continue; }
            if (c == 'ँ' || c == 'ं') { if (pend) sb.append('a'); pend = false; sb.append('n'); continue; }
            if (c == 'ः') { if (pend) sb.append('a'); pend = false; sb.append('h'); continue; }
            if (c >= '०' && c <= '९') { if (pend) sb.append('a'); pend = false; sb.append((char) ('0' + (c - '०'))); continue; }
            if (pend) sb.append('a');
            pend = false;
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isRomanVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }

    /**
     * Sound key shared by Roman Hindi ("kya", "kyaa", "kia"), Devanagari and English words:
     * a consonant skeleton (vowels dropped, aspirates and look-alikes merged, word-final nasal dropped).
     */
    static String romanKey(String s) {
        s = norm(s);
        if (s.isEmpty()) return s;
        s = s.replace("chh", "C").replace("ch", "C").replace("sh", "s").replace("ph", "p")
                .replace("th", "t").replace("dh", "d").replace("bh", "b").replace("gh", "g")
                .replace("kh", "k").replace("jh", "j").replace("ck", "k").replace("x", "ks")
                .replace("q", "k").replace("w", "v").replace("z", "j").replace("f", "p")
                .replaceAll("c(?=[eiy])", "s").replace("c", "k").replace("C", "c");
        int len = s.length();
        if (len >= 2 && (s.charAt(len - 1) == 'n' || s.charAt(len - 1) == 'm') && isRomanVowel(s.charAt(len - 2))) {
            s = s.substring(0, len - 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isRomanVowel(c)) { if (i == 0) sb.append('a'); continue; }
            if (c == 'y' && i > 0) continue;
            sb.append(c);
        }
        String r = REPEATS.matcher(sb.toString()).replaceAll("$1");
        return r.isEmpty() ? s : r;
    }

    /** Key for one word, whether it is written in Devanagari or in Roman letters. */
    public static String hiKey(String word) {
        String w = Normalizer.normalize(word, Normalizer.Form.NFC);
        return romanKey(devaToRoman(w));
    }

    /** Spoken Hindi text (Devanagari from the recogniser) -> sound keys. */
    public static List<String> tokenizeHi(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD_RE.matcher(text);
        while (m.find()) {
            String raw = m.group();
            if (FILLERS_HI.contains(raw) || FILLERS.contains(raw.toLowerCase())) continue;
            String k = hiKey(raw);
            if (!k.isEmpty()) out.add(k);
        }
        return out;
    }
}
