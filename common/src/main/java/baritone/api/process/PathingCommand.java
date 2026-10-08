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

import baritone.api.pathing.goals.Goal;

import java.util.Objects;

/**
 * @author leijurv
 */
public class PathingCommand {

    /**
     * The target goal, may be {@code null}.
     */
    public final Goal goal;

    /**
     * The command type.
     *
     * @see PathingCommandType
     */
    public final PathingCommandType commandType;

    /**
     * Create a new {@link PathingCommand}.
     *
     * @param goal        The target goal, may be {@code null}.
     * @param commandType The command type, cannot be {@code null}.
     * @throws NullPointerException if {@code commandType} is {@code null}.
     * @see Goal
     * @see PathingCommandType
     */
    public PathingCommand(Goal goal, PathingCommandType commandType) {
        Objects.requireNonNull(commandType);

        this.goal = goal;
        this.commandType = commandType;
    }

    @Override
    public String toString() {
        return commandType + " " + goal;
    }
}
