#version 320 es
// ===========================================================================
// infinitium_core.glsl — INFINITIUM LIGHT ENGINE — Shader Core (ESSL 3.20)
// Clustered Forward Shading + Beer-Lambert espectral para PowerVR GE8320
//
// v2.1 — correcoes sobre o nucleo original:
//   1) O nucleo original RECEBIA clusterCoord e o IGNORAVA — iterava todas as
//      64 luzes, ou seja, NAO era clustered. Agora o indice por cluster
//      (uClusterRange + uLightIdx) e realmente consultado: O(luzes do cluster).
//   2) precision highp -> mediump: a GE8320 executa FP16 com 2x de throughput
//      ALU (confirmado: MEDIUM_FLOAT = 14 14 10 no driver real).
//   3) Branch de teste de distancia substituido por step() -> sem desvio
//      condicional na ALU (importante no TBDR: evita divergencia entre quads).
//   4) Layout std140 documentado e verificado contra o driver real
//      (MAX_UNIFORM_BLOCK_SIZE = 134217728 bytes = 128 MB nessa GPU).
//   5) Ambos os caminhos (forward e clustered) usam EXATAMENTE a mesma funcao
//      de avaliacao de luz, na mesma ordem -> resultado identico bit a bit
//      (validado por comparacao de pixels no teste infinitium_gpu_bench.cpp).
//
// Integracao (MobileGlues / Sodium / pipeline do Minecraft):
//   - Substitua o bloco de iluminacao do fragment shader por:
//         vec3 direct = infinitiumShadeClustered(worldPos, normal);
//   - Faca upload da cena por frame via UBO binding = 1 (ver layout abaixo).
//   - Para o caminho de referencia (sem culling): infinitiumShadeForward().
// ===========================================================================

precision mediump float;
precision mediump int;

// --- Configuracao da grade (ajuste junto com o build da CPU) ---------------
// Mundo de referencia: 64 x 32 x 64 voxels -> 16 x 8 x 16 clusters de 4^3
#define INF_MAX_LIGHTS  256
#define INF_CLUSTER_X   16
#define INF_CLUSTER_Y   8
#define INF_CLUSTER_Z   16
#define INF_MAX_IDX     32768            // slots na lista de indices (4/uvec4)
#define INF_WORLD_SIZE  vec3(64.0, 32.0, 64.0)
#define INF_CLUSTER_SIZE (INF_WORLD_SIZE / vec3(float(INF_CLUSTER_X), \
                                                float(INF_CLUSTER_Y), \
                                                float(INF_CLUSTER_Z)))

// --- Cena (std140, binding 1) ----------------------------------------------
// Layout verificado byte a byte em C++ (infinitium_gpu_bench.cpp):
//   [0      .. 16    ) ivec4  uCfg        (x = luzes ativas)
//   [16     .. 8208  ) InfLight uLights[256]   (32 B cada)
//   [8208   .. 40976 ) uvec2   uClusterRange[2048] (16 B cada: x=offset y=contador)
//   [40976  .. fim   ) uvec4   uLightIdx[]     (indices empacotados, 4 por word)
struct InfLight {
    vec4 posRadius;   // xyz = posicao no mundo, w = raio maximo
    vec4 colorInten;  // rgb = cor (linear), w = intensidade base
};

layout(std140, binding = 1) uniform InfScene {
    ivec4     uCfg;
    InfLight  uLights[INF_MAX_LIGHTS];
    uvec2     uClusterRange[INF_CLUSTER_X * INF_CLUSTER_Y * INF_CLUSTER_Z];
    uvec4     uLightIdx[INF_MAX_IDX / 4];
};

// Voxel grid (Rethinking Voxels): sigma de absorcao por voxel, usado pelo
// caminho de meio participante. Nao e tocado no shading direto.
layout(binding = 0) uniform mediump sampler3D uVoxelGrid;

// --- Utilidades -------------------------------------------------------------
int infClusterIndex(mediump vec3 worldPos) {
    mediump vec3 cf = worldPos / INF_CLUSTER_SIZE;
    ivec3 c = clamp(ivec3(floor(cf)), ivec3(0),
                    ivec3(INF_CLUSTER_X - 1, INF_CLUSTER_Y - 1, INF_CLUSTER_Z - 1));
    return (c.y * INF_CLUSTER_Z + c.z) * INF_CLUSTER_X + c.x;
}

// Avaliacao de UMA luz — identica nos dois caminhos (mesma aritmetica, mesma
// ordem de operacoes => forward e clustered produzem o mesmo pixel).
//   - step() em vez de if: sem desvio condicional
//   - inversesqrt: 1 instrucao no PowerVR, evita sqrt + div
//   - atenuacao quadratica (1 - d/r)^2, igual a do benchmark de CPU
mediump vec3 infinitiumEvalLight(InfLight L, mediump vec3 worldPos, mediump vec3 N) {
    mediump vec3  d    = L.posRadius.xyz - worldPos;
    mediump float r    = max(L.posRadius.w, 1e-4);
    mediump float d2   = dot(d, d);
    mediump float hit  = step(d2, r * r);              // 1.0 se dentro do raio
    mediump float dist = sqrt(d2);
    mediump vec3  Ldir = d * inversesqrt(max(d2, 1e-8));
    mediump float ndotl = max(dot(Ldir, N), 0.0);
    mediump float atten = 1.0 - dist / r;
    atten *= atten;
    return L.colorInten.rgb * (L.colorInten.w * ndotl * atten * hit);
}

// --- Caminho 1: Vanilla Forward (O(N) — referencia) -------------------------
mediump vec3 infinitiumShadeForward(mediump vec3 worldPos, mediump vec3 N) {
    mediump vec3 acc = vec3(0.0);
    int n = uCfg.x;
    for (int i = 0; i < n; ++i) {
        acc += infinitiumEvalLight(uLights[i], worldPos, N);
    }
    return acc;
}

// --- Caminho 2: Infinitium Clustered (O(luzes do cluster)) ------------------
// Consulta o indice do cluster e so avalia as luzes que o alcancam.
mediump vec3 infinitiumShadeClustered(mediump vec3 worldPos, mediump vec3 N) {
    int ci = infClusterIndex(worldPos);
    uvec2 range = uClusterRange[ci];
    uint  last   = range.x + range.y;
    mediump vec3 acc = vec3(0.0);
    for (uint i = range.x; i < last; ++i) {
        uint li = uLightIdx[int(i >> 2)][int(i & 3u)];
        acc += infinitiumEvalLight(uLights[int(li)], worldPos, N);
    }
    return acc;
}

// --- Meio participante: transmissao espectral Beer-Lambert ------------------
// T = exp(-sigma * d) por canal — usado para vidro colorido / neblina.
// Em mediump (FP16) a GE8320 calcula exp() no caminho rapido.
mediump vec3 infinitiumBeerLambert(mediump vec3 color, mediump vec3 sigmaAbs,
                                   mediump float dist) {
    return color * exp(-sigmaAbs * dist);
}

// Amostra o sigma do voxel grid 3D e aplica a transmitancia ate o ponto.
mediump vec3 infinitiumMediumTransmission(mediump vec3 color, mediump vec3 from,
                                          mediump vec3 to) {
    mediump vec3  mid  = (from + to) * 0.5;
    mediump vec3  uvw  = mid / INF_WORLD_SIZE;
    mediump vec3  sig  = texture(uVoxelGrid, uvw).rgb;
    mediump float dist = length(to - from);
    return infinitiumBeerLambert(color, sig, dist);
}
