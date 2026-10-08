package net.irisshaders.iris.metal;

import mcopt.metal.*;
import org.lwjgl.system.MemoryUtil;
import java.util.Map;

/** Actual GPU readback: viewport, std140 matrix/scalar upload, dynamic comparison sampler and PSO cache. */
public final class MetalRuntimeCheck {
    public static void main(String[] args) throws Exception {
        long ctx = MetalBridge.createHeadlessContext();
        long enc = MetalBridge.createHeadlessEncoder(ctx);
        long color = MetalBridge.newTexture(ctx, 70, 4, 4, 1, 5);
        long depth = MetalBridge.newTexture(ctx, 252, 4, 4, 1, 5);
        long readback = MetalBridge.newBuffer(ctx, 64);
        long state = MetalBridge.depthStateNew(ctx, 7, false);
        long sampler = MetalBridge.comparisonSamplerNew(ctx, 0, 0, 0, 0, 0, 0, 3);
        String vertex = """
            #version 330 core
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
        String fragment = """
            #version 330 core
            uniform float red;
            uniform mat3 matrix;
            uniform sampler2DShadow shadowtex0;
            layout(location = 0) out vec4 color;
            void main() { color = vec4(red, matrix[2][1], texture(shadowtex0, vec3(0.5, 0.5, 0.4)), 1); }
            """;
        try (var program = new PackProgram(ctx, "runtime-check", vertex, fragment, null, Map.of())) {
            var compiled = program.compiled();
            var fields = compiled.fields().values().toArray(new PackCompiler.Field[0]);
            var uniforms = new PackUniforms(fields, compiled.uniformSize());
            try {
                for (int i = 0; i < fields.length; i++) {
                    if (fields[i].name().equals("red")) uniforms.floats(i, 0.25f);
                    if (fields[i].name().equals("matrix")) uniforms.matrix(i, new float[] {0,0,0,0,0,0,0,0.5f,0});
                }
                int[] descriptor = {0, 0, 1, 70, 15, 0, 1, 0, 0, 1, 0, 0, 0, 3};
                long pso = program.pipeline(descriptor);
                descriptor[4] = 0; // Must not mutate the cache key already stored.
                int[] original = {0, 0, 1, 70, 15, 0, 1, 0, 0, 1, 0, 0, 0, 3};
                if (program.pipeline(original) != pso) throw new AssertionError("Mutable PSO key");
                MetalBridge.renderBegin(enc, new long[0], null, depth, true, 0.6f, 4, 4);
                MetalBridge.renderBegin(enc, new long[] {color}, new float[][] {{0,0,0,0}}, 0, false, 0, 4, 4);
                MetalBridge.viewport(enc, 1, 1, 2, 2);
                MetalBridge.bindPipeline(enc, pso, state, false, 3);
                uniforms.upload(enc, compiled.uniformSlot());
                MetalBridge.texture(enc, compiled.textureSlots().get("shadowtex0"), depth, sampler);
                MetalBridge.draw(enc, 3, 1, 0, 0);
                MetalBridge.readTexture(enc, color, 4, 4, 4, readback);
                MetalBridge.commitAndWait(enc);
                long bytes = MetalBridge.bufferContents(readback);
                for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                    boolean inside = x >= 1 && x < 3 && y >= 1 && y < 3;
                    int[] expected = inside ? new int[] {64,128,255,255} : new int[4];
                    for (int c = 0; c < 4; c++) {
                        int actual = Byte.toUnsignedInt(MemoryUtil.memGetByte(bytes + (y * 4 + x) * 4 + c));
                        if (Math.abs(actual - expected[c]) > 1) throw new AssertionError("pixel " + x + "," + y + " channel " + c + ": " + actual);
                    }
                }
            } finally { uniforms.free(); }
        } finally {
            for (long handle : new long[] {sampler, state, readback, color, depth}) MetalBridge.release(handle);
        }
        checkCenterDepth(ctx);
        System.out.println("runtime-check: PASS (viewport, std140 matrix/scalar upload, LEQUAL sampler, immutable PSO cache)");
    }
    private static void checkCenterDepth(long ctx) throws Exception {
        String vertex = java.nio.file.Files.readString(java.nio.file.Path.of("common/src/main/resources/centerDepth.vsh"))
            .replace("iris_Position", "Position");
        String fragment = java.nio.file.Files.readString(java.nio.file.Path.of("common/src/main/resources/centerDepth.fsh"))
            .replace("out float iris_fragColor;", "layout(location = 0) out float iris_fragColor;\nuniform int firstSample;")
            .replace("if (isnan(oldDepth))", "if (firstSample != 0 || isnan(oldDepth))");
        long enc = MetalBridge.createHeadlessEncoder(ctx);
        long depth = MetalBridge.newTexture(ctx, 252, 1, 1, 1, 5);
        long old = MetalBridge.newTexture(ctx, 55, 1, 1, 1, 5);
        long output = MetalBridge.newTexture(ctx, 55, 1, 1, 1, 5);
        long buffer = MetalBridge.newBuffer(ctx, 36), readback = MetalBridge.newBuffer(ctx, 4);
        long sampler = MetalBridge.samplerNew(ctx, 0, 0, 0, 0, 0, 0);
        long state = MetalBridge.depthStateNew(ctx, 7, false);
        float[] triangle = {0,0,0, 2,0,0, 0,2,0};
        for (int i = 0; i < triangle.length; i++) MemoryUtil.memPutFloat(MetalBridge.bufferContents(buffer) + i * 4L, triangle[i]);
        try (var program = new PackProgram(ctx, "center-depth-check", vertex, fragment, null, Map.of())) {
            var c = program.compiled();
            var fields = c.fields().values().toArray(new PackCompiler.Field[0]);
            var u = new PackUniforms(fields, c.uniformSize());
            try {
                for (int i = 0; i < fields.length; i++) switch (fields[i].name()) {
                    case "projection" -> u.matrix(i, new float[] {2,0,0,0, 0,2,0,0, 0,0,0,0, -1,-1,0,1});
                    case "firstSample" -> u.ints(i, 1);
                    case "lastFrameTime", "decay" -> u.floats(i, 1);
                }
                int vb = MetalBridge.vertexBufferSlot(0);
                int[] descriptor = {1,vb,12,0, 1,c.vertexInputs().get("Position"),vb,0,30, 1,55,15,0,1,0,0,1,0,0, 0,3};
                long pso = program.pipeline(descriptor);
                MetalBridge.renderBegin(enc, new long[0], null, depth, true, 0.6f, 1, 1);
                MetalBridge.renderBegin(enc, new long[] {old}, new float[][] {{0,0,0,0}}, 0, false, 0, 1, 1);
                MetalBridge.renderBegin(enc, new long[] {output}, null, 0, false, 0, 1, 1);
                MetalBridge.bindPipeline(enc, pso, state, false, 3);
                u.upload(enc, c.uniformSlot());
                MetalBridge.texture(enc, c.textureSlots().get("depth"), depth, sampler);
                MetalBridge.texture(enc, c.textureSlots().get("altDepth"), old, sampler);
                MetalBridge.vertexBuffer(enc, vb, buffer, 0);
                MetalBridge.draw(enc, 3, 1, 0, 0);
                MetalBridge.readTexture(enc, output, 1, 1, 4, readback);
                MetalBridge.commitAndWait(enc);
                float value = MemoryUtil.memGetFloat(MetalBridge.bufferContents(readback));
                if (Math.abs(value - 0.4f) > 0.0001f) throw new AssertionError("center depth: " + value);
            } finally { u.free(); }
        } finally {
            for (long handle : new long[] {depth, old, output, buffer, readback, sampler, state}) MetalBridge.release(handle);
        }
        System.out.println("center-depth-check: PASS (production shader resources, first sample, depth inversion, R32F target)");
    }

}
