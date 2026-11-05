package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.TAU;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.core.Noise.rnd;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;

/**
 * 9. Stateless erosion ("eroded terrain noise", Clay John): sum of waves whose fronts follow the slope, branching
 * between octaves. Depth ∝ rock erodibility × f(precipitation, temperature). Only digs.
 */
public class ErosionStage extends GenStage {

    public ErosionStage(WorldGen gen) {
        super(gen);
    }

    /** Writes value and gradient into {@code s.EV, s.EGX, s.EGY}. */
    protected void gullies(double px, double py, double dx, double dy, GenScratch s) {
        int ix = floor(px), iy = floor(py);
        double fx = px - ix, fy = py - iy;
        double va = 0, vx = 0, vy = 0, wt = 0;
        for (int j = -2; j <= 1; j++) {
            for (int i = -2; i <= 1; i++) {
                int cx = ix - i, cy = iy - j;
                double ppx = fx + i - (rnd(cx, cy, SD.ero) - 0.5);
                double ppy = fy + j - (rnd(cx, cy, SD.ero + 1) - 0.5);
                double w = Math.exp(-2 * (ppx * ppx + ppy * ppy));
                wt += w;
                double m = (ppx * dx + ppy * dy) * TAU;
                va += Math.cos(m) * w;
                double sn = -Math.sin(m) * w;
                vx += sn * dx;
                vy += sn * dy;
            }
        }
        s.EV = va / wt;
        s.EGX = vx / wt;
        s.EGY = vy / wt;
    }

    /** Gradient of the smoothed relief around (x, y). Override to read a cached field. */
    protected void reliefGradient(double x, double y, GenPoint o, double bpp, GenScratch s) {
        final double E = 12;
        o.gx = (gen.heightAt(x + E, y, false) - gen.heightAt(x - E, y, false)) / (2 * E);
        o.gy = (gen.heightAt(x, y + E, false) - gen.heightAt(x, y - E, false)) / (2 * E);
    }

    public void apply(double x, double y, GenPoint o, double bpp, GenScratch s) {
        double h0 = o.h0;
        reliefGradient(x, y, o, bpp, s);
        double gx = o.gx, gy = o.gy;
        double g = Math.sqrt(gx * gx + gy * gy);
        o.slope = g;
        double Lg = P.erosionScale, BRANCH = 1.0, GAIN = 0.45;
        // wave frequency ∝ slope (0 on flats → no gullies), saturated to avoid noise
        double freq = Math.min(g * 2.5, 1.1), inv = g > 1e-6 ? freq / g : 0;
        double dX = gy * inv, dY = -gx * inv; // perpendicular to the gradient
        double hv = 0, hdx = 0, hdy = 0, a = 0.5, f = 1, sumA = 0;
        for (int oc = 0; oc < 4; oc++) {
            double fade = ss(bpp * 1.2, bpp * 3, Lg / f); // anti-aliasing when zoomed out
            if (fade <= 0) break;
            gullies(x * f / Lg, y * f / Lg, dX + hdy * BRANCH, dY - hdx * BRANCH, s);
            double af = a * fade;
            hv += s.EV * af;
            hdx += s.EGX * af * f;
            hdy += s.EGY * af * f;
            sumA += af;
            a *= GAIN;
            f *= 2;
        }
        double raw = (hv - sumA) * 0.5; // ∈ [-sumA, 0]: only digs
        double tN = o.T / 7;
        double clim = (0.3 + clamp(o.P / 1500, 0, 1.5)) * (1 + 0.6 * Math.exp(-tN * tN)); // rain + freeze/thaw
        double amount = P.erosionStrength * 32 * o.erod * clim * ss(-8, 6, h0);
        o.eroDelta = raw * amount;
        o.h = h0 + o.eroDelta;
    }
}
