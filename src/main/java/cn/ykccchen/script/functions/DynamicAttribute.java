package cn.ykccchen.script.functions;

import cn.ykccchen.script.exception.ScriptRuntimeException;

import java.beans.Transient;

public interface DynamicAttribute<T, R> {

	@Transient
	T getDynamicAttribute(String key);

	@Transient
	default R setDynamicAttribute(String key, T value) {
		throw new ScriptRuntimeException("不支持此赋值操作");
	}
}
