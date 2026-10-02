// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import org.maiwithu.maicraft.server.machine.NativeApi;
import org.maiwithu.maicraft.client.actor.FtbQuestSubmission;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import static org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestApi.*;

/** 原生消息走 Architectury 的正常客户端通道；背包、任务进度和奖励记录只观察，从不直接写入。 */
public final class MinecraftFtbQuestActions implements FtbQuestActionAccess {
    private static final String CLIENT = "dev.ftb.mods.ftbquests.client.ClientQuestFile";
    private static final String NETWORK = "dev.architectury.networking.NetworkManager";
    private static final String MESSAGES = "dev.ftb.mods.ftbquests.net.";
    private final UUID owner;
    private record Scope(Object connection, Object file, Object body, UUID player, UUID team) {}
    private record Context(Scope scope, LocalPlayer player, Object team) {}
    private record NativeRequest(FtbQuestActionTarget target, Object payload) {}
    public MinecraftFtbQuestActions(LocalPlayer player) { owner = player == null ? null : player.getUUID(); }

    @Override public Prepared prepare(FtbQuestActionRequest request) {
        Context context = context();
        FtbQuestSubmission.requireAuthority(ClientRuntime.requireContext(context.player()));
        if (!context.player().isAlive()) throw new IllegalStateException("Player is not alive");
        var target = FtbQuestActionTarget.resolve(context.scope().file(), context.team(), owner, request);
        Object payload = null;
        if (!target.satisfied()) {
            String channel = switch (target.packet()) {
                case "SubmitTaskMessage" -> "submit_task_message";
                case "ClaimChoiceRewardMessage" -> "claim_choice_reward_message";
                default -> "claim_reward_message";
            };
            if (!NativeApi.truth(NativeApi.call(null, NETWORK, "canServerReceive", ResourceLocation.parse("ftbquests:" + channel))))
                throw new IllegalStateException("Server has not enabled this FTB action channel");
            // 构造消息只准备数据；先确认构造器可用，再进入持久预约，避免接口缺失时消耗操作编号。
            try {
                Class<?> type = NativeApi.type(MESSAGES + target.packet()); long id = FtbQuestActionRequest.number(request.subjectId());
                payload = switch (target.packet()) {
                    case "SubmitTaskMessage" -> type.getConstructor(long.class).newInstance(id);
                    case "ClaimChoiceRewardMessage" -> type.getConstructor(long.class, int.class).newInstance(id, target.choiceIndex());
                    default -> type.getConstructor(long.class, boolean.class).newInstance(id, true);
                };
            } catch (ReflectiveOperationException unavailable) { throw new IllegalStateException("FTB native message API unavailable", unavailable); }
        }
        JsonObject before = snapshot(context, target);
        if (!request.operation().equals("claim")) {
            JsonObject selected = identity(target.subject()); String type = call(call(target.subject(), "getType"), "getTypeId").toString();
            selected.addProperty("type", type); selected.addProperty("consumes_resources", flag(target.subject(), "consumesResources"));
            try { selected.add("conditions", FtbTaskConditions.read(type, FtbQuestData.definition(target.subject()), call(target.subject(), "getMaxProgress").toString())); }
            catch (RuntimeException | LinkageError unavailable) { selected.addProperty("conditions_status", "api_unavailable"); }
            before.add("selected_task", selected);
        }
        if (request.operation().equals("claim") && !target.satisfied()) {
            // 选奖时把本次实际选中的内容放进回执，不要求模型回翻旧任务书来比较预期与到账。
            Object selected = target.choiceIndex() < 0 ? target.subject()
                    : call(FtbRewardTables.rows(call(target.subject(), "getTable")).get(target.choiceIndex()), "getReward");
            JsonObject expected = identity(selected); String type = FtbQuestRewards.type(selected); expected.addProperty("type", type);
            if (!FtbRewardTables.isTable(selected)) {
                try { FtbRewardContents.append(expected, selected, type); }
                catch (RuntimeException | LinkageError unavailable) { expected.addProperty("content_status", "api_unavailable"); }
            }
            before.add("selected_reward", expected);
        }
        return new Prepared(context.scope(), before, target.satisfied(), new NativeRequest(target, payload));
    }
    @Override public void submit(Prepared prepared) {
        Context current = context(); requireScope(prepared, current);
        NativeRequest nativeRequest = (NativeRequest) prepared.nativeRequest();
        // 持久屏障放行后唯一的一次发包；服务端仍执行 FTB 原生的权限、前置、消费和领奖判定。
        if (nativeRequest.payload() == null) throw new IllegalStateException("No native FTB payload prepared");
        FtbQuestSubmission.send(ClientRuntime.requireContext(current.player()), nativeRequest.payload());
    }
    @Override public JsonObject observe(Prepared prepared) {
        Context current = context(); requireScope(prepared, current);
        return snapshot(current, ((NativeRequest) prepared.nativeRequest()).target());
    }
    private static void requireScope(Prepared prepared, Context current) {
        if (!prepared.scope().equals(current.scope())) throw new IllegalStateException("Player, connection, team or quest book changed during the action");
    }
    private Context context() {
        Minecraft client = Minecraft.getInstance(); LocalPlayer player = client == null ? null : client.player;
        if (player == null || client.level == null || client.getConnection() == null || !player.getUUID().equals(owner))
            throw new IllegalStateException("Current player is unavailable for the FTB action");
        if (!NativeApi.present(CLIENT)) throw new IllegalStateException("FTB Quests client API is unavailable; check the installed mod and dependencies");
        Object file = NativeApi.call(null, CLIENT, "getInstance");
        if (file == null || !FtbQuestSync.matches(file, client.getConnection()) || !flag(file, "isValid"))
            throw new IllegalStateException("FTB quest book is not synchronized on this connection");
        Object team = NativeApi.field(file, null, "selfTeamData");
        if (team == null || call(team, "getFile") != file) throw new IllegalStateException("FTB team data is not synchronized");
        UUID teamId = (UUID) call(team, "getTeamId");
        if (teamId.equals(new UUID(0, 0))) throw new IllegalStateException("FTB team data is still loading");
        // 死亡或跨维度重建的身体可能还没同步背包，不能拿其临时空背包与旧身体比较并误报丢失。
        return new Context(new Scope(client.getConnection(), file, player, owner, teamId), player, team);
    }
    private static JsonObject snapshot(Context context, FtbQuestActionTarget target) {
        JsonObject out = new JsonObject(); out.addProperty("observed_at", Instant.now().toString());
        out.addProperty("player_id", context.scope().player().toString()); out.addProperty("team_id", context.scope().team().toString());
        JsonObject ftb = new JsonObject(); ftb.addProperty("quest_id", target.request().questId());
        ftb.addProperty("subject_id", target.request().subjectId()); ftb.addProperty("subject_available", flag(target.subject(), "isValid"));
        ftb.addProperty("quest_available", flag(target.quest(), "isValid"));
        if (flag(target.quest(), "isValid")) {
            ftb.addProperty("quest_completed", flag(context.team(), "isCompleted", target.quest()));
            ftb.addProperty("quest_completion_count", (Number) call(context.team(), "getCompletionCount", target.quest()));
        }
        if (flag(target.subject(), "isValid")) {
            if (target.request().operation().equals("claim")) ftb.add("reward", FtbQuestRewards.state(target.subject(), context.team(), context.scope().player()));
            else {
                ftb.addProperty("progress", call(context.team(), "getProgress", target.subject()).toString());
                ftb.addProperty("required", call(target.subject(), "getMaxProgress").toString());
                ftb.addProperty("task_completed", flag(context.team(), "isCompleted", target.subject()));
            }
        }
        out.add("ftb", ftb); out.add("inventory", FtbInventoryEvidence.capture(context.player()));
        LocalPlayer player = context.player(); JsonObject body = new JsonObject();
        body.addProperty("dimension", player.level().dimension().location().toString());
        body.addProperty("x", player.getX()); body.addProperty("y", player.getY()); body.addProperty("z", player.getZ());
        body.addProperty("reported_total_experience", player.totalExperience); body.addProperty("experience_levels", player.experienceLevel);
        body.addProperty("experience_progress", player.experienceProgress); body.addProperty("health", player.getHealth());
        out.add("player", body); return out;
    }
}
