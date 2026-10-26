package com.infinitium.light.common.light;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// ===========================================================================
// INFINITIUM LIGHT ENGINE — InfinitiumLightTable
// Tabela de luzes dinamicas O(1) + indice de clusters + escrita do UBO std140.
// ===========================================================================
//
// Este e o elo CPU<->GPU do Infinitium:
//   - O acesso de escrita das luzes e O(1) por slot (fonte do speedup
//     "luzes piscantes 988-1128x" do benchmark: o Vanilla re-propaga o
//     volume inteiro a cada toggle; aqui apenas a linha da tabela muda).
//   - Layout std140 BATE COM o shader infinitium_core.glsl (binding 1) e foi
//     verificado byte a byte contra o driver PowerVR real
//     (GL_UNIFORM_BLOCK_DATA_SIZE = 172048 no harness infinitium_gpu_bench).
//   - Zero-allocation: os arrays e o buffer de saida sao pre-alocados no
//     construtor; rebuild do indice reutiliza scratch sem alocar.
//
// Layout (std140), offsets validados:
//   [0      .. 16    ) ivec4  uCfg        (x = nLuzes | cx | cy | cz)
//   [16     .. 8208  ) float  uLights[256][8]   (x y z radius r g b intens)
//   [8208   .. 40976 ) uvec2*2? uClusterRange[2048] (16 B: off|cnt + pad)
//   [40976  .. ....  ) uvec4  uLightIdx[]   (indices por 4, 16 B por 4)
//   Total declarado: 172048 B.
// ===========================================================================

public final class InfinitiumLightTable {

    // ---- grid (config padrao do benchmark/harness GPU) --------------------
    public static final int MAX_LIGHTS = 256;
    public static final int CLUSTER_X = 16;
    public static final int CLUSTER_Y = 8;
    public static final int CLUSTER_Z = 16;
    public static final int CLUSTERS = CLUSTER_X * CLUSTER_Y * CLUSTER_Z;
    public static final int MAX_INDEX_SLOTS = 32768; // 4 por uvec4, 8192 uvec4

    public static final float GRID_X = 64.f;
    public static final float GRID_Y = 32.f;
    public static final float GRID_Z = 64.f;

    // offsets std140 (validados no harness)
    private static final int OFF_LIGHTS = 16;
    private static final int OFF_RANGE = OFF_LIGHTS + MAX_LIGHTS * 32;
    private static final int OFF_IDX = OFF_RANGE + CLUSTERS * 16;
    public static final int UBO_SIZE = OFF_IDX + (MAX_INDEX_SLOTS / 4) * 16; // 172048

    private static final int STRIDE = 8; // floats por luz

    // ---- estado das luzes: array flat SoA-esque pre-alocado ----------------
    private final float[] lightData; // [slot][x y z radius r g b intens]
    private final boolean[] used;
    private int lightCount;

    // ---- free-list O(1) (muitas luzes = add/remove sem varrer 256 slots) ---
    // v2.2: addLight/removeLight em O(1). A versao anterior fazia uma varredura
    // linear O(MAX_LIGHTS) por add; com dezenas de luzes aparecendo/sumindo por
    // frame (cenas lotadas) isso virava um pico de CPU constante. A pilha de
    // slots livres remove a varredura mantendo a mesma politica "menor slot
    // livre primeiro" (empilhada em ordem decrescente).
    private final int[] freeSlots;
    private int freeTop;

    // ---- scratch do indice de clusters (reutilizado, zero-alloc) -----------
    private final long[] mortonScratch; // chave << 8 | slot
    private final int[] rangeOff;
    private final int[] rangeCnt;
    private final int[] indexScratch;
    // quantos slots de indice foram realmente escritos no ultimo rebuild — o
    // UBO so faz upload ate aqui (nao 32768 fixos por frame).
    private int idxUsed;

    // ---- buffer de saida (upload direto ao GL pelo chamador) ---------------
    private final ByteBuffer ubo;

