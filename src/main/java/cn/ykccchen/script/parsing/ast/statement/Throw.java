package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.exception.ScriptRuntimeException;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.Node;

public class Throw extends Node {

	private final Expression expression;

	public Throw(Span span, Expression expression) {
		super(span);
		this.expression = expression;
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		expression.visitMethod(compiler);
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.visit(expression)
				.invoke(INVOKESTATIC, ScriptRuntimeException.class, "create", ScriptRuntimeException.class, Object.class)
				.insn(ATHROW);
	}
}
