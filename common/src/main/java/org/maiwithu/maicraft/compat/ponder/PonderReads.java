// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ponder;

import java.util.List;
import java.util.Objects;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 思索的模组读写接缝：列出注册的场景，把一个场景在独立演示世界里一刻一刻放下去，每刻读演示世界的方块、
 * 新出现的旁白与操作提示。只用 Minecraft 与 Java 的类型；读写端只翻译，怎么分段、怎么比较方块由联动入口做。
 * 只在客户端线程上调用。
 */
public interface PonderReads {

    /** 注册的全部思索场景，按注册顺序；同一件物品的场景序号从 1 起，按注册的先后编（思索界面可能再按作者给的先后排过）。 */
    List<RegisteredScene> scenes();

    /** 一件物品当前语言里的名字；注册表里没有时用 ID。 */
    String itemName(String itemId);

    /** 读写端此刻的环境（游戏语言、所在世界）：变了，放过的场景就要重放。 */
    String environment();

    /**
     * 在一个新的独立演示世界里开始放这个场景，停在第 0 刻（作者的结构文件刚摆好）。
     * 角色不在世界里时演示世界建不起来，抛 IllegalStateException。
     */
    ScenePlayback play(RegisteredScene scene);

    /**
     * 一个注册的思索场景。
     *
     * @param component 它属于哪件物品（注册 ID），思索界面就是按物品列场景的
     * @param number    这件物品的第几个场景，从 1 起
     * @param schematic 作者的演示结构文件，例如 create:mechanical_mixer/mixing
     * @param tags      思索标签，例如 create:kinetic_appliances
     */
    record RegisteredScene(String component, int number, String schematic, List<String> tags) {
        public RegisteredScene {
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(schematic, "schematic");
            if (number < 1) throw new IllegalArgumentException("场景序号从 1 起：" + number);
            tags = List.copyOf(tags);
        }
    }

    /** 一次在演示世界里的播放：一刻一刻往下放，每刻读得到演示世界与新出现的文字。 */
    interface ScenePlayback {

        /** 场景的 ID 与当前语言的标题。 */
        String sceneId();

        String title();

        /** 演示世界的范围：最小角与最大角（都含）。 */
        BlockPos minCorner();

        BlockPos maxCorner();

        /** 演示世界里这一格现在是什么方块。 */
        BlockState blockAt(BlockPos position);

        /** 放到第几刻了，以及整场一共几刻。 */
        int tick();

        int totalTicks();

        /** 场景已经放完。 */
        boolean finished();

        /** 到现在为止经过了几个关键帧。 */
        int keyframesPassed();

        /** 往下放一刻。 */
        void advance();

        /** 上次问过之后新出现的旁白与操作提示，按出现顺序；读不到内容的也给一条，写明读不到。 */
        List<ShownInScene> newlyShown();
    }

    /**
     * 场景里新出现的一段：旁白或操作提示。
     *
     * @param text     旁白的文字；操作提示时为空串
     * @param input    操作提示是哪种：左键、右键、滚轮或自定义图标的名字；旁白时为空串
     * @param modifier 操作提示要同时按住什么：sneak、ctrl，不用按住时为空串
     * @param itemId   操作提示里手持的东西；不用拿东西时为空串
     * @param count    手持的件数
     * @param readable 这一段的内容读得到；Ponder 版本对不上时读不到，只知道出现了一段
     */
    record ShownInScene(String text, String input, String modifier, String itemId, int count, boolean readable) {
        public ShownInScene {
            text = text == null ? "" : text;
            input = input == null ? "" : input;
            modifier = modifier == null ? "" : modifier;
            itemId = itemId == null ? "" : itemId;
        }

        /** 一段旁白。 */
        public static ShownInScene narration(String text) {
            return new ShownInScene(text, "", "", "", 0, true);
        }

        /** 一个操作提示。 */
        public static ShownInScene input(String input, String modifier, String itemId, int count) {
            return new ShownInScene("", input, modifier, itemId, count, true);
        }

        /** 出现了一段，但内容读不到。 */
        public static ShownInScene unreadable(boolean isInput) {
            return new ShownInScene("", isInput ? "unknown" : "", "", "", 0, false);
        }

        /** 这一段是操作提示。 */
        public boolean isInput() {
            return !input.isEmpty();
        }
    }
}
