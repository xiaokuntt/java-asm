package cn.ykccchen.script;

import java.io.InputStream;
import java.util.Map;

public class BaseTest {

	public static String readScript(String filename) {
		try (InputStream is = BaseTest.class.getResourceAsStream("/" + filename)) {
			byte[] buf = new byte[1024];
			StringBuilder sb = new StringBuilder();
			int len = -1;
			while ((len = is.read(buf, 0, buf.length)) != -1) {
				sb.append(new String(buf, 0, len));
			}
			return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public static Object execute(String filename) {
		return execute(filename, null);
	}

	public static Object execute(String filename, Map<String, Object> parameters) {
		String str = readScript(filename);
		long t = System.currentTimeMillis();
		Script script = Script.create(str, null);
		script.compile();
		System.out.println("编译耗时：" + (System.currentTimeMillis() - t) + "ms");
		t = System.currentTimeMillis();
		ScriptContext context = new ScriptContext();
		context.setScriptName(filename);
		context.putMapIntoContext(parameters);
		Object value = script.execute(context);
		System.out.println("执行耗时：" + (System.currentTimeMillis() - t) + "ms");
		System.out.println("执行结果：" + value);
		return value;
	}
}
