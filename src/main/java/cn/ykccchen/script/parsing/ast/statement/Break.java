package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Node;

/**
 * break 语句
 */
public class Break extends Node {

	public Break(Span span) {
		super(span);
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.end();
	}
}
