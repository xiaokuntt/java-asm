package cn.ykccchen.script.parsing.ast;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;

/**
 * 常量
 */
public abstract class Literal extends Expression {

	protected Object value = null;

	protected Literal(Span span) {
		super(span);
	}

	protected Literal(Span span, Object value) {
		super(span);
		this.value = value;
	}

	public void setValue(Object value) {
		this.value = value;
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.ldc(value);
	}
}
