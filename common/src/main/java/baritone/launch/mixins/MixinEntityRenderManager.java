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

package baritone.launch.mixins;

import baritone.utils.accessor.IEntityRenderManager;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderManager implements IEntityRenderManager {


    @Override
    public double renderPosX() {
        return ((EntityRenderDispatcher) (Object) this).camera.getPosition().x;
    }

    @Override
    public double renderPosY() {
        return ((EntityRenderDispatcher) (Object) this).camera.getPosition().y;
    }

    @Override
    public double renderPosZ() {
        return ((EntityRenderDispatcher) (Object) this).camera.getPosition().z;
    }
}
