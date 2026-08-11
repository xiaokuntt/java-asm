package cn.ykccchen.script;

import cn.ykccchen.script.runtime.Variables;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class ScriptDebugContext extends ScriptContext {
	public enum State {
		RUNNING,
		PAUSED,
		TIMED_OUT
	}

	public enum PauseReason {
		BREAKPOINT,
		STEP
	}

	private enum StepMode {
		NONE,
		INTO,
		OVER,
		OUT
	}

	private final BlockingQueue<String> producer = new ArrayBlockingQueue<>(1);
	private final BlockingQueue<String> consumer = new LinkedBlockingQueue<>();
	private final AtomicBoolean resumePending = new AtomicBoolean();
	private volatile List<ScriptBreakpoint> breakpoints = Collections.emptyList();
	private volatile String id = UUID.randomUUID().toString().replace("-", "");
	private volatile Consumer<Map<String, Object>> callback = info -> { };
	private volatile State state = State.RUNNING;
	private volatile PauseReason pauseReason;
	private volatile StepMode stepMode = StepMode.NONE;
	private volatile int stepDepth;
	private volatile int frameDepth;
	private volatile int pausedFrameDepth;
	private volatile Map<String, Object> pausedVariables = Collections.emptyMap();

	private volatile int[] line;

	private int timeout = 60;

	private volatile boolean stepInto = false;

	public ScriptDebugContext(List<Integer> breakpoints) {
		enableCancellation();
		setBreakpoints(breakpoints);
	}

	public void setCallback(Consumer<Map<String, Object>> callback) {
		this.callback = Objects.requireNonNull(callback, "callback");
	}

	public void setTimeout(int timeout) {
		if (timeout <= 0) {
			throw new IllegalArgumentException("timeout must be greater than zero");
		}
		this.timeout = timeout;
	}

	public void setBreakpoints(List<Integer> breakpoints) {
		if (breakpoints == null || breakpoints.isEmpty()) {
			this.breakpoints = Collections.emptyList();
			return;
		}
		List<ScriptBreakpoint> definitions = new ArrayList<>();
		for (Integer breakpoint : new LinkedHashSet<>(breakpoints)) {
			if (breakpoint != null) {
				definitions.add(ScriptBreakpoint.atLine(breakpoint));
			}
		}
		setScriptBreakpoints(definitions);
	}

	public void setScriptBreakpoints(List<ScriptBreakpoint> breakpoints) {
		this.breakpoints = breakpoints == null
				? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(breakpoints));
	}

	@Override
	public synchronized void pause(int startRow, int startCol, int endRow, int endCol, Variables variables) throws InterruptedException {
		boolean stepPause = shouldPauseForStep(frameDepth);
		boolean lineBreakpoint = false;
		for (ScriptBreakpoint breakpoint : breakpoints) {
			if (breakpoint.getLine() == startRow) {
				lineBreakpoint = true;
				break;
			}
		}
		if (!stepPause && !lineBreakpoint) {
			return;
		}
		Map<String, Object> varMap = new LinkedHashMap<>(getRootVariables());
		varMap.putAll(variables.getVariables(this));
		Map<String, Object> snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(varMap));
		boolean breakpointPause = false;
		if (lineBreakpoint) {
			for (ScriptBreakpoint breakpoint : breakpoints) {
				if (breakpoint.matches(startRow, snapshot)) {
					breakpointPause = true;
					break;
				}
			}
		}
		if (!stepPause && !breakpointPause) {
			return;
		}
		if (stepPause && !stepInto) {
			stepMode = StepMode.NONE;
		}
		producer.clear();
		resumePending.set(false);
		this.line = new int[]{startRow, startCol, endRow, endCol};
		this.pausedFrameDepth = frameDepth;
		this.pausedVariables = snapshot;
		this.pauseReason = breakpointPause ? PauseReason.BREAKPOINT : PauseReason.STEP;
		this.state = State.PAUSED;
		callback.accept(getDebugInfo(snapshot));
		consumer.offer(this.id);
		String command = producer.poll(timeout, TimeUnit.SECONDS);
		this.state = command == null ? State.TIMED_OUT : State.RUNNING;
	}

	private boolean shouldPauseForStep(int depth) {
		if (stepInto) {
			return true;
		}
		switch (stepMode) {
			case INTO:
				return true;
			case OVER:
				return depth <= stepDepth;
			case OUT:
				return depth < stepDepth;
			default:
				return false;
		}
	}

	public void await() throws InterruptedException {
		consumer.take();
	}

	public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
		return consumer.poll(timeout, unit) != null;
	}

	public void signal() {
		if (state == State.PAUSED && resumePending.compareAndSet(false, true)) {
			if (!producer.offer(this.id)) {
				resumePending.set(false);
			}
		}
	}

	public void resume() {
		stepMode = StepMode.NONE;
		signal();
	}

	public void stepInto() {
		stepMode = StepMode.INTO;
		stepDepth = pausedFrameDepth;
		signal();
	}

	public void stepOver() {
		stepMode = StepMode.OVER;
		stepDepth = pausedFrameDepth;
		signal();
	}

	public void stepOut() {
		stepMode = StepMode.OUT;
		stepDepth = pausedFrameDepth;
		signal();
	}

	@Override
	public void cancel() {
		super.cancel();
		signal();
	}

	/**
	 * @deprecated Use {@link #signal()} and call {@link #await()} separately
	 * when waiting for a later breakpoint is desired.
	 */
	@Deprecated
	public void singal() throws InterruptedException {
		signal();
	}

	public void setStepInto(boolean stepInto) {
		this.stepInto = stepInto;
		if (!stepInto) {
			this.stepMode = StepMode.NONE;
		}
	}

	public State getState() {
		return state;
	}

	public PauseReason getPauseReason() {
		return pauseReason;
	}

	public int getCurrentLine() {
		return line == null ? -1 : line[0];
	}

	public int getFrameDepth() {
		return state == State.PAUSED ? pausedFrameDepth : frameDepth;
	}

	public Map<String, Object> getPausedVariables() {
		return pausedVariables;
	}

	public Object evaluate(String expression) {
		if (state != State.PAUSED) {
			throw new IllegalStateException("script is not paused");
		}
		ScriptContext evaluationContext = new ScriptContext(new LinkedHashMap<>(pausedVariables));
		evaluationContext.setScriptName(getScriptName());
		evaluationContext.setEngineConfig(getEngineConfig());
		return Script.create(true, Objects.requireNonNull(expression, "expression"), null)
				.execute(evaluationContext);
	}

	public void enterFrame() {
		frameDepth++;
	}

	public void exitFrame() {
		if (frameDepth > 0) {
			frameDepth--;
		}
	}

	private Map<String, Object> getDebugInfo(Map<String, Object> variables) {
		List<Map<String, Object>> varList = new ArrayList<>();
		Set<Map.Entry<String, Object>> entries = variables.entrySet();
		for (Map.Entry<String, Object> entry : entries) {
			Object value = entry.getValue();
			Map<String, Object> variable = new HashMap<>();
			variable.put("name", entry.getKey());
			if (value != null) {
				variable.put("value", value);
				variable.put("type", value.getClass());
			} else {
				variable.put("value", "null");
			}
			varList.add(variable);
		}
		Collections.reverse(varList);
		Map<String, Object> info = new HashMap<>();
		info.put("variables", varList);
		info.put("range", line);
		return info;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}
}
