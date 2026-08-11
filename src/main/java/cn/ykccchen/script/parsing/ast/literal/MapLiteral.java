package cn.ykccchen.script.parsing.ast.literal;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.Literal;

import java.util.List;
import java.util.Objects;

/**
 * map常量
 */
public class MapLiteral extends Literal {
	private final List<Expression> keys;
	private final List<Expression> values;

	public MapLiteral(Span span, List<Expression> keys, List<Expression> values) {
		super(span);
		this.keys = keys;
		this.values = values;
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		values.forEach(it -> it.visitMethod(compiler));
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		int size = keys.size();
		compiler.visitInt((int) (size * 2 - keys.stream().filter(Objects::isNull).count()))
				.typeInsn(ANEWARRAY, Object.class);
		int index = 0;
		for (int i = 0; i < size; i++) {
			Expression key = keys.get(i);
			Expression expression = values.get(i);
			compiler.insn(DUP).visitInt(index++);
			compiler.visit(key)
					.insn(AASTORE)
					.insn(DUP)
					.visitInt(index++)
					.visit(expression);
			compiler.insn(AASTORE);
		}
		compiler.call("newLinkedHashMap", 1);
	}
}
