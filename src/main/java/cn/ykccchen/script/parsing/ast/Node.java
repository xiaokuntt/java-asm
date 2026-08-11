package cn.ykccchen.script.parsing.ast;

import org.objectweb.asm.Opcodes;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.exception.ScriptErrorCode;
import cn.ykccchen.script.exception.ScriptRuntimeException;

/**
 * 节点
 */
public abstract class Node implements Opcodes {
	/**
	 * 对应的文本
	 */
	private final Span span;



	protected Node(Span span) {
		this.span = span;
	}

	public Span getSpan() {
		return span;
	}


	@Override
	public String toString() {
		return getClass().getSimpleName() + ":" + span.getText();
	}

	public void visitMethod(ScriptCompiler compiler) {

	}

	public void compile(ScriptCompiler compiler) {
		throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_UNSUPPORTED_OPERATION,
				this.getClass().getSimpleName() + "不支持编译");
	}

}
