package cn.ykccchen.script.parsing.ast;

import cn.ykccchen.script.ParseError;
import cn.ykccchen.script.compile.ScriptCompileException;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Token;
import cn.ykccchen.script.parsing.TokenType;
import cn.ykccchen.script.parsing.ast.statement.VariableAccess;
import cn.ykccchen.script.runtime.handle.BitHandle;
import cn.ykccchen.script.runtime.handle.OperatorHandle;

import java.util.function.Supplier;

/**
 * 一元操作符
 */
public class UnaryOperation extends Expression {

	private final UnaryOperator operator;
	private final Expression operand;
	private final boolean atAfter;

	public UnaryOperation(Token operator, Expression operand) {
		this(operator, operand, false);
	}

	public UnaryOperation(Token operator, Expression operand, boolean atAfter) {
		super(operator.getSpan());
		if(operator.getType().isModifiable() && operand instanceof VariableAccess && ((VariableAccess) operand).getVarIndex().isReadonly()){
			ParseError.error("const修饰的变量不能被修改", getSpan());
		}
		this.operator = UnaryOperator.getOperator(operator);
		this.operand = operand;
		this.atAfter = atAfter;
	}

	public UnaryOperator getOperator() {
		return operator;
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		operand.visitMethod(compiler);
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		switch (getOperator()) {
			case Not:
				compiler.compile(operand)
						.invoke(INVOKESTATIC, OperatorHandle.class, "isFalse", boolean.class, Object.class)
						.asBoolean();
				break;
			case BitNot:
				compiler.compile(operand)
						.invoke(INVOKESTATIC, BitHandle.class, "not", Object.class, Object.class);
				break;
			case PlusPlus:
			case MinusMinus:
				if (operand instanceof VariableSetter) {
					boolean access = operand instanceof VariableAccess;
					Supplier<ScriptCompiler> plus = () -> compiler.visit(operand)  // 访问变量
							// 执行 ± 1 操作
							.visitInt(operator == UnaryOperator.PlusPlus ? 1 : -1)
							.asInteger()
							.arithmetic("plus");
					if (atAfter) {    // ++ -- 在后
						if (access) { // a++ a--
							// 执行 ± 操作
							compiler.compile(operand).pre_store(((VariableAccess) operand).getVarIndex());
							plus.get().store(((VariableAccess) operand).getVarIndex());
						} else {  // map.key++ map.key--
							compiler.compile(operand);    // 先访问变量，后续返回使用
							compiler.newRuntimeContext();
							((VariableSetter) operand).compile_visit_variable(compiler); // 赋值前准备
							plus.get()
									.call("set_variable_value", 4)   // 赋值操作
									.insn(POP); // 抛弃 ++ -- 的返回值。
						}
					} else {  // ++ -- 在前
						if (access) { // ++a --a
							compiler.pre_store(((VariableAccess) operand).getVarIndex());
							// 执行 ± 操作
							plus.get().store(((VariableAccess) operand).getVarIndex())   // 结果存入到变量中
									.visit(operand);
						} else {  // ++map.key --map.key
							compiler.newRuntimeContext();
							// 赋值前准备
							((VariableSetter) operand).compile_visit_variable(compiler);
							// 将执行结果赋值给变量。
							plus.get().call("set_variable_value", 4);
						}
					}
					break;
				}
				throw new ScriptCompileException("此处不支持++/--操作");
			case Negate:
				compiler.visit(operand)
						.insn(ACONST_NULL)
						.arithmetic("neg");
				break;
			default:break;
		}
	}

	public enum UnaryOperator {
		/**
		 * !
		 */
		Not,

		/**
		 * -
		 */
		Negate,

		/**
		 * +
		 */
		Positive,

		/**
		 * ++
		 */
		PlusPlus,

		/**
		 * --
		 */
		MinusMinus,

		/**
		 * ~
		 */
		BitNot;

		public static UnaryOperator getOperator(Token op) {
			if (op.getType() == TokenType.Not) {
				return UnaryOperator.Not;
			}
			if (op.getType() == TokenType.Plus) {
				return UnaryOperator.Positive;
			}
			if (op.getType() == TokenType.Minus) {
				return UnaryOperator.Negate;
			}
			if (op.getType() == TokenType.PlusPlus) {
				return UnaryOperator.PlusPlus;
			}
			if (op.getType() == TokenType.MinusMinus) {
				return UnaryOperator.MinusMinus;
			}
			if (op.getType() == TokenType.BitNot) {
				return UnaryOperator.BitNot;
			}
			ParseError.error("不支持的一元操作符：" + op, op.getSpan());
			return null; // not reached
		}
	}
}
