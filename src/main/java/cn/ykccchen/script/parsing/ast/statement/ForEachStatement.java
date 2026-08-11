package cn.ykccchen.script.parsing.ast.statement;

import org.objectweb.asm.Label;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.VarIndex;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.Node;
import cn.ykccchen.script.runtime.handle.FunctionCallHandle;

import java.util.Iterator;
import java.util.List;

public class ForEachStatement extends ForStatement {
    private final VarIndex value;
    private final VarIndex anonymousVariable;
    private final Expression mapOrArray;
    private final List<Node> body;

    public ForEachStatement(Span span,
                            VarIndex value,
                            VarIndex anonymousVariable,
                            Expression mapOrArray,
                            List<Node> body) {
        super(span, null, null, null, null);
        this.anonymousVariable = anonymousVariable;
        this.value = value;
        this.mapOrArray = mapOrArray;
        this.body = body;
    }

    @Override
    public void visitMethod(ScriptCompiler compiler) {
        mapOrArray.visitMethod(compiler);
        body.forEach(it -> it.visitMethod(compiler));
    }

    @Override
    public void compile(ScriptCompiler compiler) {
        Label start = new Label();
        Label end = new Label();
        compiler.markLabel(start, end)    // 标记 continue 、 break位置
                .pre_store(anonymousVariable)
                //	初始化 iterator
                .compile(mapOrArray)
                .invoke(INVOKESTATIC, FunctionCallHandle.class, "newValueIterator", Iterator.class, Object.class)
                // 保存至临时变量
				.store(anonymousVariable)
				.label(start)
				.checkpoint()
                // 判断是否有值
                .load(anonymousVariable)
                .invoke(INVOKEINTERFACE, Iterator.class, "hasNext", true, boolean.class)
                // 值为false时，跳出循环
                .jump(IFEQ, end)
                .pre_store(value)
                // 获取当前 value
                .load(anonymousVariable)
                .invoke(INVOKEINTERFACE, Iterator.class, "next", true, Object.class)
                // 存入到 value 中
                .store(value)
                // 执行循环体
                .compile(body)
                // 执行完毕后跳转到循环起始位置
                .jump(GOTO, start)
                .label(end)
                // 移除 value 变量
                .remove(value)
                .exitLabel();
    }
}
