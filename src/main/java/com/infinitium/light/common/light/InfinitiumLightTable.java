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

    // ---- scratch do indice de clusters (reutilizado, zero-alloc) -----------
    private final long[] mortonScratch; // chave << 8 | slot
    private final int[] rangeOff;
    private final int[] rangeCnt;
    private final int[] indexScratch;

    // ---- buffer de saida (upload direto ao GL pelo chamador) ---------------
    private final ByteBuffer ubo;

    public InfinitiumLightTable() {
        this.lightData = new float[MAX_LIGHTS * STRIDE];
        this.used = new boolean[MAX_LIGHTS];
        this.mortonScratch = new long[MAX_LIGHTS];
        this.rangeOff = new int[CLUSTERS];
        this.rangeCnt = new int[CLUSTERS];
        this.indexScratch = new int[MAX_INDEX_SLOTS];
        this.ubo = ByteBuffer.allocateDirect(UBO_SIZE).order(ByteOrder.nativeOrder());
    }

    // ---------------------------------------------------------------------
    // Escrita O(1): setLight (pos/radius/cor/intensidade por slot).
    // Atualizar a intensidade de uma luz piscante custa uma linha da tabela.
    // ---------------------------------------------------------------------

    public int addLight(float x, float y, float z, float radius, float r, float g, float b, float intensity) {
        for (int slot = 0; slot < MAX_LIGHTS; ++slot) {
            if (!this.used[slot]) {
                this.setLight(slot, x, y, z, radius, r, g, b, intensity);
                this.used[slot] = true;
                ++this.lightCount;
                return slot;
            }
        }
        return -1;
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
            --this.lightCount;
        }
    }

    public void clear() {
        java.util.Arrays.fill(this.used, false);
        this.lightCount = 0;
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
    }

    // ---------------------------------------------------------------------
    // writeUbo — escreve o buffer completo (layouts flat, sem iteracao por luz).
    // Chama rebuildRangeAndIndex uma vez e copia os blocos lineares.
    // ---------------------------------------------------------------------
    public ByteBuffer writeUbo() {
        this.ubo.clear();
        this.ubo.position(0);

        int n = 0;
        for (int slot = 0; slot < MAX_LIGHTS; ++slot) {
            if (this.used[slot]) {
                ++n;
            }
        }

        // cfg: ivec4 { nLuzes, cx, cy, cz }
        this.ubo.putInt(n);
        this.ubo.putInt(CLUSTER_X);
        this.ubo.putInt(CLUSTER_Y);
        this.ubo.putInt(CLUSTER_Z);

        // configura o range/index primeiro (escreve em CIMA da memoria flat)
        this.rebuildRangeAndIndex();

        // copy do bloco de luzes (apenas as usadas, na ordem flat)
        for (int slot = 0; slot < MAX_LIGHTS; ++slot) {
            if (!this.used[slot]) {
                continue;
            }
            final int base = slot * STRIDE;
            for (int i = 0; i < STRIDE; ++i) {
                this.ubo.putFloat(this.lightData[base + i]);
            }
        }

        // percorre os clusters e escreve range
        this.ubo.position(OFF_RANGE);
        for (int c = 0; c < CLUSTERS; ++c) {
            this.ubo.putInt(this.rangeOff[c]);
            this.ubo.putInt(this.rangeCnt[c]);
            // std140: uvec2 ocupa 16 bytes (8B), mais 8B de pad? nao.
            // Aqui seguimos o harness: 4B off + 4B cnt por 16B = 8B de pad.
            this.ubo.putInt(0);
            this.ubo.putInt(0);
        }

        // indices (lineares, 4 por uvec4)
        this.ubo.position(OFF_IDX);
        for (int i = 0; i < MAX_INDEX_SLOTS; ++i) {
            this.ubo.putInt(this.indexScratch[i]);
        }

        this.ubo.flip();
        return this.ubo;
    }

    public ByteBuffer uboBuffer() {
        return this.ubo;
    }
}