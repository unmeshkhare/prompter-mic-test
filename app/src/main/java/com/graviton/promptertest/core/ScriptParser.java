package com.graviton.promptertest.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Script parsing: words, sentences, paragraphs and non-spoken [cues] / //notes. */
public final class ScriptParser {
    private ScriptParser() {}

    private static final Pattern CUE_RE = Pattern.compile("\\[[^\\]\\n]*\\]|^[ \\t]*//[^\\n]*", Pattern.MULTILINE);
    private static final String TRAIL_PUNCT = ".,!?;:\u2026\"'\u201d\u2019)]}\u2014-\u0964\u0965";
    private static final Pattern SENT_BREAK_RE = Pattern.compile("[.?!\u0964\u0965\\n]|\\[|//");
    private static final Pattern PARA_BREAK_RE = Pattern.compile("\\n[ \\t]*\\n");

    public static final class Word {
        public final String text;
        public final String norm;
        public final int start;  // UTF-16 offsets into ParsedScript.text
        public final int end;
        public final int fend;   // end incl. trailing punctuation
        public final int sent;
        public final int para;

        Word(String text, String norm, int start, int end, int fend, int sent, int para) {
            this.text = text; this.norm = norm; this.start = start; this.end = end;
            this.fend = fend; this.sent = sent; this.para = para;
        }
    }

    public static final class Parsed {
        public String text = "";
        public final List<Word> words = new ArrayList<>();
        public final List<Integer> sentFirst = new ArrayList<>();
        public final List<Integer> sentLast = new ArrayList<>();
        public final List<Integer> paraFirst = new ArrayList<>();
        public final List<int[]> cues = new ArrayList<>(); // {start, end}

        public int n() { return words.size(); }

        public List<String> norms() {
            List<String> r = new ArrayList<>(words.size());
            for (Word x : words) r.add(x.norm);
            return r;
        }

        public int sentOf(int pos) {
            if (words.isEmpty()) return 0;
            return words.get(Math.min(Math.max(pos, 0), n() - 1)).sent;
        }

        public int paraOf(int pos) {
            if (words.isEmpty()) return 0;
            return words.get(Math.min(Math.max(pos, 0), n() - 1)).para;
        }

        public int backSentence(int pos) {
            if (words.isEmpty()) return 0;
            int s = sentOf(pos);
            int start = sentFirst.get(s);
            if (pos > start) return start;
            return s > 0 ? sentFirst.get(s - 1) : 0;
        }

        public int forwardSentence(int pos) {
            if (words.isEmpty()) return 0;
            int s = sentOf(pos);
            return s + 1 < sentFirst.size() ? sentFirst.get(s + 1) : n();
        }

        public int backParagraph(int pos) {
            if (words.isEmpty()) return 0;
            int p = paraOf(pos);
            int start = paraFirst.get(p);
            if (pos > start) return start;
            return p > 0 ? paraFirst.get(p - 1) : 0;
        }

        /** Index of the word containing / next after this character offset. */
        public int wordAtOffset(int off) {
            int lo = 0, hi = words.size() - 1, ans = Math.max(0, hi);
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (words.get(mid).end > off) { ans = mid; hi = mid - 1; } else lo = mid + 1;
            }
            return ans;
        }
    }

    /** Tidy pasted text: odd line breaks, invisible characters, non-breaking spaces. */
    public static String cleanText(String t) {
        t = t.replace("\r\n", "\n").replace("\r", "\n").replace("\u2028", "\n").replace("\u2029", "\n\n");
        t = t.replace("\u00a0", " ").replace("\u202f", " ").replace("\u2007", " ");
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\u200b' || c == '\u2060' || c == '\ufeff' || c == '\u00ad' || c == '\u200e' || c == '\u200f') continue;
            sb.append(c);
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC);
    }

    public static Parsed parse(String raw) {
        String text = cleanText(raw);
        Parsed ps = new Parsed();
        ps.text = text;

        char[] masked = text.toCharArray();
        Matcher cm = CUE_RE.matcher(text);
        while (cm.find()) {
            ps.cues.add(new int[]{cm.start(), cm.end()});
            for (int k = cm.start(); k < cm.end(); k++) masked[k] = ' ';
        }
        String maskedS = new String(masked);

        int sent = -1, para = -1;
        int prevEnd = -1;
        Matcher m = TextProc.WORD_RE.matcher(maskedS);
        while (m.find()) {
            String gap = prevEnd >= 0 ? text.substring(prevEnd, m.start()) : null;
            boolean newSent = gap == null || SENT_BREAK_RE.matcher(gap).find();
            boolean newPara = gap == null || PARA_BREAK_RE.matcher(gap).find();
            if (newSent) {
                sent++;
                ps.sentFirst.add(ps.words.size());
                ps.sentLast.add(ps.words.size());
            }
            if (newPara) {
                para++;
                ps.paraFirst.add(ps.words.size());
            }
            int j = m.end();
            while (j < text.length() && TRAIL_PUNCT.indexOf(text.charAt(j)) >= 0 && text.charAt(j) != '[') j++;
            ps.words.add(new Word(m.group(), TextProc.norm(m.group()), m.start(), m.end(), j, sent, para));
            ps.sentLast.set(sent, ps.words.size() - 1);
            prevEnd = m.end();
        }
        return ps;
    }
}
