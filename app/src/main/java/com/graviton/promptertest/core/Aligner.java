package com.graviton.promptertest.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Voice-following aligner (port of matcher.py). update() gets the tail of the latest transcript;
 * the tail is matched against the script around the current position.
 * Movements: fwd (reading / skipping a few words), back (retake, confirmed by 2 updates),
 * resync (lost: search the whole script for a strong match).
 */
public final class Aligner {
    public static final class Result {
        public final int pos;
        public final String kind; // none | stay | fwd | back | resync
        Result(int pos, String kind) { this.pos = pos; this.kind = kind; }
        public boolean moved() { return kind.equals("fwd") || kind.equals("back") || kind.equals("resync"); }
    }

    private static final double[] THR_FWD = {0, 0.88, 0.80, 0.74, 0.72, 0.70, 0.70};

    private final List<String> w;
    private final int n;
    private final int back;
    private final int ahead;
    private final int confirm;
    private int pos = 0;
    private boolean hasPending = false;
    private int pendingE = 0;
    private int pendingCnt = 0;
    private int miss = 0;

    public Aligner(List<String> norms) { this(norms, 90, 30, 2); }

    public Aligner(List<String> norms, int back, int ahead, int confirm) {
        this.w = norms;
        this.n = norms.size();
        this.back = back;
        this.ahead = ahead;
        this.confirm = confirm;
    }

    public int pos() { return pos; }

    public void setPos(int p) {
        pos = Math.max(0, Math.min(p, n));
        hasPending = false;
        miss = 0;
    }

    private double score(List<String> sub, int e) {
        int L = sub.size();
        int s0 = e - L + 1;
        if (s0 < 0 || e >= n) return -1.0;
        double tot = 0, wt = 0;
        for (int k = 0; k < L; k++) {
            String sw = w.get(s0 + k);
            double weight = sw.length() <= 2 ? 0.5 : 1.0;
            tot += weight * TextProc.wordSim(sub.get(k), sw);
            wt += weight;
        }
        return tot / wt;
    }

    /** returns {score, e} or null */
    private double[] best(List<String> sub, int lo, int hi) {
        double bestAdj = -9.0;
        double[] best = null;
        int anchor = pos - 1;
        int from = Math.max(lo, sub.size() - 1);
        int to = Math.min(hi, n - 1);
        for (int e = from; e <= to; e++) {
            double s = score(sub, e);
            double adj = s - 0.003 * Math.abs(e - anchor);
            if (adj > bestAdj) {
                bestAdj = adj;
                best = new double[]{s, e};
            }
        }
        return best;
    }

    public Result update(List<String> tokens) {
        if (tokens.isEmpty() || n == 0) return new Result(pos, "none");
        List<String> tail = tokens.subList(Math.max(0, tokens.size() - 8), tokens.size());
        List<List<String>> bases = new ArrayList<>();
        bases.add(tail);
        if (tail.size() > 1) bases.add(tail.subList(0, tail.size() - 1));
        List<List<String>> subs = new ArrayList<>();
        int[] lens = {6, 4, 3, 2, 1};
        for (List<String> base : bases) {
            for (int L : lens) {
                if (L <= base.size()) {
                    List<String> sub = new ArrayList<>(base.subList(base.size() - L, base.size()));
                    if (!subs.contains(sub)) subs.add(sub);
                }
            }
        }
        subs.sort((a, b) -> Integer.compare(b.size(), a.size())); // stable

        boolean haveFwd = false;
        int fwdLen = 0;
        double fwdScore = 0;
        int fwdE = 0;
        for (List<String> sub : subs) {
            int L = sub.size();
            int hi = L == 1 ? pos + 2 : pos + ahead - 1;
            double[] b = best(sub, pos - 1, hi);
            if (b != null && b[0] >= THR_FWD[L]) {
                haveFwd = true;
                fwdLen = L;
                fwdScore = b[0];
                fwdE = (int) b[1];
                break;
            }
        }

        boolean haveBack = false;
        int backLen = 0;
        double backScore = 0;
        int backE = 0;
        for (List<String> sub : subs) {
            int L = sub.size();
            if (L < 3) continue;
            double[] b = best(sub, pos - back, pos - 2);
            double thr = L == 3 ? 0.90 : 0.82;
            if (b != null && b[0] >= thr) {
                haveBack = true;
                backLen = L;
                backScore = b[0];
                backE = (int) b[1];
                break;
            }
        }

        if (haveBack && (!haveFwd || fwdLen < backLen || (fwdLen == backLen && backScore > fwdScore + 0.04))) {
            int e = backE;
            int cnt;
            if (hasPending && -1 <= e - pendingE && e - pendingE <= 10) cnt = pendingCnt + 1;
            else cnt = 1;
            hasPending = true;
            pendingE = e;
            pendingCnt = cnt;
            if (cnt >= confirm) {
                pos = e + 1;
                hasPending = false;
                miss = 0;
                return new Result(pos, "back");
            }
            return new Result(pos, "none");
        }

        hasPending = false;
        if (haveFwd) {
            miss = 0;
            int newpos = fwdE + 1;
            if (newpos > pos) {
                pos = newpos;
                return new Result(pos, "fwd");
            }
            return new Result(pos, "stay");
        }

        if (tokens.size() >= 4) {
            miss++;
            if (miss >= 3) {
                miss = 0;
                for (List<String> sub : subs) {
                    if (sub.size() < 5) continue;
                    double[] b = best(sub, 0, n - 1);
                    if (b != null && b[0] >= 0.88) {
                        pos = (int) b[1] + 1;
                        return new Result(pos, "resync");
                    }
                }
            }
        }
        return new Result(pos, "none");
    }
}
