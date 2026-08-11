package cn.ykccchen.script.parsing.ast.literal;

import cn.ykccchen.script.ParseError;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;

/**
 * float常量
 */
public class FloatLiteral extends NumberLiteral {

	public FloatLiteral(Span literal) {
		super(literal);
	}

	@Override
	public void compile(ScriptCompiler context) {
		if(this.value == null) {
			try {
				setValue(Float.parseFloat(getText().replace("_", "")));
			} catch (NumberFormatException e) {
				ParseError.error("定义float变量值不合法", getSpan(), e);
			}
		}
		context.ldc(value).invoke(INVOKESTATIC, Float.class, "valueOf", Float.class, float.class);
	}
}
