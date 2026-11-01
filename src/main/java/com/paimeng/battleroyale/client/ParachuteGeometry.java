package com.paimeng.battleroyale.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [BR-PARA2] 降落伞几何解析：Blockbench 导出的骨骼 JSON（逐面 UV）→ 平坦四边形数组。
 *
 * <p><b>为什么单独一个类、而且完全不碰 {@code net.minecraft.util.math}：</b>
 * 上一版这里用 {@code new Matrix4f()} 起头做骨骼/立方体变换链，但 1.18.2 的
 * {@code Matrix4f} 无参构造<b>不是单位矩阵而是全零矩阵</b>（字节码里只有
 * {@code invokespecial Object.<init>}，要单位阵得自己 {@code loadIdentity()}）。
 * 结果 3×3 部分恒为 0 → 每个面的法线变换出来长度 0 → 全部命中"退化面"判定被丢弃，
 * 日志表现就是 {@code 9 根骨骼 / 441 个立方体 / 0 个四边形}：伞永远画不出来。
 * 这里改成自带 16 个 double 的行主序 4×4（{@code m[i*4+j] = a_ij}，点变换
 * {@code p' = M·p}），语义唯一、不随 MC 版本变，而且能脱离游戏用
 * {@code tools/check_parachute} 直接对着真 JSON 跑数值。
 * 变换链与 {@code tools/preview_parachute.py} 逐式一致（那边已出图验证过 2646 面）。
 *
 * <p><b>永不丢面</b>：UV 缺失退回自动箱式展开并计数；法线退化退回原始面法线。
 * 数学再出任何问题，伞最差也是"贴图不对"，不会再变成"完全看不见"。
 */
final class ParachuteGeometry {

    static final int FLOATS_PER_QUAD_P = 12; // 4 顶点 × x y z
    static final int FLOATS_PER_QUAD_U = 8;  // 4 顶点 × u v
    static final int FLOATS_PER_QUAD_N = 3;  // 面法线

    /** 单位立方体角点表：0 = origin(min) 侧，1 = origin+size(max) 侧。 */
    private static final float[][][] FACE_CORNERS = {
            // up(+Y)
            {{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}},
            // down(-Y)
            {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
            // north(-Z)
            {{1, 1, 0}, {0, 1, 0}, {0, 0, 0}, {1, 0, 0}},
            // south(+Z)
            {{0, 1, 1}, {1, 1, 1}, {1, 0, 1}, {0, 0, 1}},
            // west(-X)
            {{0, 1, 0}, {0, 1, 1}, {0, 0, 1}, {0, 0, 0}},
            // east(+X)
            {{1, 1, 1}, {1, 1, 0}, {1, 0, 0}, {1, 0, 1}},
    };
    private static final double[][] FACE_NORMALS = {
            {0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0},
    };
    /** 面名顺序必须与 {@link #FACE_CORNERS}/{@link #FACE_NORMALS} 一致（4=west、5=east）。 */
    private static final String[] FACE_NAMES = {"up", "down", "north", "south", "west", "east"};
    /** 每个面 4 个角对应的 UV 角（左上起，顺时针配 u1v1 / u2v1 / u2v2 / u1v2）。 */
    private static final float[][] UV_CORNERS = {
            {0, 0}, {1, 0}, {1, 1}, {0, 1},
    };

    /** 顶点位置（4 顶点 × xyz，已减 attach、除以 16 换算成方块单位）。 */
    final float[] pos;
    /** 逐顶点 UV（归一化到 0~1）。 */
    final float[] uv;
    /** 每个四边形一条面法线。 */
    final float[] normal;
    final int quadCount;
    final int boneCount;
    final int cubeCount;
    /** 解析不到该面 UV、退回自动箱式展开的面数（正常应为 0）。 */
    final int fallbackFaces;

    /** 解析失败时的占位结果（渲染端直接跳过，不会抛异常）。 */
    static final ParachuteGeometry EMPTY = new ParachuteGeometry(new float[0], new float[0],
            new float[0], 0, 0, 0, 0);

    private ParachuteGeometry(float[] pos, float[] uv, float[] normal, int quadCount,
                              int boneCount, int cubeCount, int fallbackFaces) {
        this.pos = pos;
        this.uv = uv;
        this.normal = normal;
        this.quadCount = quadCount;
        this.boneCount = boneCount;
        this.cubeCount = cubeCount;
        this.fallbackFaces = fallbackFaces;
    }

    boolean isEmpty() {
        return quadCount == 0;
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析 {@code minecraft:geometry[0].bones}。
     *
     * @param root {@code parachute.json} 的根对象
     * @return 四边形数组；JSON 结构不对时抛异常，由调用方兜
     */
    static ParachuteGeometry parse(JsonObject root) {
        int texW = optInt(root, "textureWidth", 64);
        int texH = optInt(root, "textureHeight", 64);
        double ax = 0.0, ay = 0.0, az = 0.0;
        if (root.has("attach")) {
            JsonArray at = root.getAsJsonArray("attach");
            ax = at.get(0).getAsDouble();
            ay = at.get(1).getAsDouble();
            az = at.get(2).getAsDouble();
        }
        JsonArray bones = root.getAsJsonArray("minecraft:geometry")
                .get(0).getAsJsonObject()
                .getAsJsonArray("bones");

        // 骨骼名 -> “本地 -> 父级”矩阵；先登记全表再算世界矩阵，父骨骼排在子后面也不丢变换。
        Map<String, double[]> local = new HashMap<>();
        Map<String, String> parentOf = new HashMap<>();
        for (int i = 0; i < bones.size(); i++) {
            JsonObject bone = bones.get(i).getAsJsonObject();
            String name = optString(bone, "name", "bone_" + i);
            parentOf.put(name, bone.has("parent") && !bone.get("parent").isJsonNull()
                    ? bone.get("parent").getAsString() : null);
            local.put(name, pivotMatrix(doubles(bone, "pivot", 3), doubles(bone, "rotation", 3)));
        }

        List<float[]> pos = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        List<float[]> nrm = new ArrayList<>();
        int fallback = 0;
        int cubeCount = 0;
        Map<String, double[]> world = new HashMap<>();

        for (int i = 0; i < bones.size(); i++) {
            JsonObject bone = bones.get(i).getAsJsonObject();
            String name = optString(bone, "name", "bone_" + i);
            double[] boneWorld = worldOf(name, local, parentOf, world);
            JsonArray cubeArr = bone.has("cubes") ? bone.getAsJsonArray("cubes") : new JsonArray();
            for (int c = 0; c < cubeArr.size(); c++) {
                JsonObject cube = cubeArr.get(c).getAsJsonObject();
                cubeCount++;
                double[] origin = doubles(cube, "origin", 3);
                double[] size = doubles(cube, "size", 3);
                double[] m = mul(boneWorld, pivotMatrix(doubles(cube, "pivot", 3),
                        doubles(cube, "rotation", 3)));
                JsonObject uvObj = cube.has("uv") && cube.get("uv").isJsonObject()
                        ? cube.getAsJsonObject("uv") : new JsonObject();

                for (int f = 0; f < FACE_CORNERS.length; f++) {
                    float[] rect = faceUv(uvObj, FACE_NAMES[f], size, f);
                    if (rect == null) {
                        fallback++;
                        float[] w = faceWH(f, size);
                        rect = new float[]{0.0F, 0.0F, w[0], w[1]};
                    }
                    float[] p = new float[FLOATS_PER_QUAD_P];
                    float[] u = new float[FLOATS_PER_QUAD_U];
                    double[] out = new double[3];
                    for (int v = 0; v < 4; v++) {
                        float[] corner = FACE_CORNERS[f][v];
                        xform(m, origin[0] + size[0] * corner[0],
                                origin[1] + size[1] * corner[1],
                                origin[2] + size[2] * corner[2], out);
                        p[v * 3] = (float) ((out[0] - ax) / 16.0);
                        p[v * 3 + 1] = (float) ((out[1] - ay) / 16.0);
                        p[v * 3 + 2] = (float) ((out[2] - az) / 16.0);
                        float[] uc = UV_CORNERS[v];
                        u[v * 2] = (rect[0] + (rect[2] - rect[0]) * uc[0]) / texW;
                        u[v * 2 + 1] = (rect[1] + (rect[3] - rect[1]) * uc[1]) / texH;
                    }
                    // 法线：只取 3×3 旋转部分（w=0）。退化时退回原始面法线而不是丢面。
                    double[] base = FACE_NORMALS[f];
                    xformDir(m, base[0], base[1], base[2], out);
                    double len = Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
                    if (!(len > 1.0E-9)) {
                        out[0] = base[0];
                        out[1] = base[1];
                        out[2] = base[2];
                        len = 1.0;
                    }
                    float nx = (float) (out[0] / len);
                    float ny = (float) (out[1] / len);
                    float nz = (float) (out[2] / len);
                    // 绕序保险：把 4 个角摆成“沿法线看过去逆时针”，entity_cutout 才不会剔掉正面
                    if (crossDot(p, nx, ny, nz) < 0.0F) {
                        reverseQuad(p, u);
                    }
                    pos.add(p);
                    uvs.add(u);
                    nrm.add(new float[]{nx, ny, nz});
                }
            }
        }
        return new ParachuteGeometry(flatten(pos, FLOATS_PER_QUAD_P), flatten(uvs, FLOATS_PER_QUAD_U),
                flatten(nrm, FLOATS_PER_QUAD_N), pos.size(), bones.size(), cubeCount, fallback);
    }

    /** 沿父链累乘到根：{@code world = world(parent) · local}（带缓存 + 防环）。 */
    private static double[] worldOf(String bone, Map<String, double[]> local,
                                    Map<String, String> parentOf, Map<String, double[]> cache) {
        double[] hit = cache.get(bone);
        if (hit != null) {
            return hit;
        }
        double[] self = local.getOrDefault(bone, identity());
        String parent = parentOf.get(bone);
        double[] result = self;
        if (parent != null && local.containsKey(parent) && !parent.equals(bone)) {
            // 防环：临时占位，父链绕回来时直接用 identity，不会无限递归
            if (cache.containsKey("#visiting:" + bone)) {
                result = self;
            } else {
                cache.put("#visiting:" + bone, self);
                result = mul(worldOf(parent, local, parentOf, cache), self);
                cache.remove("#visiting:" + bone);
            }
        }
        cache.put(bone, result);
        return result;
    }

    // ------------------------------------------------------------------
    // 行主序 4×4（m[i*4+j] = a_ij，p' = M·p）
    // ------------------------------------------------------------------

    private static double[] identity() {
        return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++) {
            int ri = i * 4;
            for (int j = 0; j < 4; j++) {
                r[ri + j] = a[ri] * b[j] + a[ri + 1] * b[4 + j]
                        + a[ri + 2] * b[8 + j] + a[ri + 3] * b[12 + j];
            }
        }
        return r;
    }

    private static double[] rotX(double rad) {
        double c = Math.cos(rad), s = Math.sin(rad);
        return new double[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1};
    }

    private static double[] rotY(double rad) {
        double c = Math.cos(rad), s = Math.sin(rad);
        return new double[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1};
    }

    private static double[] rotZ(double rad) {
        double c = Math.cos(rad), s = Math.sin(rad);
        return new double[]{c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    /** Blockbench 欧拉角顺序：{@code R = Rz · Ry · Rx}（角度制入参）。 */
    private static double[] rotation(double[] deg) {
        return mul(rotZ(Math.toRadians(deg[2])),
                mul(rotY(Math.toRadians(deg[1])), rotX(Math.toRadians(deg[0]))));
    }

    /** {@code T(pivot) · R · T(-pivot)}，合并成“R + 平移列 = pivot − R·pivot”。 */
    private static double[] pivotMatrix(double[] pivot, double[] deg) {
        double[] r = rotation(deg);
        double[] m = r.clone();
        for (int i = 0; i < 3; i++) {
            int ri = i * 4;
            m[ri + 3] = pivot[i] - (r[ri] * pivot[0] + r[ri + 1] * pivot[1] + r[ri + 2] * pivot[2]);
        }
        return m;
    }

    private static void xform(double[] m, double x, double y, double z, double[] out) {
        out[0] = m[0] * x + m[1] * y + m[2] * z + m[3];
        out[1] = m[4] * x + m[5] * y + m[6] * z + m[7];
        out[2] = m[8] * x + m[9] * y + m[10] * z + m[11];
    }

    private static void xformDir(double[] m, double x, double y, double z, double[] out) {
        out[0] = m[0] * x + m[1] * y + m[2] * z;
        out[1] = m[4] * x + m[5] * y + m[6] * z;
        out[2] = m[8] * x + m[9] * y + m[10] * z;
    }

    // ------------------------------------------------------------------
    // UV / 杂项
    // ------------------------------------------------------------------

    /**
     * 取某个面的 UV 矩形 {@code [u1,v1,u2,v2]}（像素）。兼容逐面四元组
     * {@code "north":[24,0,36,12]}、逐面二元组 {@code "north":[24,0]}（按面自然尺寸
     * 展开）、以及 modded 写法的 {@code {"uv":[24,0],"textureWidth":12}}。
     *
     * @return 该面 UV；完全解析不到时返回 {@code null}，由调用方退回自动箱式展开
     */
    private static float[] faceUv(JsonObject uvObj, String face, double[] size, int index) {
        if (!uvObj.has(face) || uvObj.get(face).isJsonNull()) {
            return null;
        }
        var el = uvObj.get(face);
        if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            float[] w = faceWH(index, size);
            if (a.size() >= 4) {
                return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(),
                        a.get(2).getAsFloat(), a.get(3).getAsFloat()};
            }
            if (a.size() >= 2) {
                float u = a.get(0).getAsFloat();
                float v = a.get(1).getAsFloat();
                return new float[]{u, v, u + w[0], v + w[1]};
            }
            return null;
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("uv")) {
                JsonArray a = o.getAsJsonArray("uv");
                float u = a.get(0).getAsFloat();
                float v = a.get(1).getAsFloat();
                float[] w = faceWH(index, size);
                float sw = o.has("textureWidth") ? o.get("textureWidth").getAsFloat() : w[0];
                float sh = o.has("textureHeight") ? o.get("textureHeight").getAsFloat() : w[1];
                return new float[]{u, v, u + sw, v + sh};
            }
        }
        return null;
    }

    /** 各面在贴图上的自然尺寸（up/down 取 x·z，north/south 取 x·y，west/east 取 z·y）。 */
    private static float[] faceWH(int index, double[] size) {
        return switch (index) {
            case 0, 1 -> new float[]{(float) size[0], (float) size[2]};
            case 2, 3 -> new float[]{(float) size[0], (float) size[1]};
            default -> new float[]{(float) size[2], (float) size[1]};
        };
    }

    private static float crossDot(float[] p, float nx, float ny, float nz) {
        float ax = p[3] - p[0], ay = p[4] - p[1], az = p[5] - p[2];
        float bx = p[6] - p[0], by = p[7] - p[1], bz = p[8] - p[2];
        float cx = ay * bz - az * by;
        float cy = az * bx - ax * bz;
        float cz = ax * by - ay * bx;
        return cx * nx + cy * ny + cz * nz;
    }

    private static void reverseQuad(float[] p, float[] u) {
        for (int i = 0; i < 2; i++) {
            int a = i * 3, b = (3 - i) * 3;
            for (int k = 0; k < 3; k++) {
                float t = p[a + k];
                p[a + k] = p[b + k];
                p[b + k] = t;
            }
            int au = i * 2, bu = (3 - i) * 2;
            for (int k = 0; k < 2; k++) {
                float t = u[au + k];
                u[au + k] = u[bu + k];
                u[bu + k] = t;
            }
        }
    }

    private static float[] flatten(List<float[]> src, int stride) {
        float[] out = new float[src.size() * stride];
        for (int i = 0; i < src.size(); i++) {
            System.arraycopy(src.get(i), 0, out, i * stride, stride);
        }
        return out;
    }

    private static double[] doubles(JsonObject o, String key, int n) {
        double[] out = new double[n];
        if (o.has(key) && o.get(key).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(key);
            for (int i = 0; i < n && i < a.size(); i++) {
                out[i] = a.get(i).getAsDouble();
            }
        }
        return out;
    }

    private static int optInt(JsonObject o, String key, int def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : def;
    }

    private static String optString(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }
}
