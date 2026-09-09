package org.maiwithu.maicraft.core.data;

import org.maiwithu.maicraft.core.init.InitTag;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.function.Consumer;

/**
 * 默认物品标签的内容清单，目标是让不同加载器共用同一份成员定义。
 * 当前仓库没有调用这份清单的生成器接线，资源里也没有相应标签输出；不要把这里写了成员等同于游戏已经加载它们。
 */
public final class ModItemTagData {

    private ModItemTagData() {}

    /**
     * Loader-agnostic adapter: each loader's tag provider implements this to
     * return an {@link Appender} for a given key. The only MC appender with a
     * {@code add(T)} sink is {@code protected}, and the public
     * {@code TagsProvider.TagAppender} only takes {@code ResourceKey}s, so common
     * defines its own neutral sink and each loader wraps its native builder via
     * {@link #appender}.
     */
    @FunctionalInterface
    public interface TagAppenderProvider<T> {
        Appender<T> tag(TagKey<T> key);
    }

    /**
     * Minimal fluent sink — the {@code .add(T)} chaining the tag lists use, plus
     * {@code .addTag} for referencing another tag ({@code #minecraft:banners} and
     * friends). Naming a vanilla tag beats copying its current members: the
     * members change between versions, the tag's meaning does not.
     */
    public interface Appender<T> {
        Appender<T> add(T value);

        Appender<T> addTag(TagKey<T> tag);
    }

    /** Adapt a native MC tag builder to an {@link Appender}; loaders pass explicit
     *  lambdas (not method refs) to dodge the {@code add(T)} vs {@code add(T...)}
     *  overload ambiguity. */
    // 把“加入单个成员”和“引用另一个标签”两种写入函数包装成链式接口，具体写到哪里由调用方提供。
    public static <T> Appender<T> appender(Consumer<T> add, Consumer<TagKey<T>> addTag) {
        return new Appender<>() {
            @Override
            public Appender<T> add(T value) {
                add.accept(value);
                return this;
            }

            @Override
            public Appender<T> addTag(TagKey<T> tag) {
                addTag.accept(tag);
                return this;
            }
        };
    }

    /** Foods that may be used to feed/heal a companion (vanilla foods only, no mod cross-deps). */
    // 描述默认食物与脚手架物品清单。必须有数据提供器调用并输出标签，单独存在这个方法不会生效。
    public static void addItemTags(TagAppenderProvider<Item> tags) {
        tags.tag(InitTag.TAME_FOODS)
                .add(Items.APPLE)
                .add(Items.BAKED_POTATO)
                .add(Items.BREAD)
                .add(Items.CARROT)
                .add(Items.COOKED_BEEF)
                .add(Items.COOKED_CHICKEN)
                .add(Items.COOKED_COD)
                .add(Items.COOKED_MUTTON)
                .add(Items.COOKED_PORKCHOP)
                .add(Items.COOKED_RABBIT)
                .add(Items.COOKED_SALMON)
                .add(Items.COOKIE)
                .add(Items.GLOW_BERRIES)
                .add(Items.GOLDEN_APPLE)
                .add(Items.GOLDEN_CARROT)
                .add(Items.HONEY_BOTTLE)
                .add(Items.MELON_SLICE)
                .add(Items.MUSHROOM_STEW)
                .add(Items.PUMPKIN_PIE)
                .add(Items.POTATO)
                .add(Items.BEETROOT)
                .add(Items.RABBIT_STEW)
                .add(Items.SWEET_BERRIES);

        // Cheap, common blocks the pathfinder may expend as scaffolding —
        // never the player's valuables. Packs can extend this tag freely.
        tags.tag(InitTag.SCAFFOLDS)
                .add(Items.COBBLESTONE)
                .add(Items.DIRT)
                .add(Items.COBBLED_DEEPSLATE)
                .add(Items.STONE)
                .add(Items.NETHERRACK)
                .add(Items.ANDESITE)
                .add(Items.DIORITE)
                .add(Items.GRANITE)
                .add(Items.TUFF)
                .add(Items.DEEPSLATE)
                // Dirt-family variants players actually hand the companion ("here,
                // 128 dirt") — a stack of coarse dirt must count as scaffolding,
                // or hasScaffold=false silently disables every pillar/bridge move.
                .add(Items.COARSE_DIRT)
                .add(Items.ROOTED_DIRT)
                .add(Items.PODZOL)
                .add(Items.MUD)
                .add(Items.MOSSY_COBBLESTONE)
                .add(Items.GRASS_BLOCK)
                .add(Items.MYCELIUM)
                .add(Items.CALCITE)
                // 各维度手边最多的那种:去了下界/末地,主世界那批可能一块都没带。
                .add(Items.BLACKSTONE)
                .add(Items.BASALT)
                .add(Items.SOUL_SOIL)
                .add(Items.END_STONE)
                .add(Items.SNOW_BLOCK);
        // 沙和砂砾故意不在其中:重力方块垫在半空会直接落下去,柱子搭不起来。
    }
}
