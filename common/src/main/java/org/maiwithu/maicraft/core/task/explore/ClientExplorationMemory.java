package org.maiwithu.maicraft.core.task.explore;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.maiwithu.maicraft.core.scan.ObservationVisibility;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.persistence.ExplorationMemoryStore;
import org.maiwithu.maicraft.intent.persistence.StateIdentity;

/** 每次探索绑定启动世界；失败待写记录也按世界隔离，并可在探索查询中看到保存状态。 */
public final class ClientExplorationMemory {
    private static final Gson GSON = new Gson();
    private static final Map<ExplorationJournal, StateIdentity> JOURNALS = new ConcurrentHashMap<>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(command -> {
        Thread worker = new Thread(command, "maicraft-exploration-memory"); worker.setDaemon(true); return worker;
    });

    static {
        // 游戏退出前补写最后到访的区域；正常运行时始终由后台写入，避免跑图卡顿。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            JOURNALS.keySet().forEach(ExplorationJournal::close);
            // 先让正在写盘的批次提交并排入最后一批，再关闭执行器；否则中途到访升级会被拒绝排队。
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline && JOURNALS.keySet().stream()
                        .anyMatch(journal -> journal.pendingCount() > 0 && journal.failure() == null)) Thread.sleep(10);
                WRITER.shutdown(); WRITER.awaitTermination(1, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "maicraft-exploration-flush"));
    }

    private final LocalPlayer player;
    private final ExplorationJournal journal;
    private final String dimension;
    private long nextFlush;

    public ClientExplorationMemory(LocalPlayer player) {
        this.player = player;
        dimension = player.level().dimension().location().toString();
        StateIdentity identity = identity();
        journal = new ExplorationJournal(new ExplorationMemoryStore(identity)::save, WRITER, JOURNALS::remove);
        JOURNALS.put(journal, identity);
    }

    /** 身体所在群系无需远望推断；途中每四十刻刷新一次待写批次。 */
    public void tick() {
        observeBiome(player.blockPosition(), true);
        long now = player.level().getGameTime();
        if (now >= nextFlush) { nextFlush = now + 40; journal.flush(); }
    }

    public void observeBiome(BlockPos at, boolean visited) {
        if (at == null || !dimension.equals(player.level().dimension().location().toString()) || !player.level().isLoaded(at)) return;
        var holder = player.level().getBiome(at);
        String id = holder.unwrapKey().map(key -> key.location().toString()).orElse(null);
        if (id == null) return;
        String name = Component.translatable("biome." + id.replace(':', '.').replace('/', '.')).getString();
        var finding = ExplorationFinding.observed("biome", id, name, dimension, at.getX(), at.getY(), at.getZ(), visited,
                System.currentTimeMillis(), "{}");
        if (!journal.needs(finding.id(), visited) || !visited && !ObservationVisibility.block(player, at)) return;
        JsonObject evidence = new JsonObject();
        evidence.addProperty("authority", visited ? "body_inside_biome" : "visible_loaded_biome_sample");
        evidence.addProperty("temperature", holder.value().getBaseTemperature());
        evidence.add("tags", GSON.toJsonTree(holder.tags().map(tag -> tag.location().toString()).sorted().toList()));
        journal.observe(new ExplorationFinding(finding.id(), finding.kind(), id, name, dimension,
                at.getX(), at.getY(), at.getZ(), visited, finding.firstSeen(), finding.lastSeen(), evidence.toString()));
    }

    /** 结构只记可见证据组合；到达后再单独升级，仍不声称它必然由世界生成器生成。 */
    public void observeStructure(String id, BlockPos at, boolean reached, Map<String, Object> evidence) {
        journal.observe(ExplorationFinding.observed("structure", id, id, dimension,
                at.getX(), at.getY(), at.getZ(), reached, System.currentTimeMillis(), GSON.toJson(evidence)));
    }

    public Map<String, Object> receipt() { journal.flush(); return journal.receipt(); }
    public void close() { journal.close(); }
    public static StateIdentity identity() {
        return StateIdentity.resolve(Minecraft.getInstance()).orElseThrow(() -> new IllegalStateException("exploration memory needs an active world identity"));
    }

    public static List<ExplorationJournal> pending(StateIdentity identity) {
        return JOURNALS.entrySet().stream().filter(entry -> entry.getValue().key().equals(identity.key())
                        && entry.getValue().databaseFile().equals(identity.databaseFile()))
                .map(Map.Entry::getKey).toList();
    }

    /** 查询返回的稳定地点标签可直接用于普通旅行；换世界后不会解析到另一存档的历史坐标。 */
    public static Goal.WorldPosition resolveLabel(String label) {
        if (label == null || !label.startsWith("exploration:")) return null;
        try {
            var finding = new ExplorationMemoryStore(identity()).find(label.substring("exploration:".length()));
            return finding == null ? null : new Goal.WorldPosition(finding.x(), finding.y(), finding.z(), finding.dimension());
        } catch (IOException failure) { throw new IllegalStateException("exploration memory lookup failed", failure); }
    }
}
