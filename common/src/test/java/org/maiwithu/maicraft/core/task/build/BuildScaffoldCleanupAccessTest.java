// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.TerrainPermit;
import org.maiwithu.maicraft.core.pathing.moves.movements.BuildPlacementRegistry;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.level.GameType;

/** 有限观察、原有保护与只点击不拆的支点例外；这里不模拟已经走到浮空支撑，也不发游戏动作。 */
public final class BuildScaffoldCleanupAccessTest {
    private static final BlockPos START = new BlockPos(7, 4, 7), GOAL = new BlockPos(8, 7, 8), GROUND = new BlockPos(7, 3, 8);
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        observedTerrainAndSupportUse(); siteBoundsAndBudgets(); preparationAndDeadline();
        System.out.println("BuildScaffoldCleanupAccessTest: bounded protection, ordinary support clicks, provider forwarding and deadlines passed");
    }
    private static void observedTerrainAndSupportUse() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.position(Vec3.atBottomCenterOf(START)); h.set(START.below(), Blocks.STONE.defaultBlockState());
            h.set(GROUND, Blocks.GRASS_BLOCK.defaultBlockState()); h.set(GOAL.below(), Blocks.STONE_BRICKS.defaultBlockState());
            var parent = new Parent(); var access = new BuildScaffoldCleanupAccess(h.player, NavGoal.exact(GOAL), () -> false, parent);
            var scope = scope(access); int before = h.level.blockReads;
            check(scope.scan(10_000) <= 512 && h.level.blockReads - before <= 512 && !scope.complete(),
                    "initial terrain observation is sliced and cannot silently become an unbounded synchronous scan");
            while (!scope.complete()) { scope.scan(512); check(scope.failure == null, "loaded fixture snapshot must stay available"); }
            check(access.permit() == TerrainPermit.TERRAFORM && access.embeddedProtectedMutationCells().contains(GROUND.asLong())
                    && access.embeddedProtectedMutationCells().contains(GOAL.below().asLong()), "grass and finished walls are both protected from digging");
            check(access.embeddedForbiddenBodyCells().contains(scope.min.asLong()), "the finite observation region has a hard body boundary");
            BlockPos next = GROUND.above();
            check(access.permitsTemporaryScaffold(next) && access.permitsScaffoldSupport(GROUND, next, h.level.getBlockState(GROUND)),
                    "scope-only no-dig protection still allows the first ordinary dirt support to be placed against native grass");
            check(!parent.permitsScaffoldSupport(GROUND, next, h.level.getBlockState(GROUND)), "the positive branch does not rely on a permissive parent exception");
            parent.protectedCells.add(GROUND.asLong());
            check(!access.permitsScaffoldSupport(GROUND, next, h.level.getBlockState(GROUND)), "original owner protection cannot be relaxed by the no-dig wrapper");
            parent.allowProtectedClick = true;
            check(access.permitsScaffoldSupport(GROUND, next, h.level.getBlockState(GROUND)), "an explicit original-provider exception remains usable");
            parent.protectedCells.clear(); parent.allowProtectedClick = false;
            h.set(next, Blocks.DIRT.defaultBlockState());
            scope.refresh(BlockPos.containing(h.player.getEyePosition()), 6);
            check(access.embeddedProtectedMutationCells().contains(next.asLong())
                    && access.permitsScaffoldSupport(next, next.above(), Blocks.DIRT.defaultBlockState()),
                    "a freshly observed native dirt pillar remains protected from breaking while usable as the next placement support");
            BlockPos foreign = new BlockPos(9, 4, 8); h.set(foreign, Blocks.CHEST.defaultBlockState());
            int reads = scope.refresh(BlockPos.containing(h.player.getEyePosition()), 6);
            check(reads <= 2197 && access.embeddedProtectedMutationCells().contains(foreign.asLong())
                    && !access.permitsScaffoldSupport(foreign, foreign.above(), h.level.getBlockState(foreign)),
                    "newly appearing external containers are protected and never used through the ordinary support exception");
            parent.forbidden.add(next.above().asLong());
            check(!access.permitsTemporaryScaffold(next.above()), "parent body exclusions remain placement exclusions");
            check(access.scaffoldReservations().equals(Map.of(Items.DIRT, 4)), "original retained construction materials stay reserved");
            // 显式重放此前取得的原生确认通知，仅验证代理身份转发，不能据此声称本回归实际放置了方块。
            BuildPlacementRegistry.register(h.player, access.provider());
            try { BuildPlacementRegistry.recordConfirmedScaffold(access.provider(), next, Blocks.DIRT.defaultBlockState()); }
            finally { BuildPlacementRegistry.unregister(h.player, access.provider()); }
            check(parent.confirmed == 1 && h.blockUses() == 0 && h.itemUses() == 0, "the active wrapper forwards exactly the native ownership receipt without a hidden world action");
        }
    }
    private static void siteBoundsAndBudgets() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var start = new BlockPos(-8, 2, 6); var goal = NavGoal.exact(new BlockPos(-8, 10, 7));
            var narrow = new BuildScaffoldCleanupAccess.Scope(h.level, start, goal);
            var whole = new BuildScaffoldCleanupAccess.Scope(h.level, start, goal, new BlockPos(-11, 1, -11), new BlockPos(11, 10, 9));
            var door = new BlockPos(0, 2, -11);
            check(!narrow.inside(door) && whole.inside(door), "including the building bounds preserves the real front-door detour from the basement");
            rejects(() -> new BuildScaffoldCleanupAccess.Scope(h.level, START, NavGoal.column(8, 8)));
            rejects(() -> new BuildScaffoldCleanupAccess.Scope(h.level, START, NavGoal.exact(GOAL), new BlockPos(-100, 1, -100), new BlockPos(100, 10, 100)));
            var loaded = new BuildScaffoldCleanupAccess.Scope(h.level, START, NavGoal.exact(GOAL));
            int before = h.level.blockReads; loaded.refresh(new BlockPos(32, 5, 8), 1);
            check(loaded.failure != null && h.level.blockReads == before, "unknown chunk observations fail before touching unloaded world data");
        }
    }
    private static void preparationAndDeadline() throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            // 原版触及距离会读取玩家缓存的游戏模式，回归显式安装生存信息，不访问不存在的联网连接。
            var info = new PlayerInfo(new GameProfile(UUID.randomUUID(), "cleanup-access"), false);
            Field cached = AbstractClientPlayer.class.getDeclaredField("playerInfo"); cached.setAccessible(true); cached.set(h.player, info);
            Field mode = PlayerInfo.class.getDeclaredField("gameMode"); mode.setAccessible(true); mode.set(info, GameType.SURVIVAL);
            h.position(Vec3.atBottomCenterOf(START)); var ready = new AtomicBoolean(false);
            var access = new BuildScaffoldCleanupAccess(h.player, NavGoal.exact(GOAL), ready::get, new Parent());
            check(access.tick() == BuildScaffoldCleanupAccess.Status.RUNNING && !(boolean) access.evidence().get("route_created"),
                    "no terrain-changing route starts before the complete finite snapshot");
            ready.set(true);
            // 触距被装备提升、累计观察超过旧阈值时仍继续清理，不能凭内部统计拒绝角色动作。
            h.player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(8);
            Field reads = BuildScaffoldCleanupAccess.class.getDeclaredField("totalReads"); reads.setAccessible(true);
            reads.setInt(access, 2_500_001);
            for (int i = 0; i < 10 && access.tick() == BuildScaffoldCleanupAccess.Status.RUNNING; i++) h.nextTick();
            check(access.tick() == BuildScaffoldCleanupAccess.Status.READY && !(boolean) access.evidence().get("route_created"),
                    "an actually ready cleanup stance finishes without manufacturing a navigation result");
            var expired = new BuildScaffoldCleanupAccess(h.player, NavGoal.exact(GOAL), () -> false, new Parent());
            for (int i = 0; i < 600; i++) h.nextTick();
            check(expired.tick() == BuildScaffoldCleanupAccess.Status.FAILED && expired.failure().equals("cleanup_access_deadline"),
                    "one approach has a finite physical game-tick deadline independent of planner activity");
            check(h.blockUses() == 0 && h.itemUses() == 0, "readiness and timeout do not consume blocks or operate a menu");
        }
    }
    private static BuildScaffoldCleanupAccess.Scope scope(BuildScaffoldCleanupAccess access) throws Exception {
        Field field = BuildScaffoldCleanupAccess.class.getDeclaredField("scope"); field.setAccessible(true); return (BuildScaffoldCleanupAccess.Scope) field.get(access);
    }
    private static final class Parent implements PlayerNav.ContextProvider, BuildPlacementRegistry.Provider {
        final LongOpenHashSet protectedCells = new LongOpenHashSet(), forbidden = new LongOpenHashSet();
        int confirmed; boolean allowProtectedClick;
        public TerrainPermit permit() { return TerrainPermit.TERRAFORM; }
        public LongSet embeddedProtectedMutationCells() { return protectedCells; }
        public LongSet embeddedForbiddenBodyCells() { return forbidden; }
        public BlockState desiredState(BlockPos at) { return null; }
        public boolean permitsScaffoldSupport(BlockPos clicked, BlockPos at, BlockState state) { return allowProtectedClick; }
        public void confirmedScaffold(BlockPos pos, BlockState state) { confirmed++; }
        public Map<Item, Integer> scaffoldReservations() { return Map.of(Items.DIRT, 4); }
    }
    private static void rejects(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("unbounded or unspecified cleanup access was admitted");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
