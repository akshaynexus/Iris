package net.irisshaders.iris.metal;

import mcopt.metal.PackUniforms;

import mcopt.metal.PackCompiler;

import mcopt.metal.PackGlsl;

import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.uniform.DynamicLocationalUniformHolder;
import net.irisshaders.iris.gl.uniform.Uniform;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformType;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A Metal program's uniforms: the holder Iris's uniform code (CommonUniforms, custom uniforms) registers into, with the
 * same update schedule as ProgramUniforms (dynamic every bind, once, per tick, per frame). A location is the member's
 * index in iris_Uniforms; values land in a PackUniforms through IrisRenderSystem.
 */
final class MetalUniforms implements DynamicLocationalUniformHolder {
	final PackUniforms sink;
	private final Map<String, Integer> index = new HashMap<>();
	private final Set<String> taken = new HashSet<>();
	private List<Uniform> once = new ArrayList<>();
	private final List<Uniform> perTick = new ArrayList<>(), perFrame = new ArrayList<>(), dynamic = new ArrayList<>();
	private long lastTick = -1;
	private int lastFrame = -1, lastCustomFrame = -1;
	private CustomUniforms custom;

	MetalUniforms(PackCompiler.Program program) {
		PackCompiler.Field[] fields = program.fields().values().toArray(new PackCompiler.Field[0]);
		for (int i = 0; i < fields.length; i++) this.index.put(fields[i].name(), i);
		this.sink = new PackUniforms(fields, program.uniformSize());
		// Declarations like "uniform bool heavyFog = false;" lost their initializer in the block: write it once here.
		for (PackGlsl.Member m : program.members()) {
			Integer at = this.index.get(m.name());
			double value = PackGlsl.literal(m.defaultValue());
			if (at != null && !Double.isNaN(value)) this.sink.floats(at, (float) value);
		}
	}

	/** The member index (uniform location) of name, or -1. */
	int index(String name) {
		Integer at = this.index.get(name);
		return at == null ? -1 : at;
	}

	boolean has(String name) {
		return this.index.containsKey(name);
	}

	/** Custom uniforms (shaders.properties "uniform.*") for this program; they're pushed on every update. */
	void attach(CustomUniforms custom) {
		this.custom = custom;
		custom.assignTo(this);
	}

	@Override
	public OptionalInt location(String name, UniformType type) {
		Integer at = this.index.get(name);
		if (at == null || !this.taken.add(name)) return OptionalInt.empty();
		return OptionalInt.of(at);
	}

	@Override
	public MetalUniforms addUniform(UniformUpdateFrequency frequency, Uniform uniform) {
		switch (frequency) {
			case ONCE -> this.once.add(uniform);
			case PER_TICK -> this.perTick.add(uniform);
			case PER_FRAME -> this.perFrame.add(uniform);
			default -> {
			}
		}
		return this;
	}

	@Override
	public MetalUniforms addDynamicUniform(Uniform uniform, ValueUpdateNotifier notifier) {
		this.dynamic.add(uniform);
		return this;
	}

	@Override
	public UniformHolder externallyManagedUniform(String name, UniformType type) {
		return this;
	}

	/** Runs the uniforms that are due into the sink. */
	void update() {
		IrisRenderSystem.metalUniforms = this.sink;
		try {
			for (Uniform u : this.dynamic) u.update();
			if (this.once != null) {
				for (Uniform u : this.once) u.update();
				for (Uniform u : this.perTick) u.update();
				for (Uniform u : this.perFrame) u.update();
				this.once = null;
				this.lastTick = currentTick();
				this.lastFrame = SystemTimeUniforms.COUNTER.getAsInt();
			} else {
				long tick = currentTick();
				if (tick != this.lastTick) {
					this.lastTick = tick;
					for (Uniform u : this.perTick) u.update();
				}
				int frame = SystemTimeUniforms.COUNTER.getAsInt();
				if (frame != this.lastFrame) {
					this.lastFrame = frame;
					for (Uniform u : this.perFrame) u.update();
				}
			}
			// Custom uniforms are evaluated once per frame (CustomUniforms.update): pushing them on every draw cost
			// hundreds of writes per draw for nothing. The block keeps the values between draws.
			int frame = SystemTimeUniforms.COUNTER.getAsInt();
			if (this.custom != null && frame != this.lastCustomFrame) {
				this.lastCustomFrame = frame;
				this.custom.push(this);
			}
		} finally {
			IrisRenderSystem.metalUniforms = null;
		}
	}

	private static long currentTick() {
		return Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
	}

	void free() {
		this.sink.free();
	}
}
