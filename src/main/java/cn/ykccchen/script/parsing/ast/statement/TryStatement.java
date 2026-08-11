package cn.ykccchen.script.parsing.ast.statement;

import org.objectweb.asm.Label;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.exception.ExitException;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.VarIndex;
import cn.ykccchen.script.parsing.ast.Node;

import java.util.Collections;
import java.util.List;

public class TryStatement extends Node {
	private final VarIndex exceptionVarNode;
	private final List<Node> tryBlock;
	private final List<VariableDefine> tryResources;
	private final List<Node> catchBlock;
	private final List<Node> finallyBlock;

	public TryStatement(Span span, VarIndex exceptionVarNode, List<Node> tryBlock, List<VariableDefine> tryResources, List<Node> catchBlock, List<Node> finallyBlock) {
		super(span);
		this.exceptionVarNode = exceptionVarNode;
		this.tryBlock = tryBlock;
		this.tryResources = tryResources;
		Collections.reverse(this.tryResources);
		this.catchBlock = catchBlock;
		this.finallyBlock = finallyBlock;
		this.finallyBlock.add(0, new Node(new Span("auto close")) {
			@Override
			public void visitMethod(ScriptCompiler compiler) {
				tryResources.forEach(it -> it.visitMethod(compiler));
			}

			@Override
			public void compile(ScriptCompiler compiler) {
				tryResources.forEach(it -> compiler.load(it.getVarIndex()).invoke(INVOKESTATIC, TryStatement.class, "autoClose", void.class, Object.class));
			}
		});
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		tryBlock.forEach(it -> it.visitMethod(compiler));
		catchBlock.forEach(it -> it.visitMethod(compiler));
		finallyBlock.forEach(it -> it.visitMethod(compiler));
	}

	public static void autoClose(Object object) {
		if (object instanceof AutoCloseable) {
			try {
				((AutoCloseable) object).close();
			} catch (Exception ignored) {
			}
		}
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		boolean hasCatch = exceptionVarNode != null;
		boolean hasFinally = !finallyBlock.isEmpty();
		if (hasFinally) {
			compileWithFinally(compiler, hasCatch);
		} else {
			compileWithoutFinally(compiler, hasCatch);
		}
	}

	private void compileWithFinally(ScriptCompiler compiler, boolean hasCatch) {
		Label tryStart = new Label();
		Label tryEnd = new Label();
		Label catchExitException = new Label();
		Label catchThrowable = new Label();
		Label catchEnd = new Label();
		Label finallyStart = new Label();
		Label finallyThrowStart = new Label();
		Label end = new Label();
		// try中如果有return语句，需要取这里压入的finallyBlock在return之前执行
		compiler.pushFinallyBlock(finallyBlock);
		compiler.label(tryStart)
				// tryResources
				.compile(tryResources)
				// try
				.compile(tryBlock)
				.label(tryEnd);
		// 弹出自身压入的finallyBlock
		compiler.popFinallyBlock();
		compiler
				.label(finallyStart)
				.compile(finallyBlock)
				// 跳转至结束
				.jump(GOTO, end);
		compiler.label(catchExitException)
				// catch (ExitException e) { throw e; }
				.insn(ATHROW);
		if (hasCatch) {
			// catch中如果有return语句，需要取这里压入的finallyBlock在return之前执行
			compiler.pushFinallyBlock(finallyBlock);
			compiler.label(catchThrowable)
					// catch (Throwable e) { }
					// 将异常变量存入variables
					.store(3)
					.pre_store(exceptionVarNode)
					.load3()
					.store(exceptionVarNode)
					.compile(catchBlock)
					.label(catchEnd);
			// 弹出自身压入的finallyBlock
			compiler.popFinallyBlock();
			compiler
					// 跳转至finally
					.jump(GOTO, finallyStart);
		}
		compiler.label(finallyThrowStart)
				.store(3)
				.compile(finallyBlock)
				.load3()
				.insn(ATHROW);
		compiler.label(end);

		compiler.tryCatch(tryStart, tryEnd, finallyThrowStart, ExitException.class);
		if (hasCatch) {
			compiler.tryCatch(tryStart, tryEnd, catchThrowable, Throwable.class);
			compiler.tryCatch(catchThrowable, catchEnd, finallyThrowStart, null);
		}
		compiler.tryCatch(tryStart, tryEnd, finallyThrowStart, null);
	}

	private void compileWithoutFinally(ScriptCompiler compiler, boolean hasCatch) {
		Label tryStart = new Label();
		Label tryEnd = new Label();
		Label catchExitException = new Label();
		Label catchThrowable = new Label();
		compiler.label(tryStart)
				// tryResources
				.compile(tryResources)
				// try
				.compile(tryBlock)
				.label(tryEnd);
		compiler.label(catchExitException)
				// catch (ExitException e) { throw e; }
				.insn(ATHROW);
		if (hasCatch) {
			compiler.label(catchThrowable)
					// catch (Throwable e) { }
					// 将异常变量存入variables
					.store(3)
					.pre_store(exceptionVarNode)
					.load3()
					.store(exceptionVarNode)
					.compile(catchBlock);
		}

		compiler.tryCatch(tryStart, tryEnd, catchExitException, ExitException.class);
		if (hasCatch) {
			compiler.tryCatch(tryStart, tryEnd, catchThrowable, Throwable.class);
		}
	}
}
