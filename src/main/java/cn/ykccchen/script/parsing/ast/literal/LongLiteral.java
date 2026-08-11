package cn.ykccchen.script.parsing.ast.literal;

import cn.ykccchen.script.ParseError;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;

/**
 * long 常量
 */
public class LongLiteral extends NumberLiteral {

	public LongLiteral(Span literal) {
		super(literal);
	}

	public LongLiteral(Span span, Object value) {
		super(span, value);
	}

	@Override
	public void compile(ScriptCompiler context) {
		if(this.value == null){
			try {
				String text = getText();
				this.value = Long.parseLong(text.substring(0, text.length() - 1).replace("_", ""));
			} catch (NumberFormatException e) {
				ParseError.error("定义long变量值不合法", getSpan(), e);
			}
		}
		context.ldc(value).invoke(INVOKESTATIC, Long.class, "valueOf", Long.class, long.class);
	}
}
