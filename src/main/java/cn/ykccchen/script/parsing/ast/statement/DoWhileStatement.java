package cn.ykccchen.script.parsing.ast.statement;

import org.objectweb.asm.Label;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.Node;
import cn.ykccchen.script.runtime.handle.OperatorHandle;

import java.util.List;

public class DoWhileStatement extends WhileStatement {



	public DoWhileStatement(Span span, Expression condition, List<Node> trueBlock) {
		super(span, condition, trueBlock);

	}


	@Override
	public void compile(ScriptCompiler compiler) {
		Label start = new Label();
		Label end = new Label();
		compiler.markLabel(start, end)    // 标记 continue 和 break 位置
					.label(start)
					.checkpoint()
				// 执行循环体
				.compile(getTrueBlock())
				// 执行完毕后跳转到循环起始位置
				// 判断是否为true
				.visit(getCondition())
				.invoke(INVOKESTATIC, OperatorHandle.class, "isTrue", boolean.class, Object.class)
				// 值为false时，跳出循环
				.jump(IFEQ, end)
				.jump(GOTO, start)
				.label(end)
				.exitLabel();

	}
}
