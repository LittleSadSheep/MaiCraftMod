// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 界面布局：判断一个容器界面两侧各是哪些槽、能否安全点击的纯函数。
 *
 * <p>只认界面类型注册 ID 证明得了的布局：角色侧必须是 36 格（27 主背包 + 9 快捷栏），
 * 容器侧是剩下的槽。认不出的模组界面给"证明不了"的结论，调用方以不支持结束，不照着普通箱子的样子乱点；
 * 模组界面的证明随各自的联动适配补进来。
 *
 * <p>机器槽（熔炉的燃料与产出格）放进去的物品可能在放置回显到达前就被吃掉，
 * 搬运确认对这些槽换用"源格确切减少 + 光标空"的核对（见 {@link MoveConfirmation}）。
 */
public final class MenuLayout {

    /**
     * 只存东西的普通容器界面：容器侧每个槽的放取都按普通槽核对。
     * 这里是菜单类型的注册 ID，不是方块 ID：箱子、木桶、末影箱用 generic_9x1 到 generic_9x6（大箱子是 9x6），
     * 发射器与投掷器用 generic_3x3，潜影盒与漏斗有自己的菜单类型。类别：游戏事实（原版菜单类型注册表）。
     */
    private static final Set<String> PLAIN_STORAGE = Set.of(
            "minecraft:generic_9x1", "minecraft:generic_9x2", "minecraft:generic_9x3",
            "minecraft:generic_9x4", "minecraft:generic_9x5", "minecraft:generic_9x6",
            "minecraft:generic_3x3", "minecraft:shulker_box", "minecraft:hopper");

    /** 熔炉一族：第 0 格是投入口，第 1 格是燃料槽，第 2 格是产出格。 */
    private static final Set<String> FURNACE_LIKE = Set.of(
            "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker");

    /** 合成台：容器侧第 0 格是产出格，其余九格是合成格。 */
    private static final Set<String> CRAFTING = Set.of("minecraft:crafting");

    /** 石切台：第 0 格是投入口，第 1 格是产出格。 */
    private static final Set<String> STONECUTTER = Set.of("minecraft:stonecutter");

    /** 角色侧固定 36 格：27 格主背包 + 9 格快捷栏。 */
    private static final int PLAYER_SLOTS = 36;

    private MenuLayout() {}

    /** 判定结论：证明了两侧怎么分，或者承认证明不了。 */
    public sealed interface Layout {
    }

    /** 证明得了的布局：两侧的槽位号，以及容器侧里要按机器槽核对的槽位。 */
    public record Supported(List<Integer> playerSlots, List<Integer> containerSlots,
                            Set<Integer> machineSlots) implements Layout {
    }

    /** 证明不了的布局：界面类型认不出，或两侧的形状对不上已知的样子。 */
    public record Unsupported(String reason) implements Layout {
    }

    /**
     * 分侧判定：读界面的类型与每个槽位背后装的是谁，给出两侧槽位号。
     * 角色侧不是恰好 36 格、或界面类型不在已证明的清单里，都按证明不了处理。
     */
    public static Layout classify(MenuSlots slots) {
        String typeId = slots.menuTypeId() == null ? "" : slots.menuTypeId().toLowerCase(Locale.ROOT);
        List<Integer> playerSlots = new ArrayList<>();
        List<Integer> containerSlots = new ArrayList<>();
        for (int slot = 0; slot < slots.slotCount(); slot++) {
            if (slots.playerBacked(slot)) playerSlots.add(slot);
            else containerSlots.add(slot);
        }
        // 玩家侧必须恰好 36 格：多出来或少了都说明这个界面的分侧方式和已知的对不上，不冒充认得出。
        if (playerSlots.size() != PLAYER_SLOTS) {
            return new Unsupported("角色侧是 " + playerSlots.size() + " 格而不是 36 格，证明不了两侧怎么分");
        }
        if (PLAIN_STORAGE.contains(typeId)) {
            return new Supported(List.copyOf(playerSlots), List.copyOf(containerSlots), Set.of());
        }
        if (FURNACE_LIKE.contains(typeId)) {
            // 熔炉一族的投入口在 0、燃料槽在 1、产出格在 2；燃料和产出按机器槽核对，投入口当普通槽。
            if (containerSlots.size() < 3) {
                return new Unsupported("界面类型 " + typeId + " 的容器侧不足 3 格，证明不了熔炉布局");
            }
            Set<Integer> machine = Set.of(1, 2);
            return new Supported(List.copyOf(playerSlots), List.copyOf(containerSlots), machine);
        }
        if (CRAFTING.contains(typeId) || STONECUTTER.contains(typeId)) {
            // 合成台与石切台的产出格只会被拿走不会被塞进，点击都按普通槽核对。
            return new Supported(List.copyOf(playerSlots), List.copyOf(containerSlots), Set.of());
        }
        // 没证明过的模组界面：不是"当作普通箱子试试"，而是承认两侧的行为证明不了。
        return new Unsupported("认不出界面类型 " + typeId + " 的布局，证明不了两侧怎么分，不点击");
    }
}
