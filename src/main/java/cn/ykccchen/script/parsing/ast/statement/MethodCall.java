package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;

import java.util.List;

public class MethodCall extends Expression {
	private final MemberAccess method;
	private final List<Expression> arguments;


	public MethodCall(Span span, MemberAccess method, List<Expression> arguments) {
		super(span);
		this.method = method;
		this.arguments = arguments;
	}

	public MemberAccess getMethod() {
		return method;
	}

	public List<Expression> getArguments() {
		return arguments;
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		method.visitMethod(compiler);
		arguments.forEach(it -> it.visitMethod(compiler));
	}


	@Override
	public void compile(ScriptCompiler compiler) {
		int size = arguments.size();
		compiler.newRuntimeContext()
				.ldc(method.getName().getText())    // 方法名
				.insn(method.isOptional() ? ICONST_1 : ICONST_0)    // 是否允许可空调用
				.asBoolean()
				.visitInt(size)
				.typeInsn(ANEWARRAY, Object.class);
		for (int i = 0; i < size; i++) {
			Expression argument = arguments.get(i);
			compiler.insn(DUP)
					.visitInt(i)
					.visit(argument)
					.insn(AASTORE);
		}
		compiler.visit(method.getObject())    // 访问目标对象
				.lineNumber(new Span(getSpan().getSource(), method.getName().getStart(), getSpan().getEnd()))
				.call("invoke_method", 5);    // 调用方法
	}
}
