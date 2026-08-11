package cn.ykccchen.script.parsing.ast.literal;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Literal;

/**
 * null 常量
 */
public class NullLiteral extends Literal {
	public NullLiteral(Span span) {
		super(span);
	}

	@Override
	public void compile(ScriptCompiler context) {
		context.insn(ACONST_NULL);
	}
}
