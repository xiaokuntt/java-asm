package cn.ykccchen.script.parsing.ast.statement;

import org.objectweb.asm.Label;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;

import cn.ykccchen.script.parsing.ast.Node;
import cn.ykccchen.script.runtime.handle.OperatorHandle;

import java.util.List;

public class ForStatement extends Node {
    private final List<Node> initDefineList;
    private final Expression booleanExpression;
    private final List<Expression> afterHandlerList;
    private final List<Node> body;

    public ForStatement(Span span,
                        List<Node> initDefineList,
                        Expression booleanExpression,
                        List<Expression> afterHandlerList,
                        List<Node> body) {
        super(span);
        this.initDefineList = initDefineList;
        this.afterHandlerList = afterHandlerList;
        this.booleanExpression = booleanExpression;
        this.body = body;
    }

    @Override
    public void visitMethod(ScriptCompiler compiler) {
        initDefineList.forEach(it -> it.visitMethod(compiler));
        if (booleanExpression != null) {
            booleanExpression.visitMethod(compiler);
        }
        afterHandlerList.forEach(it -> it.visitMethod(compiler));
        body.forEach(it -> it.visitMethod(compiler));
    }

    @Override
    public void compile(ScriptCompiler compiler) {
        Label start = new Label();
        Label continueMark = new Label();
        Label end = new Label();
        // 标记 continue 、 break位置
        compiler.markLabel(continueMark, end);
        // 有初始化逻辑先初始化
        for (Node node : initDefineList) {
            compiler.compile(node);
        }
		compiler.label(start).checkpoint();
        if (booleanExpression != null) {
            // 判断是否可以执行
            compiler.visit(booleanExpression)
                    .invoke(INVOKESTATIC, OperatorHandle.class, "isTrue", boolean.class, Object.class)    // 判断是否为true
                    // 值为false时，跳出循环
                    .jump(IFEQ, end);
        }

        // 执行循环体
        compiler.compile(body)
                //continue位置在这里
                .label(continueMark);
        for (Expression afterHandler : afterHandlerList) {
            compiler.compile(afterHandler, true);
        }
        compiler.jump(GOTO, start)
                .label(end)
                .exitLabel();
    }
}
