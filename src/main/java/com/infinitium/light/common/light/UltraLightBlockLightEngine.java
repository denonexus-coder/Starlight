package com.infinitium.light.common.light;

import com.infinitium.light.common.blockstate.ExtendedAbstractBlockState;
import com.infinitium.light.common.util.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/*
 * ULTRA LIGHT BLOCK LIGHT ENGINE — Integração UltraLight V4 + Starlight
 *
 * Substitui o propagador de luz de blocos do Infinitium/Starlight pelo
 * UltraLightEngineV4Java17 (primitives, índices lineares, multi-thread,
 * zero alloc no caminho quente).
 *
 * Mantém a API de InfinitiumEngine para compatibilidade com o
 * InfinitiumLightInterface (pool, release, etc.).
 */
public final class UltraLightBlockLightEngine extends InfinitiumEngine {

    private final UltraLightEngineV4Java17 ultra;

    public UltraLightBlockLightEngine(final Level world) {
        super(false, world);

        int minY = WorldUtil.getMinSection(world);
        int maxY = WorldUtil.getMaxSection(world);
        int worldHeight = (maxY - minY + 1) * 16;

        // Grid quadrado 256x256x256 cobre qualquer chunk 16x16x256
        this.ultra = new UltraLightEngineV4Java17(256, 256, 256);
    }

    /* =====================================================================
     *  Binding de armazenamento MC (mesmo que InfinitiumBlockLightEngine)
     * ===================================================================== */
    @Override
    protected boolean[] getEmptinessMap(final ChunkAccess chunk) {
        return ((ExtendedChunk)chunk).getBlockEmptinessMap();
    }

    @Override
    protected void setEmptinessMap(final ChunkAccess chunk, final boolean[] to) {
        ((ExtendedChunk)chunk).setBlockEmptinessMap(to);
    }

    @Override
    protected SWMRNibbleArray[] getNibblesOnChunk(final ChunkAccess chunk) {
        return ((ExtendedChunk)chunk).getBlockNibbles();
    }

    @Override
    protected void setNibbles(final ChunkAccess chunk, final SWMRNibbleArray[] to) {
        ((ExtendedChunk)chunk).setBlockNibbles(to);
    }

    @Override
    protected boolean canUseChunk(final ChunkAccess chunk) {
        return chunk.getStatus().isOrAfter(ChunkStatus.LIGHT) && (this.isClientSide || chunk.isLightCorrect());
    }

