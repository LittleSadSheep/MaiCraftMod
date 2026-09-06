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

package baritone.utils;

import baritone.api.utils.input.Input;

public class PlayerMovementInput extends net.minecraft.client.player.Input {

    private final InputOverrideHandler handler;

    PlayerMovementInput(InputOverrideHandler handler) {
        this.handler = handler;
    }

    @Override
    public void tick(boolean p_225607_1_, float f) {
        this.leftImpulse = 0.0F;
        this.forwardImpulse = 0.0F;

        this.jumping = handler.isInputForcedDown(Input.JUMP); // oppa gangnam style

        if (this.up = handler.isInputForcedDown(Input.MOVE_FORWARD)) {
            this.forwardImpulse++;
        }

        if (this.down = handler.isInputForcedDown(Input.MOVE_BACK)) {
            this.forwardImpulse--;
        }

        if (this.left = handler.isInputForcedDown(Input.MOVE_LEFT)) {
            this.leftImpulse++;
        }

        if (this.right = handler.isInputForcedDown(Input.MOVE_RIGHT)) {
            this.leftImpulse--;
        }

        if (this.shiftKeyDown = handler.isInputForcedDown(Input.SNEAK)) {
            this.leftImpulse *= 0.3D;
            this.forwardImpulse *= 0.3D;
        }
    }
}
