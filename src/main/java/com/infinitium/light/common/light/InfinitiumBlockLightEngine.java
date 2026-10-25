package com.infinitium.light.common.light;

import com.infinitium.light.common.blockstate.ExtendedAbstractBlockState;
import com.infinitium.light.common.chunk.ExtendedChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// ===========================================================================
// INFINITIUM LIGHT ENGINE — Beyond the Frames: One to Infinity
// InfinitiumBlockLightEngine — nucleo de LUZ DE BLOCOS (0-15) do Infinitium
// ===========================================================================
//
// Substitui o antigo BlockStarLightEngine (removido neste fork).
// Estrutura do algoritmo Infinitium aplicada a fisica de luz do Minecraft:
//
//   1) PRIME-A-PODA (hot/cold): o caminho quente toca APENAS nivel de luz;
//      blockstate/opacity (dado frio) so e lido DEPOIS da poda barata por
//      nivel de luz — o principio do layout quente/frio do Infinitium v2.1.
//   2) FRONTEIRAS DIRIGIDAS: checkBlock enfileira um unico increase dirigido
//      pela emissao nova + um decrease de fronteira que cobre opacidade,
//      recuo de fonte e inversao de direcao — nunca varre volumes.
//   3) LUT DE FALLOUT (InfinitiumLut.MC_FALL): regra max(1, opacidade)
//      precomputada (16 entradas) em vez de ramo por leitura — o equivalente
//      0-15 da LUT Q8 do benchmark (zero float no caminho quente).
//   4) ZERO-ALLOCATION no caminho quente: nada e alocado em checkBlock ou
//      calculateLightValue (filas e caches sao do InfinitiumEngine base).
//
// Fisica preservada: o benchmark v2.1 mediu paridade Vanilla == Starlight na
// propagacao 0-15; a fronteira de remocao dirigida foi o que deu 1.3-2.5x.
// As filas long (encode pos|nivel|direcoes|flags) sao o contrato compartilhado
// com o motor de ceu — a integridade do protocolo/salvamento (SWMRNibbleArray,
// SaveUtil) tambem permanece intocada.
//
// O que e NOVO do Infinitium neste repositorio alem deste nucleo:
//   - InfinitiumLut         : LUT Q8 Beer-Lambert + tabela de falloff MC
//   - InfinitiumLightTable  : tabela de luzes zero-allocation + indice de
//                             clusters + escrita do UBO std140 (172048 B)
//   - assets/infinitium/shaders/infinitium_core.glsl : shading clusterizado
//     forward+clustered validado em hardware real (9.28x-13.84x medido)
// ===========================================================================

public final class InfinitiumBlockLightEngine extends InfinitiumEngine {

    public InfinitiumBlockLightEngine(final Level world) {
        super(false, world);
    }

    // -------------------------------------------------------------------------
    // Binding de armazenamento MC (persistencia/protocolo) — nao e o algoritmo.
    // -------------------------------------------------------------------------

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

