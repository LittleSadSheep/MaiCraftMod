// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ponder;

import com.google.gson.JsonArray;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.mcp.knowledge.PonderKnowledgeSource;

// 用给定演示状态检查平移投影、旋转拒绝、章节切分、拆除前保留和旧资源失效；不运行实际 Ponder 动画世界。
public final class PonderReplayTest {
    private static final PonderAccess.Entry ENTRY = new PonderAccess.Entry("fixture", "addon:machine", "addon:usage", List.of(), null);
    public static void main(String[] args) throws Exception {
        PonderBlueprintStore.clear();
        var source = snapshot(true, Vec3.ZERO, Vec3.ZERO, 1);
        var moved = snapshot(true, new Vec3(0, 0, 1), Vec3.ZERO, 1);
        var blueprint = moved.blueprint(ENTRY, "addon:scene");
        var block = blueprint.getAsJsonArray("blocks").get(0).getAsJsonObject();
        check(block.getAsJsonArray("offset").get(2).getAsInt() == 2, "visible translation must move depot from z=1 to z=2");
        check(!block.has("nbt") && !block.has("observed_nbt"), "observed kinetic NBT must never become desired placement configuration");
        check(blueprint.getAsJsonObject("evidence").toString().contains("Speed"), "raw NBT remains available as evidence");
        check(!source.losesStructureTo(moved), "mere motion must not create per-tick retained snapshots");
        var fading = snapshot(true, Vec3.ZERO, Vec3.ZERO, .8);
        check(source.losesStructureTo(fading), "retain settled structure before hide animation starts");
        var hidden = snapshot(false, Vec3.ZERO, Vec3.ZERO, 0);
        check(source.losesStructureTo(hidden), "retain layout before destructive hide");
        check(hidden.blueprint(ENTRY, "addon:scene").getAsJsonArray("blocks").isEmpty(), "hidden source blocks are not built");
        String uri = PonderBlueprintStore.put(ENTRY.key(), blueprint);
        check(uri.equals(PonderBlueprintStore.put(ENTRY.key(), blueprint)), "identical snapshots share one resource");
        var copy = PonderBlueprintStore.resolve(uri); copy.remove("blocks");
        check(PonderBlueprintStore.resolve(uri).has("blocks"), "resource callers cannot mutate stored blueprints");
        var rotated = snapshot(true, Vec3.ZERO, new Vec3(0, 45, 0), 1).blueprint(ENTRY, "addon:scene");
        rejects(() -> PonderBlueprintStore.resolve(PonderBlueprintStore.put(ENTRY.key(), rotated)), "rotated snapshots require explicit transform resolution");
        var offGrid = snapshot(true, new Vec3(.5, 0, 0), Vec3.ZERO, 1).blueprint(ENTRY, "addon:scene");
        rejects(() -> PonderBlueprintStore.resolve(PonderBlueprintStore.put(ENTRY.key(), offGrid)), "half-grid offsets must not be rounded into a build");
        PonderKnowledgeSource resources = new PonderKnowledgeSource(PonderFixture.access(), id -> id);
        var document = resources.read(uri);
        check(document.content().get("mimeType").getAsString().equals("application/json"), "structure resources use JSON MIME");
        check(document.entry().metadata().get("mimeType").getAsString().equals("application/json"), "metadata preserves JSON MIME");
        List<PonderTranscript.Step> narration = List.of(new PonderTranscript.Step(1, 0, "旁白", "Place the machine", null),
                new PonderTranscript.Step(2, 2, "控制提示", "Right click", "(2,1,2)"), new PonderTranscript.Step(3, 3, "旁白", "Remove it", null));
        var transcript = new PonderTranscript("addon:scene", "Fixture", narration, Map.of(), List.of());
        var midway = snapshot(true, new Vec3(0, 0, .5), Vec3.ZERO, 1);
        var driver = new FixtureDriver(List.of(source, source, midway, moved, hidden), List.of(3));
        var session = new PonderReplaySession(ENTRY, transcript, driver);
        session.advance(1, Long.MAX_VALUE);
        check(session.ticks() == 1 && session.status().equals("running"), "client slice must return while replay is pending");
        session.advance(10, Long.MAX_VALUE);
        check(session.status().equals("complete") && session.chapters().size() == 2, "keyframes partition chapter end snapshots");
        check(session.chapters().get(0).frames().getLast().projectionComplete()
                && session.chapters().get(0).frames().getLast().tick() == 3, "chapter boundary keeps settled current tick, not previous off-grid motion tick");
        check(session.chapters().get(0).steps().size() == 2 && session.chapters().get(1).steps().size() == 1, "all narration and controls stay in their chapters");
        check(session.chapters().get(1).frames().stream().anyMatch(frame -> frame.reason().equals("before_removal_or_replacement")), "destructive pre-state is retained");
        check(session.markdown("maicraft://knowledge/ponder/replay/fixture", 0).contains("Right click"), "chapter resources contain control hints");
        var broken = new FixtureDriver(List.of(source, source), List.of()); broken.fail = true;
        var failed = new PonderReplaySession(ENTRY, transcript, broken); failed.advance(1, Long.MAX_VALUE);
        check(failed.status().equals("failed") && failed.markdown("replay", 0).contains("custom callback failed"), "custom callback failure is explicit, not a successful partial capture");
        PonderReplayRuntime.invalidate(); rejects(() -> PonderBlueprintStore.resolve(uri), "reload invalidation rejects stale resource imports");
        System.out.println("PonderReplayTest: passed");
    }

    private static PonderStructureSnapshot snapshot(boolean visible, Vec3 offset, Vec3 rotation, double fade) {
        BlockPos position = new BlockPos(2, 1, 1);
        return new PonderStructureSnapshot(List.of(new PonderStructureSnapshot.Block(position, "addon:depot", Map.of("facing", "north"), "{Speed:32f}")),
                List.of(new PonderStructureSnapshot.Section("depot", List.of(position), visible, offset, rotation,
                        Vec3.ZERO, null, fade, new Vec3(0, 1, 0))), new JsonArray());
    }
    private static final class FixtureDriver implements PonderReplaySession.Driver {
        final List<PonderStructureSnapshot> states; final List<Integer> frames; int tick; boolean fail;
        FixtureDriver(List<PonderStructureSnapshot> states, List<Integer> frames) { this.states = states; this.frames = frames; }
        public void tick() { if (fail) throw new IllegalStateException("custom callback failed"); tick++; }
        public boolean finished() { return tick == states.size() - 1; }
        public int time() { return tick; }
        public List<Integer> keyframes() { return frames; }
        public PonderStructureSnapshot snapshot() { return states.get(tick); }
    }
    private static void rejects(Runnable action, String detail) {
        try { action.run(); throw new AssertionError(detail); } catch (IllegalArgumentException expected) { }
    }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
}
