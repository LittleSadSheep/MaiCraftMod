// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 撤离：往哪跑、甩没甩开、跑不动了没有。点名战斗与自卫用同一份。
 *
 * <p>往哪跑：以所有追兵位置的重心为反方向，左右九十度内依次试八个方向、四十格外，挑已加载、脚下踩得住、
 * 身子两格空的落点交给寻路，寻路同样尽量不进追兵的危险半径。上一条路走不通就从下一个方向试起，不反复撞同一堵墙。
 *
 * <p>甩开：四十格内没有正在追我的才算，只认这次参与了追击的与新近打过我的——远处没参与的怪不让这次逃跑无限延长。
 * 开始了就跑到甩开，回了一点血不回头；身体真走出两格才清失败账，连续三次走不通才承认退无可退、背水一战。
 */
public final class Retreat {

    /** 落点离我多远：跑到就该甩开了。 */
    static final double REACH_OUT = ThreatAssessment.FLEE_CLEAR_DISTANCE;
    /** 从正背离方向起，依次偏这么多度试（左右九十度内八个方向）。 */
    private static final double[] OFFSETS = {0, 22.5, -22.5, 45, -45, 67.5, -67.5, 90};
    /** 一列里上下找落脚处最多看这么多格：再多是坑或悬崖。 */
    private static final int MAX_DROP = 4;
    /** 身体真走出这么远才算甩开了上一个死路，失败账清零。 */
    static final double REAL_PROGRESS = 2.0;

    /** 落点能不能站：生产实现读当刻世界，测试换替身。 */
    @FunctionalInterface
    public interface Probe {
        boolean standable(int x, int y, int z);
    }

    private String why;
    private final Set<Integer> pursuers = new HashSet<>();
    /** 这次的追兵最后一次露面在哪（x, z）、之后又过了几刻：拐过墙角一时看不见的不当它凭空消失。 */
    private final Map<Integer, double[]> lastSeen = new HashMap<>();
    private int failures;
    private int turns;
    private double[] lastFailureAt;

    /** 开始撤离：记下为什么、这次有哪些在追。 */
    void begin(String reason, Battlefield field) {
        why = reason;
        pursuers.clear();
        for (Battlefield.Foe foe : field.chasers()) pursuers.add(foe.id());
        lastSeen.clear();
        failures = 0;
        turns = 0;
        lastFailureAt = null;
    }

    String why() {
        return why;
    }

    /** 新冒出来追我的也算进这次的追兵。 */
    void noteChasers(Battlefield field) {
        for (Battlefield.Foe foe : field.chasers()) {
            if (foe.attacker() || foe.imminent()) pursuers.add(foe.id());
        }
    }

    /** 甩开了：四十格内没有这次参与追击的（追没追着都算）、也没有新近打过我的。 */
    boolean escaped(Battlefield field) {
        remember(field);
        for (Battlefield.Foe foe : field.chasers()) {
            if (pursuers.contains(foe.id()) || foe.attacker()) return false;
        }
        // 这次追我的还在四十格内：这一刻没在"追"（没对着我、隔着坡看不见）也没甩开，它转眼又追上来。
        // 实机 10-14 低血被尸壳追，每跑二三十格就算甩开、收手站住，尸壳追上来又跑，三分钟跑出两百多格，最后被追上。
        for (Battlefield.Foe foe : field.foes()) {
            if (pursuers.contains(foe.id())) return false;
        }
        // 还看得见、会还手的就在 10 格内：这一刻它只是没在"追"（目标一闪一闪），不算甩开。原先撤一步就算甩开，
        // 自卫刚收场下一刻又插进来，一秒里"已撤离，离打点 0 格"好几回，赶路的主任务跟着走走停停。
        for (Battlefield.Foe foe : field.foes()) {
            if (foe.fightsBack() && foe.threat().visible() && foe.distance() < CLEAR_GAP) return false;
        }
        // 追兵一时被地形挡住看不见（夜里、山里一拐弯就没了）：它最后露面的地方还在 10 格内、离露面不到三秒，
        // 就当它还在后面接着跑。原先一看不见就算甩开，没武器时"已撤离，离打点 0 格"一秒好几回。
        Battlefield.Me me = field.me();
        for (double[] seen : lastSeen.values()) {
            if (Math.hypot(seen[0] - me.x(), seen[1] - me.z()) < CLEAR_GAP) return false;
        }
        return true;
    }