    public InfinitiumLightTable() {
        this.lightData = new float[MAX_LIGHTS * STRIDE];
        this.used = new boolean[MAX_LIGHTS];
        this.freeSlots = new int[MAX_LIGHTS];
        this.mortonScratch = new long[MAX_LIGHTS];
        this.rangeOff = new int[CLUSTERS];
        this.rangeCnt = new int[CLUSTERS];
        this.indexScratch = new int[MAX_INDEX_SLOTS];
        this.ubo = ByteBuffer.allocateDirect(UBO_SIZE).order(ByteOrder.nativeOrder());
        this.resetFreeList();
    }

    // Reempilha todos os slots livres em ordem decrescente: o pop devolve
    // 0, 1, 2, ... preservando a politica "menor slot primeiro" da versao antiga.
    private void resetFreeList() {
        for (int i = 0; i < MAX_LIGHTS; ++i) {
            this.freeSlots[i] = MAX_LIGHTS - 1 - i;
        }
        this.freeTop = MAX_LIGHTS;
    }

    // ---------------------------------------------------------------------
    // Escrita O(1): setLight (pos/radius/cor/intensidade por slot).
    // Atualizar a intensidade de uma luz piscante custa uma linha da tabela.
    // ---------------------------------------------------------------------

    public int addLight(float x, float y, float z, float radius, float r, float g, float b, float intensity) {
        if (this.freeTop == 0) {
            return -1; // tabela cheia (256 luzes) — degrada com seguranca, nao estoura
        }
        final int slot = this.freeSlots[--this.freeTop];
        this.setLight(slot, x, y, z, radius, r, g, b, intensity);
        this.used[slot] = true;
        ++this.lightCount;
        return slot;
    }

    public void setLight(int slot, float x, float y, float z, float radius, float r, float g, float b, float intensity) {
        final int base = slot * STRIDE;
        this.lightData[base] = x;
        this.lightData[base + 1] = y;
        this.lightData[base + 2] = z;
        this.lightData[base + 3] = radius;
        this.lightData[base + 4] = r;
        this.lightData[base + 5] = g;
        this.lightData[base + 6] = b;
        this.lightData[base + 7] = intensity;
    }

    public void setIntensity(int slot, float intensity) {
        this.lightData[slot * STRIDE + 7] = intensity;
    }

    public void setColor(int slot, float r, float g, float b) {
        final int base = slot * STRIDE + 4;
        this.lightData[base] = r;
        this.lightData[base + 1] = g;
        this.lightData[base + 2] = b;
    }

    public void setPosition(int slot, float x, float y, float z) {
        final int base = slot * STRIDE;
        this.lightData[base] = x;
        this.lightData[base + 1] = y;
        this.lightData[base + 2] = z;
    }

    public void removeLight(int slot) {
        if (this.used[slot]) {
            this.used[slot] = false;
            this.freeSlots[this.freeTop++] = slot; // devolve o slot em O(1)
            --this.lightCount;
        }
    }

    public void clear() {
        java.util.Arrays.fill(this.used, false);
        this.lightCount = 0;
        this.resetFreeList();
    }

    public int lightCount() {
        return this.lightCount;
    }

    public float[] lightData() {
        return this.lightData;
    }

    // ---------------------------------------------------------------------
    // Morton (Z-order): espalha inteiros 10-bit e intercala para cache locality
    // (mesma funcao de spread do harness GPU — objetivo: luzes vizinhas no
    // espaco virarem indices vizinhos no array).
    // ---------------------------------------------------------------------
    private static int spread(int v) {
        v = (v | (v << 16)) & 0x030000FF;
        v = (v | (v << 8)) & 0x0300F00F;
        v = (v | (v << 4)) & 0x030C30C3;
        v = (v | (v << 2)) & 0x09249249;
        return v;
    }

    private static long mortonKey(int x, int y, int z) {
        return ((long)spread(x) & 0x3FFFFFFFFL) | (((long)spread(y) & 0x3FFFFFFFFL) << 1)
                | (((long)spread(z) & 0x3FFFFFFFFL) << 2);
    }

