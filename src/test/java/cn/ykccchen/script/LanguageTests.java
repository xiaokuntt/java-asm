package cn.ykccchen.script;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import cn.ykccchen.script.category.IntegrationCategory;

import javax.script.*;

@Ignore("Embedded language block syntax is not enabled in current parser baseline")
@Category(IntegrationCategory.class)
public class LanguageTests extends BaseTest{

	@Test
	public void customLanguageTest(){
		ResourceLoader.addScriptLanguageLoader((language)->{
			if("custom".equalsIgnoreCase(language)){
				return (context,content)-> "get name is " + context.get("name");
			}
			return null;
		});
		Assert.assertEquals("get name is hello",execute("language/custom.ms"));
	}

	@Test
	public void jsrTest(){
		ScriptEngineManager sem = new ScriptEngineManager();
		ResourceLoader.addScriptLanguageLoader((language)->{
			javax.script.ScriptEngine engine = sem.getEngineByName(language);
			if(engine != null){
				return (context,content)-> {
					try {
						return engine.eval(content,new SimpleBindings(context));
					} catch (ScriptException e) {
						throw new RuntimeException(e);
					}
				};
			}
			return null;
		});
		System.out.println(execute("language/javascript.ms"));
	}
}