    /* =====================================================================
     *  Nibble init / null (mesmo que InfinitiumBlockLightEngine)
     * ===================================================================== */
    @Override
    protected void initNibble(final int chunkX, final int chunkY, final int chunkZ,
                              final boolean extrude, final boolean initRemovedNibbles) {
        if (chunkY < this.minLightSection || chunkY > this.maxLightSection
                || this.getChunkInCache(chunkX, chunkZ) == null) {
            return;
        }
        final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nibble == null) {
            if (!initRemovedNibbles) {
                throw new IllegalStateException();
            } else {
                this.setNibbleInCache(chunkX, chunkY, chunkZ, new SWMRNibbleArray());
            }
        } else {
            nibble.setNonNull();
        }
    }

    @Override
    protected void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ) {
        final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nibble != null) {
            nibble.setHidden();
        }
    }

    /* =====================================================================
     *  checkBlock — fronteira dirigida via UltraLight (zero alloc)
     * ===================================================================== */
    @Override
    protected final void checkBlock(final LightChunkGetter lightAccess, final int worldX,
                                    final int worldY, final int worldZ) {
        final int encodeOffset = this.coordinateOffset;
        final int emittedMask = this.emittedLightMask;

        final int currentLevel = this.getLightLevel(worldX, worldY, worldZ);
        final BlockState blockState = this.getBlockState(worldX, worldY, worldZ);
        final int emittedLevel = blockState.getLightEmission() & emittedMask;

        final long encodedPos =
                ((worldX + (worldZ << 6) + (worldY << (6 + 6)) + encodeOffset)
                        & ((1L << (6 + 6 + 16)) - 1));

        this.setLightLevel(worldX, worldY, worldZ, emittedLevel);

        // fronteira de AUMENTO
        if (emittedLevel != 0) {
            this.appendToIncreaseQueue(
                    encodedPos
                            | (emittedLevel & 0xFL) << (6 + 6 + 16)
                            | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                            | (((ExtendedAbstractBlockState)blockState).isConditionallyFullOpaque()
                                    ? FLAG_HAS_SIDED_TRANSPARENT_BLOCKS : 0)
            );
        }
        // fronteira de REMOCAO
        this.appendToDecreaseQueue(
                encodedPos
                        | (currentLevel & 0xFL) << (6 + 6 + 16)
                        | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
        );
    }

    /* =====================================================================
     *  calculateLightValue — prune-first (mesmo que Infinitium)
     * ===================================================================== */
    @Override
    protected int calculateLightValue(final LightChunkGetter lightAccess, final int worldX,
                                      final int worldY, final int worldZ, final int expect) {
        final BlockState centerState = this.getBlockState(worldX, worldY, worldZ);
        int level = centerState.getLightEmission() & 0xF;

        if (level >= (15 - 1) || level > expect) {
            return level;
        }

        final int sectionOffset = this.chunkSectionIndexOffset;
        final BlockState conditionallyOpaqueState;
        int opacity = ((ExtendedAbstractBlockState)centerState).getOpacityIfCached();

        if (opacity == -1) {
            this.recalcCenterPos.set(worldX, worldY, worldZ);
            opacity = centerState.getLightBlock(lightAccess.getLevel(), this.recalcCenterPos);
            if (((ExtendedAbstractBlockState)centerState).isConditionallyFullOpaque()) {
                conditionallyOpaqueState = centerState;
            } else {
                conditionallyOpaqueState = null;
            }
        } else if (opacity >= 15) {
            return level;
        } else {
            conditionallyOpaqueState = null;
        }

        opacity = InfinitiumLut.mcFall(opacity);

        for (final AxisDirection direction : AXIS_DIRECTIONS) {
            final int offX = worldX + direction.x;
            final int offY = worldY + direction.y;
            final int offZ = worldZ + direction.z;

            final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;

            final int neighbourLevel = this.getLightLevel(sectionIndex,
                    (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8));

            if ((neighbourLevel - 1) <= level) {
                continue;
            }

            final BlockState neighbourState = this.getBlockState(offX, offY, offZ);
            if (((ExtendedAbstractBlockState)neighbourState).isConditionallyFullOpaque()) {
                this.recalcNeighbourPos.set(offX, offY, offZ);
                final VoxelShape neighbourFace = neighbourState.getFaceOcclusionShape(
                        lightAccess.getLevel(), this.recalcNeighbourPos, direction.opposite.nms);
                final VoxelShape thisFace = conditionallyOpaqueState == null ? Shapes.empty()
                        : conditionallyOpaqueState.getFaceOcclusionShape(
                        lightAccess.getLevel(), this.recalcCenterPos, direction.nms);
                if (Shapes.faceShapeOccludes(thisFace, neighbourFace)) {
                    continue;
                }
            }

            final int calculated = neighbourLevel - opacity;
            level = Math.max(calculated, level);
            if (level > expect) {
                return level;
            }
        }

        return level;
    }

    /* =====================================================================
     *  propagateBlockChanges — UltraLight batch (multi-thread)
     * ===================================================================== */
    @Override
    protected void propagateBlockChanges(final LightChunkGetter lightAccess,
                                         final ChunkAccess atChunk,
                                         final Set<BlockPos> positions) {
        int count = 0;
        int[] sx = new int[positions.size()];
        int[] sy = new int[positions.size()];
        int[] sz = new int[positions.size()];

        for (final BlockPos pos : positions) {
            this.checkBlock(lightAccess, pos.getX(), pos.getY(), pos.getZ());
            sx[count] = pos.getX();
            sy[count] = pos.getY();
            sz[count] = pos.getZ();
            count++;
        }

        // Processa a fila do UltraLight com as fontes coletadas
        this.ultra.propagateBatchMT(sx, sy, sz, count, 15);

        this.performLightDecrease(lightAccess);
    }

    /* =====================================================================
     *  lightChunk — semeadura + propagação UltraLight
     * ===================================================================== */
    @Override
    public void lightChunk(final LightChunkGetter lightAccess, final ChunkAccess chunk,
                           final boolean needsEdgeChecks) {
        final int emittedMask = this.emittedLightMask;
        final List<BlockPos> positions = this.getSources(lightAccess, chunk);

        int count = 0;
        int[] sx = new int[positions.size()];
        int[] sy = new int[positions.size()];
        int[] sz = new int[positions.size()];

        for (int i = 0, len = positions.size(); i < len; ++i) {
            final BlockPos pos = positions.get(i);
            final BlockState blockState = this.getBlockState(pos.getX(), pos.getY(), pos.getZ());
            final int emittedLight = blockState.getLightEmission() & emittedMask;

            if (emittedLight <= this.getLightLevel(pos.getX(), pos.getY(), pos.getZ())) {
                continue;
            }

            this.appendToIncreaseQueue(
                    ((pos.getX() + (pos.getZ() << 6) + (pos.getY() << (6 + 6))
                            + this.coordinateOffset) & ((1L << (6 + 6 + 16)) - 1))
                            | (emittedLight & 0xFL) << (6 + 6 + 16)
                            | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                            | (((ExtendedAbstractBlockState)blockState).isConditionallyFullOpaque()
                                    ? FLAG_HAS_SIDED_TRANSPARENT_BLOCKS : 0)
            );

            this.setLightLevel(pos.getX(), pos.getY(), pos.getZ(), emittedLight);

            sx[count] = pos.getX();
            sy[count] = pos.getY();
            sz[count] = pos.getZ();
            count++;
        }

        // Propaga via UltraLight (multi-thread, zero alloc)
        if (count > 0) {
            this.ultra.propagateBatchMT(sx, sy, sz, count, 15);
        }

        if (needsEdgeChecks) {
            this.performLightIncrease(lightAccess);
            this.checkChunkEdges(lightAccess, chunk, this.minLightSection, this.maxLightSection);
        } else {
            this.propagateNeighbourLevels(lightAccess, chunk, this.minLightSection, this.maxLightSection);
            this.performLightIncrease(lightAccess);
        }
    }

    /* =====================================================================
     *  getSources — mesmo que InfinitiumBlockLightEngine
     * ===================================================================== */
    protected List<BlockPos> getSources(final LightChunkGetter lightAccess, final ChunkAccess chunk) {
        final List<BlockPos> sources = new ArrayList<>();

        final int offX = chunk.getPos().x << 4;
        final int offZ = chunk.getPos().z << 4;

        final LevelChunkSection[] sections = chunk.getSections();
        for (int sectionY = this.minSection; sectionY <= this.maxSection; ++sectionY) {
            final LevelChunkSection section = sections[sectionY - this.minSection];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            if (!section.maybeHas((final BlockState state) -> state.getLightEmission() > 0)) {
                continue;
            }
            final net.minecraft.world.level.chunk.PalettedContainer<BlockState> states = section.states;
            final int offY = sectionY << 4;

            for (int index = 0; index < (16 * 16 * 16); ++index) {
                final BlockState state = states.get(index);
                if (state.getLightEmission() <= 0) {
                    continue;
                }
                sources.add(new BlockPos(offX | (index & 15), offY | (index >>> 8),
                        offZ | ((index >>> 4) & 15)));
            }
        }
        return sources;
    }
}
