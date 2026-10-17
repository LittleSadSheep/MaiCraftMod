// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ponder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeDocument;
import org.maiwithu.maicraft.kernel.knowledge.KnowledgeNotReady;

/**
 * 思索场景当资料：放一遍记下旁白（关键帧处分段）、开场结构与每段改了哪些格；一次放不完说还在准备；
 * 搜索、物品资料页的相关资料、索引、现状都按注册的场景给；换语言或世界后重放；放不出来如实说。
 */
class PonderScenesTest {

    private static final PonderReads.RegisteredScene MIXER_1 =
            new PonderReads.RegisteredScene("create:mechanical_mixer", 1, "create:mixer/mixing", List.of("create:kinetic_appliances"));
    private static final PonderReads.RegisteredScene MIXER_2 =
            new PonderReads.RegisteredScene("create:mechanical_mixer", 2, "create:mixer/brass", List.of());
    private static final PonderReads.RegisteredScene SHAFT =
            new PonderReads.RegisteredScene("create:shaft", 1, "create:shaft/relay", List.of());

    @BeforeAll
    static void 引导注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 关键帧处分段_每段记下改了哪些格() {
        FakeReads reads = new FakeReads();
        SceneRecorder recorder = new SceneRecorder(reads.play(MIXER_1), () -> 0);

        assertTrue(recorder.advance(Long.MAX_VALUE));

        assertEquals(3, recorder.lines().size());
        List<SceneRecorder.Segment> segments = recorder.segments();
        assertEquals(2, segments.size());
        assertEquals(List.of(1), segments.get(0).lines(), "关键帧前出现的旁白归第一段");
        assertEquals(List.of(2, 3), segments.get(1).lines(), "关键帧那一刻出现的旁白归新的一段");
        assertTrue(segments.get(0).changed().isEmpty());
        assertEquals(Blocks.STONE.defaultBlockState(), segments.get(1).changed().get(new BlockPos(2, 0, 1)), "坐标相对最小角");
        assertEquals(List.of(new BlockPos(1, 0, 1)), segments.get(1).removed());
        assertEquals(Blocks.ANDESITE.defaultBlockState(), recorder.opening().get(new BlockPos(1, 0, 1)), "开场结构照原样");
    }

    @Test
    void 一次放不完说还在准备_下一次接着放() {
        FakeReads reads = new FakeReads();
        long[] now = {0};
        PonderScenes scenes = new PonderScenes(new BareModule(), reads, () -> now[0] += 10_000_000L);

        KnowledgeNotReady preparing = assertThrows(KnowledgeNotReady.class, () -> scenes.read(PonderScenes.uri(MIXER_1)));
        assertTrue(preparing.getMessage().contains("共 6 刻"), preparing.getMessage());
        KnowledgeDocument scene = readUntilDone(scenes, PonderScenes.uri(MIXER_1));

        String text = scene.text();
        assertTrue(text.startsWith("# 搅拌（动力搅拌器的第 1 个思索场景）"), text);
        assertTrue(text.contains("  第 2 段\n    2. 下方要放工作盆\n    3. 〔操作提示〕潜行 + 右键，手持 create:wrench ×1"), text);
        assertTrue(text.contains("\"block\":\"minecraft:andesite\""), text);
        assertTrue(text.contains("\"removed\":[[1,0,1]]"), text);
        assertEquals(1, reads.played, "放完的场景留着，不重放");
        scenes.read(PonderScenes.uri(MIXER_1));
        assertEquals(1, reads.played);
    }

    @Test
    void 换了语言或世界就重放() {
        FakeReads reads = new FakeReads();
        PonderScenes scenes = new PonderScenes(new BareModule(), reads, () -> 0);
        readUntilDone(scenes, PonderScenes.uri(MIXER_1));

        reads.environment = "en_us@2";
        readUntilDone(scenes, PonderScenes.uri(MIXER_1));

        assertEquals(2, reads.played);
    }

    @Test
    void 放不出来时如实说原因() {
        FakeReads reads = new FakeReads();
        reads.broken = true;
        PonderScenes scenes = new PonderScenes(new BareModule(), reads, () -> 0);

        String text = scenes.read(PonderScenes.uri(MIXER_1)).text();

        assertTrue(text.contains("这一场景放不出来：角色不在世界里"), text);
    }

