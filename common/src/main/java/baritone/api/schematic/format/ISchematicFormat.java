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

package baritone.api.schematic.format;

import baritone.api.schematic.ISchematic;
import baritone.api.schematic.IStaticSchematic;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * The base of a {@link ISchematic} file format
 *
 * @author Brady
 * @since 12/23/2019
 */
public interface ISchematicFormat {

    /**
     * @return The parser for creating schematics of this format
     */
    IStaticSchematic parse(InputStream input) throws IOException;

    /**
     * @param file The file to check against
     * @return Whether or not the specified file matches this schematic format
     */
    boolean isFileType(File file);

    /**
     * @return A list of file extensions used by this format
     */
    List<String> getFileExtensions();
}
