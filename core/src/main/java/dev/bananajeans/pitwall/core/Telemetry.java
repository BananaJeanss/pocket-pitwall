package dev.bananajeans.pitwall.core;

import java.util.*;

/** Pure, deterministic analysis. No integration to fictitious speed or position. */
public final class Telemetry {
    private Telemetry() {}
    public static final class Point {
        public final double t, value;
        public Point(double t, double value) { this.t = t; this.value = value; }
    }
    public static final class Mark {
        public final double t;
        public final String kind;
        public final boolean estimated;
        public Mark(double t, String kind, boolean estimated) {
            if (!Double.isFinite(t) || t < 0 || !Arrays.asList("SF", "S2", "S3").contains(kind))
                throw new IllegalArgumentException("Invalid timing marker");
            this.t = t; this.kind = kind; this.estimated = estimated;
        }
    }
    public static final class Lap {
        public final double start, end, s1, s2, s3;
        public final boolean estimated;
        Lap(double start, double end, double s1, double s2, double s3, boolean estimated) {
            this.start = start; this.end = end; this.s1 = s1; this.s2 = s2; this.s3 = s3;
            this.estimated = estimated;
        }
        public double duration() { return end - start; }
    }
    public static List<Lap> laps(List<Mark> marks) {
        List<Mark> sorted = new ArrayList<>(marks);
        sorted.sort(Comparator.comparingDouble(m -> m.t));
        List<Lap> out = new ArrayList<>();
        Mark previous = null;
        for (Mark m : sorted) {
            if (!m.kind.equals("SF")) continue;
            if (previous != null && m.t > previous.t) {
                double a = previous.t, b = m.t;
                List<Mark> s2 = new ArrayList<>(), s3 = new ArrayList<>();
                for (Mark s : sorted) if (s.t > a && s.t < b) {
                    if (s.kind.equals("S2")) s2.add(s);
                    if (s.kind.equals("S3")) s3.add(s);
                }
                boolean valid = s2.size() == 1 && s3.size() == 1 && s2.get(0).t < s3.get(0).t;
                out.add(new Lap(a, b, valid ? s2.get(0).t-a : Double.NaN,
                    valid ? s3.get(0).t-s2.get(0).t : Double.NaN,
                    valid ? b-s3.get(0).t : Double.NaN,
                    previous.estimated || m.estimated || (valid && (s2.get(0).estimated || s3.get(0).estimated))));
            }
            previous = m;
        }
        return out;
    }
    /** Linear interpolation refuses sensor gaps > 250 ms. */
    public static double at(List<Point> points, double t) {
        if (points.isEmpty() || t < points.get(0).t || t > points.get(points.size()-1).t) return Double.NaN;
        int lo = 0, hi = points.size()-1;
        while (lo < hi) { int mid = (lo+hi)/2; if (points.get(mid).t < t) lo = mid+1; else hi = mid; }
        Point right = points.get(lo);
        if (right.t == t || lo == 0) return right.value;
        Point left = points.get(lo-1);
        if (right.t-left.t > .25) return Double.NaN;
        return left.value + (right.value-left.value)*(t-left.t)/(right.t-left.t);
    }
    private static double correlation(List<Point> points, double a, double b) {
        double sx=0, sy=0, xx=0, yy=0, xy=0;
        int n=81;
        for (int i=0; i<n; i++) {
            double offset=-2+i*.05;
            double x=at(points,a+offset), y=at(points,b+offset);
            if (!Double.isFinite(x) || !Double.isFinite(y)) return -1;
            sx+=x; sy+=y; xx+=x*x; yy+=y*y; xy+=x*y;
        }
        double vx=xx-sx*sx/n, vy=yy-sy*sy/n;
        if (vx/n < .0025 || vy/n < .0025) return -1; // Reject stationary/flat windows.
        return (xy-sx*sy/n)/Math.sqrt(vx*vy);
    }
    /** Candidate finish crossings only. Never reported as measured crossings. */
    public static List<Mark> suggest(List<Point> rotationMagnitude, double anchor, double expectedLap) {
        if (!Double.isFinite(anchor) || !Double.isFinite(expectedLap) || anchor < 2 || expectedLap < 10 || expectedLap > 180)
            throw new IllegalArgumentException("Anchor must be ≥2 s and expected lap 10–180 s");
        List<Mark> out = new ArrayList<>();
        if (rotationMagnitude.isEmpty()) return out;
        double end=rotationMagnitude.get(rotationMagnitude.size()-1).t, previous=anchor;
        while (previous+expectedLap*.8+2 <= end && out.size()<360) {
            double best=-1, found=0;
            for (double t=previous+expectedLap*.8; t<=Math.min(previous+expectedLap*1.2,end-2); t+=.1) {
                double score=correlation(rotationMagnitude,anchor,t);
                if (score>best) { best=score; found=t; }
            }
            if (best < .85) break;
            out.add(new Mark(found,"SF",true));
            previous=found;
        }
        return out;
    }
    public static double averageKmh(double lapLengthMeters, double durationSeconds) {
        if (!Double.isFinite(lapLengthMeters) || !Double.isFinite(durationSeconds) || lapLengthMeters<=0 || durationSeconds<=0)
            return Double.NaN;
        return lapLengthMeters/durationSeconds*3.6;
    }
}
