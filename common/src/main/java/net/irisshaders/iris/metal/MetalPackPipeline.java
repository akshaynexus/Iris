package net.irisshaders.iris.metal;

import mcopt.metal.PackCompiler;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import mcopt.metal.MetalBridge;
import mcopt.metal.MetalHooks;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.framebuffer.ViewportData;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.layer.GbufferPrograms;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.CloudSetting;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.ParticleRenderingSettings;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.targets.BufferFlipper;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;

/**
 * Iris on mcopt's Metal backend: the pack's render targets are Metal textures and its programs are Metal pipelines
 * (PackCompiler), drawn straight into mcopt's encoder through MetalBridge. Takes the place of IrisRenderingPipeline,
 * which is OpenGL throughout.
 *
 * <p>Phase 4 (docs/METAL-PORT.md): clears, begin/prepare/deferred/composite passes, the final pass and the end-of-frame
 * swap copies. The world itself (gbuffers) still draws into Minecraft's main target until phase 5.
 */
public final class MetalPackPipeline implements WorldRenderingPipeline {
	private static final int QUAD_STRIDE = 20;
	private static volatile Vector2i albedoSize = new Vector2i();

	final ProgramSet programSet;
	private final PackDirectives directives;
	private final FrameUpdateNotifier updateNotifier = new FrameUpdateNotifier();
	final CustomUniforms customUniforms;
	final long ctx;
	MetalTargets targets;
	private MetalGbuffers gbuffers;
	private MetalCustomTextures custom;
	/** Buffers the composite stage writes: the final pass's custom textures don't replace them. */
	private final Set<Integer> compositeWritten = new HashSet<>();
	/** The shadow pass (null when the pack has none): Iris's ShadowRenderer, drawing through MetalGbuffers' redirect. */
	private @org.jspecify.annotations.Nullable ShadowRenderer shadowRenderer;
	final int shadowResolution;
	/** Forward shadow depth cleared to 1, matching the observed GL runtime, its pre-translucent copy, and shadowcolor0..1. */
	long shadowDepth, shadowDepthNoTranslucents;
	final long[] shadowColors = new long[2];
	final int[] shadowColorFormats = new int[2];
	private boolean isBeforeTranslucent;
	private boolean initializedBlockIds;
	private final List<MetalProgram> programs = new ArrayList<>();
	private final List<Pass> begin, prepare, deferred, composite;
	private final Pass finalPass;
	private final Pass centerDepthPass;
	private long centerDepth, centerDepthAlt;
	private boolean centerDepthUsed, centerDepthSampled;
	private final ImmutableSet<Integer> flippedBeforeShadow, flippedAfterPrepare, flippedAfterTranslucent, flippedAtEnd;
	private final List<Integer> swaps = new ArrayList<>();
	private DynamicTexture white, noise, defaultNormal, defaultSpecular;
	private GpuTexture shadowDepthDummy;
	private long linearClamp, nearestClamp, linearRepeat, linearMipClamp;
	private final long[] shadowRawSamplers = new long[2], shadowCompareSamplers = new long[2];
	private long alwaysDepth;
	private long quad;
	private final Set<Long> mipSampling = new HashSet<>();
	private net.irisshaders.iris.pathways.HorizonRenderer horizonRenderer;
	private final Set<String> warned = new HashSet<>();
	private WorldRenderingPhase phase = WorldRenderingPhase.NONE, overridePhase;
	private boolean fullClearPending = true;
	private boolean destroyed;
	private boolean isRenderingWorld;

	/** A full-screen pass: begin, prepare, deferred, composite or final. */
	private static final class Pass {
		String name;
		MetalProgram program;
		int[] drawBuffers;
		ImmutableSet<Integer> readsAlt;
		BlendMode blend;
		ImmutableSet<Integer> mipmapped;
		TextureStage stage;
		ViewportData viewport = new ViewportData(1, 0, 0);
		/** Buffers written by earlier passes of the stage: a custom texture no longer replaces them (as on GL). */
		Set<Integer> writtenBefore = Set.of();
	}

	public static Vector2i albedoSize() {
		return albedoSize;
	}