    @Override
    protected void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ) {
        final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nibble != null) {
            // de-init de luz de blocos pode causar DECREASE (bloco removido), ao
            // contrario do ceu — escondemos a secao em vez de remove-la para que a
            // fronteira de remocao propagada pela decrease-queue continue correta.
            nibble.setHidden();
        }
    }

    @Override
    protected void initNibble(final int chunkX, final int chunkY, final int chunkZ, final boolean extrude, final boolean initRemovedNibbles) {
        if (chunkY < this.minLightSection || chunkY > this.maxLightSection || this.getChunkInCache(chunkX, chunkZ) == null) {
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

    // -------------------------------------------------------------------------
    // checkBlock — fronteira dirigida Infinitium (hot path)
    //
    // Um unico encode de coordenada (a v1 calculava a expressão longa 2x),
    // depois: increase dirigido pela nova emissao + decrease de fronteira.
    // Nenhuma alocacao, nenhum volume varrido.
    // -------------------------------------------------------------------------

    @Override
    protected final void checkBlock(final LightChunkGetter lightAccess, final int worldX, final int worldY, final int worldZ) {
        // blocos podem mudar: opacidade, emissao e direcao de propagacao

        final int encodeOffset = this.coordinateOffset;
        final int emittedMask = this.emittedLightMask;

        // hot path: nivel atual + blockstate (frio) lidos uma unica vez
        final int currentLevel = this.getLightLevel(worldX, worldY, worldZ);
        final BlockState blockState = this.getBlockState(worldX, worldY, worldZ);
        final int emittedLevel = blockState.getLightEmission() & emittedMask;

        // encode da posicao UMA vez, reaproveitado nas duas filas
        final long encodedPos =
                ((worldX + (worldZ << 6) + (worldY << (6 + 6)) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1));

        this.setLightLevel(worldX, worldY, worldZ, emittedLevel);

        // fronteira de AUMENTO: so a emissao nova (dirigida, nunca volume)
        if (emittedLevel != 0) {
            this.appendToIncreaseQueue(
                    encodedPos
                            | (emittedLevel & 0xFL) << (6 + 6 + 16)
                            | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                            | (((ExtendedAbstractBlockState)blockState).isConditionallyFullOpaque() ? FLAG_HAS_SIDED_TRANSPARENT_BLOCKS : 0)
            );
        }
        // fronteira de REMOCAO: cobre recuo de fonte, mudanca de opacidade e
        // inversao de direcao — checa os vizinhos mesmo com nivel atual 0
        // (mantido sided-transparent=false: se a fronteira errar a
        // re-propagacao dirigida da decrease-queue corrige).
        this.appendToDecreaseQueue(
                encodedPos
                        | (currentLevel & 0xFL) << (6 + 6 + 16)
                        | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
        );
    }

    protected final BlockPos.MutableBlockPos recalcCenterPos = new BlockPos.MutableBlockPos();
    protected final BlockPos.MutableBlockPos recalcNeighbourPos = new BlockPos.MutableBlockPos();

    // -------------------------------------------------------------------------
    // calculateLightValue — recalculo de re-propagacao, prune-first (Infinitium)
    //
    // Ordem quente -> frio (estrutura v2.1):
    //   1. emissao propria            (leitura de bloco minima)
    //   2. poda por nivel do vizinho  (NAO le blockstate do vizinho se nao adianta)
    //   3. frio: opacidade/occlusao   (so apos a poda passar)
    // A regra de decremento vem da LUT InfinitiumLut.MC_FALL = max(1, opacidade),
    // exatamente a regra do MC (resultados identicos, branch precomputado).
    // -------------------------------------------------------------------------

    @Override
    protected int calculateLightValue(final LightChunkGetter lightAccess, final int worldX, final int worldY, final int worldZ,
                                      final int expect) {
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
        // LUT Infinitium: max(1, opacity) precomputada (16 entradas, zero ramo)
        opacity = InfinitiumLut.mcFall(opacity);

        for (final AxisDirection direction : AXIS_DIRECTIONS) {
            final int offX = worldX + direction.x;
            final int offY = worldY + direction.y;
            final int offZ = worldZ + direction.z;

            final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;

            final int neighbourLevel = this.getLightLevel(sectionIndex, (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8));

            if ((neighbourLevel - 1) <= level) {
                // poda quente: com nivel ja suficiente, o blockstate (frio)
                // do vizinho NUNCA e lido
                continue;
            }

            final BlockState neighbourState = this.getBlockState(offX, offY, offZ);
            if (((ExtendedAbstractBlockState)neighbourState).isConditionallyFullOpaque()) {
                // aqui o bloco pode ser condicionalmente opaco — testa a forma real
                this.recalcNeighbourPos.set(offX, offY, offZ);
                final VoxelShape neighbourFace = neighbourState.getFaceOcclusionShape(lightAccess.getLevel(), this.recalcNeighbourPos, direction.opposite.nms);
                final VoxelShape thisFace = conditionallyOpaqueState == null ? Shapes.empty() : conditionallyOpaqueState.getFaceOcclusionShape(lightAccess.getLevel(), this.recalcCenterPos, direction.nms);
                if (Shapes.faceShapeOccludes(thisFace, neighbourFace)) {
                    // nao propagado: faces se fecham
                    continue;
                }
            }

            // passou da poda e da frialdade -> calcula
            final int calculated = neighbourLevel - opacity;
            level = Math.max(calculated, level);
            if (level > expect) {
                return level;
            }
        }

        return level;
    }

    @Override
    protected void propagateBlockChanges(final LightChunkGetter lightAccess, final ChunkAccess atChunk, final Set<BlockPos> positions) {
        for (final BlockPos pos : positions) {
            this.checkBlock(lightAccess, pos.getX(), pos.getY(), pos.getZ());
        }

        this.performLightDecrease(lightAccess);
    }

    protected List<BlockPos> getSources(final LightChunkGetter lightAccess, final ChunkAccess chunk) {
        final List<BlockPos> sources = new ArrayList<>();

        final int offX = chunk.getPos().x << 4;
        final int offZ = chunk.getPos().z << 4;

        final LevelChunkSection[] sections = chunk.getSections();
        for (int sectionY = this.minSection; sectionY <= this.maxSection; ++sectionY) {
            final LevelChunkSection section = sections[sectionY - this.minSection];
            if (section == null || section.hasOnlyAir()) {
                // secoes vazias nao tem fontes
                continue;
            }
            if (!section.maybeHas((final BlockState state) -> {
                return state.getLightEmission() > 0;
            })) {
                // paleta sem fontes de luz
                continue;
            }
            final PalettedContainer<BlockState> states = section.states;
            final int offY = sectionY << 4;

            for (int index = 0; index < (16 * 16 * 16); ++index) {
                final BlockState state = states.get(index);
                if (state.getLightEmission() <= 0) {
                    continue;
                }

                // index = x | (z << 4) | (y << 8)
                sources.add(new BlockPos(offX | (index & 15), offY | (index >>> 8), offZ | ((index >>> 4) & 15)));
            }
        }

        return sources;
    }

    // -------------------------------------------------------------------------
    // lightChunk — semeadura de fontes + fronteiras dirigidas (Infinitium)
    // -------------------------------------------------------------------------

    @Override
    public void lightChunk(final LightChunkGetter lightAccess, final ChunkAccess chunk, final boolean needsEdgeChecks) {
        // semear fontes: increase dirigido por fonte (so se a emissao for
        // maior que o nivel ja existente — poda quente)
        final int emittedMask = this.emittedLightMask;
        final List<BlockPos> positions = this.getSources(lightAccess, chunk);
        for (int i = 0, len = positions.size(); i < len; ++i) {
            final BlockPos pos = positions.get(i);
            final BlockState blockState = this.getBlockState(pos.getX(), pos.getY(), pos.getZ());
            final int emittedLight = blockState.getLightEmission() & emittedMask;

            if (emittedLight <= this.getLightLevel(pos.getX(), pos.getY(), pos.getZ())) {
                // alguma outra fonte ja e mais brilhante — poda, nao enfileira
                continue;
            }

            this.appendToIncreaseQueue(
                    ((pos.getX() + (pos.getZ() << 6) + (pos.getY() << (6 + 6)) + this.coordinateOffset) & ((1L << (6 + 6 + 16)) - 1))
                            | (emittedLight & 0xFL) << (6 + 6 + 16)
                            | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                            | (((ExtendedAbstractBlockState)blockState).isConditionallyFullOpaque() ? FLAG_HAS_SIDED_TRANSPARENT_BLOCKS : 0)
            );

            // a propagacao nao escreve a fonte para nos — grava direto
            this.setLightLevel(pos.getX(), pos.getY(), pos.getZ(), emittedLight);
        }

        if (needsEdgeChecks) {
            // nao precisa propagar aqui, mas reduz o custo das checagens de borda
            this.performLightIncrease(lightAccess);

            // verificar bordas dos vizinhos
            this.checkChunkEdges(lightAccess, chunk, this.minLightSection, this.maxLightSection);
        } else {
            this.propagateNeighbourLevels(lightAccess, chunk, this.minLightSection, this.maxLightSection);

            this.performLightIncrease(lightAccess);
        }
    }
}
