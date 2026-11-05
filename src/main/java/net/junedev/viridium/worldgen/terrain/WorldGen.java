package net.junedev.viridium.worldgen.terrain;

import java.util.function.Function;

import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.core.Seeds;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;
import net.junedev.viridium.worldgen.terrain.stages.ClimateBaseStage;
import net.junedev.viridium.worldgen.terrain.stages.ContinentStage;
import net.junedev.viridium.worldgen.terrain.stages.GeologyStage;
import net.junedev.viridium.worldgen.terrain.stages.PlateStage;
import net.junedev.viridium.worldgen.terrain.stages.ReliefStage;
import net.junedev.viridium.worldgen.terrain.stages.VolcanismStage;

/**
 * Random-access terrain pipeline: {@link #evaluate} depends only on (x, z) and the parameters, never on evaluation
 * order. Thread-safe; all mutable temporaries live in a per-thread {@link GenScratch}.
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
    }

    public GenScratch scratch() {
        return scratch.get();
    }

    /** Evaluates every stage up to and including {@code upTo} at (x, z), writing into {@code o}. */
    public GenPoint evaluate(double x, double z, Stage upTo, GenPoint o) {
        GenScratch s = scratch();
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
        return o;
    }

    /**
     * Base height (plates + continents + relief) at an arbitrary point. {@code detail = false} skips fine octaves;
     * used by stages that sample the relief around a point.
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

        public WorldGen build() {
            return new WorldGen(this);
        }
    }
}
