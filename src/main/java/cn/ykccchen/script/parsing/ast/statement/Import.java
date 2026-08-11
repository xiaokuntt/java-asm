package cn.ykccchen.script.parsing.ast.statement;

import org.objectweb.asm.Label;
import cn.ykccchen.script.ResourceLoader;
import cn.ykccchen.script.ScriptContext;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.functions.DynamicModuleImport;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.VarIndex;
import cn.ykccchen.script.parsing.ast.Node;

public class Import extends Node {

	private final VarIndex varIndex;
	private final boolean module;
	private String packageName;
	private boolean function;

	public Import(Span span, String packageName, VarIndex varIndex, boolean module) {
		super(span);
		this.packageName = packageName;
		this.varIndex = varIndex;
		this.module = module;
		if (!module && packageName.startsWith("@")) {
			function = true;
			this.packageName = packageName.substring(1);
		}
	}

	public boolean isImportPackage() {
		return packageName.endsWith(".*");
	}

	@Override
	public void compile(ScriptCompiler compiler) {
		if (isImportPackage()) {
			compiler.loadContext()
					.ldc(packageName.substring(0, packageName.length() - 1))
					.invoke(INVOKEVIRTUAL, ScriptContext.class, "addImport", void.class, String.class);
		} else {
			String methodName = "loadClass";
			if (this.module) {
				methodName = "loadModule";
			} else if (this.function) {
				methodName = "loadFunction";
			}
			compiler.pre_store(varIndex)    // 保存变量前的准备
					.loadContext()
					.ldc(packageName)    // 包名&函数名
					.invoke(INVOKESTATIC, ResourceLoader.class, methodName, Object.class, ScriptContext.class, String.class);    // 加载资源
			if (this.module) {
				// if(module instanceof DynamicModuleImport){ module = ((DynamicModuleImport)module).getDynamicModule(context); }
				Label end = new Label();
				compiler.insn(DUP)
						.typeInsn(INSTANCEOF, DynamicModuleImport.class)
						.jump(IFEQ, end)
						.typeInsn(CHECKCAST, DynamicModuleImport.class)
						.loadContext()
						.invoke(INVOKEVIRTUAL, DynamicModuleImport.class, "getDynamicModule", Object.class, ScriptContext.class)
						.label(end);
			}
			compiler.store(varIndex);    // 保存变量
		}
	}
}