    // 每刻记下这次追兵露面的位置；没露面的又老一刻，老过三秒就忘掉。
    private void remember(Battlefield field) {
        for (double[] seen : lastSeen.values()) seen[2]++;
        for (Battlefield.Foe foe : field.foes()) {
            if (pursuers.contains(foe.id()) || foe.attacker()) {
                lastSeen.put(foe.id(), new double[] {foe.threat().x(), foe.threat().z(), 0});
            }
        }
        lastSeen.values().removeIf(seen -> seen[2] > REMEMBER_TICKS);
    }

    /**
     * 往哪个反方向跑用的追兵重心：此刻露面的按此刻位置，一时看不见的按最后露面的位置；一个都没记着为 null。
     */
    double[] pursuersCenter() {
        if (lastSeen.isEmpty()) return null;
        double x = 0;
        double z = 0;
        for (double[] seen : lastSeen.values()) {
            x += seen[0];
            z += seen[1];
        }
        return new double[] {x / lastSeen.size(), z / lastSeen.size()};
    }

    /** 甩开要拉开的距离（格）：看得见的会还手的怪都在这之外。 */
    static final double CLEAR_GAP = 10;
    /** 追兵看不见多久（刻）才当它不在了：三秒。 */
    static final int REMEMBER_TICKS = 60;

    /**
     * 一条撤离路线走不通：换下一个方向再试；身体比上次失败处真走出两格就清账重数。
     *
     * @return 连续走不通到上限，退无可退
     */
    boolean routeFailed(double x, double z) {
        if (lastFailureAt != null && Math.hypot(x - lastFailureAt[0], z - lastFailureAt[1]) >= REAL_PROGRESS) {
            failures = 0;
        }
        lastFailureAt = new double[] {x, z};
        failures++;
        turns++;
        return failures >= ThreatAssessment.ESCAPE_FAILURES_BEFORE_LAST_STAND;
    }

    int turns() {
        return turns;
    }

    /**
     * 挑一个落点：背离追兵重心，从第 turns 个方向试起，返回第一个能站的 {x, y, z}；都不行为 null（交给"离开追兵"的寻路）。
     */
    static int[] haven(double x, double y, double z, double fromX, double fromZ, int turns, Probe probe) {
        double awayX = x - fromX;
        double awayZ = z - fromZ;
        if (Math.hypot(awayX, awayZ) < 1.0e-4) {
            awayX = 1;
            awayZ = 0;
        }
        double base = Math.atan2(awayZ, awayX);
        for (int i = 0; i < OFFSETS.length; i++) {
            double angle = base + Math.toRadians(OFFSETS[(i + turns) % OFFSETS.length]);
            int cx = (int) Math.round(x + Math.cos(angle) * REACH_OUT);
            int cz = (int) Math.round(z + Math.sin(angle) * REACH_OUT);
            int[] landing = standableNear(cx, (int) Math.floor(y), cz, probe);
            if (landing != null) return landing;
        }
        return null;
    }

    // 这一列上离原高度最近的能站处：先试同高，再上下各试到四格。
    private static int[] standableNear(int x, int aroundY, int z, Probe probe) {
        for (int dy = 0; dy <= MAX_DROP; dy++) {
            if (probe.standable(x, aroundY + dy, z)) return new int[] {x, aroundY + dy, z};
            if (dy > 0 && probe.standable(x, aroundY - dy, z)) return new int[] {x, aroundY - dy, z};
        }
        return null;
    }
}
