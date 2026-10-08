package net.irisshaders.iris.metal;

import mcopt.metal.PackUniforms;
import mcopt.metal.PackCompiler;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.BlendMode;

import java.util.Map;

/**
 * Iris uniform-value and blending policy for a program owned and compiled by mcopt.
 */
final class MetalProgram {
	final String name;
	final boolean mayDiscard;
	final PackCompiler.Program compiled;
	final MetalUniforms uniforms;
	private final mcopt.metal.PackProgram backend;

	MetalProgram(long ctx, String name, String vertexGlsl, String fragmentGlsl) {
		this(ctx, name, vertexGlsl, fragmentGlsl, null, Map.of());
	}

	MetalProgram(long ctx, String name, String vertexGlsl, String fragmentGlsl, int[] outputRemap, Map<String, Integer> fixedBuffers) {
		this.name = name;
		this.mayDiscard = java.util.regex.Pattern.compile("\\b(?:discard|demote|gl_SampleMask)\\b").matcher(fragmentGlsl).find();
		this.backend = new mcopt.metal.PackProgram(ctx, name, vertexGlsl, fragmentGlsl, outputRemap, fixedBuffers);
		this.compiled = this.backend.compiled();
		try {
			this.uniforms = new MetalUniforms(this.compiled);
		} catch (RuntimeException | Error e) {
			this.backend.close();
			throw e;
		}
	}

	/** The pipeline state for a descriptor in mc_pipeline_new's layout (see MetalBridge.pipelineNew). */
	long pipeline(int[] desc) {
		return this.backend.pipeline(desc);
	}

	/** Texture names by slot (slot i samples textureNames()[i]). */
	String[] textureNames() {
		return this.backend.textureNames();
	}

	boolean isCompare(String sampler) {
		return this.compiled.compareSamplers().contains(sampler);
	}

	/** Runs the uniforms that are due and hands the block to the encoder (both stages). */
	void bindUniforms(long enc) {
		int slot = this.compiled.uniformSlot();
		if (slot < 0) return;
		this.uniforms.update();
		PackUniforms sink = this.uniforms.sink;
		sink.upload(enc, slot);
	}

	void destroy() {
		this.backend.close();
		this.uniforms.free();
	}

	/** MTLBlendFactor for a GL blend factor. */
	static int blendFactor(int gl) {
		return switch (gl) {
			case 0 -> 0;
			case 1 -> 1;
			case 0x300 -> 2;
			case 0x301 -> 3;
			case 0x302 -> 4;
			case 0x303 -> 5;
			case 0x304 -> 8;
			case 0x305 -> 9;
			case 0x306 -> 6;
			case 0x307 -> 7;
			case 0x308 -> 10;
			default -> {
				Iris.logger.warn("Metal: unknown GL blend factor 0x" + Integer.toHexString(gl));
				yield 1;
			}
		};
	}

	/** Nine ints of a color attachment for mc_pipeline_new: format, write mask, blending and its factors (add ops). */
	static int[] color(int mtlFormat, boolean write, BlendMode blend) {
		if (blend == null) return new int[] {mtlFormat, write ? 15 : 0, 0, 1, 0, 0, 1, 0, 0};
		return new int[] {mtlFormat, write ? 15 : 0, 1, blendFactor(blend.srcRgb()), blendFactor(blend.dstRgb()), 0,
			blendFactor(blend.srcAlpha()), blendFactor(blend.dstAlpha()), 0};
	}
}
