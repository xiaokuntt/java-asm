package cn.ykccchen.script.parsing.ast.literal;

import cn.ykccchen.script.ParseError;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;

/**
 * int常量
 */
public class IntegerLiteral extends NumberLiteral {

	public IntegerLiteral(Span literal) {
		super(literal);
	}

	public IntegerLiteral(Span span, Object value) {
		super(span, value);
	}

	@Override
	public void compile(ScriptCompiler context) {
		if(this.value == null){
			try {
				this.value = Integer.parseInt(getText().replace("_",""));
			} catch (NumberFormatException e) {
				ParseError.error("定义int变量值不合法", getSpan(), e);
			}
		}
		context.visitInt((Integer) value)
				.invoke(INVOKESTATIC, Integer.class, "valueOf", Integer.class, int.class);
	}
}
