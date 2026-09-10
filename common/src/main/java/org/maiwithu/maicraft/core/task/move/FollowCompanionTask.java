package org.maiwithu.maicraft.core.task.move;

import org.maiwithu.maicraft.core.pathing.calc.NavGoal;
import org.maiwithu.maicraft.core.pathing.execute.PlayerNav;
import org.maiwithu.maicraft.core.pathing.moves.MovementHelper;
import org.maiwithu.maicraft.core.task.base.AbstractCompanionTask;
import net.minecraft.client.player.LocalPlayer;
import org.maiwithu.maicraft.task.TaskState;
import org.maiwithu.maicraft.core.FailureType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * 持续跟随已经选定的实体，靠近后暂时不占控制权，目标再离远一些才重新起步。目标丢失或路走不通会失败。
 * 空中目标的导航位置取脚下地面，但开始和停止判断仍用到实体本体的距离；这两套要求目前并不一致。
 * 旧的空目标分支只会等待，现用工具必须指定实体，不会自动找到所谓默认主人。
 */
public final class FollowCompanionTask extends AbstractCompanionTask<FollowTaskRecord> {

    private static final double WALK_SPEED = 1.0;
    /** 比 {@code keepWithin} 多出这么远才重新起步,免得在临界距离上抖着走走停停。 */
    private static final double RESUME_MARGIN = 2.0;

    /** 主人悬空时,往下找地面最多找几格。 */
    private static final int GROUND_SCAN = 64;

    /** 上一刻是不是在走——用来只在真正起步/到位时重建导航。 */
    private boolean moving;

    public FollowCompanionTask(LocalPlayer player, FollowTaskRecord record) {
        super(player, record);
    }

    @Override
    public boolean canRun(LocalPlayer companion) {
        Entity target = target(companion);
        if (target == null) {
            // 点名的目标没了:要放它跑一刻才收得了尾(canRun 返 false 的任务不会 tick,
            // 也就永远报不出去)。跟的是主人就单纯睡着等他回来。
            return r.entityId != null;
        }
        double gap = companion.position().distanceTo(target.position());
        // 迟滞:走出 keepWithin + margin 才起步,回到 keepWithin 之内才停——
        // 单阈值会让她在临界距离上一步一停地抖。
        return moving ? gap > r.keepWithin : gap > r.keepWithin + RESUME_MARGIN;
    }

    @Override
    protected void onStart() {
        moving = false;
    }

    @Override
    protected TaskState onTick() {
        Entity target = target(player);
        if (target == null) {
            if (r.entityId == null) {
                return TaskState.RUNNING;   // 主人下线:canRun 已经挡住了,这里只是防御
            }
            stopNav();
            fail("the entity you were following is gone (killed, or it left the loaded area)",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (nav == null) {
            // 目标每次重规划时现取,所以主人边走她也跟得上。地形许可按记录来,默认只走不改;
            // 探针开着——跟不上的时候回执里要有"会动哪些方块"的清单
            nav = PlayerNav.toGoal(player, this::goal, WALK_SPEED, this::closeEnough,
                    r.mayAlterTerrain ? PlayerNav.ContextProvider.TERRAFORM
                            : PlayerNav.ContextProvider.DEFAULT).withTerrainProbe();
        }
        moving = true;
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED -> {
                stopNav();
                moving = false;
            }
            case FAILED -> {
                // 够不着就是这件活的结果:原因与清单交给模型,别攥着站在原地空算
                String why = nav.failReason();
                FailureType type = nav.failType();
                stopNav();
                fail("can't keep up: " + why, type);
                return TaskState.FAILED;
            }
        }
        if (closeEnough()) {
            stopNav();
            moving = false;
        }
        // 不返终态就是"常驻"的全部含义;只有够不着和目标没了才收场。
        return TaskState.RUNNING;
    }

    /**
     * 每次用运行编号找到目标，再用 UUID 确认还是原来那一只；编号被重用时不会改跟其他实体。
     */
    private Entity target(LocalPlayer companion) {
        if (r.entityId == null) {
            return null; // the real LocalPlayer runtime has no synthetic owner relationship
        }
        Entity e = companion.clientLevel.getEntity(r.entityId);
        if (e == null || e.isRemoved() || e == companion) {
            return null;
        }
        // id 对上还不够:重启之后同一个号可能发给了别的东西。
        return r.targetUuid != null && !r.targetUuid.equals(e.getUUID()) ? null : e;
    }

    private NavGoal goal() {
        Entity target = target(player);
        BlockPos at = target == null ? player.blockPosition() : anchor(target);
        return NavGoal.nearGround(at, r.keepWithin);
    }

    /**
     * 目标在空中时，向下找最多六十四格，把导航引向它脚下的地面；遇到不能穿过的格子也停止下探。
     */
    private BlockPos anchor(Entity target) {
        BlockPos at = target.blockPosition();
        if (target.onGround()) {
            return at;
        }
        Level level = player.level();
        BlockPos p = at;
        for (int i = 0; i < GROUND_SCAN; i++) {
            if (MovementHelper.canWalkOn(level, p.below())) {
                return p;                        // 站得住,就是这儿
            }
            if (!MovementHelper.canWalkThrough(level, p.below())) {
                return p;                        // 下面是穿不过又站不住的东西,不再往下
            }
            p = p.below();
        }
        return p;
    }

    private boolean closeEnough() {
        Entity target = target(player);
        return target != null && player.position().distanceTo(target.position()) <= r.keepWithin;
    }

    @Override
    protected String successMessage() {
        // 常驻任务走不到 SUCCESS;真被换掉时走的是 cancelledMessage。
        return "跟随结束";
    }
}
