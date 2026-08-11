package cn.ykccchen.script.parsing.ast.statement;

import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.ast.Node;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

public class Return extends Node {

	private final Node returnValue;

	public Return(Span span, Node returnValue) {
		super(span);
		this.returnValue = returnValue;
	}

	public Node getReturnValue() {
		return returnValue;
	}

	@Override
	public void visitMethod(ScriptCompiler compiler) {
		if (returnValue != null) {
			returnValue.visitMethod(compiler);
		}
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		/*
		 * 单独弹出栈顶的finallyBlock
		 * 防止在return_1所属的finallyBlock_1中存在return_2语句时，
		 * 编译过程: return_1 -> finallyBlock_1 -> return_2 -> finallyBlock_1 (loop)，最终造成堆栈溢出
		 */
		// 单独弹出栈顶的finallyBlock_1
		List<Node> finallyBlock = compiler.popFinallyBlock();
		if (returnValue == null) {
			// return; or return null;
			// 在返回前执行finallyBlockStack
			if (finallyBlock != null) {
				// 编译return_1的finallyBlock_1，此时若finallyBlock_1中存在return_2，不会再在return_2中编译finallyBlock_1
				compiler.compile(finallyBlock);
				// 编译栈中剩余的finallyBlock。这里需要拷贝到新的Deque，防止编译finallyBlock的return时抛出ConcurrentModificationException
				Deque<List<Node>> finallyBlockStack = new LinkedList<>(compiler.finallyBlockStack());
				for (List<Node> fb : finallyBlockStack) {
					compiler.compile(fb);
				}
				// 压入finallyBlock_1，供try-catch-finally其他地方的return再次编译finallyBlock_1
				compiler.pushFinallyBlock(finallyBlock);
			}
			// 压入 NULL
			compiler.insn(ACONST_NULL);
		} else {
			// return expr;
			compiler.visit(returnValue);
			// 在返回前执行finallyBlockStack
			if (finallyBlock != null) {
				// 保存返回结果
				compiler.store(3);
				// 编译return_1的finallyBlock_1，此时若finallyBlock_1中存在return_2，不会再在return_2中编译finallyBlock_1
				compiler.compile(finallyBlock);
				// 编译栈中剩余的finallyBlock。这里需要拷贝到新的Deque，防止编译finallyBlock的return时抛出ConcurrentModificationException
				Deque<List<Node>> finallyBlockStack = new LinkedList<>(compiler.finallyBlockStack());
				for (List<Node> fb : finallyBlockStack) {
					compiler.compile(fb);
				}
				// 加载返回结果
				compiler.load3();
				// 压入finallyBlock_1，供try-catch-finally其他地方的return再次编译finallyBlock_1
				compiler.pushFinallyBlock(finallyBlock);
			}
		}
		// 返回
		compiler.insn(ARETURN);
	}
}
