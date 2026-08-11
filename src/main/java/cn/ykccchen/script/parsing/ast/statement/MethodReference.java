package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;

/**
 * A bound method reference such as {@code orderService::findOrder}.
 */
public class MethodReference extends Expression {

	private final Expression target;
	private final Span methodName;

	public MethodReference(Span span, Expression target, Span methodName) {
		super(span);
		this.target = target;
		this.methodName = methodName;
	}

	public Expression getTarget() {
		return target;
	}

	public Span getMethodName() {
		return methodName;
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		target.visitMethod(compiler);
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.newRuntimeContext()
				.visit(target)
				.ldc(methodName.getText())
				.lineNumber(methodName)
				.call("method_reference", 3);
	}
}
