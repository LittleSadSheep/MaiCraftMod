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

package baritone.api.utils.input;

/**
 * An {@link Enum} representing the inputs that control the player's
 * behavior. This includes moving, interacting with blocks, jumping,
 * sneaking, and sprinting.
 */
public enum Input {

    /**
     * The move forward input
     */
    MOVE_FORWARD,

    /**
     * The move back input
     */
    MOVE_BACK,

    /**
     * The move left input
     */
    MOVE_LEFT,

    /**
     * The move right input
     */
    MOVE_RIGHT,

    /**
     * The attack input
     */
    CLICK_LEFT,

    /**
     * The use item input
     */
    CLICK_RIGHT,

    /**
     * The jump input
     */
    JUMP,

    /**
     * The sneak input
     */
    SNEAK,

    /**
     * The sprint input
     */
    SPRINT
}
