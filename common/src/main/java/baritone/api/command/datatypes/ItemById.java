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

package baritone.api.command.datatypes;

import baritone.api.command.exception.CommandException;
import baritone.api.command.helpers.TabCompleteHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.stream.Stream;

public enum ItemById implements IDatatypeFor<Item> {
    INSTANCE;

    @Override
    public Item get(IDatatypeContext ctx) throws CommandException {
        ResourceLocation id = ResourceLocation.parse(ctx.getConsumer().getString());
        Item item;
        if ((item = BuiltInRegistries.ITEM.getOptional(id).orElse(null)) == null) {
            throw new IllegalArgumentException("No item found by that id");
        }
        return item;
    }

    @Override
    public Stream<String> tabComplete(IDatatypeContext ctx) throws CommandException {
        return new TabCompleteHelper()
                .append(
                        BuiltInRegistries.ITEM.keySet()
                                .stream()
                                .map(ResourceLocation::toString)
                )
                .filterPrefixNamespaced(ctx.getConsumer().getString())
                .sortAlphabetically()
                .stream();
    }
}
