// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.acquire.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.recipe.RecipeViewer;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.travel.ReadsSeenTargets;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 机器能力离线测试共用的替身：机器类型、网络读取器、范围读端、现场视图、编号与地点、
 * 配方查看器、缺东西去拿、靠近、交互。测试类各自摆场景，这里只放机械。
 */
final class MachineTestDoubles {

    private MachineTestDoubles() {
    }

    /** 跑几刻后按给定结论收场的动作；记下被暂停、被收尾了几次。 */
    static final class Scripted implements Action {
        private final int ticks;
        private final ActionStatus end;
        private final Runnable onEnd;
        private int ran;
        int pauses;
        int closes;

        Scripted(int ticks, Runnable onEnd) {
            this(ticks, ActionStatus.done(), onEnd);
        }

        Scripted(int ticks, ActionStatus end, Runnable onEnd) {
            this.ticks = ticks;
            this.end = end;
            this.onEnd = onEnd;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (++ran >= ticks) {
                onEnd.run();
                return end;
            }
            return ActionStatus.progressed();
        }

        @Override public void pause() {
            pauses++;
        }

        @Override public void close() {
            closes++;
        }

        @Override public String describe() {
            return "替身动作";
        }
    }

    /** 一格读出的事实。 */
    record WorldCell(BlockPos pos, BlockState state, boolean hasBlockEntity) {
    }

    /** 机器类型替身：认领给定的方块状态谓词；设置与动作按测试摆的脚本回答。 */
    static final class FakeMachineType implements MachineType {
        final String id;
        final String name;
        final MachineRole role;
        private final Predicate<BlockState> covers;
        /** 设置项与现值：键 → 值。 */
        final Map<String, String> settingValues = new LinkedHashMap<>();
        /** 改设置时执行的动作：测试用它把读回的值真的改掉或让它打转。 */
        final Map<String, Runnable> onChange = new HashMap<>();
        /** 投料与取货脚本：每次调用弹一个；空了给空。 */
        final Deque<Action> feedActions = new ArrayDeque<>();
        final Deque<Action> takeActions = new ArrayDeque<>();
        /** 出口读数：测试随时间改这份表。 */
        final Map<String, Integer> output = new LinkedHashMap<>();
        Supplier<MachineState> state = () -> MachineState.unknown("读不到");
        Predicate<Installation> installationOk = installation -> false;
        Predicate<PartCell> partOk = part -> false;
        /** 这段现在装成了没有、这个部件装上没有：测试在动作效果里翻转。 */
        Predicate<Installation> installedOk = installation -> false;
        Predicate<PartCell> mountedOk = part -> false;
        /** 装安装段与装部件的动作脚本：每次调用弹一个；空了给空，按"这种机器不给装"对待。 */
        final Deque<Action> installActions = new ArrayDeque<>();
        final Deque<Action> mountActions = new ArrayDeque<>();
        /** 动手顺序的流水：施工、装段、装部件、改设置谁先谁后从这里看。 */
        final List<String> touched = new ArrayList<>();
        List<ExchangePoint> ports = List.of();

        FakeMachineType(String id, String name, MachineRole role, Predicate<BlockState> covers) {
            this.id = id;
            this.name = name;
            this.role = role;
            this.covers = covers;
        }

        @Override public String id() {
            return id;
        }

        @Override public String name() {
            return name;
        }

        @Override public String modId() {
            return id.contains(":") ? id.substring(0, id.indexOf(':')) : id;
        }

        @Override public boolean covers(BlockState state) {
            return covers.test(state);
        }

        @Override public MachineRole role() {
            return role;
        }

        @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) {
            return ports;
        }

        @Override public MachineState state(BlockPos at) {
            return state.get();
        }

        @Override public Optional<String> installationProblem(Installation installation) {
            return installationOk.test(installation) ? Optional.empty()
                    : Optional.of("形状不合规则");
        }

        @Override public Optional<String> partProblem(PartCell part, BlockState host) {
            return partOk.test(part) ? Optional.empty() : Optional.of("宿主不对");
        }

        @Override public Optional<Action> install(Installation installation, Permissions permissions) {
            touched.add("install:" + installation.kind());
            return Optional.ofNullable(installActions.poll());
        }

        @Override public boolean installed(Installation installation) {
            return installedOk.test(installation);
        }

        @Override public Optional<Action> mount(PartCell part, Permissions permissions) {
            touched.add("mount:" + part.itemId());
            return Optional.ofNullable(mountActions.poll());
        }

        @Override public boolean mounted(PartCell part) {
            return mountedOk.test(part);
        }

        @Override public List<MachineSetting> settings(BlockPos at) {
            List<MachineSetting> out = new ArrayList<>();
            settingValues.forEach((key, value) -> out.add(
                    new MachineSetting(key, value, List.of(), key + " 的说明")));
            return out;
        }

