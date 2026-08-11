package cn.ykccchen.script.parsing.ast;

import cn.ykccchen.script.parsing.Span;

/**
 * 表达式
 */
public abstract class Expression extends Node {
	protected Expression(Span span) {
		super(span);
	}

}
