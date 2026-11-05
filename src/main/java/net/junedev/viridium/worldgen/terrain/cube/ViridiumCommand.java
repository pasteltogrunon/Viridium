package net.junedev.viridium.worldgen.terrain.cube;

import java.util.Collections;
import java.util.List;

import net.junedev.viridium.Viridium;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanner;
import net.junedev.viridium.worldgen.terrain.column.TerrainLayout;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * {@code /viridium bench [radius] [cubesDown] [cubesUp]}: measures the real generator around the sender (column plans
 * and real block storage for every cube) without adding anything to the world. Plans are computed fresh (not taken
 * from the cache), river networks and coarse fields stay warm, like a player exploring.
 */
public class ViridiumCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "viridium";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/viridium bench [radius=4] [cubesDown=8] [cubesUp=8]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.emptyList();
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equals("bench")) {
            say(sender, getCommandUsage(sender));
            return;
        }
        World world = sender.getEntityWorld();
        ViridiumWorldGenerator gen = ViridiumWorldGenerator.forWorld(world);
        if (gen == null) {
            say(sender, "This world does not use the Viridium generator.");
            return;
        }
        int radius = args.length > 1 ? parseIntBounded(sender, args[1], 0, 32) : 4;
        int down = args.length > 2 ? parseIntBounded(sender, args[2], 0, 200) : 8;
        int up = args.length > 3 ? parseIntBounded(sender, args[3], 0, 100) : 8;
        int pcx = MathHelper.floor_double(sender.getPlayerCoordinates().posX) >> 4;
        int pcz = MathHelper.floor_double(sender.getPlayerCoordinates().posZ) >> 4;
        int pcy = sender.getPlayerCoordinates().posY >> 4;
        ColumnPlanner planner = gen.plans()
            .planner();
        long planNs = 0, fillNs = 0;
        int columns = 0;
        int[] kinds = new int[CubeFiller.Kind.values().length];
        long[] kindNs = new long[kinds.length];
        long nets0 = planner.gen().rivers.buildCount(), netNs0 = planner.gen().rivers.buildNanos();
        for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
            long t0 = System.nanoTime();
            ColumnPlan plan = planner.plan(pcx + dx, pcz + dz);
            planNs += System.nanoTime() - t0;
            columns++;
            for (int cy = pcy - down; cy <= pcy + up; cy++) {
                CubeFiller.Kind k = CubeFiller.classify(plan, cy);
                long t1 = System.nanoTime();
                gen.filler()
                    .fill(plan, pcx + dx, cy, pcz + dz);
                long dt = System.nanoTime() - t1;
                fillNs += dt;
                kinds[k.ordinal()]++;
                kindNs[k.ordinal()] += dt;
            }
        }
        long nets = planner.gen().rivers.buildCount() - nets0;
        double netMs = (planner.gen().rivers.buildNanos() - netNs0) / 1e6;
        int cubes = columns * (down + up + 1);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Viridium bench: %d columns, %d cubes (Y %d..%d)%n", columns, cubes,
            (pcy - down) << 4, ((pcy + up) << 4) + 15));
        sb.append(String.format("  column plan: %.3f ms each (incl. %d river network builds, %.0f ms)%n",
            planNs / 1e6 / columns, nets, netMs));
        sb.append(String.format("  cube fill: %.3f ms each on average%n", fillNs / 1e6 / cubes));
        for (CubeFiller.Kind k : CubeFiller.Kind.values()) {
            int n = kinds[k.ordinal()];
            if (n > 0) sb.append(String.format("    %-7s %5d cubes, %.3f ms each%n", k, n, kindNs[k.ordinal()] / 1e6 / n));
        }
        sb.append(String.format("  ≈ %.1f ms per column of %d cubes (sea level %d, world floor %d)",
            (planNs + fillNs) / 1e6 / columns, down + up + 1, TerrainLayout.SEA_LEVEL, TerrainLayout.MIN_Y));
        Viridium.LOGGER.info(sb.toString());
        for (String l : sb.toString()
            .split("\n")) say(sender, l);
    }

    private static void say(ICommandSender s, String msg) {
        s.addChatMessage(new ChatComponentText(msg));
    }
}
