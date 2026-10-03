// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.lighting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;
import org.maiwithu.maicraft.core.task.base.LandmarkProtection;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;

/** 跟随已经授权的身体移动补光；不导航、不停步、不替换主任务，也不为补火把去取料。 */
public final class AutomaticLighting {
    private static final AutomaticLighting INSTANCE = new AutomaticLighting();
    private LocalPlayer owner;
    private OffhandTorchPlacer placer = new OffhandTorchPlacer();
    private boolean enabled = true;
    private int minimum = 8;
    private List<String> protectedLabels = List.of();
    private final Set<BlockPos> visited = new LinkedHashSet<>();
    private final List<Map<String, Object>> placements = new ArrayList<>();
    private BlockPos lastAttemptOrigin;
    private String state = "waiting_for_movement";
    private String observationProblem;

    public static AutomaticLighting get() { return INSTANCE; }

    public void configure(LocalPlayer player, boolean enabled, int minimum, List<String> protectedLabels) {
        bind(player);
        this.enabled = enabled;
        this.minimum = minimum;
        this.protectedLabels = List.copyOf(protectedLabels);
        state = enabled ? "enabled" : "disabled";
    }

    private void bind(LocalPlayer player) {
        if (owner == player) return;
        // 不把旧身体的消耗、路线与候选带进重生或另一个世界；设置只在当前连接生命周期内有效。
        owner = player;
        placer = new OffhandTorchPlacer();
        visited.clear(); placements.clear(); lastAttemptOrigin = null;
    }

    public void reset() {
        owner = null; placer = new OffhandTorchPlacer(); visited.clear(); placements.clear();
        enabled = true; minimum = 8; protectedLabels = List.of(); lastAttemptOrigin = null;
        state = "waiting_for_movement";
    }

    public void tick(LocalPlayerContext context, boolean allowed) {
        try { advance(context, allowed); observationProblem = null; }
        catch (RuntimeException unavailable) {
            // 补光只是助手；光照、背包或模组观察暂时不可用时保留事实，不能让主任务跟着异常退出。
            state = "observation_unavailable"; observationProblem = unavailable.toString();
        }
    }

    private void advance(LocalPlayerContext context, boolean allowed) {
        bind(context.player());
        var receipt = placer.poll(context);
        if (receipt != null) {
            var at = placer.target().pos();
            placements.add(Map.of("position", position(at), "status", receipt.status().name(), "detail", receipt.detail()));
            state = receipt.status() == NativeActionReceipt.Status.CONFIRMED_APPLIED ? "light_settling" : "placement_unconfirmed";
        }
        // 关闭只停止新动作，已经发出的火把继续读回执；自救、暂停与区域补光独占时同样只观察。
        if (!enabled || !context.permitsNativeActions()) return;
        if (CompanionTickDispatcher.current() instanceof SemanticLightAreaTaskRecord) return;
        // 指定区域拥有自己的灯位与验收；随行助手不能同时插灯污染该任务的材料和覆盖回执。
        if (CompanionTickDispatcher.current() instanceof IntentTaskRecord intent && (intent.paused()
                || intent.stepIndex() < intent.steps().size() && intent.steps().get(intent.stepIndex()).ability().equals("maicraft:light_area"))) return;
        BlockPos feet = context.player().blockPosition(), eye = BlockPos.containing(context.player().getEyePosition());
        if (!context.level().isLoaded(feet) || !context.level().isLoaded(eye)) { state = "light_sample_unloaded"; return; }
        visited.add(feet.immutable()); visited.add(eye.immutable());
        // 主任务忙碌时经过的暗格也属于真实路线，先保留观察再让位，不能只抽取成功插灯的片段宣称覆盖。
        if (!allowed || !OffhandTorchPlacer.idle(context)) { state = "yielding_to_primary"; return; }
        int light = Math.min(context.level().getBrightness(LightLayer.BLOCK, feet), context.level().getBrightness(LightLayer.BLOCK, eye));
        if (light >= minimum) { state = "bright_enough"; return; }
        // 同一站位只尝试一支，避免高阈值、遮挡或服务端拒绝造成连续撒灯；继续移动后重新实测。
        if (lastAttemptOrigin != null && feet.distSqr(lastAttemptOrigin) < 4) { state = "waiting_for_route_progress"; return; }
        var protection = protection(context.player());
        if (!protection.problems().isEmpty()) { state = "unresolved_protection"; return; }
        if (!placer.prepare(context)) { state = placer.state(); return; }
        var target = protection.run(() -> RoutineTorchPlacement.find(context.player(), Set.of()));
        if (target == null) { state = "no_reachable_support"; return; }
        if (protection.run(() -> placer.place(context, target, Set.of()))) lastAttemptOrigin = feet.immutable();
        state = placer.state();
    }

    private LandmarkProtection protection(LocalPlayer player) {
        Set<String> labels = new LinkedHashSet<>(protectedLabels);
        if (CompanionTickDispatcher.current() instanceof IntentTaskRecord intent && intent.stepIndex() < intent.steps().size()) {
            var step = intent.steps().get(intent.stepIndex()); labels.addAll(step.inheritedProtectionLabels());
            var explicit = step.parameters().getAsJsonArray("protected_labels");
            if (explicit != null) explicit.forEach(label -> labels.add(label.getAsString()));
        }
        return LandmarkProtection.resolve(List.copyOf(labels), IntentRuntime.get().landmarks(), player.level().dimension().location().toString());
    }

    public Map<String, Object> snapshot(LocalPlayer player) {
        bind(player);
        List<Map<String, Object>> dark = new ArrayList<>();
        List<List<Integer>> unknown = new ArrayList<>();
        int lowest = 15, observed = 0;
        // 查询时重新读实际方块光；已卸载路线单列未知，不把插灯数量或理论传播冒充最低亮度验收。
        for (BlockPos pos : visited) {
            if (!player.level().isLoaded(pos)) { unknown.add(position(pos)); continue; }
            int light;
            try { light = player.level().getBrightness(LightLayer.BLOCK, pos); }
            catch (RuntimeException unavailable) { unknown.add(position(pos)); continue; }
            observed++; lowest = Math.min(lowest, light);
            if (light < minimum) dark.add(Map.of("position", position(pos), "block_light", light));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled); result.put("minimum_light", minimum); result.put("state", state);
        result.put("source", "minecraft:torch"); result.put("hand", "offhand");
        result.put("scope", "visited_feet_and_eyes_only"); result.put("dimension", player.level().dimension().location().toString());
        result.put("observed_cells", observed); result.put("minimum_observed_block_light", observed == 0 ? null : lowest);
        result.put("below_target", dark); result.put("unloaded_cells", unknown); result.put("placements", List.copyOf(placements));
        result.put("coverage_verified", observed > 0 && dark.isEmpty() && unknown.isEmpty() && !placer.pending());
        result.put("protected_labels", protectedLabels);
        if (observationProblem != null) result.put("observation_problem", observationProblem);
        return result;
    }

    private static List<Integer> position(BlockPos pos) { return List.of(pos.getX(), pos.getY(), pos.getZ()); }
}
