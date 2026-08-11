package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.VariableSetter;

public class MapOrArrayAccess extends Expression implements VariableSetter {
	private final Expression mapOrArray;
	private final Expression keyOrIndex;

	public MapOrArrayAccess(Span span, Expression mapOrArray, Expression keyOrIndex) {
		super(span);
		this.mapOrArray = mapOrArray;
		this.keyOrIndex = keyOrIndex;
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		mapOrArray.visitMethod(compiler);
		keyOrIndex.visitMethod(compiler);
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.visit(mapOrArray)
				.visit(keyOrIndex)
				.operator("map_or_array_access");
	}

	@Override
	public void compile_visit_variable(ScriptCompiler compiler) {
		compiler.visit(mapOrArray).visit(keyOrIndex);
	}
}
