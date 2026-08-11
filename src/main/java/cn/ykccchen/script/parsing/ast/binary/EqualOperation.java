package cn.ykccchen.script.parsing.ast.binary;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.BinaryOperation;
import cn.ykccchen.script.parsing.ast.Expression;

/**
 * ==、===操作
 */
public class EqualOperation extends BinaryOperation {

	protected final boolean accurate;

	public EqualOperation(Expression leftOperand, Span span, Expression rightOperand, boolean accurate) {
		super(leftOperand, span, rightOperand);
		this.accurate = accurate;
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.visit(getLeftOperand())
				.visit(getRightOperand())
				.lineNumber(getSpan())
				.operator(accurate ? "accurate_equals" : "equals");
	}
}
