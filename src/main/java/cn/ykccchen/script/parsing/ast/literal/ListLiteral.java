package cn.ykccchen.script.parsing.ast.literal;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.Literal;

import java.util.List;

/**
 * List常量
 */
public class ListLiteral extends Literal {

	public final List<Expression> values;

	public ListLiteral(Span span, List<Expression> values) {
		super(span);
		this.values = values;
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		values.forEach(expr -> expr.visitMethod(compiler));
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		int size = values.size();
		if (size == 0) {
			compiler.newArrayList();
		} else {
			compiler.newArray(values)
					.call("newArrayList", 1);
		}
	}
}
