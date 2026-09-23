// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonObject;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.maiwithu.maicraft.server.machine.NativeApi;
import static org.maiwithu.maicraft.core.integration.ftbquests.FtbQuestApi.*;

/** 从当前连接的客户端任务书读取自身队伍；不创建 TeamData，不访问其他队伍，也不打开任务界面。 */
public final class ReflectiveFtbQuestsAccess implements FtbQuestBook {
    private static final String CLIENT = "dev.ftb.mods.ftbquests.client.ClientQuestFile";
    record Environment(Object file, UUID player, String dimension, Object connection) {}
    private static final class NotInstalled extends RuntimeException {}
    private final Supplier<Environment> environment;
    // 玩家退出后不能靠知识入口的会话标识留住整张任务书和连接对象，游戏自身决定其生命周期。
    private WeakReference<Object> lastFile = new WeakReference<>(null), lastConnection = new WeakReference<>(null);
    private UUID lastPlayer, lastTeam;
    private String session;

    public ReflectiveFtbQuestsAccess() { this(ReflectiveFtbQuestsAccess::current); }
    ReflectiveFtbQuestsAccess(Supplier<Environment> environment) { this.environment = environment; }

    /** 在 FTB 完成原生同步的游戏线程记录接收方；接口失配只让知识不可用，不打断玩家收包。 */
    public static void recordSync() {
        try { FtbQuestSync.received(NativeApi.call(null, CLIENT, "getInstance"), Minecraft.getInstance().getConnection()); }
        catch (RuntimeException | LinkageError unavailable) { FtbQuestSync.received(null, null); }
    }

    private static Environment current() {
        try { Class.forName(CLIENT, false, ReflectiveFtbQuestsAccess.class.getClassLoader()); }
        catch (ClassNotFoundException absent) { throw new NotInstalled(); }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.getConnection() == null) return null;
        Object file = NativeApi.call(null, CLIENT, "getInstance");
        // FTB 退出服务器时未必清空旧文件，必须同时核对原生同步回执中的连接身份。
        return new Environment(FtbQuestSync.matches(file, client.getConnection()) ? file : null, client.player.getUUID(),
                client.level.dimension().location().toString(), client.getConnection());
    }

    @Override public Snapshot snapshot() {
        JsonObject context = new JsonObject();
        context.addProperty("observed_at", Instant.now().toString());
        context.addProperty("source", "ftbquests_client_sync");
        try {
            Environment env = environment.get();
            if (env == null) return unavailable(context, "no_world", "尚未进入游戏世界");
            Object file = env.file();
            if (file == null || !flag(file, "isValid")) return unavailable(context, "sync_pending", "等待服务器同步任务书");
            Object team = NativeApi.field(file, null, "selfTeamData");
            if (team == null) return unavailable(context, "sync_pending", "等待当前玩家的队伍进度");
            UUID teamId = (UUID) call(team, "getTeamId");
            if (teamId.equals(new UUID(0, 0)) || call(team, "getFile") != file)
                return unavailable(context, "sync_pending", "队伍进度仍是占位数据或属于旧任务书");
            // 切服、任务书重载、切玩家或换队伍后更换会话号；任何旧页码都不能接到新的任务书。
            if (file != lastFile.get() || env.connection() != lastConnection.get() || !env.player().equals(lastPlayer) || !teamId.equals(lastTeam)) {
                session = UUID.randomUUID().toString(); lastFile = new WeakReference<>(file); lastConnection = new WeakReference<>(env.connection());
                lastPlayer = env.player(); lastTeam = teamId;
            }
            context.addProperty("session_id", session); context.addProperty("player_id", env.player().toString());
            context.addProperty("team_id", teamId.toString()); context.addProperty("team_name", call(team, "getName").toString());
            context.addProperty("dimension", env.dimension()); context.addProperty("locale", call(file, "getLocale").toString());
            if (flag(file, "isDisableGui")) return unavailable(context, "book_disabled", "服务器禁用了任务书界面");
            if (flag(team, "isLocked")) return unavailable(context, "book_locked", "当前队伍的任务书被锁定");
            List<Chapter> chapters = catalog(file, team, env.player());
            context.addProperty("status", "available");
            context.addProperty("detail", "当前玩家可见的章节与任务；进度来自读取时已同步到客户端的数据");
            return new Snapshot(context, chapters);
        } catch (NotInstalled absent) {
            return unavailable(context, "not_installed", "未安装 FTB Quests");
        } catch (RuntimeException | LinkageError unavailable) {
            return unavailable(context, "api_unavailable", "无法读取已安装版本的任务书接口；未返回猜测的任务或进度");
        }
    }

    private Snapshot unavailable(JsonObject context, String status, String detail) {
        // 暂时断线也撤销旧身份；重连后重新发现，不能沿用断线前的目录和进度。
        lastFile.clear(); lastConnection.clear(); context.addProperty("status", status); context.addProperty("detail", detail);
        return new Snapshot(context, List.of());
    }

    private static List<Chapter> catalog(Object file, Object team, UUID player) {
        List<Object> nativeChapters = new ArrayList<>();
        call(file, "forAllChapters", (Consumer<Object>) nativeChapters::add);
        Map<Object, List<Object>> visible = new LinkedHashMap<>(); Set<String> ids = new LinkedHashSet<>();
        for (Object chapter : nativeChapters) {
            if (!flag(chapter, "isVisible", team)) continue;
            Map<String, Object> quests = new LinkedHashMap<>();
            for (Object quest : (Iterable<?>) call(chapter, "getQuests"))
                if (flag(quest, "isVisible", team)) quests.put(id(quest), quest);
            // 章节也可能通过任务链接展示别处的任务；保留玩家实际能点到的目标并去重。
            for (Object link : (Iterable<?>) call(chapter, "getQuestLinks")) {
                if (!flag(link, "isVisible", team)) continue;
                Optional<?> target = (Optional<?>) call(link, "getQuest");
                target.ifPresent(quest -> { if (flag(quest, "isVisible", team)) quests.putIfAbsent(id(quest), quest); });
            }
            visible.put(chapter, List.copyOf(quests.values())); ids.add(id(chapter)); ids.addAll(quests.keySet());
        }
        List<Chapter> result = new ArrayList<>();
        visible.forEach((chapter, quests) -> {
            List<Quest> entries = new ArrayList<>();
            for (Object quest : quests) {
                JsonObject summary = FtbQuestDetails.summary(quest, team, player);
                entries.add(new Quest(id(quest), summary.get("title").getAsString(), summary,
                        () -> FtbQuestDetails.read(quest, team, player, ids),
                        (path, offset) -> summary.get("details_visible").getAsBoolean()
                                ? FtbQuestRewards.read(quest, team, player, path, offset) : null));
            }
            result.add(new Chapter(id(chapter), text(call(chapter, "getTitle")), entries,
                    () -> lines(call(chapter, "getRawSubtitle"))));
        });
        return result;
    }
}
