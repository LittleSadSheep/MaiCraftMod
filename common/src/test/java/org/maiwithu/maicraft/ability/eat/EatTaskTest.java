// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.eat;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.OffhandContents;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.interaction.UseKeyProjection;
import org.maiwithu.maicraft.behavior.inventory.MovesToMainhand;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.interaction.PendingInteraction;
import org.maiwithu.maicraft.game.interaction.ScriptedInteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.game.player.ReadsEffects;
import org.maiwithu.maicraft.game.player.ReadsEquipment;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.player.ReadsHunger;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 吃东西的离线场景：确认条件是"同类总量减少"，确认窗口里捡回来的干扰被扣除；
 * 数量没少就不冒充吃饱，失败时带着当前的饥饿值。
 */
class EatTaskTest {

    private static final String BREAD = "minecraft:bread";

    /** 背包替身：一张可以改的格子表，模拟吃掉与捡回都直接改它。 */
    static final class FakeBackpack implements BackpackView {
        final List<BackpackStack> stacks = new ArrayList<>();

        void set(String itemId, int count) {
            stacks.removeIf(stack -> stack.itemId().equals(itemId));
            if (count > 0) {
                stacks.add(new BackpackStack(itemId, count, 64, false, true, false, false));
            }
        }

        int countOf(String itemId) {
            return stacks.stream().filter(stack -> stack.itemId().equals(itemId))
                    .mapToInt(BackpackStack::count).sum();
        }

        @Override public List<BackpackStack> stacks() { return List.copyOf(stacks); }
        @Override public int usedSlots() { return stacks.size(); }
        @Override public int totalSlots() { return 36; }
    }

    /** 只给食物数值的替身：面包一件 1.6 秒，无效果。 */
    static final class FakeFoods implements ReadsFoodValues {
        final Map<String, ReadsFoodValues.FoodValue> values = new HashMap<>();

        @Override public Optional<ReadsFoodValues.FoodValue> of(String itemId) {
            return Optional.ofNullable(values.get(itemId));
        }
    }

    /** 饥饿与效果替身：字段直接改。 */
    static final class FakeHunger implements ReadsHunger {
        int foodLevel = 10;
        @Override public int foodLevel() { return foodLevel; }
        @Override public boolean hungerMechanicsOn() { return true; }
    }

    static final class FakeEffects implements ReadsEffects {
        final List<String> effects = new ArrayList<>();
        @Override public List<String> active() { return List.copyOf(effects); }
    }

    /** 装备栏替身：只关心主手。 */
    static final class FakeEquipment implements ReadsEquipment {
        final Map<GearSlotName, BackpackStack> slots = new HashMap<>();
        @Override public Optional<BackpackStack> slot(GearSlotName name) {
            return Optional.ofNullable(slots.get(name));
        }
    }

    /** 角色上下文替身：交互提交按脚本回，其余都不碰真实客户端。 */
    static final class FakePlayerContext implements PlayerContext {
        long tick;
        private final InteractionSender sender;
        FakePlayerContext(InteractionSender sender) { this.sender = sender; }
        void advance() { tick++; }
        @Override public LocalPlayer localPlayer() { return null; }
        @Override public ClientLevel level() { return null; }
        @Override public ClientPacketListener connection() { return null; }
        @Override public PlayerInput input() {
            return new PlayerInput() {
                @Override public boolean automationOwnsControls() { return true; }
                @Override public void applyMovement(Movement movement, long requestTick) {}
                @Override public void requestLook(float yaw, float pitch, long requestTick) {}
                @Override public void clearLook() {}
                @Override public void releaseAll() {}
                @Override public void lookAt(LocalPlayer player, Vec3 point) {}
                @Override public void halt(LocalPlayer player) {}
            };
        }
        @Override public InteractionSender interactionSender() { return sender; }
        @Override public MenuActions menuActions() { return null; }
        @Override public long clientTick() { return tick; }
        @Override public boolean isCurrent() { return true; }
        @Override public boolean canInteractThisTick() { return true; }
        @Override public boolean tryClaimInteraction() { return true; }
    }

