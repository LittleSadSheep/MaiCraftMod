// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 生存需求的离线测试替身：不接触真实客户端，按脚本回答处境、记下发出的输入与挖过的格子。
 */
final class SurvivalFakes {

    private SurvivalFakes() {}

    /** 处境构造器：默认是站在地上、头在空气里、氧气满、没被埋的平静处境，测试只改要的那几项。 */
    static final class Situation {
        double health = 20.0;
        double feetY = 64.0;
        float yaw = 37.0f;
        boolean falling;
        boolean overVoid;
        boolean landsInWater;
        boolean survivesLanding = true;
        BlockPos waterCell;
        boolean headInWater;
        boolean canBreatheUnderwater;
        int air = 300;
        int maxAir = 300;
        int airToSurface = 69;
        boolean stuck;
        BlockPos buried;

        Situation feet(double y) { feetY = y; return this; }
        Situation fallingOnto(BlockPos cell, boolean survives) {
            falling = true;
            waterCell = cell;
            survivesLanding = survives;
            return this;
        }
        Situation fallingIntoVoid() { falling = true; overVoid = true; survivesLanding = false; return this; }
        Situation fallingIntoWater() { falling = true; landsInWater = true; return this; }
        Situation underwater(int airTicks) { headInWater = true; air = airTicks; return this; }
        Situation breathing() { canBreatheUnderwater = true; return this; }
        Situation surfaceNeeds(int airTicks) { airToSurface = airTicks; return this; }
        Situation air(int airTicks) { air = airTicks; return this; }
        Situation buriedAt(BlockPos cell) { stuck = true; buried = cell; return this; }

        SurvivalSituation build() {
            return new SurvivalSituation(health, feetY, yaw, falling, overVoid, landsInWater, survivesLanding,
                    waterCell, headInWater, canBreatheUnderwater, air, maxAir, airToSurface, stuck, buried);
        }
    }

    static Situation calm() {
        return new Situation();
    }

    /** 头在水里、氧气只剩一泡：必须立刻换气。 */
    static SurvivalSituation underwater(double feetY) {
        return calm().feet(feetY).underwater(30).build();
    }

    /** 头在水面上、氧气已经补满。 */
    static SurvivalSituation inAir(double feetY) {
        return calm().feet(feetY).build();
    }

    /** 处境读取器替身：每刻给一份脚本里的处境（同一刻内重复读取得到同一份），用完后重复最后一份。 */
    static SurvivalSituation.SituationReader scripted(SurvivalSituation... situations) {
        Deque<SurvivalSituation> queue = new ArrayDeque<>(List.of(situations));
        SurvivalSituation last = situations[situations.length - 1];
        return new SurvivalSituation.SituationReader() {
            private long lastTick = Long.MIN_VALUE;
            private SurvivalSituation current;

            @Override
            public SurvivalSituation read(TickContext context) {
                long tick = context.gameTick();
                if (tick != lastTick) {
                    lastTick = tick;
                    current = queue.isEmpty() ? last : queue.poll();
                }
                return current;
            }
        };
    }

    /** 角色上下文替身：记录每刻发出的移动与转头指令。 */
    static final class TestPlayer implements PlayerContext {
        final PlayerInput input = new FakeInput();
        long tick;
        int movements;
        final List<PlayerInput.Movement> applied = new ArrayList<>();
        final List<float[]> looks = new ArrayList<>();

        TestPlayer nextTick() { tick++; return this; }

        @Override public LocalPlayer localPlayer() { return null; }
        @Override public ClientLevel level() { return null; }
        @Override public ClientPacketListener connection() { return null; }
        @Override public InteractionSender interactionSender() { return null; }
        @Override public MenuActions menuActions() { return null; }
        @Override public PlayerInput input() { return input; }
        @Override public long clientTick() { return tick; }
        @Override public boolean isCurrent() { return true; }
        @Override public boolean canInteractThisTick() { return true; }
        @Override public boolean tryClaimInteraction() { return true; }

