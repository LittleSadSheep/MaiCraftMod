// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 无需游戏或图形上下文即可核对审核状态和不可变手工几何。 */
public final class PreviewSessionTest {
    public static void main(String[] args) {
        // 独立测试进程关闭人工审图时不修改保存开关，清除启动覆盖后立即恢复原来的玩家设置。
        String prior = System.getProperty("maicraft.preview.enabled");
        boolean before = PreviewConfig.enabled();
        try {
            System.setProperty("maicraft.preview.enabled", "false");
            check(!PreviewConfig.enabled(), "自动验收启动覆盖关闭人工预览");
            System.setProperty("maicraft.preview.enabled", "true");
            check(PreviewConfig.enabled(), "显式人工审图覆盖仍可启用");
        } finally {
            if (prior == null) System.clearProperty("maicraft.preview.enabled");
            else System.setProperty("maicraft.preview.enabled", prior);
        }
        check(PreviewConfig.enabled() == before, "启动覆盖不改写原来的预览开关");
        // F9+P 的路线开关与 Dev 分离：默认关闭，翻转会记住，也不受审图启动覆盖影响。
        boolean pathBefore = PreviewConfig.pathLines();
        check(!pathBefore, "导航路线默认不显示");
        try {
            PreviewConfig.pathLines(!pathBefore);
            check(PreviewConfig.pathLines() != pathBefore, "路线开关可以翻转");
            System.setProperty("maicraft.preview.enabled", "false");
            check(PreviewConfig.pathLines() != pathBefore, "路线开关独立于审图启动覆盖");
            PreviewConfig.pathLines(pathBefore);
        } catch (IOException impossible) {
            throw new AssertionError("配置文件未初始化时 persist 不产生 IO", impossible);
        } finally {
            if (prior == null) System.clearProperty("maicraft.preview.enabled");
            else System.setProperty("maicraft.preview.enabled", prior);
        }
        check(PreviewConfig.pathLines() == pathBefore, "路线开关翻回原状");
        // F9+A 的事件流档位与 Dev、路线开关分离：默认全部显示，三档循环会记住，也不受审图启动覆盖影响。
        PreviewConfig.AttentionFeedMode feedBefore = PreviewConfig.attentionFeed();
        check(feedBefore == PreviewConfig.AttentionFeedMode.ALL, "attention 事件流默认全部显示");
        check(PreviewConfig.AttentionFeedMode.ALL.next() == PreviewConfig.AttentionFeedMode.LATEST
                && PreviewConfig.AttentionFeedMode.LATEST.next() == PreviewConfig.AttentionFeedMode.OFF
                && PreviewConfig.AttentionFeedMode.OFF.next() == PreviewConfig.AttentionFeedMode.ALL,
                "F9+A 循环次序：全部、只看最新一行、隐藏");
        try {
            PreviewConfig.attentionFeed(PreviewConfig.AttentionFeedMode.LATEST);
            check(PreviewConfig.attentionFeed() == PreviewConfig.AttentionFeedMode.LATEST, "可以切到只看最新一行");
            PreviewConfig.attentionFeed(PreviewConfig.AttentionFeedMode.OFF);
            check(PreviewConfig.attentionFeed() == PreviewConfig.AttentionFeedMode.OFF, "可以切到隐藏");
            System.setProperty("maicraft.preview.enabled", "false");
            check(PreviewConfig.attentionFeed() == PreviewConfig.AttentionFeedMode.OFF, "事件流档位独立于审图启动覆盖");
            PreviewConfig.attentionFeed(feedBefore);
        } catch (IOException impossible) {
            throw new AssertionError("配置文件未初始化时 persist 不产生 IO", impossible);
        } finally {
            if (prior == null) System.clearProperty("maicraft.preview.enabled");
            else System.setProperty("maicraft.preview.enabled", prior);
        }
        check(PreviewConfig.attentionFeed() == feedBefore, "事件流档位翻回原状");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(1, 64, 2);
        Map<BlockPos, BlockState> source = new LinkedHashMap<>();
        source.put(mutable, Blocks.OAK_STAIRS.defaultBlockState());
        List<PreviewPart> parts = new ArrayList<>();
        parts.add(new PreviewPart(mutable, "ae2:cable", "center"));
        var session = new PreviewSession("task-1", "minecraft:overworld", "house", source, parts);
        source.clear(); parts.clear(); mutable.set(10, 80, 20);
        check(session.cells().size() == 1 && session.cells().containsKey(new BlockPos(1, 64, 2)),
                "the preview must freeze positions and caller collections before review");
        check(session.parts().getFirst().position().equals(new BlockPos(1, 64, 2)), "multipart positions are frozen");
        try { session.cells().clear(); throw new AssertionError("authored geometry remained mutable"); }
        catch (UnsupportedOperationException expected) { }
        session.visible(false);
        check(session.decision() == PreviewSession.Decision.WAITING && !session.visible(),
                "hiding cannot accidentally authorize construction");
        session.layers(63, 64);
        check(session.includes(new BlockPos(1, 64, 2)) && !session.includes(new BlockPos(1, 65, 2)),
                "layer endpoints are inclusive and only affect presentation");
        check(session.cells().size() == 1 && session.confirm(), "slice review confirms the entire frozen plan");
        check(!session.confirm(), "a confirmation is consumed only once");
        session.cancel();
        session.visible(true);
        check(session.decision() == PreviewSession.Decision.CANCELLED && !session.confirm() && !session.visible(),
                "world changes and cancellation revoke approval permanently");
        var second = new PreviewSession("task-2", "minecraft:the_nether", "new plan",
                Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState()));
        check(second.decision() == PreviewSession.Decision.WAITING, "new plans never inherit old approval");
        rejects(() -> second.layers(5, 4));
        rejects(() -> new PreviewSession("task", "dimension", "empty", Map.of()));
        rejects(() -> new PreviewPart(BlockPos.ZERO, "ae2:cable", "unknown"));
        check(PreviewPartGeometry.localBox("north").maxZ < .5
                && PreviewPartGeometry.localBox("south").minZ > .5
                && PreviewPartGeometry.localBox("center").getXsize() < 1,
                "multipart side and centre geometry retain their distinct physical locations");
        // 事件流档位的落盘解析必须最后跑：load 会把配置路径定到临时目录，影响此后同 JVM 的 persist 去向。
        try {
            feedModeValuesParseThroughLoad();
        } catch (IOException impossible) {
            throw new AssertionError("临时配置文件读写不应失败", impossible);
        }
        System.out.println("PreviewSessionTest: passed");
    }

    /** 旧版布尔迁移（true=全部、false=隐藏）、latest 新档、非法值与缺键回退，全部经 load 读真实属性文件验证。 */
    private static void feedModeValuesParseThroughLoad() throws IOException {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("maicraft-preview-config");
        java.nio.file.Path file = dir.resolve("config/maicraft-preview.properties");
        java.nio.file.Files.createDirectories(file.getParent());
        checkFeedMode(dir, file, "attentionFeed=false\n", PreviewConfig.AttentionFeedMode.OFF, "旧布尔 false 迁移为隐藏");
        checkFeedMode(dir, file, "attentionFeed=true\n", PreviewConfig.AttentionFeedMode.ALL, "旧布尔 true 迁移为全部");
        checkFeedMode(dir, file, "attentionFeed=latest\n", PreviewConfig.AttentionFeedMode.LATEST, "latest 档位可读回");
        checkFeedMode(dir, file, "attentionFeed=banana\n", PreviewConfig.AttentionFeedMode.ALL, "非法值回退全部");
        checkFeedMode(dir, file, "", PreviewConfig.AttentionFeedMode.ALL, "缺键默认全部");
    }

    private static void checkFeedMode(java.nio.file.Path dir, java.nio.file.Path file, String content,
            PreviewConfig.AttentionFeedMode expected, String message) throws IOException {
        java.nio.file.Files.writeString(file, content);
        PreviewConfig.load(dir);
        check(PreviewConfig.attentionFeed() == expected, message);
    }

    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("invalid preview accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
