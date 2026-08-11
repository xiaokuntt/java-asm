package cn.ykccchen.script.runtime;

import cn.ykccchen.script.ScriptContext;
import cn.ykccchen.script.exception.ScriptErrorCode;
import cn.ykccchen.script.exception.ScriptRuntimeException;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.runtime.function.ScriptLambdaFunction;

import java.util.List;

public abstract class ScriptRuntime {

	protected ScriptContext context;

	private String[] varNames;

	private List<Span> spans;

	public abstract Object execute(ScriptContext context);

	public String[] getVarNames() {
		return varNames;
	}

	public void setVarNames(String[] varNames) {
		this.varNames = varNames;
	}

	public void setSpans(List<Span> spans) {
		this.spans = spans;
	}

	public Span getSpan(int index){
		return spans.get(index);
	}

	public List<Span> getSpans() {
		return spans;
	}

	protected ScriptLambdaFunction createLambda(ScriptLambdaFunction function, Variables variables){
		return (var, arguments) -> function.apply(variables, arguments);
	}

	/**
	 * 类型强转：(int)x、(double)x 等
	 */
	public static Object castTo(Object value, String typeName) {
		if (value == null) return null;
		if (value instanceof Number) {
			Number n = (Number) value;
			switch (typeName) {
				case "int":
				case "Integer": return n.intValue();
				case "long":
				case "Long": return n.longValue();
				case "double":
				case "Double": return n.doubleValue();
				case "float":
				case "Float": return n.floatValue();
				case "byte":
				case "Byte": return n.byteValue();
				case "short":
				case "Short": return n.shortValue();
				case "char":
				case "Character": return (char) n.intValue();
				case "boolean":
				case "Boolean": return n.intValue() != 0;
			}
		}
		if ("String".equals(typeName) || "java.lang.String".equals(typeName)) {
			return String.valueOf(value);
		}
		if (("char".equals(typeName) || "Character".equals(typeName))
				&& value instanceof Character) {
			return value;
		}
		if (("boolean".equals(typeName) || "Boolean".equals(typeName))
				&& value instanceof Boolean) {
			return value;
		}
		throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_TYPE_CONVERSION_ERROR,
				String.format("不能将%s转换为%s", value.getClass().getName(), typeName));
	}

}
