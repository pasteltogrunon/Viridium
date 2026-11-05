package net.junedev.viridium.worldgen.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.core.Noise;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks that the Java port reproduces the JS prototype. Reference data comes from
 * {@code tools/worldgen/parity_dump.js}; regenerate it whenever worldgen.js changes.
 *
 * <p>
 * Continuous fields must match to ~1e-6 (the fixture keeps 10 significant digits). Discrete fields (plate, rock,
 * volcano type) may differ only where a value sits exactly on a threshold, which floating point libm differences
 * between V8 and the JVM can flip; that must stay rare.
 */
class ParityTest {

    private static List<String[]> csv(String name) throws IOException {
        InputStream in = ParityTest.class.getResourceAsStream("/worldgen/" + name);
        if (in == null) throw new IOException("missing test resource " + name);
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) if (!line.isEmpty()) rows.add(line.split(","));
        }
        return rows;
    }

    @Test
    void noisePrimitivesMatch() throws IOException {
        List<String[]> rows = csv("noise.csv");
        double maxErr = 0;
        for (String[] r : rows.subList(1, rows.size())) {
            String kind = r[0];
            int s = Integer.parseInt(r[3]);
            switch (kind) {
                case "hash" -> assertEquals(
                    Long.parseLong(r[5]),
                    Noise.hash(Integer.parseInt(r[1]), Integer.parseInt(r[2]), s) & 0xffffffffL,
                    "hash " + String.join(",", r));
                case "perlin" -> maxErr = Math.max(
                    maxErr,
                    Math.abs(Double.parseDouble(r[5]) - Noise.perlin(Double.parseDouble(r[1]), Double.parseDouble(r[2]), s)));
                case "fbm" -> maxErr = Math.max(
                    maxErr,
                    Math.abs(
                        Double.parseDouble(r[5]) - Noise
                            .fbm(Double.parseDouble(r[1]), Double.parseDouble(r[2]), s, Integer.parseInt(r[4]))));
                default -> throw new IllegalStateException(kind);
            }
        }
        assertTrue(maxErr < 1e-12, "noise max error " + maxErr);
    }

    private static final String[] DISCRETE = { "pi", "pj", "volcType", "rock", "rock2" };

    private static double field(GenPoint o, String f) {
        return switch (f) {
            case "Tb" -> o.Tb;
            case "Pb" -> o.Pb;
            case "lat" -> o.lat;
            case "pole" -> o.pole;
            case "windX" -> o.windX;
            case "windY" -> o.windY;
            case "pi" -> o.pi;
            case "pj" -> o.pj;
            case "edge" -> o.edge;
            case "edgeNC" -> o.edgeNC;
            case "conv" -> o.conv;
            case "ridge" -> o.ridge;
            case "orog" -> o.orog;
            case "trench" -> o.trench;
            case "arc" -> o.arc;
            case "collZ" -> o.collZ;
            case "collK" -> o.collK;
            case "collS" -> o.collS;
            case "L" -> o.L;
            case "h0" -> o.h0;
            case "mtnMask" -> o.mtnMask;
            case "volc" -> o.volc;
            case "volcType" -> o.volcType;
            case "collR" -> o.collR;
            case "rock" -> o.rock;
            case "rock2" -> o.rock2;
            case "rockT" -> o.rockT;
            case "erod" -> o.erod;
            default -> throw new IllegalArgumentException(f);
        };
    }

    private static final java.util.Set<String> FULL_DISCRETE = new java.util.HashSet<>(
        java.util.Arrays.asList("river", "lake", "seaIce", "biome"));

    private static double fullField(GenPoint o, String f) {
        return switch (f) {
            case "T" -> o.T;
            case "P" -> o.P;
            case "wfx" -> o.wfx;
            case "wfy" -> o.wfy;
            case "sst" -> o.sst;
            case "shadow" -> o.shadow;
            case "oro" -> o.oro;
            case "slope" -> o.slope;
            case "eroDelta" -> o.eroDelta;
            case "h" -> o.h;
            case "river" -> o.river;
            case "riverD" -> o.riverD;
            case "riverHW" -> o.riverHW;
            case "valley" -> o.valley;
            case "delta" -> o.delta;
            case "estu" -> o.estu;
            case "lake" -> o.lake;
            case "lakeZ" -> o.lakeZ;
            case "glac" -> o.glac;
            case "gorge" -> o.gorge;
            case "seaIce" -> o.seaIce;
            case "berg" -> o.berg;
            case "perma" -> o.perma;
            case "iceFrac" -> o.iceFrac;
            case "hs" -> o.hs;
            case "biome" -> o.biome;
            case "cr" -> o.color >> 16 & 255;
            case "cg" -> o.color >> 8 & 255;
            case "cb" -> o.color & 255;
            default -> throw new IllegalArgumentException(f);
        };
    }

    /** Climate, erosion, rivers (per-plate networks), cryosphere and biomes against the prototype. */
    @ParameterizedTest
    @ValueSource(ints = { 12345, 987654321 })
    void fullPipelineMatchesPrototype(int seed) throws IOException {
        List<String[]> rows = csv("full_" + seed + ".csv");
        String[] header = rows.get(0);
        GenParams p = new GenParams();
        p.seed = seed;
        p.fastFields = false; // exact port: compare with the prototype
        WorldGen gen = new WorldGen(p);
        GenPoint o = new GenPoint();
        Map<String, Double> maxErr = new LinkedHashMap<>();
        Map<String, Integer> miss = new LinkedHashMap<>();
        int bad = 0, rivers = 0, lakes = 0;
        StringBuilder examples = new StringBuilder();
        for (String[] r : rows.subList(1, rows.size())) {
            gen.evaluate(Double.parseDouble(r[0]), Double.parseDouble(r[1]), Stage.BIOMES, o);
            if (o.river != 0) rivers++;
            if (o.lake != 0) lakes++;
            boolean b = false;
            for (int c = 2; c < header.length; c++) {
                String f = header[c];
                double want = Double.parseDouble(r[c]), got = fullField(o, f);
                if (FULL_DISCRETE.contains(f)) {
                    if (want != got) {
                        miss.merge(f, 1, Integer::sum);
                        b = true;
                    }
                    continue;
                }
                boolean colour = f.length() == 2 && f.charAt(0) == 'c';
                double err = colour ? Math.max(0, Math.abs(want - got) - 1) : Math.abs(want - got) / Math.max(1, Math.abs(want));
                maxErr.merge(f, err, Math::max);
                if (err > 1e-5) {
                    b = true;
                    if (examples.length() < 2500)
                        examples.append(String.format("  (%s,%s) %s want %s got %s%n", r[0], r[1], f, want, got));
                }
            }
            if (b) bad++;
        }
        int n = rows.size() - 1;
        System.out.printf("full seed %d: %d points (%d river, %d lake), %d differ (%.3f%%), %d nets built%n", seed, n,
            rivers, lakes, bad, 100.0 * bad / n, gen.rivers.buildCount());
        System.out.println("  max relative error per field: " + maxErr);
        if (!miss.isEmpty()) System.out.println("  discrete mismatches: " + miss);
        if (examples.length() > 0) System.out.print(examples);
        assertTrue(bad <= n * 0.005, "too many points differ from the JS prototype: " + bad + "/" + n);
    }

    @ParameterizedTest
    @ValueSource(ints = { 12345, 987654321 })
    void pipelineMatchesPrototype(int seed) throws IOException {
        List<String[]> rows = csv("points_" + seed + ".csv");
        String[] header = rows.get(0);
        GenParams p = new GenParams();
        p.seed = seed;
        p.fastFields = false; // exact port: compare with the prototype
        WorldGen gen = new WorldGen(p);
        GenPoint o = new GenPoint();

        Map<String, Double> maxErr = new LinkedHashMap<>();
        Map<String, Integer> discreteMiss = new LinkedHashMap<>();
        int badPoints = 0;
        StringBuilder examples = new StringBuilder();
        for (String[] r : rows.subList(1, rows.size())) {
            double x = Double.parseDouble(r[0]), z = Double.parseDouble(r[1]);
            gen.evaluate(x, z, Stage.GEOLOGY, o);
            boolean bad = false;
            for (int c = 2; c < header.length; c++) {
                String f = header[c];
                double want = Double.parseDouble(r[c]), got = field(o, f);
                boolean discrete = java.util.Arrays.asList(DISCRETE)
                    .contains(f);
                if (discrete) {
                    if (want != got) {
                        discreteMiss.merge(f, 1, Integer::sum);
                        bad = true;
                    }
                } else {
                    double err = Math.abs(want - got) / Math.max(1, Math.abs(want));
                    maxErr.merge(f, err, Math::max);
                    if (err > 1e-6) {
                        bad = true;
                        if (examples.length() < 2000)
                            examples.append(String.format("  (%s,%s) %s want %s got %s%n", r[0], r[1], f, want, got));
                    }
                }
            }
            if (bad) badPoints++;
        }
        int n = rows.size() - 1;
        System.out.printf("seed %d: %d points, %d differ (%.3f%%)%n", seed, n, badPoints, 100.0 * badPoints / n);
        System.out.println("  max relative error per field: " + maxErr);
        if (!discreteMiss.isEmpty()) System.out.println("  discrete mismatches: " + discreteMiss);
        if (examples.length() > 0) System.out.print(examples);
        assertTrue(badPoints <= n * 0.005, "too many points differ from the JS prototype: " + badPoints + "/" + n);
    }
}
