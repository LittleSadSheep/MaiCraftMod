// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** Terrain preservation must not block a requested menu, and must never remove inherited use protection. */
public final class NavigationUseProtectionTest {
    public static void main(String[] args) {
        BlockPos user = new BlockPos(1,0,0), machine = new BlockPos(2,0,0);
        var before = NavigationSafetyContext.protectedMutationCells();
        NavigationSafetyContext.withProtectedArea(List.of(user),List.of(),() -> {
            NavigationSafetyContext.withPreservedStructures(List.of(user,machine),() -> {
                check(NavigationSafetyContext.protectsMutation(machine),"Navigation must not dig through the machine");
                check(!NavigationSafetyContext.protectsUse(machine),"An explicitly authorised machine may open its own menu");
                check(NavigationSafetyContext.protectsUse(user),"A preserved machine must not erase existing user protection");
                try {
                    NavigationSafetyContext.withProtectedArea(List.of(machine),List.of(),() -> {
                        check(NavigationSafetyContext.protectsUse(machine),"Inner explicit protection must still prohibit use");
                        throw new IllegalStateException("fixture");
                    });
                } catch (IllegalStateException expected) { check(expected.getMessage().equals("fixture"),"Unexpected exception"); }
                check(!NavigationSafetyContext.protectsUse(machine),"Exceptional scope exit must restore use rules");
                return null;
            });
            check(!NavigationSafetyContext.protectsMutation(machine) && NavigationSafetyContext.protectsUse(user),"Preservation scope must unwind");
            return null;
        });
        check(NavigationSafetyContext.protectedMutationCells().equals(before) && !NavigationSafetyContext.protectsUse(user),"Scopes leaked into another task");
        System.out.println("NavigationUseProtectionTest: native menu use and inherited protection remain separate");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
