package net.junedev.viridium.worldgen.terrain.stages;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.junedev.viridium.worldgen.terrain.core.GenFingerprint;
import net.junedev.viridium.worldgen.terrain.core.GenParams;

/**
 * River networks on disk: a network takes a few hundred milliseconds to build and a few to read back, and it is a pure
 * function of the generator, so the ones a world has needed are kept under
 * {@code <dir>/<generator fingerprint>/<ci>_<cj>.bin} (gzip, ~50 KB each) and reused by the chunk generator, the LODs
 * and the map in later sessions. A stored network is bit for bit the one that would be built. Thread-safe; writes
 * happen on a background thread.
 */
public final class RiverNetStore {

    private static final int MAGIC = 0x56524e31; // "VRN1"
    private static final int MAX_ARRAY = 1 << 26;

    /** The stored fields (every public one but the lake map, which mirrors two arrays), in a fixed order. */
    private static final Field[] FIELDS;
    /** Names and types of {@link #FIELDS}: a file written by another layout is ignored. */
    private static final long SCHEMA;

    static {
        List<Field> fs = new ArrayList<>();
        for (Field f : RiverNet.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Map.class.isAssignableFrom(f.getType())) continue;
            fs.add(f);
        }
        fs.sort(Comparator.comparing(Field::getName));
        StringBuilder sb = new StringBuilder();
        for (Field f : fs) sb.append(f.getName())
            .append(':')
            .append(
                f.getType()
                    .getName())
            .append(';');
        FIELDS = fs.toArray(new Field[0]);
        SCHEMA = GenFingerprint.hash(sb.toString());
    }

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Viridium river network writer");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private final File dir;

    private RiverNetStore(File dir) {
        this.dir = dir;
    }

    /**
     * The store of a generator under {@code root} (e.g. {@code <world>/viridium/rivernets}), or null when its
     * directory cannot be created.
     */
    public static RiverNetStore open(File root, GenParams params) {
        File dir = GenFingerprint.cacheDir(root, params);
        return dir.isDirectory() ? new RiverNetStore(dir) : null;
    }

    public File dir() {
        return dir;
    }

    private File file(int ci, int cj) {
        return new File(dir, ci + "_" + cj + ".bin");
    }

    /** The stored network of basin cell (ci, cj), or null (none, unreadable or of another layout: then removed). */
    public RiverNet load(int ci, int cj) {
        File f = file(ci, cj);
        if (!f.isFile()) return null;
        try (DataInputStream in = new DataInputStream(
            new BufferedInputStream(new GZIPInputStream(new FileInputStream(f), 1 << 16), 1 << 16))) {
            if (in.readInt() != MAGIC || in.readLong() != SCHEMA) throw new IOException("layout");
            RiverNet n = new RiverNet();
            for (Field fd : FIELDS) read(in, fd, n);
            if (n.ci != ci || n.cj != cj
                || n.lakeIdx == null
                || n.lakeLv == null
                || n.lakeIdx.length != n.lakeLv.length) throw new IOException("content");
            Map<Integer, Double> lakes = new HashMap<>();
            for (int q = 0; q < n.lakeIdx.length; q++) lakes.put(n.lakeIdx[q], n.lakeLv[q]);
            n.lakes = lakes;
            return n;
        } catch (IOException | RuntimeException | IllegalAccessException e) {
            f.delete();
            return null;
        }
    }

    /** Writes the network (atomically: a reader sees the whole file or none). */
    public void save(RiverNet n) {
        File f = file(n.ci, n.cj), tmp = new File(dir, n.ci + "_" + n.cj + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
            new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(tmp), 1 << 16), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeLong(SCHEMA);
            for (Field fd : FIELDS) write(out, fd, n);
        } catch (IOException | RuntimeException | IllegalAccessException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) tmp.delete();
        }
    }

    /** {@link #save} on the background writer. */
    public void saveAsync(RiverNet n) {
        WRITER.execute(() -> save(n));
    }

    /** Waits for the writes queued so far. */
    public static void awaitWrites() {
        try {
            WRITER.submit(() -> {})
                .get();
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        } catch (ExecutionException ignored) {}
    }

    private static void write(DataOutputStream out, Field f, RiverNet n) throws IOException, IllegalAccessException {
        Class<?> t = f.getType();
        if (t == int.class) out.writeInt(f.getInt(n));
        else if (t == long.class) out.writeLong(f.getLong(n));
        else if (t == float.class) out.writeFloat(f.getFloat(n));
        else if (t == double.class) out.writeDouble(f.getDouble(n));
        else if (t.isArray()) {
            Object v = f.get(n);
            out.writeInt(v == null ? -1 : Array.getLength(v));
            if (v == null) return;
            if (v instanceof int[]a) for (int x : a) out.writeInt(x);
            else if (v instanceof float[]a) for (float x : a) out.writeInt(Float.floatToRawIntBits(x));
            else if (v instanceof double[]a) for (double x : a) out.writeLong(Double.doubleToRawLongBits(x));
            else if (v instanceof byte[]a) out.write(a);
            else if (v instanceof char[]a) for (char x : a) out.writeChar(x);
            else if (v instanceof short[]a) for (short x : a) out.writeShort(x);
            else throw new IOException("field " + f.getName());
        } else throw new IOException("field " + f.getName());
    }

    private static void read(DataInputStream in, Field f, RiverNet n) throws IOException, IllegalAccessException {
        Class<?> t = f.getType();
        if (t == int.class) f.setInt(n, in.readInt());
        else if (t == long.class) f.setLong(n, in.readLong());
        else if (t == float.class) f.setFloat(n, in.readFloat());
        else if (t == double.class) f.setDouble(n, in.readDouble());
        else if (t.isArray()) {
            int len = in.readInt();
            if (len < -1 || len > MAX_ARRAY) throw new IOException("length");
            if (len < 0) {
                f.set(n, null);
                return;
            }
            Class<?> c = t.getComponentType();
            if (c == int.class) {
                int[] a = new int[len];
                for (int i = 0; i < len; i++) a[i] = in.readInt();
                f.set(n, a);
            } else if (c == float.class) {
                float[] a = new float[len];
                for (int i = 0; i < len; i++) a[i] = Float.intBitsToFloat(in.readInt());
                f.set(n, a);
            } else if (c == double.class) {
                double[] a = new double[len];
                for (int i = 0; i < len; i++) a[i] = Double.longBitsToDouble(in.readLong());
                f.set(n, a);
            } else if (c == byte.class) {
                byte[] a = new byte[len];
                in.readFully(a);
                f.set(n, a);
            } else if (c == char.class) {
                char[] a = new char[len];
                for (int i = 0; i < len; i++) a[i] = in.readChar();
                f.set(n, a);
            } else if (c == short.class) {
                short[] a = new short[len];
                for (int i = 0; i < len; i++) a[i] = in.readShort();
                f.set(n, a);
            } else throw new IOException("field " + f.getName());
        } else throw new IOException("field " + f.getName());
    }
}
