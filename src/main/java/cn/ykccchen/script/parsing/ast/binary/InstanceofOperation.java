package cn.ykccchen.script.parsing.ast.binary;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.BinaryOperation;
import cn.ykccchen.script.parsing.ast.Expression;

/**
 * instanceof
 */
public class InstanceofOperation extends BinaryOperation {

    public InstanceofOperation(Expression leftOperand, Span span, Expression rightOperand) {
        super(leftOperand, span, rightOperand);
    }

    @Override
    public void compile(ScriptCompiler compiler) {
        compiler.visit(getLeftOperand())
                .visit(getRightOperand())
                .typeInsn(CHECKCAST, Class.class)
                .lineNumber(getSpan())
                .invoke(INVOKESTATIC, InstanceofOperation.class, "is", boolean.class, Object.class, Class.class)
                .asBoolean();
    }
    public static boolean is(Object target, Class<?> clazz) {
        if (target == null || clazz == null) {
            return false;
        }
        return clazz.isAssignableFrom(target.getClass());
    }


}
