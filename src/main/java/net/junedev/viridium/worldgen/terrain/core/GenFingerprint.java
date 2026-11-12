package net.junedev.viridium.worldgen.terrain.core;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;

/**
 * Identifies what the generator produces: its parameters (seed included) and its code. Names the on-disk caches (map
 * tiles, river networks), so a changed generator never reads stale data.
 */
public final class GenFingerprint {

    private static final long MAX_AGE_MS = 14L * 24 * 3600 * 1000;

    private static String codeStamp;

    private GenFingerprint() {}

    /** Every public parameter as text: equal strings, equal worlds. */
    public static String paramsKey(GenParams p) {
        StringBuilder sb = new StringBuilder();
        for (Field f : GenParams.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                sb.append(f.getName())
                    .append('=')
                    .append(f.get(p))
                    .append(';');
            } catch (IllegalAccessException ignored) {}
        }
        return sb.toString();
    }

    /** Parameters and code as a short hex name. */
    public static String of(GenParams p) {
        return Long.toHexString(hash(paramsKey(p) + "|" + codeStamp()));
    }

    /**
     * The directory of this generator under {@code root} (created if needed; check {@link File#isDirectory}). Removes
     * the directories of other generator versions that nobody has used for a while.
     */
    public static File cacheDir(File root, GenParams p) {
        File dir = new File(root, of(p));
        dir.mkdirs();
        File[] dirs = root.listFiles(File::isDirectory);
        long now = System.currentTimeMillis();
        if (dirs != null) for (File d : dirs) {
            if (d.equals(dir) || now - d.lastModified() < MAX_AGE_MS) continue;
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            d.delete();
        }
        dir.setLastModified(now);
        return dir;
    }

    /** Identifies the generator code: the jar (size, date), or in a dev workspace the newest compiled terrain class. */
    public static synchronized String codeStamp() {
        if (codeStamp != null) return codeStamp;
        String stamp;
        try {
            URL u = GenFingerprint.class.getProtectionDomain()
                .getCodeSource()
                .getLocation();
            File src = new File(u.toURI());
            if (src.isFile()) stamp = src.length() + "@" + src.lastModified();
            else {
                long[] acc = { 0, 0 };
                walk(new File(src, "net/junedev/viridium/worldgen/terrain"), acc);
                stamp = acc[0] + "#" + acc[1];
            }
        } catch (Exception e) {
            stamp = String.valueOf(System.nanoTime()); // unknown code: never reuse what is on disk
        }
        return codeStamp = stamp;
    }

    private static void walk(File d, long[] acc) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) walk(f, acc);
            else {
                acc[0] = Math.max(acc[0], f.lastModified());
                acc[1] = acc[1] * 31 + f.length();
            }
        }
    }

    public static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
