package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;

import java.util.List;

/**
 * ,连续初始化变量
 * @author ykccchen
 */
public class VariableArrayDefine extends VariableDefine {

	private final List<VariableDefine> variableDefines;

	public VariableArrayDefine(Span span, List<VariableDefine> variableDefines) {
		super(span, null, null);
		this.variableDefines = variableDefines;
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		for (VariableDefine v : variableDefines) {
			v.compile(compiler);
		}
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		for (VariableDefine variableDefine : variableDefines) {
			variableDefine.compile(compiler);
		}
	}

}
