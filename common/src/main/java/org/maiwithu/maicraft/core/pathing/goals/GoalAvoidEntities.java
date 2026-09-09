package org.maiwithu.maicraft.core.pathing.goals;

/**
 * 计算怎样远离一组威胁，当前由 NavGoal 复用。必须离每个威胁都足够远才算到达，不能只躲开最近的一只。
 * 判断是否脱身看水平距离；比较往哪里走时则把所有威胁的远近惩罚相加。威胁位置在创建这份目标时固定。
 */
public class GoalAvoidEntities implements Goal {

    /**
     * 记下一只威胁的位置和两种距离：radius 决定靠近它时有多不划算，clearance 决定要离多远才算脱身。
     * 例如短暂退开后继续战斗，与彻底逃离，可以使用不同的脱身距离，而不必扩大附近每一格的危险惩罚范围。
     */
    public record Threat(double x, double y, double z, double radius, double clearance) {
        public Threat {
            if (radius < 0.0 || clearance < 0.0) {
                throw new IllegalArgumentException("距离不能为负:" + radius + " / " + clearance);
            }
        }

        /** 拉扯:退出危险半径就算脱身,两者同一个数。 */
        public Threat(double x, double y, double z, double radius) {
            this(x, y, z, radius, radius);
        }

        /** 逃跑:惩罚球还是那么大,但要拉开到这么远才算脱身。 */
        public Threat withClearance(double newClearance) {
            return new Threat(x, y, z, radius, newClearance);
        }
    }

    /**
     * 候选位置恰好与威胁重合时使用这个大惩罚，避免除以零。
     */
    static final double ON_TOP_OF_THREAT = 1000.0;

    private final Threat[] threats;
    private final double penaltyFactor;

    /**
     * 保存至少一只威胁的副本；正的 penaltyFactor 越大，搜索时越不愿贴近威胁。
     */
    public GoalAvoidEntities(double penaltyFactor, Threat... threats) {
        if (threats.length == 0) {
            throw new IllegalArgumentException("躲避目标至少需要一个威胁");
        }
        this.threats = threats.clone();
        this.penaltyFactor = penaltyFactor;
    }

    /**
     * 比较两个水平位置是否至少相隔指定半径；不把上下楼层的高度差算作已经脱身。
     */
    public static boolean clearOf(double x, double z, double threatX, double threatZ,
                                  double radius) {
        double dx = x - threatX;
        double dz = z - threatZ;
        return dx * dx + dz * dz >= radius * radius;
    }

    /**
     * 返回威胁列表的副本，外部改数组不会改掉这份目标。
     */
    public Threat[] threats() {
        return threats.clone();
    }

    /**
     * 候选格以水平中心计算，必须在每个威胁的脱身圈之外；这里不检查视线、墙体或真实攻击是否能够命中。
     */
    @Override
    public boolean isInGoal(int x, int y, int z) {
        for (Threat t : threats) {
            if (!clearOf(x + 0.5, z + 0.5, t.x(), t.z(), t.clearance())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 给搜索提供远离威胁的倾向：三维距离越近惩罚越大，按各自危险半径调整后相加。
     * 这是搜索估价，不是已经验证的安全程度，也不证明最后路线一定最短。
     */
    @Override
    public double heuristic(int x, int y, int z) {
        double sum = 0.0;
        for (Threat t : threats) {
            double dx = x + 0.5 - t.x();
            double dy = y - t.y();
            double dz = z + 0.5 - t.z();
            double span = Math.max(1.0, t.radius());
            double cost = (dx * dx + dy * dy + dz * dz) / (span * span);
            sum += cost <= 0.0 ? ON_TOP_OF_THREAT : 1.0 / cost;
        }
        return sum * penaltyFactor;
    }

    @Override
    public String toString() {
        return String.format("GoalAvoidEntities{n=%d,k=%.0f}", threats.length, penaltyFactor);
    }
}
