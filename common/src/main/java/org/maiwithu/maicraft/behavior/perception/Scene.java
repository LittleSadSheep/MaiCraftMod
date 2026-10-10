// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 场景：角色此刻的观察事实，一份、按需增量更新，所有观察视图都从它整理出来。
 *
 * <p>各路读取接缝（角色自己、实体、声音、俯视网格、设施、环境）各自送来原始观察，
 * 这里统一整理成方位说法并发观察编号；实体与地形特征、设施在保留期内没再见到的
 * 编号由 {@link #expire} 报失效，最后一次方位一并交代。亲眼看到的设施与产地顺路
 * 写进世界记忆，来源标"亲眼看到"；没见过的东西不写。
 *
 * <p>列表永远完整：数量真的很大时，速写里按远近分档给出总数，视图本身不截断——
 * 中间层悄悄删掉的每一条都可能是决策事实。
 */
public final class Scene {

    /** 实体多到这个数以上，速写就按远近分档报总数；结构化列表仍然全量。 */
    public static final int GROUP_THRESHOLD = 40;

    private final SeenRegistry ids = new SeenRegistry();
    private final RemembersSightings memory;
    private final FacilityKinds kinds;

    private SceneSelf self;
    private SceneEnvironment environment;
    private OverheadGrid.View grid;
    private List<SceneEntity> entities = List.of();
    private List<HeardSound> heard = List.of();
    private List<TerrainFeature> features = List.of();
    private List<FacilitySighting> facilities = List.of();

    /**
     * @param kinds 设施分类：看见的功能方块是容器还是工作设施，按它写进世界记忆
     */
    public Scene(RemembersSightings memory, FacilityKinds kinds) {
        this.memory = memory;
        this.kinds = kinds;
    }

    /** 更新角色自己的观察：方位说法的基准，先有它，别的观察才整理得出方位。 */
    public void updateSelf(SelfSight.Facts facts) {
        String facingWord = DirectionWords.compassOf(
                -Math.sin(Math.toRadians(facts.facingYaw())), Math.cos(Math.toRadians(facts.facingYaw())));
        self = new SceneSelf(facts.x(), facts.y(), facts.z(), facts.facingYaw(), facingWord,
                facts.health(), facts.food(), facts.air(), facts.maxAir(),
                facts.heldItem(), facts.armor(), facts.effects(),
                facts.onGround(), facts.inWater());
    }

    /** 更新环境观察：时间、天气、光照、生物群系。 */
    public void updateEnvironment(SceneEnvironment current) {
        environment = current;
    }

    /**
     * 更新实体观察：每个实体整理出方位、距离、高低差并发观察编号；
     * 同一只实体还在保留期内就沿用旧编号。
     */
    public void updateEntities(long nowTick, List<EntitySight.Observation> sightings) {
        if (self == null) {
            // 没有角色基准就算不出方位：这一刻先不整理实体，等自己的观察到了再说。
            return;
        }
        List<SceneEntity> next = new ArrayList<>();
        for (EntitySight.Observation sighting : sightings) {
            int dx = sighting.position().x() - (int) Math.floor(self.x());
            int dy = sighting.position().y() - (int) Math.floor(self.y());
            int dz = sighting.position().z() - (int) Math.floor(self.z());
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            String direction = DirectionWords.relative(dx, dz, self.facingYaw());
            String id = ids.entity(sighting.entityId(),
                    WorldPosition.here(sighting.position().x(), sighting.position().y(), sighting.position().z()),
                    direction, nowTick);
            next.add(new SceneEntity(id, sighting.type(), sighting.name(),
                    direction, DirectionWords.compassOf(dx, dz), (int) Math.round(distance), dy,
                    sighting.visible(), sighting.hostile(), sighting.targetingMe(), sighting.traits()));
        }
        entities = List.copyOf(next);
    }

    /** 更新听见的声音：字幕事件只有种类和大致来源，整理成方位加远近，不给精确位置。 */
    public void updateSounds(long nowTick, List<SubtitleEar.Event> events) {
        if (self == null) {
            return;
        }
        List<HeardSound> next = new ArrayList<>();
        for (SubtitleEar.Event event : events) {
            int dx = event.approximatePosition().x() - (int) Math.floor(self.x());
            int dz = event.approximatePosition().z() - (int) Math.floor(self.z());
            double distance = Math.sqrt(dx * dx + dz * dz);
            next.add(new HeardSound(event.kind(),
                    DirectionWords.relative(dx, dz, self.facingYaw()),
                    DirectionWords.distanceWord(distance)));
        }
        heard = List.copyOf(next);
    }

    /**
     * 更新俯视网格，并从网格里聚出地形特征：成片的水、树、落差各领一个观察编号；
     * 成片的树林顺路记一条产地线索——路过看见一片树，"这里大概有木头"就成立。
     */
    public void updateGrid(OverheadGrid.View view, long nowTick, Instant when) {
        grid = view;
        if (self == null) {
            return;
        }
        List<TerrainFeature> next = new ArrayList<>();
        for (FeatureSpotter.Marked marked : FeatureSpotter.spot(view)) {
            int dx = marked.center().x() - (int) Math.floor(self.x());
            int dz = marked.center().z() - (int) Math.floor(self.z());
            String direction = DirectionWords.relative(dx, dz, self.facingYaw());
            String id = ids.feature(marked.center(), direction, nowTick);
            next.add(new TerrainFeature(id, marked.kind(), marked.center(),
                    direction, DirectionWords.compassOf(dx, dz),
                    (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz)),
                    "约" + marked.cells() + "格"));
            if ("树林".equals(marked.kind())) {
                memory.siteSeen(marked.center(), List.of("木头"), when);
            }
        }
        features = List.copyOf(next);
    }

    /**
     * 更新设施观察：看得见的容器、工作设施、床各领一个观察编号；
     * 容器与工作设施亲眼看到就写进世界记忆——看见只记"这里有只"，不猜里面有什么。
     */
    public void updateFacilities(long nowTick, Instant when, List<NearbyBlocksSight.BlockSighting> sightings) {
        if (self == null) {
            return;
        }
        List<FacilitySighting> next = new ArrayList<>();
        for (NearbyBlocksSight.BlockSighting sighting : sightings) {
            int dx = sighting.position().x() - (int) Math.floor(self.x());
            int dy = sighting.position().y() - (int) Math.floor(self.y());
            int dz = sighting.position().z() - (int) Math.floor(self.z());
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            String direction = DirectionWords.relative(dx, dz, self.facingYaw());
            String id = ids.facility(sighting.position(), direction, nowTick);
            next.add(new FacilitySighting(id, sighting.blockType(), sighting.position(),
                    direction, DirectionWords.compassOf(dx, dz), (int) Math.round(distance)));
            if (kinds.isContainer(sighting.blockType())) {
                memory.containerSeen(sighting.position(), sighting.blockType(), when);
            } else if (kinds.isWorkstation(sighting.blockType())) {
                memory.workstationSeen(sighting.position(), sighting.blockType(), when);
            }
        }
        facilities = List.copyOf(next);
    }

    /**
     * 把保留期内没再见到的编号移出场景，逐条报告最后一次在哪个方位看到、最后位置在哪：
     * 能力拿着编号找不到东西时，以"目标不在了"结束并如实交代。
     * 失效的条目同时从各视图里撤下——编号已经不认得，视图里还留着会冒充在场。
     */
    public List<SeenRegistry.Gone> expire(long nowTick) {
        List<SeenRegistry.Gone> gone = ids.expire(nowTick);
        if (gone.isEmpty()) {
            return gone;
        }
        List<String> dead = gone.stream().map(SeenRegistry.Gone::id).toList();
        entities = entities.stream().filter(entry -> !dead.contains(entry.id())).toList();
        features = features.stream().filter(entry -> !dead.contains(entry.id())).toList();
        facilities = facilities.stream().filter(entry -> !dead.contains(entry.id())).toList();
        return gone;
    }

    /** 查一个观察编号还认不认得。 */
    public Optional<SeenRegistry.Entry> lookup(String id) {
        return ids.get(id);
    }

    /** 观察编号登记表：战斗与跟随拿它把编号换回游戏实体。 */
    public SeenRegistry seen() {
        return ids;
    }

    public SceneSelf self() {
        return self;
    }

    public SceneEnvironment environment() {
        return environment;
    }

    public OverheadGrid.View grid() {
        return grid;
    }

    /** 实体视图：完整列表，不截断。 */
    public List<SceneEntity> entities() {
        return entities;
    }

    /** 听见的声音视图。 */
    public List<HeardSound> heard() {
        return heard;
    }

    /** 地形特征视图。 */
    public List<TerrainFeature> features() {
        return features;
    }

    /** 设施视图。 */
    public List<FacilitySighting> facilities() {
        return facilities;
    }

    /**
     * 方位速写：按固定方位顺序，每个方位一行，写清这一方位上有什么。
     * 和结构化视图出自同一份数据，可以直接复述给人听；实体太多时按远近分档报总数。
     */
    public String summary() {
        if (self == null) {
            return "还没看清自己的位置。";
        }
        Map<String, List<String>> byDirection = new LinkedHashMap<>();
        for (String word : DirectionWords.relativeOrder()) {
            byDirection.put(word, new ArrayList<>());
        }
        for (SceneEntity entity : entities) {
            byDirection.get(entity.direction())
                    .add("%s（%s，%d格）".formatted(entity.id(), entity.type(), entity.distance()));
        }
        for (TerrainFeature feature : features) {
            byDirection.get(feature.direction())
                    .add("%s（%s，%d格）".formatted(feature.id(), feature.kind(), feature.distance()));
        }
        for (FacilitySighting facility : facilities) {
            byDirection.get(facility.direction())
                    .add("%s（%s，%d格）".formatted(facility.id(), facility.blockType(), facility.distance()));
        }
        for (HeardSound sound : heard) {
            List<String> line = byDirection.get(sound.direction());
            if (line != null) {
                line.add("听见%s（%s）".formatted(sound.kind(), sound.nearness()));
            }
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<String>> line : byDirection.entrySet()) {
            if (line.getValue().isEmpty()) {
                continue;
            }
            text.append(line.getKey()).append("：").append(String.join("；", line.getValue())).append("。\n");
        }
        if (entities.size() > GROUP_THRESHOLD) {
            text.append("实体太多，按远近计：").append(countByDistance()).append("；上面的列表仍按条目全量给出。\n");
        }
        if (text.isEmpty()) {
            return "四周没什么可看的。";
        }
        return text.toString();
    }

    // 按远近档位数一遍实体，给出"很近几只、近处几只"这样的总数；只数一遍，不重复列条目。
    private String countByDistance() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SceneEntity entity : entities) {
            String band = DirectionWords.distanceWord(entity.distance());
            counts.merge(band, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            parts.add(count.getKey() + count.getValue() + "只");
        }
        return "共" + entities.size() + "只：" + String.join("，", parts);
    }
}
