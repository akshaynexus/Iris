package net.irisshaders.iris.metal;

import mcopt.metal.PackUniforms;

import mcopt.metal.PackCompiler;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import mcopt.metal.MetalBridge;
import mcopt.metal.MetalHooks;
import mcopt.metal.PassDelegate;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.helpers.MatrixUtils;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.VanillaUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.gl.state.FogMode;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * The gbuffer half of the pack on Metal. While the world draws, every frontend pass aimed at Minecraft's main color
 * target is redirected (mcopt's MetalHooks) to the pack's gbuffer targets, and this delegate swaps each of the game's
 * pipelines for the pack program IrisPipelines picks for it (what MixinShaderManager_Overrides does on GL). The game
 * still binds its own uniforms (transforms, fog, atlas, lightmap); the delegate maps them to the program's slots and
 * binds the pack's own textures and uniform block.
 */
public final class MetalGbuffers implements MetalHooks.PassRedirector, PassDelegate {
	/** Metal buffer slot of the constant (zero) vertex buffer that feeds inputs the game's format doesn't have. */
	private static final int CONSTANT_SLOT = 25;
	/** Backend pipeline to the RenderPipeline it was built for (see MixinRenderSystem_MetalOnly). */
	private static final Map<Object, RenderPipeline> PIPELINES = Collections.synchronizedMap(new WeakHashMap<>());

	private final MetalPackPipeline pipeline;
	private final ProgramFallbackResolver resolver;
	/** Pack targets every gbuffer pass carries, in attachment order (Metal: at most 8). */
	final int[] union;
	/** The same for the shadow pass: shadowcolor indices. */
	final int[] shadowUnion;
	private boolean shadow;
	private final Map<ShaderKey, Optional<Gbuffer>> programs = new EnumMap<>(ShaderKey.class);
	private final Map<Long, Long> depthStates = new HashMap<>();
	private final Set<String> warned = new HashSet<>();
	private final long constants;
	private final Matrix4f tmp4 = new Matrix4f();
	private final Matrix3f tmp3 = new Matrix3f();
	private final float[] floats16 = new float[16], floats9 = new float[9];
	/** Everything setPipeline works out for one game pipeline in one kind of pass, so it's done once, not per switch. */
	private record Bound(long pso, long depthState, int[] slots, Set<String> fromGame) {
	}

	private record BoundKey(ShaderKey key, Object backend, boolean shadow, boolean depth) {
	}

	private final Map<BoundKey, Bound> bound = new HashMap<>();
    private long[] originalColors, redirectedColors;
    private long originalDepth, redirectedDepth;
    private int originalWidth, originalHeight, redirectedWidth, redirectedHeight;
    private boolean passthrough, encoderRestart;
	private boolean hasDepth;
	private @Nullable Gbuffer current;
	private Set<Integer> readsAlt = Set.of();

	/** A gbuffer program with what it needs at draw time. */
	/**
	 * overridden: the pack set the program's blending ("blend.<program>"), blend being null for "off"; otherwise the game
	 * pipeline's own blending applies. bufferBlend: per draw buffer index ("blend.<program>.<buffer>"), null value = off.
	 */
	private record Gbuffer(ShaderKey key, MetalProgram program, int[] remap, boolean overridden, BlendMode blend, Map<Integer, BlendMode> bufferBlend,
						   AlphaTest alpha, int modelViewInverse, int normalMat, int projInverse) {
	}

	public static void remember(Object backend, RenderPipeline pipeline) {
		PIPELINES.put(backend, pipeline);
	}

	MetalGbuffers(MetalPackPipeline pipeline) {
		this.pipeline = pipeline;
		this.resolver = new ProgramFallbackResolver(pipeline.programSet);
		TreeSet<Integer> targets = new TreeSet<>();
		for (ProgramId id : ProgramId.values()) {
			if (id.name().startsWith("Shadow") || id.name().startsWith("Dh") || id == ProgramId.Final) continue;
			this.resolver.resolve(id).ifPresent(source -> {
				for (int b : source.getDirectives().getDrawBuffers()) targets.add(b);
			});
		}
		targets.add(0); // GL fallback shaders write colortex0.
		List<Integer> list = new ArrayList<>(targets);
		if (list.size() > 8) {
			throw new IllegalStateException("Metal gbuffer attachment union exceeds 8: " + list);
		}
		this.union = list.stream().mapToInt(Integer::intValue).toArray();
		TreeSet<Integer> shadowTargets = new TreeSet<>();
		for (ProgramId id : ProgramId.values()) {
			if (!id.name().startsWith("Shadow")) continue;
			this.resolver.resolve(id).ifPresent(source -> {
				for (int b : source.getDirectives().getDrawBuffers()) if (b < pipeline.shadowColors.length) shadowTargets.add(b);
			});
		}
		shadowTargets.add(0);
		this.shadowUnion = shadowTargets.stream().mapToInt(Integer::intValue).toArray();
		this.constants = MetalBridge.newBuffer(pipeline.ctx, 256);
		MemoryUtil.memSet(MetalBridge.bufferContents(this.constants), 0, 256);
		Iris.logger.info("Metal: gbuffer passes draw into colortex {}", Arrays.toString(this.union));
	}

	private final List<MetalProgram> ownedPrograms = new ArrayList<>();

	void destroy() {
		this.bound.clear();
		this.ownedPrograms.forEach(MetalProgram::destroy);
		this.ownedPrograms.clear();
		this.programs.clear();
		this.depthStates.values().forEach(MetalBridge::release);
		MetalBridge.release(this.constants);
	}

	private void warnOnce(String message) {
		if (this.warned.add(message)) Iris.logger.warn("Metal: " + message);
	}

	// --- Redirect ---

	@Override
	public MetalHooks.@Nullable Redirect redirect(RenderPassDescriptor descriptor) {
		if (!this.pipeline.isRenderingWorld() || descriptor.colorAttachments().isEmpty()) return null;
		var first = descriptor.colorAttachments().get(0);
		GpuTexture main = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();
		if (first == null || first.textureView().texture() != main) return null;
        this.originalColors = descriptor.colorAttachments().stream()
            .mapToLong(a -> a == null ? 0 : MetalBridge.textureHandle(a.textureView().texture())).toArray();
        this.originalDepth = descriptor.depthAttachment() == null ? 0 : MetalBridge.textureHandle(descriptor.depthAttachment().textureView().texture());
        this.originalWidth = main.getWidth(0);
        this.originalHeight = main.getHeight(0);
		this.shadow = ShadowRenderingState.areShadowsCurrentlyBeingRendered();
		if (this.shadow) {
			if (!this.pipeline.hasShadows()) return null;
			this.readsAlt = this.pipeline.flippedBeforeShadow();
			long[] colors = new long[this.shadowUnion.length];
			for (int i = 0; i < colors.length; i++) colors[i] = this.pipeline.shadowColors[this.shadowUnion[i]];
			int res = this.pipeline.shadowResolution;
			this.redirectedColors = colors; this.redirectedDepth = this.pipeline.shadowDepth;
            this.redirectedWidth = res; this.redirectedHeight = res;
            return new MetalHooks.Redirect(colors, null, res, res, this.pipeline.shadowDepth, MetalHooks.Redirect.NO_CLEAR, this);
		}
		this.readsAlt = this.pipeline.gbufferReadsAlt();
		long[] colors = new long[this.union.length];
		for (int i = 0; i < this.union.length; i++) {
			MetalTargets.Target t = this.pipeline.targets.get(this.union[i]);
			colors[i] = t.get(this.readsAlt.contains(t.index));
		}
		this.redirectedColors = colors; this.redirectedDepth = this.originalDepth;
        this.redirectedWidth = this.originalWidth; this.redirectedHeight = this.originalHeight;
        return new MetalHooks.Redirect(colors, null, main.getWidth(0), main.getHeight(0), 0, MetalHooks.Redirect.NO_CLEAR, this);
	}

	// --- Delegate ---

	@Override
	public void begin(long enc, boolean hasDepth) {
		this.passthrough = false;
		this.encoderRestart = false;
		this.hasDepth = hasDepth;
		this.current = null;
	}

	@Override
	public int @Nullable [] setPipeline(long enc, Object backend, List<BindGroupLayout.UniformDescription> uniforms) {
		this.current = null;
		RenderPipeline renderPipeline = PIPELINES.get(backend);
		BackendRenderPipeline.CreateInfo info = MetalBridge.createInfo(backend);
		if (renderPipeline == null || info == null) {
			warnOnce("Unmapped device pipeline: " + (info == null ? backend : info.name()));
			return passthrough(enc, backend, uniforms);
		}
		ShaderKey key = IrisPipelines.getPipeline(this.pipeline, renderPipeline);
		if (key == null) {
			warnOnce("No shader key for " + renderPipeline.getLocation());
			return passthrough(enc, backend, uniforms);
		}
		Gbuffer g = this.programs.computeIfAbsent(key, this::create).orElse(null);
		if (g == null) return null;

		BoundKey boundKey = new BoundKey(key, backend, this.shadow, this.hasDepth);
		Bound b = this.bound.get(boundKey);
		if (b == null) {
			b = bind(g, renderPipeline, info, backend, uniforms);
			if (b == null) return null;
			this.bound.put(boundKey, b);
		}
		if (this.passthrough) {
            MetalBridge.renderBegin(enc, this.redirectedColors, null, this.redirectedDepth, false, 0, this.redirectedWidth, this.redirectedHeight);
            this.passthrough = false; this.encoderRestart = true;
        }
		var depth = info.depthStencilState();
		MetalBridge.bindPipeline(enc, b.pso(), b.depthState(), info.cull() && !this.shadow, depth != null ? depth.depthBiasConstant() : 0,
			depth != null ? depth.depthBiasScaleFactor() : 0, MetalBridge.primitive(info.primitiveTopology()));
		this.pipeline.bindTextures(MetalBridge.encoder(), enc, g.program(), this.readsAlt, b.fromGame(), TextureStage.GBUFFERS_AND_SHADOW);
		MetalBridge.buffer(enc, CONSTANT_SLOT, this.constants, 0);
		this.current = g;
		return b.slots();
	}

    private int[] passthrough(long enc, Object backend, List<BindGroupLayout.UniformDescription> uniforms) {
        if (!this.passthrough) {
            MetalBridge.renderBegin(enc, this.originalColors, null, this.originalDepth, false, 0, this.originalWidth, this.originalHeight);
            this.passthrough = true;
            this.encoderRestart = true;
        }
        MetalBridge.bindDevicePipeline(enc, backend, this.originalDepth != 0);
        return java.util.stream.IntStream.range(0, uniforms.size()).toArray();
    }

    @Override public boolean takeEncoderRestart() {
        boolean restart = this.encoderRestart;
        this.encoderRestart = false;
        return restart;
    }

	private @Nullable Bound bind(Gbuffer g, RenderPipeline renderPipeline, BackendRenderPipeline.CreateInfo info, Object backend,
								 List<BindGroupLayout.UniformDescription> uniforms) {
		ShaderKey key = g.key();
		long pso;
		try {
			pso = g.program().pipeline(describe(g, renderPipeline, info));
		} catch (RuntimeException e) {
			warnOnce(key + " with " + renderPipeline.getLocation() + ": " + e.getMessage());
			return null;
		}
		boolean write = info.depthStencilState() != null && info.depthStencilState().writeDepth();
		int gameCompare = MetalBridge.depthCompare(backend);
		// The game uses reverse depth, but the pack's shadow projection and comparison sampler use forward depth.
		// Match the GL runtime: clear 1, retain the nearest shadow caster, and sample with LEQUAL.
		int compare = this.shadow ? switch (gameCompare) { case 1 -> 4; case 3 -> 6; case 4 -> 1; case 6 -> 3; default -> gameCompare; } : gameCompare;
		long state = this.depthStates.computeIfAbsent(compare * 2L + (write ? 1 : 0), k -> MetalBridge.depthStateNew(this.pipeline.ctx, compare, write));

		// The game's uniforms land in the program's slots; the rest of the program's textures are the pack's.
		int[] slots = new int[uniforms.size()];
		Set<String> fromGame = new HashSet<>();
		for (int i = 0; i < slots.length; i++) {
			BindGroupLayout.UniformDescription u = uniforms.get(i);
			String name = u.type() == UniformType.UNIFORM_BUFFER ? buffer(g.program(), u.name()) : texture(g.program(), u.name());
			if (name == null) {
				slots[i] = -1;
			} else if (u.type() == UniformType.UNIFORM_BUFFER) {
				slots[i] = g.program().compiled.bufferSlots().get(name);
			} else {
				slots[i] = g.program().compiled.textureSlots().get(name);
				fromGame.add(name);
			}
		}
		return new Bound(pso, state, slots, fromGame);
	}

	private static final List<String> ALBEDO = List.of("tex", "gtexture", "texture", "iris_Sampler0", "u_MainSampler", "Sampler0");
	private static final List<String> LIGHTMAP = List.of("lightmap", "iris_LightmapTexture", "iris_Sampler2", "Sampler2");
	private static final List<String> OVERLAY = List.of("iris_overlay", "iris_Sampler1", "Sampler1");

	/** The program's block for a game uniform buffer (Iris prefixes vanilla's blocks with iris_). */
	private static @Nullable String buffer(MetalProgram program, String game) {
		Map<String, Integer> slots = program.compiled.bufferSlots();
		if (slots.containsKey(game)) return game;
		if (slots.containsKey("iris_" + game)) return "iris_" + game;
		return null;
	}

	/** The program's sampler for a game texture uniform. */
	private static @Nullable String texture(MetalProgram program, String game) {
		Map<String, Integer> slots = program.compiled.textureSlots();
		List<String> candidates = switch (game) {
			case "Sampler0", "u_BlockTex" -> ALBEDO;
			case "Sampler1" -> OVERLAY;
			case "Sampler2", "u_LightTex" -> LIGHTMAP;
			default -> List.of(game, "iris_" + game);
		};
		for (String c : candidates) {
			if (slots.containsKey(c)) return c;
		}
		return null;
	}

	/** mc_pipeline_new's descriptor: the game's vertex format under the program's input names, the union targets, depth. */
	private int[] describe(Gbuffer g, RenderPipeline renderPipeline, BackendRenderPipeline.CreateInfo info) {
		PackCompiler.Program c = g.program().compiled;
		List<Integer> d = new ArrayList<>();
		List<VertexFormat> formats = renderPipeline.getVertexFormatBindings();
		Map<String, Integer> inputs = c.vertexInputs();
		Set<String> fed = new HashSet<>();
		List<int[]> attributes = new ArrayList<>();
		int buffers = 0;
		List<int[]> layouts = new ArrayList<>();
		for (int slot = 0; slot < formats.size(); slot++) {
			VertexFormat format = formats.get(slot);
			if (format == null) continue;
			int metalSlot = MetalBridge.vertexBufferSlot(slot);
			layouts.add(new int[] {metalSlot, format.getVertexSize(), format.getStepRate()});
			buffers++;
			for (VertexFormatElement e : format.getElements()) {
				String name = inputs.containsKey("iris_" + e.name()) ? "iris_" + e.name() : inputs.containsKey(e.name()) ? e.name() : null;
				if (name == null || !fed.add(name)) continue;
				attributes.add(new int[] {inputs.get(name), metalSlot, e.offset(), matchSign(MetalBridge.vertexFormat(e.format()), c.vertexInputTypes().get(name))});
			}
		}
		boolean constant = false;
		for (var input : inputs.entrySet()) {
			if (fed.contains(input.getKey())) continue;
			constant = true;
			attributes.add(new int[] {input.getValue(), CONSTANT_SLOT, 0, constantFormat(c.vertexInputTypes().get(input.getKey()))});
		}
		if (constant) {
			// Metal rejects stride 0: a per-instance step this long makes every vertex read element 0 (all zero).
			layouts.add(new int[] {CONSTANT_SLOT, 16, Integer.MAX_VALUE});
			buffers++;
		}
		d.add(buffers);
		for (int[] l : layouts) for (int x : l) d.add(x);
		d.add(attributes.size());
		for (int[] a : attributes) for (int x : a) d.add(x);

		ColorTargetState game = info.colorTargetStates().isEmpty() ? null : info.colorTargetStates().get(0);
		int[] union = this.shadow ? this.shadowUnion : this.union;
		d.add(union.length);
		Set<Integer> written = new HashSet<>();
		for (int r : g.remap()) written.add(r);
		for (int i = 0; i < union.length; i++) {
			int format = this.shadow ? this.pipeline.shadowColorFormats[union[i]] : this.pipeline.targets.get(union[i]).mtlFormat;
			boolean write = written.contains(i);
			int[] color;
			if (g.bufferBlend().containsKey(union[i])) color = MetalProgram.color(format, write, g.bufferBlend().get(union[i]));
			else if (g.overridden()) color = MetalProgram.color(format, write, g.blend());
			else color = gameBlend(format, write, game);
			if (game != null) color[1] &= (game.writeRed() ? 8 : 0) | (game.writeGreen() ? 4 : 0) | (game.writeBlue() ? 2 : 0) | (game.writeAlpha() ? 1 : 0);
			for (int x : color) d.add(x);
		}
		d.add(this.shadow ? 252 : this.hasDepth ? this.pipeline.targets.depthFormat : 0);
		d.add(MetalBridge.topologyClass(info.primitiveTopology()));
		return d.stream().mapToInt(Integer::intValue).toArray();
	}

	/** The game pipeline's own blending, on a pack target. */
	private static int[] gameBlend(int format, boolean write, @Nullable ColorTargetState game) {
		if (game == null || game.blendFunction().isEmpty()) return MetalProgram.color(format, write, null);
		BlendFunction b = game.blendFunction().get();
		return new int[] {format, write ? 15 : 0, 1, MetalBridge.blendFactor(b.color().sourceFactor()), MetalBridge.blendFactor(b.color().destFactor()),
			MetalBridge.blendOp(b.color().op()), MetalBridge.blendFactor(b.alpha().sourceFactor()), MetalBridge.blendFactor(b.alpha().destFactor()),
			MetalBridge.blendOp(b.alpha().op())};
	}

	/**
	 * GL converts any integer attribute to the shader's int or uint input; Metal only reads signed formats into int and
	 * unsigned ones into uint. Swap to the same-size format of the sign the input wants.
	 */
	static int matchSign(int format, String type) {
		if (type == null) return format;
		boolean wantSigned = type.startsWith("ivec") || type.equals("int");
		boolean wantUnsigned = type.startsWith("uvec") || type.equals("uint");
		int[][] pairs = {{1, 4}, {2, 5}, {3, 6}, {13, 16}, {14, 17}, {15, 18}, {36, 32}, {37, 33}, {38, 34}, {39, 35}, {45, 46}, {49, 50}};
		for (int[] p : pairs) {
			if (wantSigned && format == p[0]) return p[1];
			if (wantUnsigned && format == p[1]) return p[0];
		}
		return format;
	}

	/** MTLVertexFormat for a constant input of a GLSL type. */
	private static int constantFormat(String type) {
		return switch (type) {
			case "int" -> 32;
			case "ivec2" -> 33;
			case "ivec3" -> 34;
			case "ivec4" -> 35;
			case "uint" -> 36;
			case "uvec2" -> 37;
			case "uvec3" -> 38;
			case "uvec4" -> 39;
			case "vec2" -> 29;
			case "vec3" -> 30;
			case "vec4" -> 31;
			default -> 28;
		};
	}

    /** ShaderCreator's GL fallback sources and value semantics, using the device projection unchanged. */
    private Gbuffer createFallback(ShaderKey key) {
        VertexFormat format = key.getVertexFormat() != null ? key.getVertexFormat()
            : WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat();
        ShaderAttributeInputs inputs = new ShaderAttributeInputs(format, key.shouldIgnoreLightmap(), false,
            key.isText(), key.hasDiffuseLighting(), false);
        boolean leash = format == com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_LIGHTMAP;
        String vertex = net.irisshaders.iris.pipeline.fallback.ShaderSynthesizer.vsh(true, inputs, key.getFogMode(), key == ShaderKey.GLINT, leash);
        String fragment = net.irisshaders.iris.pipeline.fallback.ShaderSynthesizer.fsh(inputs, key.getFogMode(), key.getAlphaTest(), key.isIntensity(), leash)
            .replace("out vec4 fragColor;", "layout(location = 0) out vec4 fragColor;");
        int slot = java.util.Arrays.binarySearch(key.isShadow() ? this.shadowUnion : this.union, 0);
        MetalProgram program = new MetalProgram(this.pipeline.ctx, key.getName() + "_fallback", vertex, fragment,
            new int[] {slot}, Map.of());
        this.ownedPrograms.add(program);
        program.uniforms.uniform1f(net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.ONCE,
            "AlphaTestValue", () -> key.getAlphaTest().reference());
        program.uniforms.uniform1f(net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME,
            "FogDensity", () -> Math.max(0, CapturedRenderingState.INSTANCE.getFogDensity()));
        program.uniforms.uniform1i(net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME,
            "FogIsExp2", () -> CapturedRenderingState.INSTANCE.getFogDensity() >= 0 ? 1 : 0);
        return new Gbuffer(key, program, new int[] {slot}, key.isShadow(), null, Map.of(), key.getAlphaTest(), -1, -1, -1);
    }

	private Optional<Gbuffer> create(ShaderKey key) {
		Optional<ProgramSource> found = this.resolver.resolve(key.getProgram());
		if (found.isEmpty()) {
			return Optional.of(createFallback(key));
		}
		ProgramSource source = found.get();
		try {
			AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(key.getAlphaTest());
			boolean isLines = key.getProgram() == ProgramId.Line && this.resolver.has(ProgramId.Line);
			VertexFormat vertexFormat = key.getVertexFormat() != null ? key.getVertexFormat() : WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat();
			Map<PatchShaderType, String> transformed;
			if (key.patch == Patch.SODIUM) {
				transformed = TransformPatcher.patchSodium(key.getName(), source.getVertexSource().orElseThrow(), source.getGeometrySource().orElse(null),
					source.getTessControlSource().orElse(null), source.getTessEvalSource().orElse(null), source.getFragmentSource().orElseThrow(), alpha,
					this.pipeline.getTextureMap(), this.pipeline.getTextureOverrides(TextureStage.GBUFFERS_AND_SHADOW), key.isShadow());
			} else {
				ShaderAttributeInputs inputs = new ShaderAttributeInputs(vertexFormat, key.shouldIgnoreLightmap(), isLines, key.isGlint(), key.isText(), false);
				transformed = TransformPatcher.patchVanilla(key.getName(), source.getVertexSource().orElseThrow(), source.getGeometrySource().orElse(null),
					source.getTessControlSource().orElse(null), source.getTessEvalSource().orElse(null), source.getFragmentSource().orElseThrow(), alpha, isLines,
					key == ShaderKey.CLOUDS, true, inputs, this.pipeline.getTextureMap(), this.pipeline.getTextureOverrides(TextureStage.GBUFFERS_AND_SHADOW));
			}
			ShaderPrinter.printProgram(key.getName()).addSources(transformed).print();
			if (transformed.get(PatchShaderType.GEOMETRY) != null || transformed.get(PatchShaderType.TESS_EVAL) != null) {
				warnOnce(key + " uses geometry or tessellation shaders, which Metal doesn't have: its draws are skipped");
				return Optional.empty();
			}
			int[] drawBuffers = source.getDirectives().getDrawBuffers();
			int[] remap = new int[drawBuffers.length];
			int[] union = key.isShadow() ? this.shadowUnion : this.union;
			for (int i = 0; i < drawBuffers.length; i++) {
				int at = -1;
				for (int u = 0; u < union.length; u++) if (union[u] == drawBuffers[i]) at = u;
				if (at < 0) throw new IllegalStateException("No attachment for target " + drawBuffers[i]);
				remap[i] = at;
			}
			MetalProgram program = new MetalProgram(this.pipeline.ctx, key.getName(), transformed.get(PatchShaderType.VERTEX),
				transformed.get(PatchShaderType.FRAGMENT), remap, Map.of("iris_SodiumPushConstants", MetalBridge.firstReservedBufferSlot()));
			this.ownedPrograms.add(program);
			// The same uniform set ShaderCreator gives ExtendedShader on GL.
			CommonUniforms.addDynamicUniforms(program.uniforms, FogMode.PER_VERTEX);
			program.uniforms.attach(this.pipeline.customUniforms);
			BuiltinReplacementUniforms.addBuiltinReplacementUniforms(program.uniforms);
			VanillaUniforms.addVanillaUniforms(program.uniforms);
			BlendModeOverride blend = source.getDirectives().getBlendModeOverride().orElse(key.getProgram().getBlendModeOverride());
			Map<Integer, BlendMode> bufferBlend = new HashMap<>();
			source.getDirectives().getBufferBlendOverrides().forEach(b -> bufferBlend.put(b.index(), b.blendMode()));
			Gbuffer g = new Gbuffer(key, program, remap, blend != null, blend == null ? null : blend.getBlendMode(), bufferBlend, alpha,
				program.uniforms.index("iris_ModelViewMatInverse"), program.uniforms.index("iris_NormalMat"), program.uniforms.index("iris_ProjMatInverse"));
			Iris.logger.info("Metal: compiled {} for {}", source.getName(), key);
			return Optional.of(g);
		} catch (RuntimeException e) {
			Iris.logger.error("Metal: couldn't build " + source.getName() + " for " + key, e);
			return Optional.empty();
		}
	}

	@Override
	public boolean beforeDraw(long enc) {
		Gbuffer g = this.current;
		if (g == null) return this.passthrough;
		CapturedRenderingState.INSTANCE.setCurrentAlphaTest(g.alpha().reference());
		PackUniforms sink = g.program().uniforms.sink;
		if (g.modelViewInverse() >= 0 || g.normalMat() >= 0) {
			Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
			if (g.modelViewInverse() >= 0) sink.matrix(g.modelViewInverse(), modelView.invert(this.tmp4).get(this.floats16));
			if (g.normalMat() >= 0) sink.matrix(g.normalMat(), modelView.invert(this.tmp4).transpose3x3(this.tmp3).get(this.floats9));
		}
		if (g.projInverse() >= 0) {
			sink.matrix(g.projInverse(), CapturedRenderingState.INSTANCE.getGbufferProjection().invert(this.tmp4).get(this.floats16));
		}
		g.program().bindUniforms(enc);
		return true;
	}

	@Override
	public void end(long enc) {
		this.current = null;
	}
}
