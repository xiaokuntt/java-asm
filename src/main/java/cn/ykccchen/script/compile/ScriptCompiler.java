package cn.ykccchen.script.compile;

import org.objectweb.asm.*;
import cn.ykccchen.script.ScriptContext;

import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.VarIndex;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.Node;
import cn.ykccchen.script.parsing.ast.VariableSetter;
import cn.ykccchen.script.parsing.ast.binary.AssigmentOperation;
import cn.ykccchen.script.parsing.ast.statement.VariableAccess;
import cn.ykccchen.script.runtime.ScriptRuntime;
import cn.ykccchen.script.runtime.RuntimeContext;
import cn.ykccchen.script.runtime.Variables;
import cn.ykccchen.script.runtime.handle.ArithmeticHandle;
import cn.ykccchen.script.runtime.handle.BitHandle;
import cn.ykccchen.script.runtime.handle.FunctionCallHandle;
import cn.ykccchen.script.runtime.handle.OperatorHandle;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static cn.ykccchen.script.compile.Descriptor.makeDescriptor;

/**
 * 脚本编译器
 */
public class ScriptCompiler implements Opcodes {

    private static final AtomicLong COUNTER = new AtomicLong(1);

    private final ClassWriter classWriter;

    private final Long id = COUNTER.getAndIncrement();

    /**
     * < <= == === != !=== >= > 等操作符处理器
     */
    private static final Handle OPERATOR_HANDLE = makeHandle(OperatorHandle.class);

    /**
     * << >> >>> & ^ | 运算
     */
    private static final Handle BIT_HANDLE = makeHandle(BitHandle.class);

    /**
     * 方法调用、lambda调用、函数调用处理器
     */
    private static final Handle FUNCTION_HANDLE = makeHandle(FunctionCallHandle.class);

    /**
     * + - * / % 处理器
     */
    private static final Handle ARITHMETIC_HANDLE = makeHandle(ArithmeticHandle.class);

    private final Deque<MethodVisitor> methodVisitors = new ArrayDeque<>();

    private final Deque<List<String>> vars = new ArrayDeque<>();

    private final Deque<Label[]> labelStack = new ArrayDeque<>();

    /*
     * 替换为双端队列实现栈，因为Stack在for遍历时先从栈尾开始
     */
    private final Deque<List<Node>> finallyStack = new LinkedList<>();

    private final Set<VarIndex> varIndices;

    private final boolean debug;

    /**
     * -1 ~ 5的int值
     */
    private static final int[] ICONST = {ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5};

    private final List<Span> spans = new ArrayList<>();

    private int functionIndex = 0;

    private int tempIndex = 4;


    private boolean contextInitFlag = false;

    public ScriptCompiler(Set<VarIndex> varIndices) {
		this(varIndices, false);
	}

