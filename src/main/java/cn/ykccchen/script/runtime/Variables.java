package cn.ykccchen.script.runtime;

import cn.ykccchen.script.ScriptContext;
import cn.ykccchen.script.parsing.Parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Variables {

	private final Object[] elements;

	private Variables parent;

	private int[] args;

	private int argLength = -1;

	private int scopeIndex = -1;

	public Variables(int size) {
		this.elements = new Object[size];
	}

	public Variables(Object[] elements, int scopeIndex, int[] args) {
		int len = elements.length;
		this.scopeIndex = scopeIndex;
		this.elements = new Object[len];
		this.args = args;
		this.argLength = args.length;
	}

	public Object getValue(int index) {
		Object value = elements[index];
		if (value == null) {
			return parent == null ? null : parent.getValue(index);
		}
		return value;
	}

	public void setScopeValue(int index, Object value){
		if (index > -1) {
			this.elements[index] = value;
		}
	}

	public void setValue(int index, Object value) {
		if (index > -1) {
			this.elements[index] = value;
			Variables target = parent;
			while (target != null && argLength > -1 && target.scopeIndex != this.scopeIndex) {
				for (int i = 0; i < argLength; i++) {
					if (index == this.args[i]) {
						return;
					}
				}
				target.elements[index] = value;
				target = target.parent;
			}
		}
	}

	public Variables copy(Object[] target, int scopeIndex, int... args) {
		Variables variables = new Variables(this.elements, scopeIndex, args);
		variables.parent = this;
		for (int i = 0, len = target == null ? 0 : Math.min(args.length, target.length); i < len; i++) {
			variables.setValue(args[i], target[i]);
		}
		return variables;
	}

	public Variables copy(Object target, int arg) {
		Variables variables = new Variables(this.elements, scopeIndex, new int[]{arg});
		variables.parent = this;
		variables.setValue(arg, target);
		return variables;
	}

	/**
	 * Create an isolated shallow snapshot of all currently visible slots.
	 */
	public Variables snapshot() {
		Variables snapshot = new Variables(elements.length);
		for (int i = 0; i < elements.length; i++) {
			snapshot.elements[i] = getValue(i);
		}
		return snapshot;
	}

	public Map<String, Object> getVariables(ScriptContext context) {
		Map<String, Object> variables = new LinkedHashMap<>(context.getRootVariables());
		Variables p = parent;
		List<Object[]> elements = new ArrayList<>();
		elements.add(this.elements);
		while (p != null) {
			elements.add(p.elements);
			p = p.parent;
		}
		String[] varNames = context.getVarNames();
		for (int j = elements.size() - 1; j >= 0; j--) {
			Object[] element = elements.get(j);
			for (int i = 0, len = element.length; i < len; i++) {
				Object value = element[i];
				if (value != null) {
					variables.put(varNames[i], value);
				}
			}
		}
		variables.remove(Parser.ANONYMOUS_VARIABLE);
		return variables;
	}
}
