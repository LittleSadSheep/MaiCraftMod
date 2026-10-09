// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.worldmemory;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.maiwithu.maicraft.behavior.perception.RemembersSightings;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.kernel.goal.RemembersPlaces;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 世界记忆：角色记得自己亲眼看到过、亲手用过的东西——箱子在哪、里面大概有什么、
 * 在哪用过工作台、哪里大概有什么。给得出"哪里大概有什么"，给不出"现在一定有什么"。
 *
 * <p>使用纪律写在查询与写入的分工里：这里存的是记忆不是事实，记忆可能过时，
 * 拿到查询结果的使用方必须到现场核对再动手；箱子里有什么，开了才算数，
 * 所以容器内容只在调用方真的打开看全了之后经写入口记下来，界面没同步全时不许把初始空槽当缺货记。
 *
 * <p>记忆按世界身份分开存：构造时传入文档库与世界身份编号，连到另一个世界时
 * 由身份编号隔离，读不到上一个世界的记忆。
 */
public final class WorldMemory implements RemembersPlaces, RemembersRegions, RemembersSightings,
        ReadsRememberedPlaces {

    /** 文档库里的范围名：世界记忆的数据都存这个范围下，与别的用途互不覆盖。 */
    public static final String SCOPE = "world-memory";

    private static final String DOCUMENT_KEY = "memory";
    // 整册记忆的存盘预算：一个角色长期记得的箱子、设施、产地几千条也用不到这么多；超出时整册不写，不做截断。
    private static final int DOCUMENT_LIMIT = 8 * 1024 * 1024;
    /** 改过的记忆最多隔多久存一次盘：感知每隔几刻就会写一笔，逐笔落盘会让游戏卡顿。 */
    private static final long FLUSH_INTERVAL_NANOS = 5_000_000_000L;
    private static final Logger LOG = LoggerFactory.getLogger(WorldMemory.class);

    private final DocumentStore documents;
    private final String worldKey;
    private final MemoryCodec codec = new MemoryCodec();
    /** 读进来的整册记忆；第一次用到时从文档库读，之后读写都在这里，改过就隔一阵存一次盘。 */
    private MemoryBook book;
    private boolean dirty;
    private long lastFlushNanos = System.nanoTime();

    public WorldMemory(DocumentStore documents, String worldKey) {
        this.documents = documents;
        this.worldKey = worldKey;
    }

    /**
     * 把改过的记忆存盘：离开世界、客户端退出前必须调用一次，平时修改后也会隔一阵自动存。
     * 存盘失败只记日志、保留在内存里下次再试，不让一次写盘失败打断游戏刻。
     */
    public void flush() {
        if (!dirty) return;
        try {
            documents.write(SCOPE, worldKey, DOCUMENT_KEY, codec.encode(book), false);
            dirty = false;
        } catch (IOException failure) {
            LOG.warn("世界记忆存盘失败，留在内存里下次再试", failure);
        }
        lastFlushNanos = System.nanoTime();
    }

    /**
     * 记下一个亲眼看到的容器：看见这里有只箱子，但没打开过。
     * 内容保持"没确认过"，不凭外观猜里面有什么。
     */
    public void rememberContainerSeen(WorldPosition position, String blockType, Instant when) {
        upsert(new MemoryRecord(MemoryKind.CONTAINER, position, blockType, null, MemoryOrigin.SEEN, when));
    }

    /**
     * 记一个亲眼看到的工作站：看见它在这里，来源只算亲眼看到，用过一次才升"亲手用过"。
     */
    @Override
    public void workstationSeen(WorldPosition position, String blockType, Instant when) {
        upsert(new MemoryRecord(MemoryKind.WORKSTATION, position, blockType, null, MemoryOrigin.SEEN, when));
    }

    /** 感知写来的亲眼看到的容器：看见这里有只箱子，但没打开过，规矩与直接写入一致。 */
    @Override
    public void containerSeen(WorldPosition position, String blockType, Instant when) {
        rememberContainerSeen(position, blockType, when);
    }

    /** 感知写来的产地线索：路过看见的这一片有什么，规矩与 rememberSite 一致。 */
    @Override
    public void siteSeen(WorldPosition position, List<String> roughlyThere, Instant when) {
        rememberSite(position, roughlyThere, when);
    }

    /**
     * 记下一个开过的容器：真的打开过、界面内容完整看到了，才知道里面有什么。
     * 内容确认是空的就记空列表——空箱子和没开过的箱子是两回事。
     * 调用方负责只在内容完整同步时调用；看了一半就记，记下的缺货会误导后面的取物。
     */
    public void rememberContainerOpened(WorldPosition position, String blockType,
            List<String> contents, Instant when) {
        upsert(new MemoryRecord(MemoryKind.CONTAINER, position, blockType, contents, MemoryOrigin.USED, when));
    }

    /** 记下一个亲手用过的工作站：工作台、熔炉这类，用过一次就知道它在这里、能用。 */
    public void rememberWorkstationUsed(WorldPosition position, String blockType, Instant when) {
        upsert(new MemoryRecord(MemoryKind.WORKSTATION, position, blockType, null, MemoryOrigin.USED, when));
    }

    /** 记一条产地线索：在这附近见过什么，例如路过时看到这一片有煤矿。只有大概位置，到现场要再找。 */
    public void rememberSite(WorldPosition position, List<String> roughlyThere, Instant when) {
        upsert(new MemoryRecord(MemoryKind.SITE, position, null, roughlyThere, MemoryOrigin.SEEN, when));
    }

    /** 忘掉一条记忆：到现场发现东西没了、搬走了，留着旧记录只会再骗一次。 */
    public void forget(MemoryKind kind, WorldPosition position) {
        modifyAndSave(book -> book.without(kind, position));
    }

    /** 记住一个按名字叫的地点（家、床这类）；同名地点用新位置覆盖。 */
    @Override
    public void remember(String name, WorldPosition position) {
        modifyAndSave(book -> book.withPlace(name, position));
    }

    /**
     * 记住一块"这是玩家的地盘"：亲眼看着别人圈出来、盖起来的一片，保护判断把里面的东西都当受保护。
     * 半径必须为正；同名区域用新范围覆盖。记区域的入口在世界记忆上，读的接缝是 RemembersRegions。
     */
    public void rememberRegion(String name, WorldPosition center, int radiusBlocks) {
        modifyAndSave(book -> book.withRegion(new RememberedRegion(name, center, radiusBlocks)));
    }

    /** 忘掉一块区域：地界变了或记错了；名字没记过就不动。 */
    public void forgetRegion(String name) {
        modifyAndSave(book -> book.withoutRegion(name));
    }

    /** 忘掉一个按名字记的地点；名字没记过就不动，返回有没有真的忘掉。 */
    public boolean forgetPlace(String name) {
        // 先查再删：名字没记过时不进文档库的事务，也如实回答没忘掉什么。
        if (readBook().places().containsKey(name)) {
            modifyAndSave(book -> book.withoutPlace(name));
            return true;
        }
        return false;
    }

    /** 全部记住的区域，按名字排好；保护判断逐块核对位置在不在里面。 */
    @Override
    public List<RememberedRegion> regions() {
        return readBook().regions().values().stream()
                .sorted(Comparator.comparing(RememberedRegion::name))
                .toList();
    }

    /** 查某个位置某类东西的记忆；没有就给空。 */
    public Optional<MemoryRecord> recordAt(MemoryKind kind, WorldPosition position) {
        return readBook().records().stream()
                .filter(record -> record.kind() == kind && record.position().equals(position))
                .findFirst();
    }

    /**
     * 查"哪里大概有什么"：以一个位置为圆心，同一个维度、给定方块距离内的全部记忆，近的在前。
     *
     * <p>结果按记录时刻从新到旧排，供取物来源问询、找设施、保护判断挑候选；
     * 每条都只是记忆，用之前到现场核对。
     */
    public List<MemoryRecord> recordsNear(WorldPosition center, double radiusBlocks) {
        record Scored(MemoryRecord record, double distance) {}
        return readBook().records().stream()
                .filter(record -> sameDimension(record.position(), center))
                .map(record -> new Scored(record, distance(record.position(), center)))
                .filter(scored -> scored.distance() <= radiusBlocks)
                // 距离近的先去核对；同远近的先核对新记忆，旧记忆更可能已经变了。
                .sorted(Comparator.<Scored>comparingDouble(Scored::distance)
                        .thenComparing(scored -> scored.record().recordedAt(), Comparator.reverseOrder()))
                .map(Scored::record)
                .toList();
    }

    /** 全部记忆，记录时刻新的在前；调用方自己分组过滤时用它，不要悄悄截断。 */
    public List<MemoryRecord> allRecords() {
        return readBook().records().stream()
                .sorted(Comparator.comparing(MemoryRecord::recordedAt).reversed())
                .toList();
    }

    /** 记过的全部地点，名字到位置；observe 的世界记忆视图按它列出，不截断。 */
    public Map<String, WorldPosition> places() {
        return Map.copyOf(readBook().places());
    }

    /** 按名字查记过的地点。 */
    public Optional<WorldPosition> place(String name) {
        return Optional.ofNullable(readBook().places().get(name));
    }

    /** 记下或更新一条记忆；同一个位置同一类东西只留合并后的一条。 */
    private void upsert(MemoryRecord incoming) {
        modify(book -> book.withRecord(incoming));
    }

    // 修改只改内存里的这一册并记下"改过了"；距上次存盘够久才写回文档库，逐笔落盘会拖慢游戏刻。
    private void modify(UnaryOperator<MemoryBook> change) {
        book = change.apply(readBook());
        dirty = true;
        if (System.nanoTime() - lastFlushNanos >= FLUSH_INTERVAL_NANOS) {
            flush();
        }
    }

    // 明确交代的事（记地点、圈地盘、忘掉一条）改了立刻存盘：保护判断靠它们，不能因为没赶上存盘就丢了。
    private void modifyAndSave(UnaryOperator<MemoryBook> change) {
        modify(change);
        flush();
    }

    private MemoryBook readBook() {
        if (book == null) {
            try {
                book = codec.decode(documents.read(SCOPE, worldKey, DOCUMENT_KEY, DOCUMENT_LIMIT));
            } catch (IOException failure) {
                throw new UncheckedIOException("world_memory_read_failed", failure);
            }
        }
        return book;
    }

    private static boolean sameDimension(WorldPosition first, WorldPosition second) {
        return first.dimension() == null
                ? second.dimension() == null
                : first.dimension().equals(second.dimension());
    }

    private static double distance(WorldPosition first, WorldPosition second) {
        double dx = first.x() - second.x();
        double dy = first.y() - second.y();
        double dz = first.z() - second.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