    @Test
    void 搜索_相关资料_索引与现状按注册的场景给() {
        PonderScenes scenes = new PonderScenes(new BareModule(), new FakeReads(), () -> 0);

        assertEquals(3, scenes.searchCandidates("搅拌").size());
        assertEquals(List.of(PonderScenes.uri(MIXER_1), PonderScenes.uri(MIXER_2)),
                scenes.entriesAbout("create:mechanical_mixer").stream().map(KnowledgeDocument.Entry::uri).toList());
        assertTrue(scenes.entriesAbout("minecraft:stone").isEmpty());
        assertEquals("思索（Ponder）：可用，3 个场景、2 件物品", scenes.status());
        String index = scenes.read(PonderScenes.INDEX).text();
        assertTrue(index.contains("共 2 件物品有思索场景，3 个场景"), index);
        assertTrue(index.contains("- 动力搅拌器（create:mechanical_mixer）：2 个场景"), index);
        assertNull(scenes.read("maicraft://knowledge/ponder/create/mechanical_mixer/9"), "没有的场景读不到");
        assertNull(scenes.read("maicraft://knowledge/ponder/nonsense"));
    }

    @Test
    void 读不到内容的一段如实标出() {
        FakeReads reads = new FakeReads();
        reads.unreadable = true;
        PonderScenes scenes = new PonderScenes(new BareModule(), reads, () -> 0);

        String text = readUntilDone(scenes, PonderScenes.uri(MIXER_1)).text();

        assertTrue(text.contains("有 1 段读不到内容"), text);
        assertTrue(text.contains("〔操作提示〕读不到内容"), text);
    }

    private static KnowledgeDocument readUntilDone(PonderScenes scenes, String uri) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                return scenes.read(uri);
            } catch (KnowledgeNotReady preparing) {
                // 下一刻接着放。
            }
        }
        throw new AssertionError("放不完");
    }

    /** 不碰任何模组的联动入口：测试里只要它包那一层 call。 */
    private static final class BareModule extends CompatModule {
        BareModule() {
            super("ponder", "思索（Ponder）");
        }

        @Override public void contribute(CompatRegistry registry) {}
    }

    /**
     * 替身读写端：三个场景；每次播放 6 刻，第 1 刻一段旁白，第 3 刻过关键帧并出一段旁白，第 4 刻一个操作提示、
     * 把一格换成石头并拆掉一格。
     */
    private static final class FakeReads implements PonderReads {
        String environment = "zh_cn@1";
        boolean broken;
        boolean unreadable;
        int played;

        @Override public List<RegisteredScene> scenes() {
            return List.of(MIXER_1, MIXER_2, SHAFT);
        }

        @Override public String itemName(String itemId) {
            return itemId.equals("create:shaft") ? "传动杆" : "动力搅拌器";
        }

        @Override public String environment() {
            return environment;
        }

        @Override public ScenePlayback play(RegisteredScene scene) {
            if (broken) throw new IllegalStateException("角色不在世界里");
            played++;
            return new ScriptedPlayback(unreadable);
        }
    }

    /** 按脚本推进的播放：演示世界的最小角是 (10, 64, 10)。 */
    private static final class ScriptedPlayback implements PonderReads.ScenePlayback {
        private static final BlockPos MIN = new BlockPos(10, 64, 10);
        private final Map<BlockPos, BlockState> world = new HashMap<>();
        private final boolean unreadable;
        private int tick;
        private int keyframes;
        private final List<PonderReads.ShownInScene> pending = new ArrayList<>();

        ScriptedPlayback(boolean unreadable) {
            this.unreadable = unreadable;
            world.put(MIN.offset(1, 0, 1), Blocks.ANDESITE.defaultBlockState());
        }

        @Override public String sceneId() { return "create:mixer"; }
        @Override public String title() { return "搅拌"; }
        @Override public BlockPos minCorner() { return MIN; }
        @Override public BlockPos maxCorner() { return MIN.offset(2, 1, 2); }
        @Override public BlockState blockAt(BlockPos position) {
            return world.getOrDefault(position, Blocks.AIR.defaultBlockState());
        }
        @Override public int tick() { return tick; }
        @Override public int totalTicks() { return 6; }
        @Override public boolean finished() { return tick >= 6; }
        @Override public int keyframesPassed() { return keyframes; }

        @Override public void advance() {
            tick++;
            switch (tick) {
                case 1 -> pending.add(PonderReads.ShownInScene.narration("动力搅拌器可以搅拌工作盆里的东西"));
                case 3 -> {
                    keyframes++;
                    pending.add(PonderReads.ShownInScene.narration("下方要放工作盆"));
                }
                case 4 -> {
                    pending.add(unreadable ? PonderReads.ShownInScene.unreadable(true)
                            : PonderReads.ShownInScene.input("ICON_RMB", "ponder:sneak_and", "create:wrench", 1));
                    world.put(MIN.offset(2, 0, 1), Blocks.STONE.defaultBlockState());
                    world.remove(MIN.offset(1, 0, 1));
                }
                default -> { }
            }
        }

        @Override public List<PonderReads.ShownInScene> newlyShown() {
            List<PonderReads.ShownInScene> shown = List.copyOf(pending);
            pending.clear();
            return shown;
        }
    }

}