        /** 输入替身：只记录，不写进任何玩家。 */
        final class FakeInput implements PlayerInput {
            @Override public boolean automationOwnsControls() { return true; }
            @Override public void applyMovement(PlayerInput.Movement movement, long leaseTickRevision) {
                movements++;
                applied.add(movement);
            }
            @Override public void requestLook(float yaw, float pitch, long leaseTickRevision) {
                looks.add(new float[]{yaw, pitch});
            }
            @Override public void clearLook() {}
            @Override public void releaseAll() {}
            @Override public void halt(LocalPlayer player) {}
            @Override public void lookAt(LocalPlayer player, Vec3 point) {}
        }
    }

    /** 每刻上下文替身：把测试角色递给任务。 */
    record TestTick(TestPlayer player) implements TickContext {
        @Override public long gameTick() { return player.tick; }
    }

    /** 挖掘动作替身：按次序回答每次推进的结果，记下被挖的格子与是否已收尾。 */
    static final class FakeBreaking implements BlockBreaking {
        final List<ActionStatus> script;
        final List<BlockPos> dug = new ArrayList<>();
        boolean closed;
        private BlockPos cell;
        private int calls;

        FakeBreaking(ActionStatus... script) {
            this.script = List.of(script);
        }

        @Override public void aimAt(BlockPos target) { cell = target; }

        @Override public ActionStatus tick(TickContext context) {
            dug.add(cell);
            if (script.isEmpty()) return ActionStatus.running();
            ActionStatus status = script.get(Math.min(calls, script.size() - 1));
            calls++;
            return status;
        }

        @Override public void close() { closed = true; }

        @Override public String describe() { return "测试挖掘"; }
    }

    /** 动作替身：按脚本做满指定刻数后完成或失败。 */
    static final class FakeCushion implements Action {
        private final long finishAfterTicks;
        private final Problem failure;
        long ticks;

        FakeCushion(long finishAfterTicks, Problem failure) {
            this.finishAfterTicks = finishAfterTicks;
            this.failure = failure;
        }

        static FakeCushion succeedingAfter(long ticks) { return new FakeCushion(ticks, null); }

        static FakeCushion failing() {
            return new FakeCushion(2, Problem.of(Problem.Kind.REFUSED_BY_GAME, "测试失败"));
        }

        @Override public ActionStatus tick(TickContext context) {
            ticks++;
            if (ticks < finishAfterTicks) return ActionStatus.running();
            return failure == null ? ActionStatus.done() : ActionStatus.failed(failure);
        }

        @Override public String describe() { return "测试放水"; }
    }

    /** 第一人称现场替身：哪些格子够得着由测试声明，其余一律看不到。 */
    static final class FakeScene implements FirstPersonScene {
        final Set<BlockPos> reachable = new HashSet<>();

        @Override public Vec3 eyePosition() { return Vec3.ZERO; }
        @Override public Vec3 viewVector() { return new Vec3(0, -1, 0); }
        @Override public HitResult sightRay() { return null; }
        @Override public BlockHitResult visibleHit(BlockPos target) {
            return reachable.contains(target)
                    ? new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false) : null;
        }
        @Override public BlockHitResult visibleItemHit(BlockPos target, InteractionHand hand) {
            return visibleHit(target);
        }
        @Override public boolean heldItemPointsAt(BlockPos target, InteractionHand hand) { return false; }
        @Override public BlockState blockAt(BlockPos pos) { return null; }
        @Override public boolean isLoaded(BlockPos pos) { return true; }
        @Override public ItemStack heldItem(InteractionHand hand) { return null; }
    }

    /** 背包替身：快捷栏哪一格放着水桶、选中第几格由测试声明。 */
    static final class FakeHotbar implements BackpackView {
        Integer waterBucketSlot;
        int selected;

        @Override public List<BackpackStack> stacks() { return List.of(); }
        @Override public int usedSlots() { return 0; }
        @Override public int totalSlots() { return 36; }
        @Override public OptionalInt hotbarSlotOf(String itemId) {
            return "minecraft:water_bucket".equals(itemId) && waterBucketSlot != null
                    ? OptionalInt.of(waterBucketSlot) : OptionalInt.empty();
        }
        @Override public int selectedHotbarSlot() { return selected; }
    }
}