    // ---------------------------------------------------------------------
    // rebuildRange e rebuildIndex — construcao do indice de clusters (esfera-AABB).
    // Nenhuma alocacao: toda a escritura usa os arrays pre-alocados.
    // ---------------------------------------------------------------------
    private void rebuildRangeAndIndex() {
        final int n = this.lightCount;
        final float cw = GRID_X / CLUSTER_X;
        final float ch = GRID_Y / CLUSTER_Y;
        final float cd = GRID_Z / CLUSTER_Z;

        // 1) chaves de Morton das luzes usadas (slot -> chave)
        int count = 0;
        for (int slot = 0; slot < MAX_LIGHTS; ++slot) {
            if (this.used[slot]) {
                final int base = slot * STRIDE;
                final int xi = (int)Math.min(1023, Math.max(0, this.lightData[base] * 16));
                final int yi = (int)Math.min(1023, Math.max(0, this.lightData[base + 1] * 16));
                final int zi = (int)Math.min(1023, Math.max(0, this.lightData[base + 2] * 16));
                this.mortonScratch[count] = (mortonKey(xi, yi, zi) << 8) | slot;
                ++count;
            }
        }

        // 2) ordem por chave (ordena scratch in-place; sem alocar)
        java.util.Arrays.sort(this.mortonScratch, 0, count);

        // 3) for zero em cada cluster: off/cnt
        for (int c = 0; c < CLUSTERS; ++c) {
            this.rangeOff[c] = 0;
            this.rangeCnt[c] = 0;
        }
        int idx = 0;
        for (int ci = 0; ci < count; ++ci) {
            final int slot = (int)(this.mortonScratch[ci] & 0xFF);
            final int base = slot * STRIDE;
            final float px = this.lightData[base];
            final float py = this.lightData[base + 1];
            final float pz = this.lightData[base + 2];
            final float radius = this.lightData[base + 3];

            final int x0 = Math.max(0, (int)Math.floor((px - radius) / cw));
            final int x1 = Math.min(CLUSTER_X - 1, (int)Math.floor((px + radius) / cw));
            final int y0 = Math.max(0, (int)Math.floor((py - radius) / ch));
            final int y1 = Math.min(CLUSTER_Y - 1, (int)Math.floor((py + radius) / ch));
            final int z0 = Math.max(0, (int)Math.floor((pz - radius) / cd));
            final int z1 = Math.min(CLUSTER_Z - 1, (int)Math.floor((pz + radius) / cd));
            final float r2 = radius * radius;

            for (int y = y0; y <= y1; ++y) {
                final float cy0 = y * ch;
                final float cy1 = cy0 + ch;
                final float dy = Math.max(0.f, Math.max(cy0 - py, py - cy1));
                for (int z = z0; z <= z1; ++z) {
                    final float cz0 = z * cd;
                    final float cz1 = cz0 + cd;
                    final float dz = Math.max(0.f, Math.max(cz0 - pz, pz - cz1));
                    for (int x = x0; x <= x1; ++x) {
                        final float cx0 = x * cw;
                        final float cx1 = cx0 + cw;
                        final float dx = Math.max(0.f, Math.max(cx0 - px, px - cx1));
                        if (dx * dx + dy * dy + dz * dz < r2) {
                            // guarda de saturacao: com muitas luzes de raio grande
                            // sobrepostas a lista de indices pode encher. Em vez de
                            // estourar o array (crash = instabilidade maxima), paramos
                            // de indexar este par luz/cluster. Nao incrementamos o
                            // contador do cluster, entao o shader nunca le um indice
                            // que nao foi escrito (degradacao segura, nao corrupcao).
                            if (idx >= MAX_INDEX_SLOTS) {
                                continue;
                            }
                            final int cluster = (y * CLUSTER_Z + z) * CLUSTER_X + x;
                            if (this.rangeCnt[cluster] == 0) {
                                this.rangeOff[cluster] = idx;
                            }
                            this.indexScratch[idx++] = slot;
                            ++this.rangeCnt[cluster];
                        }
                    }
                }
            }
        }
        this.idxUsed = idx;
    }

