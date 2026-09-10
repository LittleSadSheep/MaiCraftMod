// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.preview;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import static org.maiwithu.maicraft.client.preview.PreviewSectionRefresh.Work.*;

/** Refresh decisions and Minecraft's retained-quad sorting run without an OpenGL context. */
public final class PreviewRefreshTest {
    public static void main(String[] args) {
        sectionRefresh(); independentBudgets(); retainedQuadSorting(); modelFallbacks();
        System.out.println("PreviewRefreshTest: passed");
    }

    private static void sectionRefresh() {
        var first = new PreviewSectionRefresh(); var second = new PreviewSectionRefresh();
        var reads = new AtomicInteger();
        java.util.function.IntSupplier unchanged = () -> { reads.incrementAndGet(); return 7; };
        Vec3 moved = new Vec3(2, 0, 0);
        check(first.required(0, Vec3.ZERO, unchanged) == REBUILD, "new sections require geometry");
        first.rebuilt(7, Vec3.ZERO, 0); second.rebuilt(7, Vec3.ZERO, 500);
        check(first.required(999, moved, unchanged) == NONE && reads.get() == 0,
                "moving does not poll the world or rebuild models on every frame");
        check(first.required(1000, moved, unchanged) == RESORT, "camera movement only requests indices");
        check(second.required(1000, moved, unchanged) == NONE && reads.get() == 1,
                "each section retains its own refresh interval");
        check(first.required(1001, moved, unchanged) == RESORT && reads.get() == 1,
                "work denied by the frame budget remains pending without another world scan");
        first.sorted(moved);
        check(first.required(1002, moved, unchanged) == NONE && first.built(),
                "index sorting keeps geometry built and clears only pending sort work");
        check(first.required(2000, moved, () -> 8) == REBUILD, "a placed block still invalidates geometry");
        check(first.required(2001, moved, unchanged) == REBUILD,
                "pending geometry work cannot be erased by a later camera-only check");
        first.rebuilt(8, moved, 2001);
        check(first.required(3001, moved, () -> 8) == NONE, "unchanged world and camera reuse all buffers");
        first.reset();
        check(!first.built() && first.required(3002, moved, () -> 8) == REBUILD,
                "resource/layer invalidation still forces a fresh mesh");
    }

    private static void independentBudgets() {
        var clock = new AtomicLong();
        var budget = new PreviewFrameBudget(clock::get, 100, 64, 2);
        check(budget.claim(REBUILD, 64) && !budget.claim(REBUILD, 1), "geometry is bounded by cell work");
        check(budget.claim(RESORT, 64) && budget.claim(RESORT, 64) && !budget.claim(RESORT, 1),
                "exhausted geometry allowance does not consume the independent section-sort allowance");
        budget = new PreviewFrameBudget(clock::get, 100, 64, 1);
        check(budget.claim(RESORT, 64) && budget.claim(REBUILD, 64), "sort work cannot exhaust geometry volume");
        budget = new PreviewFrameBudget(clock::get, 100, 64, 2);
        clock.set(100);
        check(!budget.claim(REBUILD, 1) && !budget.claim(RESORT, 1), "both queues respect the shared frame deadline");
        clock.set(0);
        budget = new PreviewFrameBudget(clock::get, 100, 1, 64, 1);
        check(budget.claimPreparation() && !budget.claimPreparation(), "initial preparation has a finite step allowance");
        check(budget.claim(REBUILD, 64) && budget.claim(RESORT, 1), "preparation steps do not consume mesh or sort work");
        budget = new PreviewFrameBudget(clock::get, 100, 10, 64, 1);
        clock.set(100);
        check(!budget.claimPreparation() && !budget.claim(REBUILD, 1) && !budget.claim(RESORT, 1),
                "preparation and section refresh all stop at the same deadline");
    }

    private static void retainedQuadSorting() {
        BlockPos origin = new BlockPos(1024, 64, -20);
        Vec3 front = Vec3.atLowerCornerOf(origin).add(0, 0, -5);
        Vec3 back = Vec3.atLowerCornerOf(origin).add(0, 0, 15);
        MeshData.SortState retained;
        try (ByteBufferBuilder memory = new ByteBufferBuilder(1024)) {
            var builder = new BufferBuilder(memory, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int z : new int[]{0, 10}) {
                builder.addVertex(0, 0, z).setColor(-1); builder.addVertex(1, 0, z).setColor(-1);
                builder.addVertex(1, 1, z).setColor(-1); builder.addVertex(0, 1, z).setColor(-1);
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                retained = mesh.sortQuads(memory, PreviewMeshSection.sorting(origin, front));
                check(mesh.drawState().indexType() == VertexFormat.IndexType.SHORT
                                && Short.toUnsignedInt(mesh.indexBuffer().getShort(0)) == 4,
                        "initial transparency order starts with the more distant second quad");
            }
        }
        // The original vertex staging memory has closed, just as after a GPU vertex upload.
        try (ByteBufferBuilder memory = new ByteBufferBuilder(1024);
             var indices = retained.buildSortedIndexBuffer(memory, PreviewMeshSection.sorting(origin, back))) {
            check(Short.toUnsignedInt(indices.byteBuffer().getShort(0)) == 0
                            && indices.byteBuffer().remaining() == 12 * Short.BYTES,
                    "moving around retained geometry produces only reordered indices using section-relative camera coordinates");
        }
    }

    private static void modelFallbacks() {
        var failedPass = new PreviewMeshSection.ModelPass();
        var otherPass = new PreviewMeshSection.ModelPass();
        var baked = new AtomicInteger();
        failedPass.bake(baked::incrementAndGet);
        failedPass.bake(baked::incrementAndGet);
        failedPass.skip();
        check(failedPass.usable() && failedPass.fallback() == 1,
                "unsupported render shapes count once without discarding successful models");
        otherPass.bake(baked::incrementAndGet);
        failedPass.bake(() -> { throw new IllegalStateException("model wrote incomplete vertices"); });
        check(!failedPass.usable() && failedPass.fallback() == 4,
                "discarding a pass must include its two earlier models, the failed model and the skipped shape");
        failedPass.bake(() -> { throw new AssertionError("a failed pass must not bake later models"); });
        check(failedPass.fallback() == 5 && baked.get() == 3,
                "later fallback models are counted without invoking their renderer");
        check(otherPass.usable() && otherPass.fallback() == 0, "other render passes keep their own successful models");
        var rebuilt = new PreviewMeshSection.ModelPass();
        rebuilt.bake(baked::incrementAndGet);
        check(rebuilt.usable() && rebuilt.fallback() == 0, "a fresh rebuild starts without old fallback counts");
    }

    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
