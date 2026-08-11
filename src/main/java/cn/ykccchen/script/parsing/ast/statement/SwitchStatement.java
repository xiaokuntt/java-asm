package cn.ykccchen.script.parsing.ast.statement;

import org.objectweb.asm.Label;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.Node;

import java.util.List;
import java.util.Objects;

public class SwitchStatement extends Node {
    private final Expression matchValueExpression;
    private final List<SwitchStatement> caseList;
    private final List<Node> bodyList;
    private final List<Node> defaultBlock;
    private final Node breakNode;

    public SwitchStatement(Span span, Expression matchValueExpression, List<SwitchStatement> caseList, List<Node> bodyList, List<Node> defaultBlock, Node breakNode) {
        super(span);
        this.matchValueExpression = matchValueExpression;
        this.caseList = caseList;
        this.bodyList = bodyList;
        this.defaultBlock = defaultBlock;
        this.breakNode = breakNode;
    }


    @Override
    public void visitMethod(ScriptCompiler compiler) {
        if (matchValueExpression != null){
            matchValueExpression.visitMethod(compiler);
        }
        caseList.forEach(it -> it.visitMethod(compiler));
        bodyList.forEach(it -> it.visitMethod(compiler));
        defaultBlock.forEach(it -> it.visitMethod(compiler));
    }

    @Override
    public void compile(ScriptCompiler compiler) {
        // 标记下一个点位和结束点位
        Label end = new Label();
        Label next = new Label();
        // body穿透
        Label nextBody = new Label();
        for (SwitchStatement caseBody : caseList) {
            compiler.label(next)
                    .visit(caseBody.matchValueExpression)
                    .visit(matchValueExpression)
                    .invoke(INVOKESTATIC, Objects.class, "equals", boolean.class, Object.class, Object.class)
                ;
            next = new Label();
            compiler.jump(IFEQ, next)
                    // 编译 true代码块
                    .label(nextBody)
                    .compile(caseBody.bodyList);
            nextBody = new Label();
            if (caseBody.breakNode != null) {
                compiler.jump(GOTO, end);
            }else {
                compiler.jump(GOTO, nextBody);
            }
        }
        compiler.label(next);
        compiler.label(nextBody);
        if (defaultBlock != null && !defaultBlock.isEmpty()) {
            // 编译 false 代码块
            compiler.compile(defaultBlock);
        }
        compiler.label(end);
    }


}
