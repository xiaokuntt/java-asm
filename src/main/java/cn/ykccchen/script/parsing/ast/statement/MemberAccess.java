package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.VariableSetter;

public class MemberAccess extends Expression implements VariableSetter {
	private final Expression object;
	private final Span name;
	private final boolean optional;
	private final boolean whole;

	public MemberAccess(Expression object, boolean optional, Span name, boolean whole) {
		super(new Span(object.getSpan(), name));
		this.object = object;
		this.name = name;
		this.optional = optional;
		this.whole = whole;
	}

	public boolean isWhole() {
		return whole;
	}

	public boolean isOptional() {
		return optional || (object instanceof MemberAccess && ((MemberAccess) object).isOptional());
	}

	public Expression getObject() {
		return object;
	}

	public Span getName() {
		return name;
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		object.visitMethod(compiler);
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		compiler.newRuntimeContext()
				.visit(object)    // 访问目标对象
				.ldc(name.getText())    // 成员名
				.insn(isOptional() ? ICONST_1 : ICONST_0)    // 是否可空调用 ?.
				.asBoolean()
				.lineNumber(name)
				.call("member_access", 4);
	}



	@Override
	public void compile_visit_variable(ScriptCompiler compiler) {
		compiler.compile(object).ldc(name.getText());
	}
}