	public MetalPackPipeline(ProgramSet programSet) {
		ShaderPrinter.resetPrintState();
		this.programSet = programSet;
		this.directives = programSet.getPackDirectives();
		this.ctx = MetalBridge.ctx();
		this.customUniforms = programSet.getPack().customUniforms.build(
			holder -> CommonUniforms.addNonDynamicUniforms(holder, programSet.getPack().getIdMap(), this.directives, this.updateNotifier));
		try {
			this.horizonRenderer = new net.irisshaders.iris.pathways.HorizonRenderer();
			this.targets = new MetalTargets(this.ctx, this.directives);
			this.custom = new MetalCustomTextures(this.ctx, programSet.getPack());

			this.linearClamp = MetalBridge.samplerNew(this.ctx, 0, 0, 1, 1, 0, Float.MAX_VALUE);
			this.nearestClamp = MetalBridge.samplerNew(this.ctx, 0, 0, 0, 0, 0, Float.MAX_VALUE);
			this.linearRepeat = MetalBridge.samplerNew(this.ctx, 2, 2, 1, 1, 0, Float.MAX_VALUE);
			this.linearMipClamp = MetalBridge.samplerNew(this.ctx, 0, 0, 1, 1, 2, Float.MAX_VALUE);
			this.alwaysDepth = MetalBridge.depthStateNew(this.ctx, 7, false);

			this.white = singleColor(1, 1, 0xFFFFFFFF);
			// What GL Iris binds for normals/specular without a PBR resource pack: a flat normal, no specular.
			this.defaultNormal = new net.irisshaders.iris.targets.backed.NativeImageBackedSingleColorTexture(net.irisshaders.iris.pbr.texture.PBRType.NORMAL.getDefaultValue());
			this.defaultSpecular = new net.irisshaders.iris.targets.backed.NativeImageBackedSingleColorTexture(net.irisshaders.iris.pbr.texture.PBRType.SPECULAR.getDefaultValue());
			this.defaultNormal.upload();
			this.defaultSpecular.upload();
			this.noise = noiseTexture(this.directives.getNoiseTextureResolution());
			this.shadowDepthDummy = com.mojang.blaze3d.systems.RenderSystem.getDevice().createTexture("iris:metal_shadow_dummy",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST, com.mojang.renderpearl.api.GpuFormat.D32_FLOAT, 1, 1, 1, 1);
			com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(this.shadowDepthDummy, 1.0);

			// Two triangles over the screen: position (0..1) and uv, as Iris's composite vertex shaders expect.
			float[] v = {0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 1, 0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 0, 1, 0, 0, 1};
			this.quad = MetalBridge.newBuffer(this.ctx, v.length * 4L);
			long at = MetalBridge.bufferContents(this.quad);
			for (int i = 0; i < v.length; i++) MemoryUtil.memPutFloat(at + i * 4L, v[i]);

			BufferFlipper flipper = new BufferFlipper();
			this.begin = passes(ProgramArrayId.Begin, TextureStage.BEGIN, flipper, "begin_pre");
			this.flippedBeforeShadow = flipper.snapshot();
			this.prepare = passes(ProgramArrayId.Prepare, TextureStage.PREPARE, flipper, "prepare_pre");
			this.flippedAfterPrepare = flipper.snapshot();
			this.deferred = passes(ProgramArrayId.Deferred, TextureStage.DEFERRED, flipper, "deferred_pre");
			this.flippedAfterTranslucent = flipper.snapshot();
			this.composite = passes(ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL, flipper, "composite_pre");
			this.flippedAtEnd = flipper.snapshot();

			this.finalPass = programSet.get(ProgramId.Final).map(source -> {
				Pass pass = new Pass();
				pass.name = source.getName();
				pass.program = program(source, TextureStage.COMPOSITE_AND_FINAL);
				pass.drawBuffers = new int[0];
				pass.readsAlt = this.flippedAtEnd;
				pass.mipmapped = source.getDirectives().getMipmappedBuffers();
				pass.stage = TextureStage.COMPOSITE_AND_FINAL;
				pass.writtenBefore = Set.copyOf(this.compositeWritten);
				return pass;
			}).orElseGet(this::copyFinalPass);

	        this.centerDepth = MetalBridge.newTexture(this.ctx, 55, 1, 1, 1, MetalTargets.USAGE);
	        this.centerDepthAlt = MetalBridge.newTexture(this.ctx, 55, 1, 1, 1, MetalTargets.USAGE);
	        this.centerDepthPass = new Pass();
	        this.centerDepthPass.name = "centerDepthSmooth";
	        try (var vs = MetalPackPipeline.class.getResourceAsStream("/centerDepth.vsh");
	             var fs = MetalPackPipeline.class.getResourceAsStream("/centerDepth.fsh")) {
	            String vertex = new String(java.util.Objects.requireNonNull(vs).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
	                .replace("iris_Position", "Position");
	            String fragment = new String(java.util.Objects.requireNonNull(fs).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
	                .replace("out float iris_fragColor;", "layout(location = 0) out float iris_fragColor;\nuniform int firstSample;")
	                .replace("if (isnan(oldDepth))", "if (firstSample != 0 || isnan(oldDepth))");
	            this.centerDepthPass.program = new MetalProgram(this.ctx, "centerDepthSmooth", vertex, fragment);
	        } catch (java.io.IOException e) {
	            throw new java.io.UncheckedIOException(e);
	        }
	        this.programs.add(this.centerDepthPass.program);
	        this.centerDepthPass.readsAlt = ImmutableSet.of();
	        this.centerDepthPass.mipmapped = ImmutableSet.of();
	        this.centerDepthPass.stage = TextureStage.COMPOSITE_AND_FINAL;
	        this.centerDepthPass.program.uniforms.uniform1i(net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME,
	            "firstSample", () -> this.centerDepthSampled ? 0 : 1);
	        this.centerDepthPass.program.uniforms.uniform1f(net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME,
	            "lastFrameTime", net.irisshaders.iris.uniforms.SystemTimeUniforms.TIMER::getLastFrameTime);
	        this.centerDepthPass.program.uniforms.uniform1f(net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.ONCE,
	            "decay", () -> (float) (1.0 / ((this.directives.getCenterDepthHalfLife() * 0.1) / Math.log(2))));
	        this.centerDepthPass.program.uniforms.uniformMatrix(net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.ONCE,
	            "projection", () -> new org.joml.Matrix4f(2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, -1, -1, 0, 1));


			for (List<Pass> list : List.of(this.begin, this.prepare, this.deferred, this.composite)) {
				for (Pass pass : list) for (int i : pass.mipmapped) if (i >= 0 && i < MetalTargets.COUNT) this.targets.get(i).mipmapped = true;
			}
			if (this.finalPass != null) for (int i : this.finalPass.mipmapped) if (i >= 0 && i < MetalTargets.COUNT) this.targets.get(i).mipmapped = true;

			var cleared = this.directives.getRenderTargetDirectives().getBuffersToBeCleared();
			for (int i : this.flippedAtEnd) {
				if (!cleared.contains(i)) this.swaps.add(i);
			}
			// What IrisRenderingPipeline sets for Sodium's meshing and the vertex formats (mc_Entity, at_tangent, ...).
			WorldRenderingSettings.INSTANCE.setVertexFormat(FormatAnalyzer.createFormat(true, true, true, true));
			WorldRenderingSettings.INSTANCE.setEntityIds(programSet.getPack().getIdMap().getEntityIdMap());
			WorldRenderingSettings.INSTANCE.setItemIds(programSet.getPack().getIdMap().getItemIdMap());
			WorldRenderingSettings.INSTANCE.setAmbientOcclusionLevel(this.directives.getAmbientOcclusionLevel());
			WorldRenderingSettings.INSTANCE.setDisableDirectionalShading(shouldDisableDirectionalShading());
			WorldRenderingSettings.INSTANCE.setUseSeparateAo(this.directives.shouldUseSeparateAo());
			WorldRenderingSettings.INSTANCE.setBreaksAnisotropy(this.directives.breaksAnisotropy());
			WorldRenderingSettings.INSTANCE.setVoxelizeLightBlocks(this.directives.shouldVoxelizeLightBlocks());
			WorldRenderingSettings.INSTANCE.setSeparateEntityDraws(this.directives.shouldUseSeparateEntityDraws());

			var shadowDirectives = this.directives.getShadowDirectives();
			ProgramFallbackResolver resolver = new ProgramFallbackResolver(programSet);
			this.shadowResolution = shadowDirectives.getResolution();
	        for (int i = 0; i < 2; i++) {
	            var settings = shadowDirectives.getDepthSamplingSettings().get(i);
	            int filter = settings.getNearest() ? 0 : 1;
	            int mip = settings.getMipmap() ? 2 : 0;
	            this.shadowRawSamplers[i] = MetalBridge.samplerNew(this.ctx, 0, 0, filter, filter, mip, Float.MAX_VALUE);
	            // GlSampler's bound comparison state overrides ShadowRenderer's texture state.
	            this.shadowCompareSamplers[i] = MetalBridge.comparisonSamplerNew(this.ctx, 0, 0, filter, filter, mip, Float.MAX_VALUE, 3);
	        }
			if (resolver.has(ProgramId.Shadow) && shadowDirectives.isShadowEnabled().orElse(true)) {
				if (shadowDirectives.getDepthSamplingSettings().stream().anyMatch(x -> x.getMipmap())) {
	                throw new IllegalStateException("Shadow depth mipmaps require a Metal depth reduction pass");
	            }
	            int usage = MetalTargets.USAGE;
				this.shadowDepth = MetalBridge.newTexture(this.ctx, 252, this.shadowResolution, this.shadowResolution, 1, usage);
				this.shadowDepthNoTranslucents = MetalBridge.newTexture(this.ctx, 252, this.shadowResolution, this.shadowResolution, 1, usage);
				for (int i = 0; i < this.shadowColors.length; i++) {
					var settings = shadowDirectives.getColorSamplingSettings().get(i);
					this.shadowColorFormats[i] = MetalTargets.pixelFormat(settings != null ? settings.getFormat() : net.irisshaders.iris.gl.texture.InternalTextureFormat.RGBA);
					this.shadowColors[i] = MetalBridge.newTexture(this.ctx, this.shadowColorFormats[i], this.shadowResolution, this.shadowResolution, settings != null && settings.getMipmap() ? 32 - Integer.numberOfLeadingZeros(this.shadowResolution) : 1, usage);
				}
				ShadowRenderer renderer = new ShadowRenderer(this, resolver.resolveNullable(ProgramId.ShadowSolid), this.directives, null, null, this.customUniforms, false);
				renderer.metalPreTranslucentDepth = () -> {
					Object encoder = encoder();
					MetalBridge.blitTextureToTexture(MetalBridge.enc(encoder), this.shadowDepth, this.shadowDepthNoTranslucents, 0, this.shadowResolution, this.shadowResolution);
				};
				this.shadowRenderer = renderer;
			} else {
				this.shadowRenderer = null;
			}

			this.customUniforms.optimise();
			this.gbuffers = new MetalGbuffers(this);
			MetalHooks.setRedirector(this.gbuffers);
			Iris.logger.info("[Iris] Shader pack on Metal (mcopt), {} programs", this.programs.size());
		} catch (RuntimeException | Error e) {
			destroy();
			throw e;
		}
	}

    private Pass copyFinalPass() {
        Pass pass = new Pass();
        pass.name = "final-copy";
        pass.program = new MetalProgram(this.ctx, pass.name, """
            #version 330 core
            in vec3 Position;
            in vec2 UV0;
            out vec2 uv;
            void main() { uv = UV0; gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0); }
            """, """
            #version 330 core
            in vec2 uv;
            uniform sampler2D colortex0;
            layout(location = 0) out vec4 color;
            void main() { color = texture(colortex0, uv); }
            """);
        pass.drawBuffers = new int[0];
        pass.readsAlt = this.flippedAtEnd;
        pass.mipmapped = ImmutableSet.of();
        pass.stage = TextureStage.COMPOSITE_AND_FINAL;
        pass.writtenBefore = Set.of(0);
        this.programs.add(pass.program);
        return pass;
    }

	private MetalProgram program(ProgramSource source, TextureStage stage) {
		Map<PatchShaderType, String> transformed = TransformPatcher.patchComposite(source.getName(),
			source.getVertexSource().orElseThrow(), source.getGeometrySource().orElse(null), source.getFragmentSource().orElseThrow(),
			stage, getTextureMap(), getTextureOverrides(stage));
		ShaderPrinter.printProgram(source.getName()).addSources(transformed).print();
		if (transformed.get(PatchShaderType.GEOMETRY) != null) {
			throw new IllegalStateException(source.getName() + " has a geometry shader: Metal has none");
		}
		MetalProgram program = new MetalProgram(this.ctx, source.getName(), transformed.get(PatchShaderType.VERTEX), transformed.get(PatchShaderType.FRAGMENT));
		this.programs.add(program);
		CommonUniforms.addDynamicUniforms(program.uniforms, FogMode.OFF);
		program.uniforms.attach(this.customUniforms);

		return program;
	}

	private List<Pass> passes(ProgramArrayId id, TextureStage stage, BufferFlipper flipper, String preFlips) {
		ImmutableMap<Integer, Boolean> explicitPre = this.directives.getExplicitFlips(preFlips);
		explicitPre.forEach((buffer, flip) -> {
			if (flip) flipper.flip(buffer);
		});
		List<Pass> result = new ArrayList<>();
		Set<Integer> written = new HashSet<>();
		ProgramSource[] sources = this.programSet.getComposite(id);
		for (ProgramSource source : sources) {
			if (source == null || !source.isValid()) continue;
			Pass pass = new Pass();
			pass.name = source.getName();
			pass.readsAlt = flipper.snapshot();
			pass.stage = stage;
			pass.writtenBefore = Set.copyOf(written);
			pass.program = program(source, stage);
			pass.drawBuffers = source.getDirectives().getDrawBuffers();
			pass.blend = source.getDirectives().getBlendModeOverride().map(BlendModeOverride::getBlendMode).orElse(null);
			pass.mipmapped = source.getDirectives().getMipmappedBuffers();
			pass.viewport = source.getDirectives().getViewportScale();
			if (pass.drawBuffers.length > 8) throw new IllegalStateException(pass.name + " writes " + pass.drawBuffers.length + " buffers, Metal allows 8");
			var explicit = source.getDirectives().getExplicitFlips();
			for (int buffer : pass.drawBuffers) {
				if (explicit.get(buffer) != Boolean.FALSE) {
					flipper.flip(buffer);
					written.add(buffer);
				}
			}
			explicit.forEach((buffer, flip) -> {
				if (flip) {
					flipper.flip(buffer);
					written.add(buffer);
				}
			});
			this.compositeWritten.addAll(id == ProgramArrayId.Composite ? written : Set.of());
			result.add(pass);
		}
		return result;
	}

	private void warnOnce(String message) {
		if (this.warned.add(message)) Iris.logger.warn("Metal: " + message);
	}

	// --- Frame ---

	private Object encoder() {
		Object encoder = MetalBridge.encoder();
		if (encoder == null) throw new IllegalStateException("No Metal encoder");
		if (MetalBridge.frontendPassOpen(encoder)) throw new IllegalStateException("Metal: a frontend render pass is open");
		return encoder;
	}

	private static RenderTarget main() {
		return Minecraft.getInstance().gameRenderer.mainRenderTarget();
	}

	@Override
	public void beginLevelRendering() {
		if (!this.initializedBlockIds) {
			WorldRenderingSettings.INSTANCE.setBlockStateIds(BlockMaterialMapping.createBlockStateIdMap(
				this.programSet.getPack().getIdMap().getBlockProperties(), this.programSet.getPack().getIdMap().getTagEntries()));
			WorldRenderingSettings.INSTANCE.setBlockTypeIds(BlockMaterialMapping.createBlockTypeMap(this.programSet.getPack().getIdMap().getBlockRenderTypeMap()));
			Minecraft.getInstance().levelExtractor.allChanged();
			this.initializedBlockIds = true;
		}
		this.dumpedThisFrame = false;
		this.mipSampling.clear();
		this.isBeforeTranslucent = true;
		this.updateNotifier.onNewFrame();
		this.customUniforms.update();

		RenderTarget main = main();
		GpuTexture depth = main.getDepthTexture();
		if (this.targets.resize(main.width, main.height, MetalBridge.pixelFormat(depth.getFormat()))) this.fullClearPending = true;

		Object encoder = encoder();
		long enc = MetalBridge.enc(encoder);
		Vector3d fog3 = CapturedRenderingState.INSTANCE.getFogColor();
		Vector4f fog = new Vector4f((float) fog3.x, (float) fog3.y, (float) fog3.z, 1.0F);
		clear(enc, fog, this.fullClearPending);
		this.fullClearPending = false;
		run(encoder, this.begin);
		this.isRenderingWorld = true;
	}

	/** Which targets gbuffer programs read (and write) from alt: before translucents, after prepare; then after deferred. */
	Set<Integer> gbufferReadsAlt() {
		return this.isBeforeTranslucent ? this.flippedAfterPrepare : this.flippedAfterTranslucent;
	}

	/** Clears the targets that are due (all of them on a full clear), main and alt, up to 8 per Metal pass. */
	private void clear(long enc, Vector4f fog, boolean full) {
		List<Long> handles = new ArrayList<>();
		List<float[]> colors = new ArrayList<>();
		int w = -1, h = -1;
		for (int i = 0; i < MetalTargets.COUNT; i++) {
			if (!full && !this.targets.shouldClear(i)) continue;
			MetalTargets.Target t = this.targets.get(i);
			if (w != -1 && (t.width != w || t.height != h)) {
				flushClears(enc, handles, colors, w, h);
			}
			w = t.width;
			h = t.height;
			Vector4f c = this.targets.clearColor(i, fog);
			float[] cc = {c.x, c.y, c.z, c.w};
			for (long handle : new long[] {t.main, t.alt}) {
				handles.add(handle);
				colors.add(cc);
				if (handles.size() == 8) flushClears(enc, handles, colors, w, h);
			}
		}
		flushClears(enc, handles, colors, w, h);
	}

	private static void flushClears(long enc, List<Long> handles, List<float[]> colors, int w, int h) {
		if (handles.isEmpty()) return;
		long[] h2 = handles.stream().mapToLong(Long::longValue).toArray();
		MetalBridge.renderBegin(enc, h2, colors.toArray(new float[0][]), 0, false, 0, w, h);
		mcopt.metal.PassProfile.label(enc, "colortex clears");
		handles.clear();
		colors.clear();
	}

	@Override
	public void renderShadows(LevelRendererAccessor worldRenderer, Camera playerCamera, CameraRenderState renderState) {
		if (this.shadowRenderer != null) {
			long enc = MetalBridge.enc(encoder());
			var settings = this.directives.getShadowDirectives().getColorSamplingSettings();
			float[][] clears = new float[this.shadowColors.length][];
			for (int i = 0; i < clears.length; i++) {
				var s = settings.get(i);
				Vector4f c = s != null ? s.getClearColor() : new Vector4f(1, 1, 1, 1);
				clears[i] = s == null || s.getClear() ? new float[] {c.x, c.y, c.z, c.w} : null;
			}
			// Shadows use forward depth. Runtime GL readback confirms clear 1 and nearest-surface selection.
			MetalBridge.renderBegin(enc, this.shadowColors, clears, this.shadowDepth, true, 1.0f, this.shadowResolution, this.shadowResolution);
			mcopt.metal.PassProfile.label(enc, "shadow opaque");
			mcopt.metal.PassProfile.group("shadow");
			try { this.shadowRenderer.renderShadows(worldRenderer, playerCamera, renderState); }
			finally { mcopt.metal.PassProfile.group(""); }
            for (int i = 0; i < this.shadowColors.length; i++) {
                var setting = settings.get(i);
                if (setting != null && setting.getMipmap()) MetalBridge.generateMipmaps(enc, this.shadowColors[i]);
            }
		}
		run(encoder(), this.prepare);
	}

	@Override
	public void beginHand() {
        if (!this.centerDepthSampled || this.centerDepthUsed) {
            Object encoder = encoder();
            long enc = MetalBridge.enc(encoder);
            if (!this.centerDepthSampled) {
                MetalBridge.renderBegin(enc, new long[] {this.centerDepthAlt},
                    new float[][] {{0, 0, 0, 0}}, 0, false, 0, 1, 1);
            }
            draw(encoder, this.centerDepthPass, new long[] {this.centerDepth}, new int[] {55}, 1, 1);
            MetalBridge.blitTextureToTexture(enc, this.centerDepth, this.centerDepthAlt, 0, 1, 1);
            this.centerDepthSampled = true;
        }
		copyDepth(this.targets.noHand);
	}

	private static java.nio.file.Path dumpRequest;
	private int dumpStage;
	private boolean dumpedThisFrame;
	/** Harness entry point. Stages are sampled on successive frames to bound staging memory. */
	public static java.util.Map<String, Object> requestDump(java.nio.file.Path directory) {
		if (!Boolean.getBoolean("harness.enabled")) throw new IllegalStateException("Harness disabled");
		if (dumpRequest != null || MetalDump.PENDING.get() != 0) throw new IllegalStateException("Dump already pending");
		MetalDump.error = null;
		dumpRequest = directory;
		return java.util.Map.of("ok", true, "path", directory.toString(), "status", "queued");
	}

	public static java.util.Map<String, Object> dumpStatus() {
		return java.util.Map.of("ok", MetalDump.error == null, "complete", dumpRequest == null && MetalDump.PENDING.get() == 0, "pending", MetalDump.PENDING.get(), "error", MetalDump.error == null ? "" : MetalDump.error);
	}

	private void dumpStage(String name, int stage, java.util.Set<Integer> readsAlt) {
		if (dumpRequest == null || dumpStage != stage || dumpedThisFrame) return;
		dumpedThisFrame = true;
		java.nio.file.Path dir = dumpRequest.resolve(name);
		Object encoder = encoder();
		for (MetalTargets.Target t : this.targets.targets) {
			MetalDump.texture(encoder, t.main, t.mtlFormat, t.width, t.height, dir.resolve("colortex" + t.index + "-main" + (!readsAlt.contains(t.index) ? "-read" : "") + ".png"));
			MetalDump.texture(encoder, t.alt, t.mtlFormat, t.width, t.height, dir.resolve("colortex" + t.index + "-alt" + (readsAlt.contains(t.index) ? "-read" : "") + ".png"));
		}
		GpuTexture depth = main().getDepthTexture();
		MetalBridge.flushClear(encoder, depth);
		MetalDump.texture(encoder, MetalBridge.textureHandle(depth), this.targets.depthFormat, this.targets.width, this.targets.height, dir.resolve("depthtex0.png"));
		MetalDump.texture(encoder, this.targets.noTranslucents, this.targets.depthFormat, this.targets.width, this.targets.height, dir.resolve("depthtex1.png"));
		MetalDump.texture(encoder, this.targets.noHand, this.targets.depthFormat, this.targets.width, this.targets.height, dir.resolve("depthtex2.png"));
		MetalDump.texture(encoder, this.shadowDepth, 252, this.shadowResolution, this.shadowResolution, dir.resolve("shadowtex0.png"));
		MetalDump.texture(encoder, this.shadowDepthNoTranslucents, 252, this.shadowResolution, this.shadowResolution, dir.resolve("shadowtex1.png"));
		for (int i = 0; i < 2; i++) MetalDump.texture(encoder, this.shadowColors[i], this.shadowColorFormats[i], this.shadowResolution, this.shadowResolution, dir.resolve("shadowcolor" + i + ".png"));
		dumpStage++;
		if (dumpStage == 3) { dumpRequest = null; dumpStage = 0; }
	}

	@Override
	public void beginTranslucents() {
		if (this.destroyed) throw new IllegalStateException("Tried to use a destroyed world rendering pipeline");
		copyDepth(this.targets.noTranslucents);
		dumpStage("after-gbuffers-opaque", 0, this.flippedAfterPrepare);
		run(encoder(), this.deferred);
		dumpStage("after-deferred", 1, this.flippedAfterTranslucent);
		this.isBeforeTranslucent = false;
	}

	private void copyDepth(long into) {
		Object encoder = encoder();
		GpuTexture depth = main().getDepthTexture();
		MetalBridge.flushClear(encoder, depth);
		MetalBridge.blitTextureToTexture(MetalBridge.enc(encoder), MetalBridge.textureHandle(depth), into, 0, depth.getWidth(0), depth.getHeight(0));
	}

	@Override
	public void finalizeLevelRendering() {
		this.isRenderingWorld = false;
		Object encoder = encoder();
		run(encoder, this.composite);
		dumpStage("after-composite", 2, this.finalPass != null ? this.finalPass.readsAlt : java.util.Set.of());
		long enc = MetalBridge.enc(encoder);
		RenderTarget main = main();
		GpuTexture color = main.getColorTexture();
		if (this.finalPass != null) {
			MetalBridge.dropPendingClear(encoder, color);
			draw(encoder, this.finalPass, new long[] {MetalBridge.textureHandle(color)}, new int[] {MetalBridge.pixelFormat(color.getFormat())},
				color.getWidth(0), color.getHeight(0));
		}
		for (int i : this.swaps) {
			MetalTargets.Target t = this.targets.get(i);
			MetalBridge.blitTextureToTexture(enc, t.alt, t.main, 0, t.width, t.height);
		}
	}

	private void run(Object encoder, List<Pass> passes) {
		for (Pass pass : passes) {
			int n = pass.drawBuffers.length;
			long[] colors = new long[n];
			int[] formats = new int[n];
			int w = 0, h = 0;
			for (int i = 0; i < n; i++) {
				MetalTargets.Target t = this.targets.get(pass.drawBuffers[i]);
				colors[i] = t.get(!pass.readsAlt.contains(t.index));
				formats[i] = t.mtlFormat;
				if (i > 0 && (w != t.width || h != t.height)) throw new IllegalStateException("Pass sizes must match for " + pass.name);
				w = t.width;
				h = t.height;
			}
			if (n == 0) continue;
			draw(encoder, pass, colors, formats, w, h);
		}
	}

	private void draw(Object encoder, Pass pass, long[] colors, int[] formats, int width, int height) {
		long enc = MetalBridge.enc(encoder);
		// Mip chains of what this pass reads mipmapped, from the level 0 written so far (Iris's setupMipmapping).
		for (int index : pass.mipmapped) {
			if (index < 0 || index >= MetalTargets.COUNT) continue;
			MetalTargets.Target t = this.targets.get(index);
			if (t.mips > 1) {
				long texture = t.get(pass.readsAlt.contains(index));
				MetalBridge.generateMipmaps(enc, texture);
				this.mipSampling.add(texture);
			}
		}
		// Only a complete, unblended draw without fragment kills can discard prior color contents.
        float[][] loads = null;
        if (Boolean.getBoolean("iris.metal.fullscreenDontCare") && pass.blend == null && !pass.program.mayDiscard
            && pass.viewport.scale() == 1 && pass.viewport.viewportX() == 0 && pass.viewport.viewportY() == 0) {
            loads = new float[colors.length][];
            java.util.Arrays.fill(loads, MetalBridge.DONT_CARE);
        }
        MetalBridge.renderBegin(enc, colors, loads, 0, false, 0, width, height);
		mcopt.metal.PassProfile.label(enc, pass.program.name);
		int viewportWidth = (int) (width * pass.viewport.scale());
		int viewportHeight = (int) (height * pass.viewport.scale());
		if (viewportWidth <= 0 || viewportHeight <= 0) return;
		MetalBridge.viewport(enc, (int) (width * pass.viewport.viewportX()), (int) (height * pass.viewport.viewportY()), viewportWidth, viewportHeight);

		PackCompiler.Program c = pass.program.compiled;
		List<Integer> desc = new ArrayList<>();
		int vb = MetalBridge.vertexBufferSlot(0);
		desc.add(1);
		desc.add(vb);
		desc.add(QUAD_STRIDE);
		desc.add(0);
		Integer position = c.vertexInputs().get("Position"), uv = c.vertexInputs().get("UV0");
		desc.add((position != null ? 1 : 0) + (uv != null ? 1 : 0));
		if (position != null) {
			desc.add(position);
			desc.add(vb);
			desc.add(0);
			desc.add(30); // float3
		}
		if (uv != null) {
			desc.add(uv);
			desc.add(vb);
			desc.add(12);
			desc.add(29); // float2
		}
		desc.add(colors.length);
		for (int f : formats) for (int x : MetalProgram.color(f, true, pass.blend)) desc.add(x);
		desc.add(0); // no depth
		desc.add(3); // triangles
		long pso = pass.program.pipeline(desc.stream().mapToInt(Integer::intValue).toArray());
		MetalBridge.bindPipeline(enc, pso, this.alwaysDepth, false, 3);
		bindTextures(encoder, enc, pass.program, pass.readsAlt, Set.of(), pass.stage, pass.writtenBefore);
		pass.program.bindUniforms(enc);
		MetalBridge.vertexBuffer(enc, vb, this.quad, 0);
		MetalBridge.draw(enc, 6, 1, 0, 0);
	}

	private static final List<String> LEGACY = List.of("gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4");

	/** Binds every texture the program samples, by name, at its slot. */
	/** As above, leaving out the textures the game binds itself (skip). */
	void bindTextures(Object encoder, long enc, MetalProgram program, Set<Integer> readsAlt, Set<String> skip, TextureStage stage) {
		bindTextures(encoder, enc, program, readsAlt, skip, stage, Set.of());
	}

	void bindTextures(Object encoder, long enc, MetalProgram program, Set<Integer> readsAlt, Set<String> skip, TextureStage stage, Set<Integer> writtenBefore) {
		String[] names = program.textureNames();
		for (int slot = 0; slot < names.length; slot++) {
			String name = names[slot];
			if (skip.contains(name)) continue;
			long texture, sampler;
			int target = colortex(name);
			MetalCustomTextures.Source own = program == this.centerDepthPass.program || target >= 0 && writtenBefore.contains(target) ? null : this.custom.get(stage, name);
			if (own != null && own.texture().get() != 0) {
				texture = own.texture().get();
				sampler = own.sampler();
			} else if (target >= 0) {
				MetalTargets.Target t = this.targets.get(target);
				texture = t.get(readsAlt.contains(target));
				sampler = t.integer ? this.nearestClamp : this.mipSampling.contains(texture) ? this.linearMipClamp : this.linearClamp;
			} else {
				switch (name) {
					case "depth", "depthtex0", "gdepthtex" -> {
						GpuTexture depth = main().getDepthTexture();
						MetalBridge.flushClear(encoder, depth);
						texture = MetalBridge.textureHandle(depth);
						sampler = this.nearestClamp;
					}
					case "altDepth", "iris_centerDepthSmooth" -> {
                        if (name.equals("iris_centerDepthSmooth")) this.centerDepthUsed = true;
                        texture = this.centerDepthAlt;
                        sampler = this.nearestClamp;
                    }
                    case "depthtex1" -> {
						texture = this.targets.noTranslucents;
						sampler = this.nearestClamp;
					}
					case "depthtex2" -> {
						texture = this.targets.noHand;
						sampler = this.nearestClamp;
					}
					case "normals" -> {
						texture = MetalBridge.textureHandle(this.defaultNormal.getTexture());
						sampler = this.nearestClamp;
					}
					case "specular" -> {
						texture = MetalBridge.textureHandle(this.defaultSpecular.getTexture());
						sampler = this.nearestClamp;
					}
					case "noisetex" -> {
						texture = MetalBridge.textureHandle(this.noise.getTexture());
						sampler = this.linearRepeat;
					}
					case "shadowtex0", "shadow", "watershadow", "shadowtex0HW" -> {
						boolean waterAlias = name.equals("shadow") && program.compiled.textureSlots().containsKey("watershadow");
						texture = this.shadowDepth != 0 ? (waterAlias ? this.shadowDepthNoTranslucents : this.shadowDepth) : dummyDepth(encoder);
						sampler = program.isCompare(name) ? this.shadowCompareSamplers[waterAlias ? 1 : 0] : this.shadowRawSamplers[waterAlias ? 1 : 0];
					}
					case "shadowtex1", "shadowtex1HW" -> {
						texture = this.shadowDepthNoTranslucents != 0 ? this.shadowDepthNoTranslucents : dummyDepth(encoder);
						sampler = program.isCompare(name) ? this.shadowCompareSamplers[1] : this.shadowRawSamplers[1];
					}
					case "shadowcolor", "shadowcolor0", "shadowcolor1" -> {
						int i = name.endsWith("1") ? 1 : 0;
						texture = this.shadowColors[i] != 0 ? this.shadowColors[i] : MetalBridge.textureHandle(this.white.getTexture());
						sampler = this.linearClamp;
					}
					default -> {
						if (!name.startsWith("shadowcolor")) warnOnce(program.name + " samples " + name + ", which Metal binds as white for now");
						texture = MetalBridge.textureHandle(this.white.getTexture());
						sampler = this.nearestClamp;
					}
				}
			}
			MetalBridge.texture(enc, slot, texture, sampler);
		}
	}

	private long dummyDepth(Object encoder) {
		MetalBridge.flushClear(encoder, this.shadowDepthDummy);
		return MetalBridge.textureHandle(this.shadowDepthDummy);
	}

	boolean hasShadows() {
		return this.shadowRenderer != null;
	}

	ImmutableSet<Integer> flippedBeforeShadow() {
		return this.flippedBeforeShadow;
	}

	private static int colortex(String name) {
		if (name.startsWith("colortex")) {
			try {
				int i = Integer.parseInt(name.substring(8));
				return i >= 0 && i < MetalTargets.COUNT ? i : -1;
			} catch (NumberFormatException e) {
				return -1;
			}
		}
		return LEGACY.indexOf(name);
	}

	private static DynamicTexture singleColor(int w, int h, int abgr) {
		NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, false);
		image.fillRect(0, 0, w, h, abgr);
		DynamicTexture texture = new DynamicTexture(() -> "iris:metal_white", image);
		texture.upload();
		return texture;
	}

	private static DynamicTexture noiseTexture(int size) {
		NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
		Random random = new Random(0);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) image.setPixel(x, y, random.nextInt() | 0xFF000000);
		}
		DynamicTexture texture = new DynamicTexture(() -> "iris:metal_noise", image);
		texture.upload();
		return texture;
	}

	// --- WorldRenderingPipeline ---

	/** PipelineManager also calls this when revisiting a cached dimension. */
	public void activate() {
		if (this.destroyed) throw new IllegalStateException("Cannot activate a destroyed Metal pipeline");
		MetalHooks.setRedirector(this.gbuffers);
	}

	@Override
	public void addDebugText(DebugScreenDisplayer messages) {
		if (this.shadowRenderer != null) this.shadowRenderer.addDebugText(messages);
		messages.addLine(net.minecraft.network.chat.Component.translatable("iris.backend.metal.active").getString());
	}

	@Override
	public OptionalInt getForcedShadowRenderDistanceChunksForDisplay() {
		return OptionalInt.empty();
	}

	@Override
	public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getTextureMap() {
		return this.directives.getTextureMap();
	}

	@Override
	public WorldRenderingPhase getPhase() {
		return this.overridePhase != null ? this.overridePhase : this.phase;
	}

	@Override
	public void setPhase(WorldRenderingPhase phase) {
		if (phase == this.phase) return;
		this.phase = phase;
		GbufferPrograms.runPhaseChangeNotifier();
	}

	@Override
	public void setOverridePhase(WorldRenderingPhase phase) {
		this.overridePhase = phase;
		GbufferPrograms.runPhaseChangeNotifier();
	}

	@Override
	public int getCurrentNormalTexture() {
		return 0;
	}

	@Override
	public int getCurrentSpecularTexture() {
		return 0;
	}

	@Override
	public void onSetAlbedoTex(GpuTextureView view) {
		if (view != null) albedoSize = new Vector2i(view.getWidth(0), view.getHeight(0));
	}

	@Override
	public void finalizeGameRendering() {
	}

	@Override
	public void destroy() {
		if (this.destroyed) return;
		this.destroyed = true;
		MetalHooks.setRedirector(null);
		for (long sampler : this.shadowRawSamplers) MetalBridge.release(sampler);
		for (long sampler : this.shadowCompareSamplers) MetalBridge.release(sampler);
		if (this.horizonRenderer != null) this.horizonRenderer.destroy();
		if (this.gbuffers != null) this.gbuffers.destroy();
		this.programs.forEach(MetalProgram::destroy);
		if (this.custom != null) this.custom.destroy();
		this.programs.clear();
		MetalBridge.release(this.centerDepth);
		MetalBridge.release(this.centerDepthAlt);
		if (this.targets != null) this.targets.destroy();
		if (this.shadowRenderer != null) {
			this.shadowRenderer.destroy();
		}
		MetalBridge.release(this.shadowDepth);
		MetalBridge.release(this.shadowDepthNoTranslucents);
		for (long c : this.shadowColors) if (c != 0) MetalBridge.release(c);
		if (this.white != null) this.white.close();
		if (this.defaultNormal != null) this.defaultNormal.close();
		if (this.defaultSpecular != null) this.defaultSpecular.close();
		if (this.noise != null) this.noise.close();
		if (this.shadowDepthDummy != null) this.shadowDepthDummy.close();
		MetalBridge.release(this.linearClamp);
		MetalBridge.release(this.nearestClamp);
		MetalBridge.release(this.linearRepeat);
		MetalBridge.release(this.linearMipClamp);
		MetalBridge.release(this.alwaysDepth);
		MetalBridge.release(this.quad);
	}

	@Override
	public FrameUpdateNotifier getFrameUpdateNotifier() {
		return this.updateNotifier;
	}

	@Override
	public boolean shouldDisableVanillaEntityShadows() {
		return this.shadowRenderer != null;
	}

	@Override
	public boolean shouldDisableDirectionalShading() {
		return !this.directives.isOldLighting();
	}

	@Override
	public boolean shouldDisableFrustumCulling() {
		return !this.directives.shouldUseFrustumCulling();
	}

	@Override
	public boolean shouldDisableOcclusionCulling() {
		return !this.directives.shouldUseOcclusionCulling();
	}

	@Override
	public CloudSetting getCloudSetting() {
		return this.directives.getCloudSetting();
	}

	@Override
	public boolean shouldRenderUnderwaterOverlay() {
		return this.directives.underwaterOverlay();
	}

	@Override
	public boolean shouldRenderVignette() {
		return this.directives.vignette();
	}

	@Override
	public boolean shouldRenderSun() {
		return this.directives.shouldRenderSun();
	}

	@Override
	public boolean shouldRenderWeather() {
		return this.directives.shouldRenderWeather();
	}

	@Override
	public boolean shouldRenderWeatherParticles() {
		return this.directives.shouldRenderWeatherParticles();
	}

	@Override
	public boolean shouldRenderMoon() {
		return this.directives.shouldRenderMoon();
	}

	@Override
	public boolean shouldRenderStars() {
		return this.directives.shouldRenderStars();
	}

	@Override
	public boolean shouldRenderSkyDisc() {
		return this.directives.shouldRenderSkyDisc();
	}

	@Override
	public boolean shouldWriteRainAndSnowToDepthBuffer() {
		return this.directives.rainDepth();
	}

	@Override
	public ParticleRenderingSettings getParticleRenderingSettings() {
		ParticleRenderingSettings s = this.directives.getParticleRenderingSettings();
		return s != ParticleRenderingSettings.UNSET ? s : ParticleRenderingSettings.MIXED;
	}

	@Override
	public boolean allowConcurrentCompute() {
		return false;
	}

	@Override
	public boolean hasFeature(FeatureFlags flag) {
		return this.programSet.getPack().hasFeature(flag);
	}

	@Override
	public float getSunPathRotation() {
		return this.directives.getSunPathRotation();
	}

	@Override
	public DHCompat getDHCompat() {
		return null;
	}

	@Override
	public void setIsMainBound(boolean mainBound) {
	}

	@Override
	public void onBeginClear() {
		setPhase(WorldRenderingPhase.SKY);
        var dimension = Minecraft.getInstance().level.dimensionType();
        if (shouldRenderSkyDisc() && (dimension.skybox() == net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD || dimension.hasSkyLight())) {
            Vector3d fog = CapturedRenderingState.INSTANCE.getFogColor();
            this.horizonRenderer.renderHorizon(CapturedRenderingState.INSTANCE.getGbufferModelView(),
                CapturedRenderingState.INSTANCE.getGbufferProjection(), new Vector4f((float) fog.x, (float) fog.y, (float) fog.z, 1));
        }
	}

	@Override
	public boolean supportsEndFlash() {
		return this.directives.supportsEndFlash();
	}

	@Override
	public int getAlbedoTex() {
		return 0;
	}

	public boolean isRenderingWorld() {
		return this.isRenderingWorld;
	}
}
