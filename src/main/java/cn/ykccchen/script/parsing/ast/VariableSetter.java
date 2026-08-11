package cn.ykccchen.script.parsing.ast;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.exception.ScriptErrorCode;
import cn.ykccchen.script.exception.ScriptRuntimeException;

public interface VariableSetter {

	default void compile_visit_variable(ScriptCompiler compiler) {
		throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_UNSUPPORTED_OPERATION,
				"暂不支持编译" + this.getClass().getSimpleName());
	}
}
