package cn.ykccchen.script.parsing.ast.literal;

import cn.ykccchen.script.ParseError;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;

/**
 * short 常量
 */
public class ShortLiteral extends NumberLiteral {

	public ShortLiteral(Span literal) {
		super(literal);

	}

	@Override
	public void compile(ScriptCompiler context) {
		if(this.value == null){
			try {
				String text = getText();
				setValue(Short.parseShort(text.substring(0, text.length() - 1).replace("_","")));
			} catch (NumberFormatException e) {
				ParseError.error("定义short变量值不合法", getSpan(), e);
			}
		}
		context.ldc(value).invoke(INVOKESTATIC, Short.class, "valueOf", Short.class, short.class);
	}
}