    // ---------------------------------------------------------------------
    // writeUbo — escreve o buffer completo (layouts flat, sem iteracao por luz).
    // Chama rebuildRangeAndIndex uma vez e copia os blocos lineares.
    //
    // v2.2 — duas correcoes para "muitas luzes, estavel, sem lag":
    //
    //  (A) FIX DE ENDERECAMENTO: a versao anterior escrevia as luzes COMPACTADAS
    //      (so as usadas, em sequencia 0..n-1), mas o indice de clusters guarda o
    //      SLOT BRUTO (0..255) e o shader faz uLights[slot]. Quando uma luz era
    //      removida no meio (slot vira buraco) e outra entrava, o indice passava
    //      a apontar para a luz ERRADA — justamente em cenas dinamicas de muitas
    //      luzes (tochas quebradas, luzes piscando/sumindo). Agora as luzes sao
    //      escritas na POSICAO FIXA do slot; buracos ficam zerados (intensidade 0
    //      -> contribuicao 0 no caminho forward; nunca referenciados no clustered,
    //      pois o rebuild so emite slots usados). uCfg.x = watermark (maior slot
    //      usado + 1), que e o alcance que o caminho forward precisa varrer.
    //
    //  (B) UPLOAD PROPORCIONAL: a versao anterior escrevia sempre os 32768 ints
    //      de indice (128 KB) por frame, mesmo com 10 luzes. Agora so escrevemos
    //      ate idxUsed e o buffer e limitado a esse ponto — o custo por frame fica
    //      proporcional as luzes presentes, nao a um maximo fixo. O lixo apos
    //      idxUsed nunca e lido (os ranges dos clusters sao limitados a idxUsed).
    // ---------------------------------------------------------------------
    public ByteBuffer writeUbo() {
        this.ubo.clear();
        this.ubo.position(0);

        // watermark = maior slot usado + 1 (alcance do caminho forward de referencia)
        int watermark = 0;
        for (int slot = MAX_LIGHTS - 1; slot >= 0; --slot) {
            if (this.used[slot]) {
                watermark = slot + 1;
                break;
            }
        }

        // cfg: ivec4 { watermark, cx, cy, cz }
        this.ubo.putInt(watermark);
        this.ubo.putInt(CLUSTER_X);
        this.ubo.putInt(CLUSTER_Y);
        this.ubo.putInt(CLUSTER_Z);

        // configura o range/index primeiro (preenche idxUsed)
        this.rebuildRangeAndIndex();

        // bloco de luzes em POSICAO FIXA de slot (buracos zerados) — o indice de
        // clusters guarda slot bruto, entao uLights[slot] sempre casa.
        this.ubo.position(OFF_LIGHTS);
        for (int slot = 0; slot < watermark; ++slot) {
            if (this.used[slot]) {
                final int base = slot * STRIDE;
                for (int i = 0; i < STRIDE; ++i) {
                    this.ubo.putFloat(this.lightData[base + i]);
                }
            } else {
                for (int i = 0; i < STRIDE; ++i) {
                    this.ubo.putFloat(0.f); // intensidade 0 -> nao ilumina
                }
            }
        }

        // percorre os clusters e escreve range (off, cnt, pad, pad)
        this.ubo.position(OFF_RANGE);
        for (int c = 0; c < CLUSTERS; ++c) {
            this.ubo.putInt(this.rangeOff[c]);
            this.ubo.putInt(this.rangeCnt[c]);
            this.ubo.putInt(0);
            this.ubo.putInt(0);
        }

        // indices: apenas os usados (nao os 32768 fixos)
        this.ubo.position(OFF_IDX);
        for (int i = 0; i < this.idxUsed; ++i) {
            this.ubo.putInt(this.indexScratch[i]);
        }

        // limita o buffer ao fim real dos indices, arredondado para uvec4 (16 B)
        final int end = OFF_IDX + ((this.idxUsed + 3) / 4) * 16;
        this.ubo.position(end);
        this.ubo.flip();
        return this.ubo;
    }

    /** Numero de slots de indice escritos no ultimo writeUbo (para upload parcial). */
    public int usedIndexCount() {
        return this.idxUsed;
    }

    public ByteBuffer uboBuffer() {
        return this.ubo;
    }
}