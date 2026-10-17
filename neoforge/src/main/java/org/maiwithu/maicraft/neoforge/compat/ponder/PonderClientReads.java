// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ponder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import net.createmod.ponder.api.element.PonderElement;
import net.createmod.ponder.api.registration.StoryBoardEntry;
import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.InputWindowElement;
import net.createmod.ponder.foundation.element.TextWindowElement;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import org.maiwithu.maicraft.compat.ponder.PonderReads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 思索的读写端：直接调用 Ponder 的类，只翻译，不判断。
 *
 * <p>场景列表、编译、在演示世界里放、读方块都走 Ponder 的公开接口（和思索界面自己用的是同一条路）。
 * 旁白的文字与操作提示的按键、手持物只存在 Ponder 的非公开字段里：所有者批准的例外，只在这里读这四个字段——
 * TextWindowElement.textGetter、InputWindowElement.icon / key / item。字段在创建读写端时取一次，
 * 取不到（Ponder 改了名）的那一类就给"读不到内容"的一段，别的照读。字段名登记在机器联动模组事实清单里。
 */
public final class PonderClientReads implements PonderReads {
    private static final Logger LOG = LoggerFactory.getLogger(PonderClientReads.class);

    private final Field textGetter = field(TextWindowElement.class, "textGetter");
    private final Field inputIcon = field(InputWindowElement.class, "icon");
    private final Field inputKey = field(InputWindowElement.class, "key");
    private final Field inputItem = field(InputWindowElement.class, "item");

    // 场景按物品分组、组内按注册顺序编号，和联动入口给的序号一一对应。
    @Override public List<RegisteredScene> scenes() {
        List<RegisteredScene> scenes = new ArrayList<>();
        Map<ResourceLocation, Integer> numbers = new LinkedHashMap<>();
        for (StoryBoardEntry entry : entries()) {
            int number = numbers.merge(entry.getComponent(), 1, Integer::sum);
            scenes.add(new RegisteredScene(entry.getComponent().toString(), number, entry.getSchematicLocation().toString(),
                    entry.getTags().stream().map(ResourceLocation::toString).toList()));
        }
        return List.copyOf(scenes);
    }

    @Override public String itemName(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) return itemId;
        return BuiltInRegistries.ITEM.getOptional(id).map(item -> item.getDescription().getString()).orElse(itemId);
    }

    @Override public String environment() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getLanguageManager().getSelected() + "@" + System.identityHashCode(minecraft.level);
    }

    // 编译并开场：Ponder 自己会新建一个演示世界、摆好作者的结构文件、停在第 0 刻；不碰玩家的世界。
    @Override public ScenePlayback play(RegisteredScene scene) {
        if (Minecraft.getInstance().level == null) {
            throw new IllegalStateException("角色不在世界里，演示世界建不起来");
        }
        int seen = 0;
        for (StoryBoardEntry entry : entries()) {
            if (!entry.getComponent().toString().equals(scene.component())) continue;
            if (++seen == scene.number()) {
                List<PonderScene> compiled = PonderIndex.getSceneAccess().compile(List.of(entry));
                if (compiled.isEmpty()) throw new IllegalStateException("Ponder 没有编出这个场景");
                return new Playback(compiled.getFirst());
            }
        }
        throw new IllegalStateException("没有这个思索场景：" + scene.component() + " 的第 " + scene.number() + " 个");
    }

    private static List<StoryBoardEntry> entries() {
        List<StoryBoardEntry> entries = new ArrayList<>();
        for (Map.Entry<ResourceLocation, StoryBoardEntry> row : PonderIndex.getSceneAccess().getRegisteredEntries()) {
            entries.add(row.getValue());
        }
        return entries;
    }

    /** 一次播放：每刻像思索界面跳着看时那样推进（先让各元素跟上，再推进一刻），不渲染。 */
    private final class Playback implements ScenePlayback {
        private final PonderScene scene;
        private final Set<PonderElement> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        Playback(PonderScene scene) {
            this.scene = scene;
        }

        @Override public String sceneId() {
            return scene.getId().toString();
        }

        @Override public String title() {
            return scene.getTitle();
        }

        @Override public BlockPos minCorner() {
            BoundingBox bounds = scene.getBounds();
            return new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
        }

        @Override public BlockPos maxCorner() {
            BoundingBox bounds = scene.getBounds();
            return new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ());
        }

        @Override public BlockState blockAt(BlockPos position) {
            return scene.getWorld().getBlockState(position);
        }

        @Override public int tick() {
            return scene.getCurrentTime();
        }

        @Override public int totalTicks() {
            return scene.getTotalTime();
        }

        // "标记完成"可能提前到来（那只是让界面亮起下一个场景的按钮）；时间也走完了才算放完。
        @Override public boolean finished() {
            return scene.isFinished() && scene.getCurrentTime() >= scene.getTotalTime();
        }

        @Override public int keyframesPassed() {
            return scene.getKeyframeCount();
        }

        @Override public void advance() {
            scene.forEach(element -> element.whileSkipping(scene));
            scene.tick();
        }

        @Override public List<ShownInScene> newlyShown() {
            List<ShownInScene> shown = new ArrayList<>();
            for (PonderElement element : List.copyOf(scene.getElements())) {
                if (element instanceof TextWindowElement text && seen.add(text)) {
                    shown.add(narration(text));
                } else if (element instanceof InputWindowElement input && seen.add(input)) {
                    shown.add(input(input));
                }
            }
            return shown;
        }
    }

    private ShownInScene narration(TextWindowElement element) {
        if (textGetter == null) return ShownInScene.unreadable(false);
        try {
            Object getter = textGetter.get(element);
            if (getter instanceof Supplier<?> supplier) return ShownInScene.narration(String.valueOf(supplier.get()));
        } catch (IllegalAccessException | RuntimeException failure) {
            LOG.debug("读思索旁白出错，这一段给读不到", failure);
        }
        return ShownInScene.unreadable(false);
    }

    private ShownInScene input(InputWindowElement element) {
        if (inputIcon == null || inputKey == null || inputItem == null) return ShownInScene.unreadable(true);
        try {
            Object icon = inputIcon.get(element);
            Object key = inputKey.get(element);
            Object item = inputItem.get(element);
            String iconName = icon instanceof Enum<?> value ? value.name() : icon == null ? "" : icon.getClass().getSimpleName();
            String itemId = "";
            int count = 0;
            if (item instanceof ItemStack stack && !stack.isEmpty()) {
                itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                count = stack.getCount();
            }
            return ShownInScene.input(iconName.isEmpty() ? "none" : iconName, key == null ? "" : key.toString(), itemId, count);
        } catch (IllegalAccessException | RuntimeException failure) {
            LOG.debug("读思索操作提示出错，这一段给读不到", failure);
            return ShownInScene.unreadable(true);
        }
    }

    // 取一个非公开字段并打开访问；取不到（改了名、打不开）就记一行日志，这一类内容给"读不到"。
    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            if (!field.trySetAccessible()) {
                LOG.warn("思索的字段 {}.{} 打不开，这一类内容读不到", owner.getSimpleName(), name);
                return null;
            }
            return field;
        } catch (NoSuchFieldException | RuntimeException missing) {
            LOG.warn("思索的字段 {}.{} 找不到（装的 Ponder 版本和实测过的不一样），这一类内容读不到", owner.getSimpleName(), name);
            return null;
        }
    }
}
