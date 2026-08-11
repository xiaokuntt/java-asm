package cn.ykccchen.script.parsing.ast.binary;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;

/**
 * !=、!==操作
 */
public class NotEqualOperation extends EqualOperation {

	public NotEqualOperation(Expression leftOperand, Span span, Expression rightOperand, boolean accurate) {
		super(leftOperand, span, rightOperand, accurate);
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.visit(getLeftOperand())
				.visit(getRightOperand())
				.lineNumber(getSpan())
				.operator(accurate ? "not_accurate_equals" : "not_equals");
	}
}
