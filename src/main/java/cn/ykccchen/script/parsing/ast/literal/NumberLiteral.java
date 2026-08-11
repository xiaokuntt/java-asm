package cn.ykccchen.script.parsing.ast.literal;

import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Literal;

public class NumberLiteral extends Literal {

	protected boolean neg;

	public NumberLiteral(Span span) {
		super(span);
	}

	public NumberLiteral(Span span, Object value) {
		super(span, value);
	}

	public String getText() {
		return (neg ? "-" : "") + getSpan().getText();
	}

	public void useNeg() {
		this.neg = !this.neg;
	}
}
