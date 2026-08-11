package cn.ykccchen.script.parsing.ast.literal;

import cn.ykccchen.script.ParseError;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;

/**
 * double常量
 */
public class DoubleLiteral extends NumberLiteral {

	public DoubleLiteral(Span literal) {
		super(literal);
	}

	@Override
	public void compile(ScriptCompiler context) {
		if(this.value == null){
			try {
				setValue(Double.parseDouble(getText().replace("_", "")));
			} catch (NumberFormatException e) {
				ParseError.error("定义double变量值不合法", getSpan(), e);
			}
		}
		context.ldc(value).invoke(INVOKESTATIC, Double.class, "valueOf", Double.class, double.class);
	}
}
