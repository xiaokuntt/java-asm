package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;

import java.util.List;

public class FunctionCall extends Expression {
    private final Expression function;
    private final List<Expression> arguments;


    public FunctionCall(Span span, Expression function, List<Expression> arguments) {
        super(span);
        this.function = function;
        this.arguments = arguments;
    }

    @Override
    public void visitMethod(ScriptCompiler compiler) {
        function.visitMethod(compiler);
        arguments.forEach(it -> it.visitMethod(compiler));
    }

    public Expression getFunction() {
        return function;
    }

    public List<Expression> getArguments() {
        return arguments;
    }

    @Override
    public void compile(ScriptCompiler compiler) {
        int size = arguments.size();
        compiler.newRuntimeContext()
                .ldc(getFunction().getSpan().getText())    // 函数名
                .visitInt(size)
                .typeInsn(ANEWARRAY, Object.class);
        for (int i = 0; i < size; i++) {
            Expression argument = arguments.get(i);
            compiler.insn(DUP)
                    .visitInt(i)
                    .visit(argument)
                    .insn(AASTORE);
        }
        compiler.visit(function)    // 访问函数
                .lineNumber(getSpan())
                .call("invoke_function", 4);    // 调用函数
    }
}
