package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.VarIndex;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.VariableSetter;

public class VariableAccess extends Expression implements VariableSetter {

	private final VarIndex varIndex;

	public VariableAccess(Span name, VarIndex varIndex) {
		super(name);
		this.varIndex = varIndex;
	}

	public VarIndex getVarIndex() {
		return varIndex;
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.load(varIndex);
	}
}
