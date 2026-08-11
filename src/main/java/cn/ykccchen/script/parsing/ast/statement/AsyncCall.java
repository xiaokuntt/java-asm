package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.LanguageAsyncRuntime;
import cn.ykccchen.script.ScriptAsyncResult;
import cn.ykccchen.script.ScriptContext;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.VarIndex;
import cn.ykccchen.script.parsing.ast.Expression;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Language-level asynchronous expression.
 */
public final class AsyncCall extends Expression {

	private final LambdaFunction expression;

	public AsyncCall(Span span, Expression expression) {
		super(span);
		if (expression instanceof LambdaFunction) {
			this.expression = (LambdaFunction) expression;
		} else {
			this.expression = new LambdaFunction(span, Collections.emptyList(),
					Collections.singletonList(new Return(span, expression)));
		}
		this.expression.setAsync(true);
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		expression.visitMethod(compiler);
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		List<VarIndex> parameters = expression.getParameters();
		compiler.loadContext().compile(expression);
		if (parameters.isEmpty()) {
			compiler.insn(ACONST_NULL);
		} else if (parameters.size() == 1) {
			compiler.load(parameters.get(0));
		} else {
			compiler.visitInt(parameters.size()).typeInsn(ANEWARRAY, Object.class);
			for (int index = 0; index < parameters.size(); index++) {
				compiler.insn(DUP)
						.visitInt(index)
						.load(parameters.get(index))
						.insn(AASTORE);
			}
		}
		compiler.invoke(INVOKESTATIC, LanguageAsyncRuntime.class, "submit", ScriptAsyncResult.class,
				ScriptContext.class, Function.class, Object.class);
	}
}
