package org.maiwithu.maicraft.core.tools;

import org.maiwithu.maicraft.core.init.InitTag;
import org.maiwithu.maicraft.core.task.MouseButton;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 内部工具共用的参数转换：把方块名字／标签变成方块集合，把 left／right 变成鼠标键。
 * 两类参数对错误的处理不同：方块列表略过无效项，鼠标键则直接报错。
 */
public final class ToolParse {

    private ToolParse() {}

    /**
     * 宽松的方块 id 集:解析失败/未知/air 的条目跳过,保留输入顺序
     * (消息里的"第一个目标"标签依赖顺序)。
     *
     * <p><b>{@code #} 开头的条目是标签</b>,原样展开成它当下的全部成员——{@code #minecraft:beds}
     * 是"床这一类"而不是某一种颜色的床。这是原版自己的语法(标签文件的 {@code values} 里就用
     * 它引用别的标签),不是我们发明的写法,所以模型写出来的和它在数据包里见过的一致。
     *
     * <p>标签内容来自数据包,世界加载后才有,所以这里<b>每次调用现查</b>——{@code /reload}
     * 改了标签下一次就生效。
     */
    // 既接受具体方块名，也接受 # 开头的方块标签并展开成员；重复成员合并，保留首次出现顺序。
    // 空值、拼错的编号、不存在的方块和空气都静默略过，调用方若需要逐项报错不能只用返回集合判断。
    public static Set<Block> parseBlocks(List<String> ids) {
        Set<Block> out = new LinkedHashSet<>();
        if (ids == null) return out;
        for (String raw : ids) {
            if (raw == null) continue;
            TagKey<Block> tag = InitTag.parseRef(Registries.BLOCK, raw);
            if (tag != null) {
                for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
                    Block b = holder.value();
                    if (b != Blocks.AIR) out.add(b);
                }
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null) continue;
            Block b = BuiltInRegistries.BLOCK.get(id);
            if (b != null && b != Blocks.AIR) out.add(b);
        }
        return out;
    }

    /** 左键=攻击、右键=使用;缺参或非法值直接报参数错。 */
    // 只接受小写 left 或 right，缺省与其他文字都报错，不猜测“左键”等近义词。
    public static MouseButton parseButton(String button) {
        if (button == null) {
            throw new IllegalArgumentException("missing required argument: button");
        }
        return switch (button) {
            case "left" -> MouseButton.LEFT;
            case "right" -> MouseButton.RIGHT;
            default -> throw new IllegalArgumentException(
                    "button must be 'left' or 'right', got: " + button);
        };
    }
}
