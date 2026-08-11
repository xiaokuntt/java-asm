package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;

import java.util.List;

public class NewStatement extends Expression {

	private final List<Expression> arguments;

	private final Expression target;

	public NewStatement(Span span, Expression target, List<Expression> arguments) {
		super(span);
		this.target = target;
		this.arguments = arguments;
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		target.visitMethod(compiler);
		arguments.forEach(it -> it.visitMethod(compiler));
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.newRuntimeContext()
				.visit(target)    // 访问目标
				.newArray(arguments)    // 访问参数
				.lineNumber(getSpan())
				.call("invoke_new_instance", 3);    // 执行new操作
	}
}
