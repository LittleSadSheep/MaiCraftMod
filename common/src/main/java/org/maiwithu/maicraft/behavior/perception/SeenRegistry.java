// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 观察编号登记：给本会话里看到的东西发 e#（实体）、f#（地形特征）、b#（设施）编号，
 * 同一样东西在还在场时编号保持不变。
 *
 * <p>编号短期有效：实体最后一次看见后只保留一小会儿，地形特征与设施多留一阵；
 * 超期就算它不在了，由 {@link #expire} 报出最后一次在哪个方位看到、最后位置在哪，
 * 让能力能以"目标不在了"结束并如实交代。之后再见到同一样东西会拿到新编号——
 * 编号是对这次观察会话说的，隔久了还沿用旧编号会让人以为中间一直在看着它。
 */
public final class SeenRegistry {

    /** 编号对应的观察种类。 */
    public enum Kind {
        /** 实体：生物、掉落物、矿车这类会动的东西。 */
        ENTITY,
        /** 地形特征：悬崖、水体、树林这类成片地物。 */
        FEATURE,
        /** 设施：箱子、工作台、熔炉、床这类功能方块。 */
        FACILITY,
    }

    /** 一个刚失效的编号：最后看到的方位与位置，用来交代"最后一次在哪儿看见它"。 */
    public record Gone(String id, Kind kind, String lastDirection, WorldPosition lastPosition) {}

    // 实体是会动的，最后一次看见后只信一小会儿；地形特征与设施不动，多留一阵。
    private static final int ENTITY_RETENTION_TICKS = 200;
    private static final int PLACE_RETENTION_TICKS = 2400;

    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<Integer, String> entityIds = new HashMap<>();
    private final Map<Long, String> placeIds = new HashMap<>();
    private int nextEntity = 1;
    private int nextFeature = 1;
    private int nextFacility = 1;

    /**
     * 登记一只实体，返回它的观察编号：同一只实体（按游戏实体编号）还在册就沿用旧编号
     * 并刷新方位与时刻；不在册或已失效就发新编号。
     */
    public String entity(int entityId, WorldPosition position, String direction, long nowTick) {
        String existing = entityIds.get(entityId);
        if (existing != null && entries.containsKey(existing)) {
            refresh(existing, position, direction, nowTick);
            return existing;
        }
        String id = "e" + nextEntity++;
        entityIds.put(entityId, id);
        put(new Entry(id, Kind.ENTITY, position, direction, nowTick, ENTITY_RETENTION_TICKS));
        return id;
    }

    /** 登记一个地形特征，返回观察编号 f#；同一个位置的特征还在册就沿用旧编号。 */
    public String feature(WorldPosition position, String direction, long nowTick) {
        return place(position, direction, nowTick, Kind.FEATURE, () -> "f" + nextFeature++);
    }

    /** 登记一个设施，返回观察编号 b#；同一个位置的设施还在册就沿用旧编号。 */
    public String facility(WorldPosition position, String direction, long nowTick) {
        return place(position, direction, nowTick, Kind.FACILITY, () -> "b" + nextFacility++);
    }

    /** 查一个编号还认不认得：认得就给它的登记项，不认得（从未见过或已失效）给空。 */
    public Optional<Entry> get(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    /** 观察编号对应的游戏实体编号；编号不是在册实体时给空。跟随这类能力用它找回真实的实体。 */
    public Optional<Integer> gameEntityOf(String id) {
        return entityIds.entrySet().stream()
                .filter(entry -> entry.getValue().equals(id))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * 把超期没再见到的编号移出登记，并逐条报告它们最后出现在哪个方位、什么位置。
     * 每次刷新场景后调用一次；返回的失效记录交给关心它们的能力。
     */
    public List<Gone> expire(long nowTick) {
        List<Gone> gone = new ArrayList<>();
        for (Entry entry : new ArrayList<>(entries.values())) {
            if (nowTick - entry.lastSeenTick() > entry.retentionTicks()) {
                entries.remove(entry.id());
                if (entry.kind() == Kind.ENTITY) {
                    entityIds.values().removeIf(id -> id.equals(entry.id()));
                } else {
                    placeIds.values().removeIf(id -> id.equals(entry.id()));
                }
                gone.add(new Gone(entry.id(), entry.kind(), entry.direction(), entry.position()));
            }
        }
        return gone;
    }

    private String place(WorldPosition position, String direction, long nowTick, Kind kind,
            Supplier<String> newId) {
        // 同一个位置的同种东西是同一样：沿用编号，方位按这次看到的刷新。
        String existing = placeIds.get(placeKey(position));
        if (existing != null && entries.containsKey(existing) && entries.get(existing).kind() == kind) {
            refresh(existing, position, direction, nowTick);
            return existing;
        }
        String id = newId.get();
        placeIds.put(placeKey(position), id);
        put(new Entry(id, kind, position, direction, nowTick, PLACE_RETENTION_TICKS));
        return id;
    }

    private void put(Entry entry) {
        entries.put(entry.id(), entry);
    }

    private void refresh(String id, WorldPosition position, String direction, long nowTick) {
        Entry old = entries.get(id);
        entries.put(id, new Entry(id, old.kind(), position, direction, nowTick, old.retentionTicks()));
    }

    private static long placeKey(WorldPosition position) {
        // 编号只在本会话内有效，跨维度的同坐标不会同时在场，键里不需要维度。
        return position.x() & 0x3FFFFFFL | (position.z() & 0x3FFFFFFL) << 26 | (position.y() & 0xFFL) << 52;
    }

    /** 登记项：编号、种类、最后位置、最后方位、最后看见的时刻与保留时长。 */
    public record Entry(String id, Kind kind, WorldPosition position, String direction,
            long lastSeenTick, int retentionTicks) {}
}
