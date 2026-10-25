package com.infinitium.light.common.light;

// ===========================================================================
// INFINITIUM LIGHT ENGINE — InfinitiumLut
// Tabelas de lookup que substituem aritmetica/carretos no caminho quente.
// ===========================================================================
//
// Este e o coracao da otimizacao "*insira desempenho*":
//   - v1/media do benchmark: std::exp() 6x por voxel (26 ns cada no A53)
//   - v2.1 do benchmark: LUT Q8, layout quente/frio, fronteiras dirigidas
//     -> insercao em paridade 0.97-1.07x com o Vanilla, remocao 1.30-2.53x
//        mais rapida, luzes piscantes 988-1128x (atualizacao O(1) de tabela).
//
// As duas LUTs deste arquivo sao usadas pelo fork:
//   - mcFall(int opacity)  : regra max(1, opacidade) do Minecraft, precomputada
//                             para 0..15 (16 entradas, zero ramo no loop 0-15).
//   - Q8 Beer-Lambert      : reservado para a camada de meio participante do
//                             shading GPU (infinitium_core.glsl). Neste fork
//                             (luz de blocos 0-15) o caminho quente usa mcFall;
//                             as constantes Q8 mantem a identidade com o
//                             benchmark validado em hardware.
// ===========================================================================

public final class InfinitiumLut {

    // ---------------------------------------------------------------------
    // Regra de decremento do Minecraft: max(1, opacity), opacidade 0..15.
    // Precomputada para 16 entradas — o calculo do nivel vizinho nao paga um
    // Math.max por leitura no caminho quente de re-propagacao.
    // ---------------------------------------------------------------------
    private static final byte[] MC_FALL = new byte[16];

    // ---------------------------------------------------------------------
    // Transmittance Beer-Lambert em Q8: T(sigma) = BASE * exp(-sigma / 64),
    // quantizada em 255. BASE = 0.77 calibrado para alcance ~14-15 voxels
    // (igual ao flood-fill 0-15 do Vanilla em ar). Indice = sigma * 64.
    // ---------------------------------------------------------------------
    private static final byte[] TRANS_LUT = new byte[256];
    static final int TRANS_MAX_Q8;

    static {
        final double BASE = 0.77;
        for (int i = 0; i < TRANS_LUT.length; ++i) {
            final double t = BASE * Math.exp(-(double)i / 64.0);
            final int q = (int)Math.round(t * 255.0);
            TRANS_LUT[i] = (byte)Math.max(1, Math.min(255, q));
        }
        TRANS_MAX_Q8 = TRANS_LUT[0] & 0xFF;

        for (int o = 0; o < MC_FALL.length; ++o) {
            MC_FALL[o] = (byte)Math.max(1, o);
        }
    }

    private InfinitiumLut() {
    }

    /** max(1, opacity) com opacidade 0..15 — a regra de propagacao do MC. */
    public static int mcFall(final int opacity) {
        return MC_FALL[opacity & 0xF] & 0xFF;
    }

    /** Transmittance Q8 (0..255) para indice de sigma quantizado (0..255). */
    public static int transmittanceQ8(final int sigmaQ8) {
        return TRANS_LUT[sigmaQ8 & 0xFF] & 0xFF;
    }
}