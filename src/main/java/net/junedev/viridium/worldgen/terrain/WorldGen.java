package net.junedev.viridium.worldgen.terrain;

import java.util.function.Function;

import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.core.Seeds;
import net.junedev.viridium.worldgen.terrain.crust.CrustModel;
import net.junedev.viridium.worldgen.terrain.fields.FastClimateStage;
import net.junedev.viridium.worldgen.terrain.fields.FastErosionStage;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;
import net.junedev.viridium.worldgen.terrain.stages.BiomeStage;
import net.junedev.viridium.worldgen.terrain.stages.ClimateBaseStage;
import net.junedev.viridium.worldgen.terrain.stages.ClimateStage;
import net.junedev.viridium.worldgen.terrain.stages.ContinentStage;
import net.junedev.viridium.worldgen.terrain.stages.CryoStage;
import net.junedev.viridium.worldgen.terrain.stages.ErosionStage;
import net.junedev.viridium.worldgen.terrain.stages.GeologyStage;
import net.junedev.viridium.worldgen.terrain.stages.PlateStage;
import net.junedev.viridium.worldgen.terrain.stages.ReliefStage;
import net.junedev.viridium.worldgen.terrain.stages.RiverStage;
import net.junedev.viridium.worldgen.terrain.stages.VolcanismStage;

/**
 * Random-access terrain pipeline: {@link #evaluate} depends only on (x, z) and the parameters, never on evaluation
 * order (caches are deterministic). Thread-safe; all mutable temporaries live in a per-thread {@link GenScratch}.
 *
 * <p>
 * Each stage is an independent class. To experiment with one, subclass it and plug it in through {@link Builder}; the
 * other stages are untouched.
 */
public final class WorldGen {

    public final GenParams params;
    public final Seeds seeds;

    public final ClimateBaseStage climate;
    public final PlateStage plates;
    public final ContinentStage continents;
    public final ReliefStage relief;
    public final VolcanismStage volcanism;
    public final GeologyStage geology;
    public final ClimateStage climateFinal;
    public final ErosionStage erosion;
    public final RiverStage rivers;
    public final CryoStage cryo;
    public final BiomeStage biomes;
    /** 3D crust (not a per-point stage: evaluated per column and per block). */
    public final CrustModel crust;
    /** Caves carved into the crust when cubes are filled (noise caves, aquifers, planned shapes). */
    public final net.junedev.viridium.worldgen.terrain.caves.CaveCarver caves;

    private final ThreadLocal<GenScratch> scratch = ThreadLocal.withInitial(GenScratch::new);

    public WorldGen(GenParams params) {
        this(new Builder(params));
    }

    private WorldGen(Builder b) {
        this.params = b.params.copy();
        this.seeds = new Seeds(this.params.seed);
        this.climate = b.climate.apply(this);
        this.plates = b.plates.apply(this);
        this.continents = b.continents.apply(this);
        this.relief = b.relief.apply(this);
        this.volcanism = b.volcanism.apply(this);
        this.geology = b.geology.apply(this);
        this.climateFinal = b.climateFinal.apply(this);
        this.erosion = b.erosion.apply(this);
        this.rivers = b.rivers.apply(this);
        this.cryo = b.cryo.apply(this);
        this.biomes = b.biomes.apply(this);
        this.crust = b.crust.apply(this);
        this.caves = new net.junedev.viridium.worldgen.terrain.caves.CaveCarver(this);
    }

    public GenScratch scratch() {
        return scratch.get();
    }

    /** Evaluates every stage up to and including {@code upTo} at (x, z) at game resolution (1 block/pixel). */
    public GenPoint evaluate(double x, double z, Stage upTo, GenPoint o) {
        return evaluate(x, z, upTo, 1, o);
    }

    /**
     * Evaluates every stage up to and including {@code upTo}. {@code bpp} (blocks per pixel) fades detail that would
     * alias when the result is viewed from far away (map); use 1 for the game.
     */
    public GenPoint evaluate(double x, double z, Stage upTo, double bpp, GenPoint o) {
        return evaluate(x, z, upTo, bpp, o, scratch());
    }

