package cn.ykccchen.script.parsing.ast.binary;

import cn.ykccchen.script.ParseError;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.BinaryOperation;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.VariableSetter;
import cn.ykccchen.script.parsing.ast.statement.VariableAccess;

/**
 * = 操作
 */
public class AssigmentOperation extends BinaryOperation {

	public AssigmentOperation(Expression leftOperand, Span span, Expression rightOperand) {
		super(leftOperand, span, rightOperand);
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		if (getLeftOperand() instanceof VariableAccess) {
			compiler.pre_store(((VariableAccess) getLeftOperand()).getVarIndex())
					.compile(getRightOperand());
			if (getRightOperand() instanceof AssigmentOperation) {
				compiler.visit(((AssigmentOperation) getRightOperand()).getLeftOperand());
			}
			compiler.store(((VariableAccess) getLeftOperand()).getVarIndex());
		} else if (getLeftOperand() instanceof VariableSetter) {
			compiler.newRuntimeContext();
			((VariableSetter) getLeftOperand()).compile_visit_variable(compiler);
			compiler.compile(getRightOperand()).call("set_variable_value", 4);
		} else {
			ParseError.error("赋值目标应为变量", getLeftOperand().getSpan());
		}
	}
}
