package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.act.Ballistics;
import static org.maiwithu.maicraft.client.actor.CombatThreatsTest.check;

/** 通过真实射击状态机和原生动作端口计数，检查发射前瞄准与不射箭的取消。 */
public final class RangedShotTest {
    private static final Ballistics.Aim ALIGNED = new Ballistics.Aim(new Vec3(0, 1, 10), new Vec3(0, 0, 1), 5);
    private static final Ballistics.Aim MISALIGNED = new Ballistics.Aim(new Vec3(10, 1, 0), new Vec3(1, 0, 0), 5);
    private static final Class<?> SHOT = type();

    public static void main(String[] args) throws Exception {
        chargedWaitsAndFiresOnce();
        loadsBeforeFiring();
        cancelBow(false); cancelBow(true);
        System.out.println("RangedShotTest: charged aim, loading, draw timeout and cancellation passed");
    }

    private static void chargedWaitsAndFiresOnce() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var bow = charged(); f.h.inventory.setItem(0, bow);
            f.h.mode.itemUse = p -> bow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
            var shot = shot(f.h.player, true);
            for (int i = 0; i < 5; i++) { tick(shot, MISALIGNED); f.h.nextTick(); }
            check(f.h.mode.items == 0, "a loaded crossbow must not use/fire before alignment");
            for (int i = 0; i < 4 && !tick(shot, ALIGNED); i++) f.h.nextTick();
            check(fired(shot) && f.h.mode.items == 1, "alignment produces one confirmed shot, with no extra load click");
        }
    }

    private static void loadsBeforeFiring() throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            var bow = new ItemStack(Items.CROSSBOW); f.h.inventory.setItem(0, bow);
            f.h.mode.itemUse = p -> {
                if (CrossbowItem.isCharged(bow)) bow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
                else p.startUsingItem(InteractionHand.MAIN_HAND);
            };
            f.h.mode.itemRelease = p -> bow.set(DataComponents.CHARGED_PROJECTILES,
                    ChargedProjectiles.of(new ItemStack(Items.ARROW)));
            var shot = shot(f.h.player, true);
            for (int i = 0; i < 100 && !tick(shot, ALIGNED); i++) f.h.nextTick();
            check(fired(shot) && f.h.mode.items == 2 && f.h.mode.releases == 1,
                    "an empty crossbow must load, release loading, then fire exactly once");
        }
    }

    private static void cancelBow(boolean explicit) throws Exception {
        try (var f = new CombatThreatsTest.Fixture()) {
            f.h.inventory.setItem(0, new ItemStack(Items.BOW));
            f.h.mode.itemUse = p -> p.startUsingItem(InteractionHand.MAIN_HAND);
            var shot = shot(f.h.player, false);
            if (explicit) {
                tick(shot, MISALIGNED); f.h.nextTick(); call(shot, "abort");
            } else {
                for (int i = 0; i < 60 && !tick(shot, MISALIGNED); i++) f.h.nextTick();
            }
            check(!fired(shot) && f.h.mode.releases == 0 && f.h.mode.items == 1,
                    "timeout or interruption must cancel without a release packet or firing another use");
            check(!f.h.player.isUsingItem() && f.h.inventory.selected != 0,
                    "native main-hand cancellation switches away from the charged bow");
            check(f.h.h.connection.packets.stream().anyMatch(p -> p instanceof net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket),
                    "cancellation must reach the server's carried-item path");
        }
    }

    private static ItemStack charged() {
        var result = new ItemStack(Items.CROSSBOW);
        result.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(new ItemStack(Items.ARROW)));
        return result;
    }
    private static Object shot(LocalPlayer player, boolean crossbow) throws Exception {
        var constructor = SHOT.getDeclaredConstructor(LocalPlayer.class, boolean.class);
        constructor.setAccessible(true); return constructor.newInstance(player, crossbow);
    }
    private static boolean tick(Object shot, Ballistics.Aim aim) throws Exception {
        Method method = SHOT.getDeclaredMethod("tick", Ballistics.Aim.class, Entity.class);
        method.setAccessible(true); return (Boolean) method.invoke(shot, aim, null);
    }
    private static Object call(Object shot, String name) throws Exception {
        var method = SHOT.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(shot);
    }
    private static boolean fired(Object shot) throws Exception { return (Boolean) call(shot, "fired"); }
    private static Class<?> type() {
        try { return Class.forName("org.maiwithu.maicraft.core.task.combat.RangedShot"); }
        catch (ClassNotFoundException failure) { throw new AssertionError(failure); }
    }
}
