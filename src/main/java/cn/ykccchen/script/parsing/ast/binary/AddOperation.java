package cn.ykccchen.script.parsing.ast.binary;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.BinaryOperation;
import cn.ykccchen.script.parsing.ast.Expression;

/**
 * + 运算
 */
public class AddOperation extends BinaryOperation {

	public AddOperation(Expression leftOperand, Span span, Expression rightOperand) {
		super(leftOperand, span, rightOperand);
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.visit(getLeftOperand())
				.visit(getRightOperand())
				.lineNumber(getSpan())
				.arithmetic("plus");
	}
}
