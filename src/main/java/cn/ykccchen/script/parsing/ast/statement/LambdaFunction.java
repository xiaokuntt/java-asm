package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.VarIndex;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.Node;

import java.util.List;

public class LambdaFunction extends Expression {
    private final List<VarIndex> parameters;
    private final List<Node> childNodes;
	private String methodName;
	private boolean async;


    public LambdaFunction(Span span, List<VarIndex> parameters, List<Node> childNodes) {
        super(span);
        this.parameters = parameters;
        this.childNodes = childNodes;
    }

    @Override
    public void visitMethod(ScriptCompiler compiler) {

        // 使用新的visitFunctionMethod来创建Function兼容的lambda
        this.methodName = compiler.visitMethod("lambda", childNodes, parameters);
    }

	public List<VarIndex> getParameters() {
        return parameters;
	}

	public void setAsync(boolean async) {
		this.async = async;
	}


    @Override
    public void compile(ScriptCompiler compiler) {
        // lambda() 内部负责 load0(this) 和 load2(Variables) 的捕获
		compiler.lambda(methodName, async);
	}
}