	public ScriptCompiler(Set<VarIndex> varIndices, boolean debug) {
        this.varIndices = varIndices;
		this.debug = debug;
        // 创建类并继承 ScriptRuntime
        classWriter = new ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS);
        classWriter.visit(V1_8, ACC_PUBLIC | ACC_SUPER, getClassName(), null, getJvmType(ScriptRuntime.class), null);
        classWriter.visitSource(getClassName() + ".ms", null);
        createMethod(ACC_PUBLIC, "<init>", makeDescriptor(void.class));
        this.load0()
                .invoke(INVOKESPECIAL, ScriptRuntime.class, "<init>", void.class)
                .insn(RETURN)
                .pop();
        // 创建execute方法
        createMethod(ACC_PUBLIC, "execute", makeDescriptor(Object.class, ScriptContext.class));
        initContext();
    }

    public List<Span> getSpans() {
        return spans;
    }

    /**
     * 创建方法
     *
     * @param access     访问属性
     * @param methodName 方法名
     * @param descriptor 方法描述
     */
    public ScriptCompiler createMethod(int access, String methodName, String descriptor) {
        MethodVisitor visitor = classWriter.visitMethod(access, methodName, descriptor, null, null);
        visitor.visitCode();
        methodVisitors.push(visitor);
        vars.push(new ArrayList<>());
        labelStack.push(new Label[2]);
        return this;
    }

    public int getTempIndex() {
        return tempIndex++;
    }

    /**
     * 获取lambda函数下标
     */
    public int getFunctionIndex() {
        return ++functionIndex;
    }

    /**
     * 标识continue和break位置
     */
    public ScriptCompiler markLabel(Label start, Label end) {
        labelStack.push(new Label[]{start, end});
        return this;
    }

    /**
     * 消除标记
     */
    public ScriptCompiler exitLabel() {
        labelStack.pop();
        return this;
    }

    /**
     * 跳转到continue位置
     */
    public ScriptCompiler start() {
        return jump(GOTO, labelStack.peek()[0]);
    }

    /**
     * 跳转到break位置
     */
    public ScriptCompiler end() {
        return jump(GOTO, labelStack.peek()[1]);
    }

    /**
     * 访问AST节点
     */
    public ScriptCompiler visit(Node node) {
        // 对于赋值语句的特殊处理
        if (node instanceof AssigmentOperation) {
            AssigmentOperation operation = (AssigmentOperation) node;
            // 如果是a = b = 1这种情况，则需要读取a的值
            if (operation.getLeftOperand() instanceof VariableAccess) {
                return compile(node, true).compile(operation.getLeftOperand());
            }
        }
        return compile(node, false);
    }

    /**
     * 编译AST节点
     */
    public ScriptCompiler compile(Node node) {
        return compile(node, false);
    }

    public ScriptCompiler lineNumber(Span span) {
        spans.add(span);
        MethodVisitor mv = self();
        Label label = new Label();
        // 设置行号（节点序号）
        mv.visitLabel(label);
        mv.visitLineNumber(spans.size() - 1, label);
        return this;
    }

    public ScriptCompiler loadContext() {
        if (contextInitFlag || methodVisitors.size() > 1) {
            load0().self().visitFieldInsn(GETFIELD, getClassName(), "context", Type.getDescriptor(ScriptContext.class));
            return this;
        }
        return load1();
    }

    public ScriptCompiler newRuntimeContext() {
        return this.typeInsn(NEW, RuntimeContext.class)
                .insn(DUP)
                .loadContext()
                .load2()
                .invoke(INVOKESPECIAL, RuntimeContext.class, "<init>", void.class, ScriptContext.class, Variables.class);
    }

    /**
     * 编译AST节点
     *
     * @param node AST节点
     * @param pop  是否需要弹出栈顶
     */
    public ScriptCompiler compile(Node node, boolean pop) {
        if (node == null) {
            return insn(ACONST_NULL);
        } else {
            lineNumber(node.getSpan());
            // 编译该节点
            node.compile(this);
        }
        // 对于赋值语句的特殊处理，因为赋值语句有两种
        // 不带返回值的： a+=1
        // 带返回值的 xxx.xx = 1
        if (node instanceof AssigmentOperation) {
            AssigmentOperation operation = (AssigmentOperation) node;
            if (operation.getLeftOperand() instanceof VariableSetter && operation.getLeftOperand() instanceof VariableAccess) {
                return this;
            }
        }
        // 对于带有返回值的表达式，且需要弹出栈顶时，追加指令POP
        return pop && node instanceof Expression ? insn(POP) : this;
    }

    /**
     * 设定tryCatch跳转
     * 如果在Label start到Label end代码范围内捕获到type异常，则跳转到Label handler
     * type为null则表示finally，只要抛异常就跳转到handler
     */
    public ScriptCompiler tryCatch(Label start, Label end, Label handle, Class<?> target) {
        self().visitTryCatchBlock(start, end, handle, getJvmType(target));
        return this;
    }

    /**
     * 访问
     */
    public ScriptCompiler visit(List<? extends Node> nodes) {
        nodes.forEach(this::visit);
        return this;
    }

    /**
     * 编译
     */
    public ScriptCompiler compile(List<? extends Node> nodes) {
		for (Node node : nodes) {
			checkpoint();
			if (debug) {
				pause(node.getSpan());
			}
			compile(node, true);
		}
		return this;
	}

	public ScriptCompiler checkpoint() {
		return loadContext().invoke(INVOKEVIRTUAL, ScriptContext.class, "checkpoint", void.class);
	}

	private void pause(Span span) {
		Span.Line line = span.getLine();
		loadContext()
				.visitInt(line.getLineNumber())
				.visitInt(line.getStartCol())
				.visitInt(line.getEndLineNumber())
				.visitInt(line.getEndCol())
				.load2()
				.invoke(INVOKEVIRTUAL, ScriptContext.class, "pause", void.class,
						int.class, int.class, int.class, int.class, Variables.class);
	}

    /**
     * 加载this
     */
    public ScriptCompiler load0() {
        self().visitVarInsn(ALOAD, 0);
        return this;
    }

    /**
     * 加载context
     */
    public ScriptCompiler load1() {
        self().visitVarInsn(ALOAD, 1);
        return this;
    }

    /**
     * 加载context
     */
    public void newArrayList() {
        this.typeInsn(NEW, ArrayList.class)
                .insn(DUP)
                .invoke(INVOKESPECIAL, ArrayList.class, "<init>", void.class);
    }

    /**
     * 加载Variables
     */
    public ScriptCompiler load2() {
        self().visitVarInsn(ALOAD, 2);
        return this;
    }

    /**
     * 加载3号变量，一般指异常(临时变量)
     */
    public ScriptCompiler load3() {
        self().visitVarInsn(ALOAD, 3);
        return this;
    }

    public ScriptCompiler load4() {
        self().visitVarInsn(ALOAD, 4);
        return this;
    }

    /**
     * 加载变量
     */
    public ScriptCompiler load(int index) {
        return load2().visitInt(index).invoke(INVOKEVIRTUAL, Variables.class, "getValue", Object.class, int.class);
    }

    /**
     * 加载变量
     */
    public ScriptCompiler load(VarIndex varIndex) {
        return load(varIndex.getIndex());
    }

    /**
     * 加载变量
     *
     * @param name 变量名
     */
    public ScriptCompiler load(String name) {
        int index = vars.peek().indexOf(name) + 1;
        if (index > 0) {    // 如果当前栈中有，则直接使用
            return load(index);
        } else {
            // 从环境中获取
            this.load1()
                    .ldc(name)
                    .invoke(INVOKEVIRTUAL, ScriptContext.class, "getEnvironmentValue", Object.class, String.class);
        }
        return this;
    }

    public ScriptCompiler label(Label label) {
        self().visitLabel(label);
        return this;
    }

    /**
     * 跳转
     */
    public ScriptCompiler jump(int opcode, Label label) {
        self().visitJumpInsn(opcode, label);
        return this;
    }

    /**
     * 移除变量
     */
    public ScriptCompiler remove(VarIndex varIndex) {
        if (varIndex == null) {
            return this;
        }
        return remove(varIndex.getName());
    }

    /**
     * 移除变量
     */
    public ScriptCompiler remove(String name) {
        List<String> varList = vars.peek();
        int index = varList.indexOf(name);
        if (index > -1) {
            varList.set(index, null);
        }
        return this;
    }

    /**
     * 配合pre_store使用，保存至数组中
     */
    public ScriptCompiler store() {
        return invoke(INVOKEVIRTUAL, Variables.class, "setValue", void.class, int.class, Object.class);
    }

    /**
     * 配合pre_store使用，保存至数组中
     */
    public ScriptCompiler store(VarIndex varIndex) {
        return varIndex.isScoped() ? scopeStore() : store();
    }

    /**
     * 配合pre_store使用，保存至数组中
     */
    public ScriptCompiler scopeStore() {
        return invoke(INVOKEVIRTUAL, Variables.class, "setScopeValue", void.class, int.class, Object.class);
    }

    /**
     * 保存变量
     */
    public ScriptCompiler store(int index) {
        self().visitVarInsn(ASTORE, index);
        return this;
    }

    /**
     * 保存变量
     */
    public ScriptCompiler frame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        self().visitFrame(type, numLocal, local, numStack, stack);
        return this;
    }

    /**
     * 写变量前的准备
     */
    public ScriptCompiler pre_store(int index) {
        return load2().visitInt(index);
    }

    /**
     * 写变量前的准备
     */
    public ScriptCompiler pre_store(VarIndex varIndex) {
        return pre_store(varIndex.getIndex());
    }

    public ScriptCompiler bipush(int value) {
        self().visitIntInsn(BIPUSH, value);
        return this;
    }

    public ScriptCompiler typeInsn(int opcode, Class<?> target) {
        self().visitTypeInsn(opcode, getJvmType(target));
        return this;
    }


    /**
     * 二元运算
     *
     * @param methodName 运算方法
     */
    public ScriptCompiler operator(String methodName) {
        self().visitInvokeDynamicInsn(methodName, MethodType.genericMethodType(2).toMethodDescriptorString(), OPERATOR_HANDLE, 2);
        return this;
    }

    /**
     * 位运算
     *
     * @param methodName 运算方法
     */
    public ScriptCompiler bit(String methodName) {
        self().visitInvokeDynamicInsn(methodName, MethodType.genericMethodType(2).toMethodDescriptorString(), BIT_HANDLE, 2);
        return this;
    }

    /**
     * 将方法转为lambda
     *
     * @param methodName 方法名
     */

    public ScriptCompiler lambda(String methodName) {
		return lambda(methodName, false);
	}

	public ScriptCompiler lambda(String methodName, boolean snapshotVariables) {
        // 捕获 this 和当前 Variables（slot 2）
        load0();
        load2();
		if (snapshotVariables) {
			invoke(INVOKEVIRTUAL, Variables.class, "snapshot", Variables.class);
		}
        Handle metaFactory = new Handle(H_INVOKESTATIC, getJvmType(LambdaMetafactory.class), "metafactory", makeDescriptor(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class, MethodType.class, MethodHandle.class, MethodType.class), false);
        // SAM 方法类型: (Object) -> Object（Function<Object,Object>.apply 的擦除签名）
        String samDescriptor = makeDescriptor(Object.class, Object.class);
        // impl: INVOKEVIRTUAL Script_N.lambda_M(Variables, Object)Object
        // LambdaMetafactory 会将捕获的 Script_N 作为 receiver，Variables 作为第一个显式参数
        String implDescriptor = makeDescriptor(Object.class, Variables.class, Object.class);
        Handle impl = new Handle(H_INVOKEVIRTUAL, getClassName(), methodName, implDescriptor, false);
        // invokedynamic 捕获描述符: (Script_N, Variables) -> Function
        String captureDesc = "(L" + getClassName() + ";L" + getJvmType(Variables.class) + ";)" + Type.getType(Function.class).getDescriptor();
        self().visitInvokeDynamicInsn("apply", captureDesc, metaFactory, Type.getType(samDescriptor), impl, Type.getType(samDescriptor));
        return this;
    }

    /**
     * invokedynamic调用
     *
     * @param methodName 方法名
     * @param arguments  参数个数
     */
    public ScriptCompiler call(String methodName, int arguments) {
        self().visitInvokeDynamicInsn(methodName, MethodType.genericMethodType(arguments).toMethodDescriptorString(), FUNCTION_HANDLE, arguments);
        return this;
    }

    /**
     * 执行算术运算
     *
     * @param methodName 方法名
     */
    public ScriptCompiler arithmetic(String methodName) {
        self().visitInvokeDynamicInsn(methodName, MethodType.genericMethodType(2).toMethodDescriptorString(), ARITHMETIC_HANDLE, 2);
        return this;
    }

    /**
     * 将int值装箱
     */
    public ScriptCompiler asInteger() {
        return invoke(INVOKESTATIC, Integer.class, "valueOf", Integer.class, int.class);
    }

    /**
     * 将boolean值装箱
     */
    public ScriptCompiler asBoolean() {
        return invoke(INVOKESTATIC, Boolean.class, "valueOf", Boolean.class, boolean.class);
    }

    /**
     * 调用方法
     *
     * @param opcode        调用类型
     * @param target        目标类
     * @param method        方法名
     * @param returnType    返回值类型
     * @param argumentTypes 参数类型
     */
    public ScriptCompiler invoke(int opcode, Class<?> target, String method, Class<?> returnType, Class<?>... argumentTypes) {
        return invoke(opcode, target, method, false, returnType, argumentTypes);
    }

    /**
     * 调用方法
     *
     * @param opcode        调用类型
     * @param target        目标类
     * @param method        方法名
     * @param isInterface   是否是接口
     * @param returnType    返回值类型
     * @param argumentTypes 参数类型
     * @return 编译器实例
     */
    public ScriptCompiler invoke(int opcode, Class<?> target, String method, boolean isInterface, Class<?> returnType, Class<?>... argumentTypes) {
        self().visitMethodInsn(opcode, getJvmType(target), method, makeDescriptor(returnType, argumentTypes), isInterface);
        return this;
    }

    /**
     * 新增一个常量到栈顶
     * @param value 常量
     * @return 编译器实例
     */
    public ScriptCompiler ldc(Object value) {
        self().visitLdcInsn(value);
        return this;
    }

    /**
     * 操作符设置
     * DUP 压入、 \ RETURN 返回 等等
     * @param opcode 操作符
     * @return 编译器实例
     */
    public ScriptCompiler insn(int opcode) {
        self().visitInsn(opcode);
        return this;
    }

    public void intInsn(int opcode, int operand) {
        self().visitIntInsn(opcode, operand);
    }

    /**
     * 编译数组
     */
    public ScriptCompiler newArray(List<Expression> values) {
        int size = values.size();
        visitInt(size).typeInsn(ANEWARRAY, Object.class);
        for (int i = 0; i < size; i++) {
            insn(DUP).visitInt(i).visit(values.get(i)).insn(AASTORE);
        }
        return this;
    }

    /**
     * 编译int值
     */
    public ScriptCompiler visitInt(int value) {
        if (value >= -1 && value <= 5) {
            insn(ICONST[value + 1]);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            intInsn(BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            intInsn(SIPUSH, value);
        } else {
            ldc(value);
        }
        return this;
    }

    private void initContext() {
        if (!this.contextInitFlag) {
            this.load0().load1();
            self().visitFieldInsn(PUTFIELD, getClassName(), "context", Type.getDescriptor(ScriptContext.class));
            this.contextInitFlag = true;
            // var2 = context.createVariables(this, varIndices.size())
            this.load1()
                    .load0()
                    .visitInt(varIndices.size())
                    .invoke(INVOKEVIRTUAL, ScriptContext.class, "createVariables", Variables.class, ScriptRuntime.class, int.class)
                    .store(2);
        }
    }

    public void loadVars() {
        // 对于未定义变量值的，从环境中获取
        // var2[xx] = super.getEnvironmentValue(varIndex.getName())
        this.varIndices.stream()
                .filter(VarIndex::isReference)
                .forEach(varIndex -> this.load2()
                        .visitInt(varIndex.getIndex())
                        .load1()
                        .ldc(varIndex.getName())
                        .invoke(INVOKEVIRTUAL, ScriptContext.class, "getEnvironmentValue", Object.class, String.class)
                        .store()
                );
    }

    public String visitMethod(String methodName, Runnable callback) {
        return visitMethod(methodName, Collections.emptyList(), Collections.emptyList(), callback);
    }

    public String visitMethod(String methodName, List<Node> childNodes, List<VarIndex> parameters, Runnable callback) {
        childNodes.forEach(it -> it.visitMethod(this));
        int index = this.getFunctionIndex();
        methodName = methodName + "_" + index;
//        this.createMethod(ACC_PRIVATE, methodName, Descriptor.makeDescriptor(Object.class, Variables.class, Object[].class))
//                .load1()    // Variables
//                .load2()    // 传入的参数
//                .visitInt(index)
//                // 构建参数
//                .visitInt(parameters.size())
//                .intInsn(NEWARRAY, T_INT);    // new int[parameters.size()]
//        for (int i = 0; i < parameters.size(); i++) {
//            this.insn(DUP)
//                    .visitInt(i)
//                    .visitInt(parameters.get(i).getIndex())
//                    .insn(IASTORE);
//        }
//        // 复制变量
//        this.invoke(INVOKEVIRTUAL, Variables.class, "copy", Variables.class, Object[].class, int.class, int[].class)
//                .store(2);

        // Lambda 方法签名: (Variables parentVars, Object arg) -> Object
        // slot 0=this, slot 1=parentVars, slot 2=arg（调用后覆盖为子作用域 Variables）
        this.createMethod(ACC_PRIVATE, methodName, Descriptor.makeDescriptor(Object.class, Variables.class, Object.class));
        if (parameters.isEmpty()) {
            // 零参 lambda：直接使用父作用域 Variables（闭包语义，变量读写直接影响外部）
            this.load1().store(2);
        } else if (parameters.size() == 1) {
            // 单参 lambda：arg 是值本身，创建子作用域并绑定参数
            this.load1()    // 推入 parentVars（copy 的 receiver）
                    .load2()    // 推入 arg（实参值）
                    .visitInt(parameters.get(0).getIndex())  // 推入参数槽位索引
                    .invoke(INVOKEVIRTUAL, Variables.class, "copy", Variables.class, Object.class, int.class)
                    .store(2);  // slot 2 现在是子作用域 Variables
        } else {
            // 多参 lambda：arg 是 Object[]（由 toLambdaArg 打包），构建 int[] 槽位数组后调用 copy
            this.load1()    // 推入 parentVars（copy 的 receiver）
                    .load2()    // 推入 arg（Object[]）
                    .typeInsn(CHECKCAST, Object[].class)  // 转为 Object[]
                    .visitInt(index)  // 推入 scopeIndex
                    .visitInt(parameters.size())
                    .intInsn(NEWARRAY, T_INT);  // new int[n]
            for (int i = 0; i < parameters.size(); i++) {
                this.insn(DUP)
                        .visitInt(i)
                        .visitInt(parameters.get(i).getIndex())
                        .insn(IASTORE);
            }
            this.invoke(INVOKEVIRTUAL, Variables.class, "copy", Variables.class, Object[].class, int.class, int[].class)
                    .store(2);  // slot 2 现在是子作用域 Variables
        }
        callback.run();
        this.pop();
        return methodName;
    }


    public String visitMethod(String methodName, List<Node> childNodes, List<VarIndex> parameters) {
        return visitMethod(methodName, childNodes, parameters, () -> this.compile(childNodes));
    }

    public Deque<List<Node>> finallyBlockStack() {
        return finallyStack;
    }

    public void pushFinallyBlock(List<Node> finallyBlock) {
        finallyStack.addFirst(finallyBlock);
    }

    public List<Node> popFinallyBlock() {
        return finallyStack.pollFirst();
    }

    public ScriptCompiler pop() {
        MethodVisitor visitor = methodVisitors.pop();
        visitor.visitInsn(ACONST_NULL);
        visitor.visitInsn(ARETURN);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
        vars.pop();
        labelStack.pop();
        return this;
    }

    public byte[] bytecode() {
        pop();
        classWriter.visitEnd();
        return classWriter.toByteArray();
    }


    /**
     * 获取类名
     */
    public String getClassName() {
        return "Script_" + id;
    }

    private MethodVisitor self() {
        return methodVisitors.peek();
    }

    private static String getJvmType(Class<?> target) {
        return target == null ? null : target.getName().replace(".", "/");
    }

    private static Handle makeHandle(Class<?> target) {
        return new Handle(H_INVOKESTATIC,
                getJvmType(target),
                "bootstrap",
                makeDescriptor(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class, int.class),
                false);
    }
}
