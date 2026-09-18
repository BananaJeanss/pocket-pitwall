package dev.bananajeans.pitwall.core;

/** Stable semantic versions only; rejects malformed and prerelease tags. */
public final class ReleaseVersion {
    private ReleaseVersion() {}
    private static int[] parse(String value) {
        if (value == null || !value.matches("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) return null;
        String[] parts = value.replaceFirst("^v", "").split("\\.");
        try { return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])}; }
        catch (NumberFormatException ignored) { return null; }
    }
    public static boolean newer(String candidate, String installed) {
        int[] a = parse(candidate), b = parse(installed);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] > b[i];
        return false;
    }
}
