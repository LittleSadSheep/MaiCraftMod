package org.maiwithu.maicraft.intent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.tools.work.BuildTool;
import sun.misc.Unsafe;

/** Exercise the actual loaded-site selector and investigation retry on default superflat terrain. */
public final class SemanticBuildSiteTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Unsafe memory = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        FlatLevel level = (FlatLevel) memory.allocateInstance(FlatLevel.class);
        LocalPlayer player = (LocalPlayer) memory.allocateInstance(LocalPlayer.class);
        level.groundY = -61; level.loaded = true;
        field(Level.class, "dimension").set(level, Level.OVERWORLD);
        field(LocalPlayer.class, "clientLevel").set(player, level);
        field(Entity.class, "level").set(player, level);
        field(Entity.class, "position").set(player, new Vec3(.5, -60, .5));
        field(Entity.class, "blockPosition").set(player, new BlockPos(0, -60, 0));
        field(Player.class, "inventory").set(player, new Inventory(player));

        for (String size : List.of("\"small\"", "{\"width\":5,\"depth\":5,\"height\":4}")) {
            for (boolean replace : List.of(false, true)) for (boolean coordinate : List.of(false, true)) {
                Goal goal = goal(size, replace, coordinate, false);
                IntentAction action = SemanticBuildPlanner.plan(goal, player, null);
                check(action instanceof IntentAction.Tool tool && tool.toolName().equals("build"),
                        "flat loaded grass at -60 must immediately produce a build, not start exploring: " + action);
                var probe = SemanticBuildPlanner.probeLoadedBuildAt(goal, player, null, player.blockPosition());
                check(probe.status() == SemanticBuildPlanner.LoadedBuildProbe.Status.READY,
                        "investigation must freeze the same loaded superflat site");
                var targets = BuildTool.resolvedTargets(probe.buildArguments().getAsJsonArray("ops"));
                int floorY = replace ? -61 : -60;
                check(targets.stream().filter(t -> t.materialCount() > 0).anyMatch(t -> t.pos().getY() == floorY),
                        "frozen plan needs a concrete floor at the verified surface");
                check(targets.stream().allMatch(t -> t.pos().getY() >= -64 && t.pos().getY() < 320),
                        "frozen target cells must stay within the world's build limits");
            }
        }

        check(noSite(goal("\"small\"", true, false, true), player),
                "a cellar below the bottom of a shallow world must still be refused");
        level.loaded = false;
        check(noSite(goal("\"small\"", false, false, false), player), "unloaded terrain is not a verified site");
        level.loaded = true; level.water = true;
        check(noSite(goal("\"small\"", false, false, false), player), "water cannot become dry house ground");
        level.water = false; level.groundY = -65;
        check(noSite(goal("\"small\"", false, false, false), player), "an empty column is not ground");
        level.groundY = -64;
        check(!noSite(goal("\"small\"", false, false, false), player),
                "a surface house above the bottom layer does not require underground clearance");
        System.out.println("SemanticBuildSiteTest: loaded superflat site freezes without frontier travel");
    }

    private static boolean noSite(Goal goal, LocalPlayer player) {
        return SemanticBuildPlanner.probeLoadedBuildAt(goal, player, null, player.blockPosition()).status()
                == SemanticBuildPlanner.LoadedBuildProbe.Status.NO_SITE;
    }

    private static Goal goal(String size, boolean replace, boolean coordinate, boolean cellar) {
        JsonObject parameters = JsonParser.parseString("{\"purpose\":\"small_house\",\"size\":" + size
                + ",\"style\":\"simple wooden hut\",\"terrain_fit\":\"surface\",\"material_policy\":\"specified\","
                + "\"preferred_materials\":[\"minecraft:oak_planks\"],\"replace_existing\":" + replace
                + ",\"features\":" + (cellar ? "[\"cellar\"]" : "[]") + "}").getAsJsonObject();
        return new Goal("maicraft:build", "small_house", new Goal.SemanticTarget(
                coordinate ? "coordinates" : "current_place", null,
                coordinate ? new Goal.WorldPosition(0, -60, 0, "minecraft:overworld") : null, null),
                parameters.toString(), "{}", List.of(), List.of());
    }

    private static final class FlatLevel extends ClientLevel {
        int groundY;
        boolean loaded, water;
        private FlatLevel() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public boolean hasChunkAt(BlockPos pos) { return loaded; }
        @Override public boolean isLoaded(BlockPos pos) { return loaded; }
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public int getHeight(Heightmap.Types type, int x, int z) { return groundY + 1; }
        @Override public BlockState getBlockState(BlockPos pos) {
            check(loaded, "site selection tried to read unloaded terrain");
            check(pos.getY() >= -64 && pos.getY() < 320, "site selection read beyond build bounds: " + pos);
            return (pos.getY() > groundY ? Blocks.AIR : pos.getY() == groundY
                    ? (water ? Blocks.WATER : Blocks.GRASS_BLOCK) : Blocks.DIRT).defaultBlockState();
        }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
