package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Node;

/**
 * continue语句
 */
public class Continue extends Node {

	public Continue(Span span) {
		super(span);
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.start();
	}
}
