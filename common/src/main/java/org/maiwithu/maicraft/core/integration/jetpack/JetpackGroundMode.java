package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.Map;
import org.maiwithu.maicraft.client.actor.LocalPlayerContext;
import org.maiwithu.maicraft.client.actor.NativeActionReceipt;

/**
 * 步行或准备落地辅助前，必要时关闭仍启用的喷气背包，并等待模式记录确认；不在快速下落中随意关掉正在使用的背包。
 */
public final class JetpackGroundMode {
    enum Decision { READY, WAIT, DISABLE, FAILED }
    private NativeActionReceipt receipt;
    private boolean attempted,failed;
    private String detail="ground mode unchanged";
    public boolean prepare(LocalPlayerContext context) {
        context.requireCurrent(); poll(context);
        if(failed) return false;
        var power=JetpackNativeAdapter.inspect(context);
        if(!power.known() && !attempted) return true;
        var decision=decide(power,context.player().onGround(),context.player().getDeltaMovement().y,
                attempted,receipt==null ? null : receipt.status());
        if(decision==Decision.FAILED) { failed=true; detail="jetpack ground-mode handoff was not confirmed or flight was re-enabled"; return false; }
        if(decision==Decision.DISABLE && context.mutationAvailable()) {
            attempted=true; receipt=JetpackNativeAdapter.setMode(context,false,false);
            detail="disabling jetpack for ground movement or prepared landing protection";
        }
        if(decision==Decision.READY && attempted) detail="native jetpack disabled; ground/fall control owns movement";
        return decision==Decision.READY;
    }
    public void poll(LocalPlayerContext context) {
        if(receipt!=null && !receipt.terminal()) context.actions().poll(context,receipt);
    }
    // 待确认时继续等；尝试后未确认或重新被开启就失败。还没尝试且无法识别背包时允许原步行流程继续。
    static Decision decide(JetpackNativeAdapter.Snapshot power,boolean grounded,double verticalSpeed,
                           boolean attempted,NativeActionReceipt.Status status) {
        if(status==NativeActionReceipt.Status.PENDING) return Decision.WAIT;
        if(status!=null && status!=NativeActionReceipt.Status.CONFIRMED_APPLIED) return Decision.FAILED;
        if(!power.known()) return attempted ? Decision.FAILED : Decision.READY;
        if(!power.active()) return Decision.READY;
        if(attempted) return Decision.FAILED;
        // A fast fall already has gravity control: do not consume its urgent water-use tick.
        return grounded || power.hover() && verticalSpeed>-.2 ? Decision.DISABLE : Decision.READY;
    }
    public boolean failed() { return failed; }
    public Map<String,Object> diagnostics() {
        return Map.of("disable_attempted",attempted,"failed",failed,"detail",detail,
                "receipt",receipt==null ? "none" : receipt.status().name());
    }
}
