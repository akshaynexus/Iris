package net.irisshaders.iris.metal;

import mcopt.metal.MetalBridge;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import org.joml.Vector2i;
import org.joml.Vector4f;

import java.util.Map;

/**
 * The pack's colortex0-15 (a main and an alt texture each, for flipping) and the depth copies (depthtex1, depthtex2),
 * as Metal textures. Metal has no 3-channel color formats: RGB formats get the RGBA format of the same type.
 */
final class MetalTargets {
	static final int COUNT = 16;
	static final int USAGE = 1 | 4; // ShaderRead | RenderTarget

	final long ctx;
	final PackDirectives directives;
	final Target[] targets = new Target[COUNT];
	int width, height;
	/** Copies of the main depth: before translucents (depthtex1) and before the hand (depthtex2). 0 until sized. */
	long noTranslucents, noHand;
	int depthFormat;

	static final class Target {
		final int index;
		final InternalTextureFormat format;
		final int mtlFormat;
		final boolean integer;
		/** A pass reads it mipmapped ("colortexNMipmapEnabled" / mipmap directives): it gets a full mip chain. */
		boolean mipmapped;
		int mips = 1;
		int width, height;
		long main, alt;

		Target(int index, InternalTextureFormat format) {
			this.index = index;
			this.format = format;
			this.mtlFormat = pixelFormat(format);
			this.integer = format.name().endsWith("I") || format.name().endsWith("UI") || format == InternalTextureFormat.RGB10_A2UI;
		}

		long get(boolean alt) {
			return alt ? this.alt : this.main;
		}
	}

	MetalTargets(long ctx, PackDirectives directives) {
		this.ctx = ctx;
		this.directives = directives;
		Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> settings = directives.getRenderTargetDirectives().getRenderTargetSettings();
		for (int i = 0; i < COUNT; i++) {
			var s = settings.get(i);
			this.targets[i] = new Target(i, s != null ? s.getInternalFormat() : InternalTextureFormat.RGBA);
		}
	}

	/** (Re)creates every texture when the screen size changed. True when it did. */
	boolean resize(int width, int height, int depthFormat) {
		if (width == this.width && height == this.height && depthFormat == this.depthFormat && this.targets[0].main != 0) return false;
		destroy();
		this.width = width;
		this.height = height;
		this.depthFormat = depthFormat;
		for (Target t : this.targets) {
			Vector2i size = this.directives.getTextureScaleOverride(t.index, width, height);
			t.width = Math.max(1, size.x);
			t.height = Math.max(1, size.y);
			t.mips = t.mipmapped ? 32 - Integer.numberOfLeadingZeros(Math.max(t.width, t.height)) : 1;
			t.main = MetalBridge.newTexture(this.ctx, t.mtlFormat, t.width, t.height, t.mips, USAGE);
			t.alt = MetalBridge.newTexture(this.ctx, t.mtlFormat, t.width, t.height, t.mips, USAGE);
		}
		this.noTranslucents = MetalBridge.newTexture(this.ctx, depthFormat, width, height, 1, USAGE);
		this.noHand = MetalBridge.newTexture(this.ctx, depthFormat, width, height, 1, USAGE);
		return true;
	}

	Target get(int index) {
		return this.targets[index];
	}

	/** The clear color of a target in a full clear: the pack's, else Iris's defaults (fog color, white, black). */
	Vector4f clearColor(int index, Vector4f fogColor) {
		var s = this.directives.getRenderTargetDirectives().getRenderTargetSettings().get(index);
		if (s != null && s.getClearColor().isPresent()) return s.getClearColor().get();
		if (index == 0) return fogColor;
		if (index == 1) return new Vector4f(1, 1, 1, 1);
		return new Vector4f(0, 0, 0, 0);
	}

	boolean shouldClear(int index) {
		var s = this.directives.getRenderTargetDirectives().getRenderTargetSettings().get(index);
		return s == null || s.shouldClear();
	}

	void destroy() {
		for (Target t : this.targets) {
			if (t.main != 0) MetalBridge.release(t.main);
			if (t.alt != 0) MetalBridge.release(t.alt);
			t.main = t.alt = 0;
		}
		if (this.noTranslucents != 0) MetalBridge.release(this.noTranslucents);
		if (this.noHand != 0) MetalBridge.release(this.noHand);
		this.noTranslucents = this.noHand = 0;
	}

	/** MTLPixelFormat for an Iris internal format. */
	static int pixelFormat(InternalTextureFormat f) {
		return switch (f) {
			case RGBA, RGBA8, RGB8, RGBA2, RGBA4, R3_G3_B2, RGB5_A1, RGB565 -> 70; // RGBA8Unorm
			case R8 -> 10;
			case RG8 -> 30;
			case R8_SNORM -> 12;
			case RG8_SNORM -> 32;
			case RGB8_SNORM, RGBA8_SNORM -> 72;
			case R16 -> 20;
			case RG16 -> 60;
			case RGB16, RGBA16 -> 110;
			case R16_SNORM -> 22;
			case RG16_SNORM -> 62;
			case RGB16_SNORM, RGBA16_SNORM -> 112;
			case R16F -> 25;
			case RG16F -> 65;
			case RGB16F, RGBA16F -> 115;
			case R32F -> 55;
			case RG32F -> 105;
			case RGB32F, RGBA32F -> 125;
			case R8I -> 14;
			case RG8I -> 34;
			case RGB8I, RGBA8I -> 74;
			case R8UI -> 13;
			case RG8UI -> 33;
			case RGB8UI, RGBA8UI -> 73;
			case R16I -> 24;
			case RG16I -> 64;
			case RGB16I, RGBA16I -> 114;
			case R16UI -> 23;
			case RG16UI -> 63;
			case RGB16UI, RGBA16UI -> 113;
			case R32I -> 54;
			case RG32I -> 104;
			case RGB32I, RGBA32I -> 124;
			case R32UI -> 53;
			case RG32UI -> 103;
			case RGB32UI, RGBA32UI -> 123;
			case RGB10_A2 -> 90;
			case RGB10_A2UI -> 91;
			case R11F_G11F_B10F -> 92;
			case RGB9_E5 -> 93;
		};
	}
}
