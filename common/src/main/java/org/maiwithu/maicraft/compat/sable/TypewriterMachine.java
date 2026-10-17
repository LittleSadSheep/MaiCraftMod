// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.StepsInOrder;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 无线红石打字机当一种机器（组装前在世界里）：设置项是每个键绑的频率——键写 {@code key.W}、{@code key.空格}、{@code key.上}……，
 * 值写两格频率物品 {@code 物品 + 物品}，不绑写 {@code none}。改的时候走到它跟前，身上要有这两样物品
 * （绑键界面的频率格从身上拿），存整张键表时带上原来绑的键，不抹掉（和界面里改一个键再保存一样），读回核对由机器能力做。
 * 别的（投料、出口、安装段、部件）它都没有。
 */
final class TypewriterMachine implements MachineType {

    /** 不绑。 */
    static final String NONE = "none";

    private final SimulatedCompat compat;
    private final PlayerServices services;

    TypewriterMachine(SimulatedCompat compat, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public String id() {
        return StructureGeometry.TYPEWRITER;
    }

    @Override public String name() {
        return "无线红石打字机";
    }

    @Override public String modId() {
        return SimulatedCompat.MOD_ID;
    }

    @Override public boolean covers(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(StructureGeometry.TYPEWRITER);
    }

    @Override public MachineRole role() {
        return MachineRole.CONTROL;
    }

    @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) {
        return List.of();
    }

    // 亮着就是有人接管着在用；读不到格子说不知道。
    @Override public MachineState state(BlockPos at) {
        ClientLevel level = level();
        if (level == null || !level.isLoaded(at)) return MachineState.unknown("那一格没加载");
        BlockState state = level.getBlockState(at);
        boolean lit = state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
        return new MachineState(lit ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE, null, null, List.of(),
                Map.of("有人接管着", lit), "");
    }

    @Override public Optional<String> installationProblem(Installation installation) {
        return Optional.empty();
    }

    @Override public Optional<String> partProblem(PartCell part, BlockState host) {
        return Optional.empty();
    }

    @Override public Optional<Action> install(Installation installation, Permissions permissions) {
        return Optional.empty();
    }

    @Override public boolean installed(Installation installation) {
        return false;
    }

    @Override public Optional<Action> mount(PartCell part, Permissions permissions) {
        return Optional.empty();
    }

    @Override public boolean mounted(PartCell part) {
        return false;
    }

    // 每个能写名字的键一项：现在绑的频率，没绑写 none。
    @Override public List<MachineSetting> settings(BlockPos at) {
        ClientLevel level = level();
        Map<Integer, List<String>> bindings = level == null ? null : compat.typewriterBindings(level, null, at);
        List<MachineSetting> settings = new ArrayList<>();
        for (int key : TypewriterKeys.nameable()) {
            List<String> bound = bindings == null ? null : bindings.get(key);
            settings.add(new MachineSetting("key." + TypewriterKeys.name(key), bound == null ? NONE : value(bound),
                    List.of(), "这个键绑的频率：两格频率物品写成 物品 + 物品（例如 minecraft:iron_ingot + minecraft:redstone），none 不绑"));
        }
        return settings;
    }

    // 绑一个键：走到跟前 → 核对身上有那两样频率物品 → 带上原来的键存整张表。
    @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
        if (!key.startsWith("key.")) return Optional.empty();
        int code = TypewriterKeys.key("打字机:" + key.substring(4));
        if (code == TypewriterKeys.UNKNOWN) return Optional.empty();
        List<String> wanted = parse(value);
        if (wanted == null && !value.trim().equalsIgnoreCase(NONE)) return Optional.empty();
        return Optional.of(new StepsInOrder("给无线红石打字机绑键", List.of(
                new StepsInOrder.Step("走到无线红石打字机跟前",
                        () -> services.approach().toward(ApproachTarget.ofBlock(at), permissions)),
                new StepsInOrder.Step("存键表", () -> new Bind(at, code, wanted)))));
    }

    @Override public Optional<Action> feed(BlockPos at, String itemId, int count, Permissions permissions) {
        return Optional.empty();
    }

    @Override public List<MachineState.Shown> output(BlockPos at) {
        return List.of();
    }

    @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
        return Optional.empty();
    }

    /** "物品 + 物品" 拆成两格；写法不对为 null。 */
    static List<String> parse(String value) {
        String[] parts = value.split("\\+");
        if (parts.length != 2) return null;
        String first = parts[0].trim().toLowerCase(Locale.ROOT);
        String second = parts[1].trim().toLowerCase(Locale.ROOT);
        if (!first.contains(":") || !second.contains(":")) return null;
        return List.of(first, second);
    }

    static String value(List<String> bound) {
        return bound.get(0) + " + " + bound.get(1);
    }

    private ClientLevel level() {
        PlayerContext context = services.context().get();
        return context == null ? null : context.level();
    }

    /** 存整张键表的一下：身上缺频率物品就不存，照实说缺什么。 */
    private final class Bind implements Action {

        private final BlockPos at;
        private final int key;
        private final List<String> wanted;

        Bind(BlockPos at, int key, List<String> wanted) {
            this.at = at;
            this.key = key;
            this.wanted = wanted;
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext context = services.context().get();
            ClientLevel level = context == null ? null : context.level();
            LocalPlayer player = context == null ? null : context.localPlayer();
            if (level == null || player == null) return ActionStatus.failed(Problem.of(Problem.Kind.INTERNAL_ERROR, "角色不在"));
            Map<Integer, List<String>> before = compat.typewriterBindings(level, null, at);
            if (before == null) return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "那一格不是无线红石打字机了"));
            if (wanted != null) {
                List<String> missing = wanted.stream().filter(item -> !item.equals("minecraft:air") && !carries(player, item))
                        .distinct().toList();
                if (!missing.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "绑键的频率物品要在身上：缺 "
                            + String.join("、", missing), "先 obtain 拿到再绑"));
                }
            }
            Map<Integer, List<String>> all = new LinkedHashMap<>(before);
            if (wanted == null) all.remove(key);
            else all.put(key, wanted);
            compat.typewriterBind(level, null, at, all);
            return ActionStatus.done();
        }

        @Override public String describe() {
            return "给无线红石打字机的 " + TypewriterKeys.name(key) + " 键绑频率";
        }
    }

    // 身上（背包加副手）有没有这样东西。
    private static boolean carries(LocalPlayer player, String itemId) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) return true;
        }
        return false;
    }
}
