package org.maiwithu.maicraft.core.task.explore;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 地点记忆按群系/结构与空间区域合并，保存观察时间和到访事实，不能冒充世界现状。 */
public record ExplorationFinding(String id, String kind, String targetId, String name, String dimension,
        int x, int y, int z, boolean visited, long firstSeen, long lastSeen, String evidenceJson) {
    public static ExplorationFinding observed(String kind, String targetId, String name, String dimension,
            int x, int y, int z, boolean visited, long time, String evidenceJson) {
        String key = kind + "\n" + targetId + "\n" + dimension + "\n" + Math.floorDiv(x, 64)
                + ":" + Math.floorDiv(y, 32) + ":" + Math.floorDiv(z, 64);
        String id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        return new ExplorationFinding(id, kind, targetId, name, dimension, x, y, z, visited, time, time, evidenceJson);
    }

    /** 再次看到同一区域时更新现场证据；若已经到访，保留真实到访落点供以后旅行使用。 */
    public ExplorationFinding merge(ExplorationFinding other) {
        if (!id.equals(other.id)) throw new IllegalArgumentException("different exploration regions");
        ExplorationFinding latest = lastSeen > other.lastSeen ? this : other;
        ExplorationFinding footing = visited && !other.visited ? this : other.visited && !visited ? other : latest;
        return new ExplorationFinding(id, kind, targetId, latest.name, dimension, footing.x, footing.y, footing.z,
                visited || other.visited, Math.min(firstSeen, other.firstSeen), Math.max(lastSeen, other.lastSeen), latest.evidenceJson);
    }
}
