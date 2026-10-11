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
 * <p>场景的列表永远完整；observe 交出去时，敌对的、盯着角色的、有名字的、玩家都逐只列，
 * 其他的只列三十二格以内，远处的按种类报只数（所有者 10-11 定：一百多格外的蝙蝠、鱿鱼全列出来，
 * 一次观察近三万字）。没列的也不是悄悄删掉：只数照报，要找用 find。
 */
public final class Scene {

    /** 不敌对、没名字的实体在这么远以内才逐只列出来，再远只按种类计数。 */
    public static final int LISTED_RADIUS = 32;
    private static final String PLAYER = "minecraft:player";

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
                facts.onGround(), facts.inWater(), facts.gearBars());
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
                    sighting.visible(), sighting.hostile(), sighting.targetingMe(),
                    sighting.health(), sighting.traits()));
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
     * observe 列出来的实体：敌对的、盯着角色的、有名字的、玩家，不论远近都列；其他的只列三十二格以内。
     * 远处不相干的蝙蝠、鱿鱼、掉落物只按种类计数（见 {@link #distantCounts}），要找哪一种用 find。
     * 场景自己的实体列表仍然完整，战斗、跟随、找东西照常从里面认编号。
     */
    public List<SceneEntity> listedEntities() {
        return entities.stream().filter(Scene::listed).toList();
    }

    /** 没列出来的远处实体：种类 → 只数，多的在前。 */
    public Map<String, Integer> distantCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SceneEntity entity : entities) {
            if (!listed(entity)) counts.merge(entity.type(), 1, Integer::sum);
        }
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    // 要逐只列出来的：会威胁角色的、有主的、别的玩家，或者就在近处的。
    private static boolean listed(SceneEntity entity) {
        return entity.hostile() || entity.targetingMe() || entity.name() != null
                || PLAYER.equals(entity.type()) || entity.distance() <= LISTED_RADIUS;
    }

    /**
     * 一句话总览：附近列出几个实体（几只敌对、几只盯着角色）、几处设施、几处地形、听见几种声音，远处只计数的有几只。
     * 每一条的方位、距离、编号都在结构化列表里，这里不再逐条复述——同样的内容写两遍只白占上下文。
     */
    public String summary() {
        if (self == null) {
            return "还没看清自己的位置。";
        }
        List<SceneEntity> listed = listedEntities();
        long hostile = listed.stream().filter(SceneEntity::hostile).count();
        long targeting = listed.stream().filter(SceneEntity::targetingMe).count();
        List<String> parts = new ArrayList<>();
        if (!listed.isEmpty()) {
            String danger = hostile == 0 ? "" : "（敌对 " + hostile + " 只" + (targeting == 0 ? "" : "，盯着你的 " + targeting + " 只") + "）";
            parts.add("附近列出 " + listed.size() + " 个实体" + danger);
        }
        if (!facilities.isEmpty()) parts.add("设施 " + facilities.size() + " 处");
        if (!features.isEmpty()) parts.add("地形 " + features.size() + " 处");
        if (!heard.isEmpty()) parts.add("听见 " + heard.size() + " 种声音");
        int distant = distantCounts().values().stream().mapToInt(Integer::intValue).sum();
        if (distant > 0) {
            parts.add(LISTED_RADIUS + " 格外另有 " + distant + " 个不相干的只按种类计数（distant_entities），要找哪一种用 find");
        }
        if (parts.isEmpty()) {
            return "四周没什么可看的。";
        }
        return String.join("；", parts) + "。方位、距离、编号见各列表。";
    }
}
