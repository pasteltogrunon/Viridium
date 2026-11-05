package net.junedev.viridium.worldgen.terrain;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import net.junedev.viridium.worldgen.terrain.bench.TerrainBenchmark;
import net.junedev.viridium.worldgen.terrain.core.GenParams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runs {@link TerrainBenchmark} only when asked: set {@code VIRIDIUM_BENCH=1} and run {@code gradlew test --tests
 * '*TerrainBenchmarkTest'}. The report is printed and saved to build/reports/worldgen-bench.txt.
 */
class TerrainBenchmarkTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_BENCH", matches = "1")
    void benchmark() throws IOException {
        String report = new TerrainBenchmark(new GenParams(), 2000).run();
        System.out.print(report);
        File f = new File("build/reports/worldgen-bench.txt");
        f.getParentFile()
            .mkdirs();
        Files.write(f.toPath(), report.getBytes(StandardCharsets.UTF_8));
    }
}
