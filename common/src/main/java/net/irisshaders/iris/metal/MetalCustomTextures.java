package net.irisshaders.iris.metal;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTexture;
import mcopt.metal.MetalBridge;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The pack's own textures ("texture.<stage>.<sampler> = path" and "customTexture.<name> = path" in shaders.properties,
 * and "texture.noise") as Metal textures, what CustomTextureManager does on GL. PNGs are loaded once; resource and
 * lightmap entries are looked up each bind, since the game can replace those textures.
 */
final class MetalCustomTextures {
	/** A bindable texture: its MTLTexture (0 when missing) and MTLSamplerState. */
	record Source(Supplier<Long> texture, long sampler) {
	}

	private final long ctx;
	private final Map<TextureStage, Map<String, Source>> staged = new EnumMap<>(TextureStage.class);
	private final Map<String, Source> named = new HashMap<>();
	private final @Nullable Source noise;
	private final List<DynamicTexture> owned = new ArrayList<>();
	private final Map<Integer, Long> samplers = new HashMap<>();

	MetalCustomTextures(long ctx, ShaderPack pack) {
		this.ctx = ctx;
		pack.getCustomTextureDataMap().forEach((stage, map) -> {
			Map<String, Source> sources = new HashMap<>();
			map.forEach((name, data) -> {
				Source s = load(name, data);
				if (s != null) sources.put(name, s);
			});
			this.staged.put(stage, sources);
		});
		pack.getIrisCustomTextureDataMap().forEach((name, data) -> {
			Source s = load(name, data);
			if (s != null) this.named.put(name, s);
		});
		CustomTextureData noiseData = pack.getCustomNoiseTexture();
		this.noise = noiseData == null ? null : load("noisetex", noiseData);
	}

	/** The pack's texture for a sampler name in a stage, or null to use the pipeline's own binding. */
	@Nullable Source get(TextureStage stage, String name) {
		Map<String, Source> sources = this.staged.get(stage);
		Source s = sources == null ? null : sources.get(name);
		if (s == null) s = this.named.get(name);
		if (s == null && name.equals("noisetex")) s = this.noise;
		return s;
	}

	boolean hasNoise() {
		return this.noise != null;
	}

	private long sampler(boolean blur, boolean clamp) {
		int key = (blur ? 1 : 0) | (clamp ? 2 : 0);
		return this.samplers.computeIfAbsent(key, k -> MetalBridge.samplerNew(this.ctx, clamp ? 0 : 2, clamp ? 0 : 2, blur ? 1 : 0, blur ? 1 : 0, 0, Float.MAX_VALUE));
	}

	private @Nullable Source load(String name, CustomTextureData data) {
		try {
			if (data instanceof CustomTextureData.PngData png) {
				NativeImage image = NativeImage.read(png.getContent());
				DynamicTexture texture = new DynamicTexture(() -> "iris:metal_custom_" + name, image);
				texture.upload();
				this.owned.add(texture);
				TextureFilteringData f = png.getFilteringData();
				long handle = MetalBridge.textureHandle(texture.getTexture());
				return new Source(() -> handle, sampler(f.shouldBlur(), f.shouldClamp()));
			}
			if (data instanceof CustomTextureData.LightmapMarker) {
				return new Source(() -> MetalBridge.textureHandle(Minecraft.getInstance().gameRenderer.levelLightmap().texture()), sampler(true, true));
			}
			if (data instanceof CustomTextureData.ResourceData resource) {
				Identifier id = Identifier.fromNamespaceAndPath(resource.getNamespace(), resource.getLocation());
				return new Source(() -> {
					AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(id);
					GpuTexture gpu = texture == null ? null : texture.getTexture();
					return gpu == null ? 0L : MetalBridge.textureHandle(gpu);
				}, sampler(false, true));
			}
			Iris.logger.warn("Metal: custom texture {} ({}) isn't supported on Metal yet", name, data.getClass().getSimpleName());
			return null;
		} catch (IOException | RuntimeException e) {
			Iris.logger.error("Metal: couldn't load custom texture " + name, e);
			return null;
		}
	}

	void destroy() {
		this.owned.forEach(DynamicTexture::close);
		this.owned.clear();
		this.samplers.values().forEach(MetalBridge::release);
		this.samplers.clear();
	}
}