        @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
            Runnable effect = onChange.get(key);
            if (effect == null) {
                return Optional.empty();
            }
            touched.add("change:" + key);
            return Optional.of(new Scripted(1, effect));
        }

        @Override public Optional<Action> feed(BlockPos at, String itemId, int count, Permissions permissions) {
            return Optional.ofNullable(feedActions.poll());
        }

        @Override public List<MachineState.Shown> output(BlockPos at) {
            List<MachineState.Shown> out = new ArrayList<>();
            output.forEach((itemId, count) -> out.add(new MachineState.Shown(itemId, count)));
            return out;
        }

        @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
            return Optional.ofNullable(takeActions.poll());
        }
    }

    /** 网络读取器替身：成员表与汇总按测试摆的给。 */
    static final class FakeNetworkReader implements NetworkReader {
        private final NetworkKind kind;
        private final Map<BlockPos, String> members = new HashMap<>();
        private NetworkSummary summary = null;

        FakeNetworkReader(String id, String description) {
            this.kind = new NetworkKind(id, description);
        }

        void put(BlockPos at, String networkId) {
            members.put(at, networkId);
        }

        void unreadable(String why) {
            summary = NetworkSummary.unreadable("net1", why);
        }

        @Override public NetworkKind kind() {
            return kind;
        }

        @Override public Optional<String> membership(BlockPos at) {
            return Optional.ofNullable(members.get(at));
        }

        @Override public NetworkSummary summary(String networkId) {
            if (summary != null) {
                return summary;
            }
            Map<String, Object> readings = new LinkedHashMap<>();
            readings.put("成员数", members.size());
            return new NetworkSummary(networkId, true, readings, "");
        }
    }

    /** 范围读端替身：按脚本一轮一轮给，最后一直给扫完的空轮。 */
    static final class FakeArea implements ReadsMachineArea {
        private final Deque<Round> script = new ArrayDeque<>();

        void offer(Round round) {
            script.add(round);
        }

        @Override public Round scan(String dimension, BlockPos center, int radius) {
            return script.isEmpty() ? new Round(List.of(), 0, true, false) : script.poll();
        }
    }

    /** 现场视图替身：位置、维度与每格的方块状态按测试摆的给。 */
    static final class FakeWorld implements MachineWorldView {
        String dimension = "minecraft:overworld";
        BlockPos playerAt = BlockPos.ZERO;
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        boolean inWorld = true;

        @Override public Optional<Spot> playerSpot() {
            return inWorld ? Optional.of(new Spot(playerAt, dimension)) : Optional.empty();
        }

        @Override public Optional<BlockState> stateAt(BlockPos at) {
            return Optional.ofNullable(blocks.get(at));
        }
    }

    /** 观察编号替身。 */
    static final class FakeSeen implements ReadsSeenTargets {
        final Map<String, WorldPosition> positions = new HashMap<>();

        @Override public Optional<WorldPosition> positionOf(String observationId) {
            return Optional.ofNullable(positions.get(observationId));
        }
    }

    /** 记过地点替身。 */
    static final class FakePlaces implements ReadsRememberedPlaces {
        final Map<String, WorldPosition> places = new HashMap<>();

        @Override public Optional<WorldPosition> place(String name) {
            return Optional.ofNullable(places.get(name));
        }
    }

    /** 配方查看器替身：making 与 atWorkstation 按给定的配方回答。 */
    static final class FakeViewer implements RecipeViewer {
        private final Map<String, List<ShownRecipe>> making = new HashMap<>();
        private final Map<String, List<ShownRecipe>> atWorkstation = new HashMap<>();

        void putMaking(String itemId, List<ShownRecipe> recipes) {
            making.put(itemId, recipes);
        }

        void putAtWorkstation(String itemId, List<ShownRecipe> recipes) {
            atWorkstation.put(itemId, recipes);
        }

        @Override public String name() {
            return "fake";
        }

        @Override public boolean importsOtherViewers() {
            return false;
        }

        @Override public Readiness readiness() {
            return Readiness.yes();
        }

        @Override public List<ShownRecipe> making(String itemId) {
            return making.getOrDefault(itemId, List.of());
        }

        @Override public List<ShownRecipe> using(String itemId) {
            return List.of();
        }

        @Override public List<ShownRecipe> atWorkstation(String itemId) {
            return atWorkstation.getOrDefault(itemId, List.of());
        }
    }

    /** 缺东西去拿的替身：记下请求，动作两刻做完。 */
    static final class FakeNeeds implements ItemNeeds {
        final List<ItemRequest> requests = new ArrayList<>();
        boolean fail = false;

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            requests.add(request);
            if (fail) {
                return new Scripted(1, ActionStatus.failed(
                        Problem.of(Problem.Kind.NOT_FOUND, "没有", null)), () -> { });
            }
            return new Scripted(2, () -> { });
        }
    }

    /** 靠近替身：一步就到。 */
    static final class FakeClose implements BringsPlayerClose {
        int calls;

        @Override public Action toward(ApproachTarget target, Permissions permissions) {
            calls++;
            return new Scripted(1, () -> { });
        }
    }

    /** 交互替身：记下右键了哪格，动作两刻做完。 */
    static final class FakeClicks implements MachineInteractions {
        final List<BlockPos> clicked = new ArrayList<>();
        Runnable onDone = () -> { };

        @Override public Action useBlock(BlockPos target, InteractionConfirmation confirmation) {
            clicked.add(target);
            return new Scripted(2, onDone);
        }
    }
}
