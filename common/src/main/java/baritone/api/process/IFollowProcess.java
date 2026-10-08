/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 only.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.process;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;

/**
 * @author Brady
 * @since 9/23/2018
 */
public interface IFollowProcess extends IBaritoneProcess {

    /**
     * Set the follow target to any entities matching this predicate
     *
     * @param filter the predicate
     */
    void follow(Predicate<Entity> filter);

    /**
     * Try to pick up any items matching this predicate
     *
     * @param filter the predicate
     */
    void pickup(Predicate<ItemStack> filter);

    /**
     * @return The entities that are currently being followed. null if not currently following, empty if nothing matches the predicate
     */
    List<Entity> following();

    Predicate<Entity> currentFilter();

    /**
     * Cancels the follow behavior, this will clear the current follow target.
     */
    default void cancel() {
        onLostControl();
    }
}