    /** As above with explicit temporaries (for evaluations nested inside another one, e.g. river network builds). */
    public GenPoint evaluate(double x, double z, Stage upTo, double bpp, GenPoint o, GenScratch s) {
        s.bpp = bpp;
        o.x = x;
        o.z = z;
        climate.apply(x, z, o);
        if (upTo == Stage.CLIMATE) return o;
        plates.apply(x, z, o, s);
        if (upTo == Stage.PLATES) return o;
        continents.apply(x, z, o);
        if (upTo == Stage.CONTINENTS) return o;
        o.h0 = relief.baseHeight(x, z, o, true, s);
        o.h = o.h0;
        if (upTo == Stage.RELIEF) return o;
        geology.apply(x, z, o, s);
        if (upTo == Stage.GEOLOGY) return o;
        climateFinal.apply(x, z, o, s);
        if (upTo == Stage.CLIMATE_FINAL) return o;
        erosion.apply(x, z, o, bpp, s);
        if (upTo == Stage.EROSION) return o;
        rivers.apply(x, z, o, s);
        caves.surface(x, z, o);
        if (upTo == Stage.RIVERS) return o;
        cryo.apply(x, z, o, bpp);
        if (upTo == Stage.CRYOSPHERE) return o;
        biomes.apply(o);
        return o;
    }

    /**
     * Base height (plates + continents + relief) at an arbitrary point. {@code detail = false} skips fine octaves;
     * used by stages that sample the relief around a point. Leaves the details in {@code scratch().heightPoint}.
     */
    public double heightAt(double x, double z, boolean detail) {
        GenScratch s = scratch();
        GenPoint p = s.heightPoint;
        plates.apply(x, z, p, s);
        continents.apply(x, z, p);
        return relief.baseHeight(x, z, p, detail, s);
    }

    /** Drops deterministic caches (the world is identical afterwards). */
    public void clearCaches() {
        volcanism.clearCache();
        rivers.clearCache();
    }

    /** Lets callers replace individual stages with custom implementations. */
    public static final class Builder {

        private final GenParams params;
        private Function<WorldGen, ClimateBaseStage> climate = ClimateBaseStage::new;
        private Function<WorldGen, PlateStage> plates = PlateStage::new;
        private Function<WorldGen, ContinentStage> continents = ContinentStage::new;
        private Function<WorldGen, ReliefStage> relief = ReliefStage::new;
        private Function<WorldGen, VolcanismStage> volcanism = VolcanismStage::new;
        private Function<WorldGen, GeologyStage> geology = GeologyStage::new;
        private Function<WorldGen, ClimateStage> climateFinal = g -> g.params.fastFields ? new FastClimateStage(g)
            : new ClimateStage(g);
        private Function<WorldGen, ErosionStage> erosion = g -> g.params.fastFields ? new FastErosionStage(g)
            : new ErosionStage(g);
        private Function<WorldGen, RiverStage> rivers = RiverStage::new;
        private Function<WorldGen, CryoStage> cryo = CryoStage::new;
        private Function<WorldGen, BiomeStage> biomes = BiomeStage::new;
        private Function<WorldGen, CrustModel> crust = g -> new CrustModel(g.params, g.seeds);

        public Builder(GenParams params) {
            this.params = params;
        }

        public Builder climate(Function<WorldGen, ClimateBaseStage> f) {
            climate = f;
            return this;
        }

        public Builder plates(Function<WorldGen, PlateStage> f) {
            plates = f;
            return this;
        }

        public Builder continents(Function<WorldGen, ContinentStage> f) {
            continents = f;
            return this;
        }

        public Builder relief(Function<WorldGen, ReliefStage> f) {
            relief = f;
            return this;
        }

        public Builder volcanism(Function<WorldGen, VolcanismStage> f) {
            volcanism = f;
            return this;
        }

        public Builder geology(Function<WorldGen, GeologyStage> f) {
            geology = f;
            return this;
        }

        public Builder climateFinal(Function<WorldGen, ClimateStage> f) {
            climateFinal = f;
            return this;
        }

        public Builder erosion(Function<WorldGen, ErosionStage> f) {
            erosion = f;
            return this;
        }

        public Builder rivers(Function<WorldGen, RiverStage> f) {
            rivers = f;
            return this;
        }

        public Builder cryo(Function<WorldGen, CryoStage> f) {
            cryo = f;
            return this;
        }

        public Builder biomes(Function<WorldGen, BiomeStage> f) {
            biomes = f;
            return this;
        }

        public Builder crust(Function<WorldGen, CrustModel> f) {
            crust = f;
            return this;
        }

        public WorldGen build() {
            return new WorldGen(this);
        }
    }
}
