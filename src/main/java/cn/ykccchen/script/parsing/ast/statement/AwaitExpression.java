package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.LanguageAsyncRuntime;
import cn.ykccchen.script.ScriptContext;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;

/**
 * Waits for a Future or CompletionStage and returns its value.
 */
public final class AwaitExpression extends Expression {

	private final Expression expression;

	public AwaitExpression(Span span, Expression expression) {
		super(span);
		this.expression = expression;
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		expression.visitMethod(compiler);
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.loadContext()
				.compile(expression)
				.invoke(INVOKESTATIC, LanguageAsyncRuntime.class, "await", Object.class,
						ScriptContext.class, Object.class);
	}
}
