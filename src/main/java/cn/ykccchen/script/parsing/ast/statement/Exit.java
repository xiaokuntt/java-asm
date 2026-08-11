package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.exception.ExitException;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.Node;
import cn.ykccchen.script.runtime.ExitValue;

import java.util.List;

public class Exit extends Node {

	private final List<Expression> expressions;

	public Exit(Span span, List<Expression> expressions) {
		super(span);
		this.expressions = expressions;
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		if (expressions != null) {
			expressions.forEach(it -> it.visitMethod(compiler));
		}
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.typeInsn(NEW, ExitException.class)
				.insn(DUP)
				.typeInsn(NEW, ExitValue.class)
				.insn(DUP);
		if (expressions == null) {
			compiler.invoke(INVOKESPECIAL, ExitValue.class, "<init>", void.class);
		} else {
			compiler.newArray(expressions)
					.invoke(INVOKESPECIAL, ExitValue.class, "<init>", void.class, Object[].class);
		}
		compiler.invoke(INVOKESPECIAL, ExitException.class, "<init>", void.class, ExitValue.class)
				.insn(ATHROW);
	}
}
