// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 放置确认：点下去之后看主格、顺带生成的另一半（门上半、床头）和身上的耗材一起变没变。
 * 服务端对这次右键的确认到了才下"没生效"的结论；这一下有进展（双层半砖先放下第一片）也算生效，
 * 整格放好没有由施工任务接着判断。耗材没扣就继续等同一单，不再点第二下。
 */
public final class PlacementConfirmation implements InteractionConfirmation {

    private final PlannedCell cell;
    private final BlockState predicted;
    private final List<PlacementPrediction.GeneratedCell> generated;
    private final Map<BlockPos, BlockState> before;
    private final String itemId;
    private final int materialBefore;

    /**
     * @param cell           要放的格
     * @param before         出手前主格与另一半的状态
     * @param predicted      原版预测这一下放成什么；预测不出时传 null
     * @param materialBefore 出手前身上这种物品几件；创造模式或读不到传 -1，不看耗材
     */
    public PlacementConfirmation(PlannedCell cell, Map<BlockPos, BlockState> before, BlockState predicted, int materialBefore) {
        this.cell = cell;
        this.predicted = predicted;
        this.before = Map.copyOf(before);
        this.materialBefore = materialBefore;
        this.itemId = BuiltInRegistries.ITEM.getKey(cell.item()).toString();
        // 门的铰链这类由原版落法决定的属性按预测来，蓝图默认值不是作者点名要的，右铰链不能被判成错。
        BlockState basis = predicted != null ? predicted : cell.state();
        generated = PlacementPrediction.generatedBy(cell.pos(), basis);
    }

    @Override public boolean requiresBlockAcknowledgement() {
        return true;
    }

    @Override public Verdict observe(PlayerContext context) {
        return withMaterial(context, observe(pos -> context.level().isLoaded(pos), pos -> context.level().getBlockState(pos), false));
    }

    @Override public Verdict observeAcknowledged(PlayerContext context) {
        return withMaterial(context, observe(pos -> context.level().isLoaded(pos), pos -> context.level().getBlockState(pos), true));
    }

    /** 顺带生成的另一半在哪。 */
    public List<PlacementPrediction.GeneratedCell> generated() {
        return generated;
    }

    /**
     * 只看世界：相关格没加载就等；全都和出手前一样，服务端确认到了就是没生效，没到就再等；
     * 主格放好或有进展、另一半也对上就是生效；变成别的样子是出乎预料。
     */
    public Verdict observe(Predicate<BlockPos> loaded, Function<BlockPos, BlockState> states, boolean acknowledged) {
        if (!loaded.test(cell.pos()) || generated.stream().anyMatch(effect -> !loaded.test(effect.pos()))) return Verdict.PENDING;
        BlockState old = before.get(cell.pos());
        BlockState live = states.apply(cell.pos());
        boolean unchanged = live.equals(old);
        boolean complete = PlacementPrediction.complete(cell, live) || PlacementPrediction.isProgress(cell, old, live);
        if (!generated.isEmpty() && predicted != null) complete &= samePlacement(live, predicted);
        boolean diverged = !complete && !unchanged;
        for (var effect : generated) {
            BlockState was = before.get(effect.pos());
            BlockState now = states.apply(effect.pos());
            boolean matches = samePlacement(now, effect.expected());
            complete &= matches;
            unchanged &= now.equals(was);
            diverged |= !matches && !now.equals(was);
        }
        if (unchanged) return acknowledged ? Verdict.NOT_APPLIED : Verdict.PENDING;
        if (complete) return Verdict.APPLIED;
        // 门的上半可能晚一刻才同步：它还没变时继续等，不因为确认号到了就判错。
        return diverged ? Verdict.DIVERGED : Verdict.PENDING;
    }

    /** 加上耗材这一证据：方块变了但一件都没扣就继续等；扣了却说没生效也要等同一单，不换角度再点。 */
    public Verdict withMaterial(Verdict world, int carriedNow) {
        if (materialBefore < 0) return world;
        int consumed = materialBefore - carriedNow;
        if (world == Verdict.NOT_APPLIED && consumed > 0) return Verdict.PENDING;
        if (world == Verdict.APPLIED && consumed == 0) return Verdict.PENDING;
        return world;
    }

    private Verdict withMaterial(PlayerContext context, Verdict world) {
        if (materialBefore < 0 || context.backpack() == null) return world;
        return withMaterial(world, carried(context.backpack(), itemId));
    }

    /** 身上这种物品一共几件。 */
    public static int carried(BackpackView backpack, String itemId) {
        int total = 0;
        for (BackpackStack stack : backpack.stacks()) {
            if (stack.itemId().equals(itemId)) total += stack.count();
        }
        return total;
    }

    /** 同一种方块，而且与摆放有关的属性（朝向、半、铰链、部位）一致；开关、含水这类运行态不比。 */
    static boolean samePlacement(BlockState live, BlockState expected) {
        if (live.getBlock() != expected.getBlock()) return false;
        for (Property<?> property : expected.getProperties()) {
            if (!placementProperty(property)) continue;
            if (!live.hasProperty(property) || !live.getValue(property).equals(expected.getValue(property))) return false;
        }
        return true;
    }

    private static boolean placementProperty(Property<?> property) {
        return switch (property.getName()) {
            case "facing", "half", "hinge", "part", "axis", "type", "face", "rotation", "shape", "attached", "hanging" -> true;
            default -> false;
        };
    }

    /** 给结果看的现场：预测、出手前与现在每一格的样子。 */
    public Map<String, String> scene(Predicate<BlockPos> loaded, Function<BlockPos, BlockState> states) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("predicted", predicted == null ? "未预测" : predicted.toString());
        out.put(cell.pos().toShortString(), before.get(cell.pos()) + " → " + (loaded.test(cell.pos()) ? states.apply(cell.pos()).toString() : "未加载"));
        for (var effect : generated) {
            out.put(effect.pos().toShortString(), before.get(effect.pos()) + " → " + (loaded.test(effect.pos()) ? states.apply(effect.pos()).toString() : "未加载"));
        }
        return out;
    }
}