    /** 按住投影替身：记次数，续期永远成功。 */
    static final class RecordingProjection implements UseKeyProjection {
        @Override public boolean renew(Object owner, PlayerContext context, PendingInteraction pending,
                InteractionHand hand, ItemStack before) { return true; }
        @Override public void release(Object owner) {}
    }

    /** 换到主手的替身：一动手就把主手换成要吃的东西，立刻做完。 */
    static final class InstantMover implements MovesToMainhand {
        private final FakeEquipment equipment;
        private final String itemId;
        InstantMover(FakeEquipment equipment, String itemId) { this.equipment = equipment; this.itemId = itemId; }
        @Override public Optional<Action> moveToMainhand(String target) {
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    equipment.slots.put(GearSlotName.MAINHAND,
                            new BackpackStack(itemId, 1, 64, false, true, false, false));
                    return ActionStatus.done();
                }
                @Override public String describe() { return "换到主手"; }
            });
        }
    }

    /** 现场替身：离线不碰注册表，手持一律没有；持续使用的开始确认按脚本走。 */
    static final class EmptyScene implements FirstPersonScene {
        @Override public Vec3 eyePosition() { return new Vec3(0.0, 64.0, 0.0); }
        @Override public Vec3 viewVector() { return new Vec3(0.0, 0.0, -1.0); }
        @Override public HitResult sightRay() { return null; }
        @Override public BlockHitResult visibleHit(BlockPos target) { return null; }
        @Override public BlockHitResult visibleItemHit(BlockPos target, InteractionHand hand) { return null; }
        @Override public boolean heldItemPointsAt(BlockPos target, InteractionHand hand) { return false; }
        @Override public BlockState blockAt(BlockPos pos) { return null; }
        @Override public boolean isLoaded(BlockPos pos) { return false; }
        @Override public ItemStack heldItem(InteractionHand hand) { return null; }
    }

    /** 一套测试环境：世界变化都写在 worldStep 钩子里，每刻跑一遍。 */
    private final class Rig {
        final FakeBackpack backpack = new FakeBackpack();
        final FakeHunger hunger = new FakeHunger();
        final FakeEffects effects = new FakeEffects();
        final FakeFoods foods = new FakeFoods();
        final FakeEquipment equipment = new FakeEquipment();
        final ScriptedInteractionSender sender = new ScriptedInteractionSender();
        final FakePlayerContext context = new FakePlayerContext(sender);
        final RecordingProjection projection = new RecordingProjection();
        Consumer<Rig> worldStep = ignored -> {};

        Rig bread(int backpackCount, int mainhandCount) {
            foods.values.put(BREAD, new ReadsFoodValues.FoodValue(BREAD, 5, 6.0f, 1.6f, false, List.of()));
            backpack.set(BREAD, backpackCount);
            if (mainhandCount > 0) {
                equipment.slots.put(GearSlotName.MAINHAND,
                        new BackpackStack(BREAD, mainhandCount, 64, false, true, false, false));
            }
            return this;
        }

        EatTask task(int count) {
            return new EatTask(new EatInput(BREAD, count), backpack, emptyOffhand(), hunger,
                    equipment, effects, foods, projection,
                    Optional.of(new InstantMover(equipment, BREAD)), ignored -> new EmptyScene());
        }

        TickResult run(EatTask eat, int ticks) {
            TickResult result = TickResult.RUNNING;
            for (int i = 0; i < ticks; i++) {
                worldStep.accept(this);
                context.advance();
                result = eat.tick(asTickContext());
                if (result instanceof TickResult.Finished finished) return finished;
            }
            return result;
        }

        /** 确认记录还没终结时，下一步按"游戏已确认"收尾：模拟开始与松手都得到了确认。 */
        void confirmAnythingPending() {
            if (sender.last != null && !sender.last.terminal()) {
                sender.nextStatus = PendingInteraction.Status.CONFIRMED_APPLIED;
            }
        }

        private TickContext asTickContext() {
            long gameTick = context.tick;
            return new TickContext() {
                @Override public long gameTick() { return gameTick; }
                @Override public PlayerContext player() { return context; }
            };
        }
    }

    private static OffhandContents emptyOffhand() {
        return Optional::empty;
    }

    @Test
    void 吃到了_按同类总量减少确认_变化记账() {
        Rig rig = new Rig().bread(2, 0);
        EatTask eat = rig.task(1);
        boolean[] dipped = {false};
        rig.worldStep = r -> {
            r.confirmAnythingPending();
            if (r.sender.submissions.size() >= 1
                    && r.sender.last != null && r.sender.last.terminal() && !dipped[0]) {
                // 开始确认过了：吃进去一件，身上少一个。
                r.backpack.set(BREAD, 1);
                dipped[0] = true;
            }
        };
        TickResult result = rig.run(eat, 60);
        assertTrue(result instanceof TickResult.Finished finished
                && finished.result().status() == TaskResult.Status.DONE, "应该吃完");
        TaskResult done = ((TickResult.Finished) result).result();
        assertEquals(1, done.changes().stream()
                .filter(change -> change.kind() == Change.Kind.ITEM_CONSUMED
                        && change.what().equals(BREAD))
                .mapToInt(change -> change.count()).sum());
        assertTrue(done.details() instanceof EatTask.EatDetails details
                && details.effectsGained().isEmpty());
    }

    @Test
    void 数量没少就不冒充吃饱_失败带着饥饿值() {
        Rig rig = new Rig().bread(2, 2);
        rig.worldStep = Rig::confirmAnythingPending;
        TickResult result = rig.run(rig.task(1), 300);
        assertTrue(result instanceof TickResult.Finished finished
                && finished.result().status() == TaskResult.Status.FAILED, "数量没少应当失败");
        assertTrue(((TickResult.Finished) result).result().problem().message().contains("饱食度"));
    }

    @Test
    void 确认窗口里捡回同类_按见过的最低数记账() {
        Rig rig = new Rig().bread(3, 0);
        EatTask eat = rig.task(1);
        boolean[] dipped = {false};
        rig.worldStep = r -> {
            r.confirmAnythingPending();
            if (r.sender.last != null && r.sender.last.terminal() && !r.sender.submissions.isEmpty()) {
                if (!dipped[0]) {
                    r.backpack.set(BREAD, 2);
                    dipped[0] = true;
                } else if (r.backpack.countOf(BREAD) < 4) {
                    // 吃掉一口之后又捡回两件：结束时总数高于基准，按最低值仍能确认吃了一件。
                    r.backpack.set(BREAD, 4);
                }
            }
        };
        TickResult result = rig.run(eat, 60);
        assertTrue(result instanceof TickResult.Finished finished
                && finished.result().status() == TaskResult.Status.DONE);
        TaskResult done = ((TickResult.Finished) result).result();
        assertTrue(done.changes().stream()
                .anyMatch(change -> change.kind() == Change.Kind.ITEM_CONSUMED
                        && change.what().equals(BREAD) && change.count() >= 1));
    }

    @Test
    void 主手那一堆吃完了_先把下一堆换到主手再吃() {
        // 主手只剩一块面包、背包里还有：吃完主手那块后先换手，再吃第二块，不对着空手按使用键。
        Rig rig = new Rig().bread(3, 1);
        EatTask eat = rig.task(2);
        int[] bites = {0};
        rig.worldStep = r -> {
            r.confirmAnythingPending();
            long starts = r.sender.submissions.stream()
                    .filter(submission -> submission.kind() == PendingInteraction.Kind.USE_ITEM).count();
            boolean breadInHand = r.equipment.slots.containsKey(GearSlotName.MAINHAND);
            if (r.sender.last != null && r.sender.last.terminal() && bites[0] < starts && breadInHand) {
                // 主手拿着面包、开始使用得到确认：吃掉一块；第一块吃完主手就空了。空手按使用键吃不到东西。
                bites[0]++;
                r.backpack.set(BREAD, r.backpack.countOf(BREAD) - 1);
                if (bites[0] == 1) r.equipment.slots.remove(GearSlotName.MAINHAND);
            }
        };
        TickResult result = rig.run(eat, 200);
        assertTrue(result instanceof TickResult.Finished finished
                && finished.result().status() == TaskResult.Status.DONE, String.valueOf(result));
        assertEquals(2, bites[0]);
    }
}
