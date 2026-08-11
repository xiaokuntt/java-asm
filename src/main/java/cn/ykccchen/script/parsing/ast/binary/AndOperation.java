package cn.ykccchen.script.parsing.ast.binary;

import org.objectweb.asm.Label;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.BinaryOperation;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.runtime.handle.OperatorHandle;

/**
 * {@code &&} 操作
 */
public class AndOperation extends BinaryOperation {

	public AndOperation(Expression leftOperand, Span span, Expression rightOperand) {
		super(leftOperand, span, rightOperand);
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		Label end = new Label();
		compiler.visit(getLeftOperand())
				.insn(DUP)
				.invoke(INVOKESTATIC, OperatorHandle.class, "isTrue", boolean.class, Object.class)
				.jump(IFEQ, end)
				.insn(POP)
				.visit(getRightOperand())
				.label(end);
	}
}
