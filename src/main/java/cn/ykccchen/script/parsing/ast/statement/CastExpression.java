package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.runtime.ScriptRuntime;

/**
 * 类型强转表达式，如 (int)x、(double)x
 */
public class CastExpression extends Expression {

	private final String typeName;
	private final Expression operand;

	public CastExpression(Span span, String typeName, Expression operand) {
		super(span);
		this.typeName = typeName;
		this.operand = operand;
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.compile(operand)
				.ldc(typeName)
				.invoke(INVOKESTATIC, ScriptRuntime.class, "castTo", Object.class, Object.class, String.class);
	}
}
