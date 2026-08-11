package cn.ykccchen.script.parsing.ast.statement;

import org.objectweb.asm.Label;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.Node;
import cn.ykccchen.script.runtime.handle.OperatorHandle;

import java.util.List;

/**
 * assert expr : expr[,expr][,expr][,expr]
 */
public class Assert extends Node {

	private final Expression condition;

	private final List<Expression> expressions;

	public Assert(Span span, Expression condition, List<Expression> expressions) {
		super(span);
		this.condition = condition;
		this.expressions = expressions;
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		condition.visitMethod(compiler);
		expressions.forEach(it -> it.visitMethod(compiler));
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		Label end = new Label();
		compiler.visit(condition)
				.invoke(INVOKESTATIC, OperatorHandle.class, "isFalse", boolean.class, Object.class)
				.jump(IFEQ, end)
				.compile(new Exit(getSpan(), expressions))
				.label(end);
	}

}
