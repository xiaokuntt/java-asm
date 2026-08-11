package cn.ykccchen.script.parsing;

import cn.ykccchen.script.exception.ScriptEvaluationException;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ParsingBoundaryTests {

	@Test
	public void tokenStreamShouldCoverNavigationMatchingAndExpectations() {
		String source = "alpha beta //comment";
		Token alpha = new Token(TokenType.Identifier, new Span(source, 0, 5));
		Token beta = new Token(TokenType.Identifier, new Span(source, 6, 10));
		Token comment = new Token(TokenType.Comment, new Span(source, 11, source.length()));
		TokenStream stream = new TokenStream(Arrays.asList(alpha, beta, comment));

		Assert.assertTrue(stream.hasMore());
		Assert.assertTrue(stream.hasNext());
		Assert.assertFalse(stream.hasPrev());
		Assert.assertEquals(0, stream.makeIndex());
		Assert.assertTrue(stream.match(TokenType.Identifier, false));
		Assert.assertTrue(stream.match("ALPHA", false, true));
		Assert.assertTrue(stream.match(Arrays.asList("missing", "alpha"), true));
		Assert.assertTrue(stream.hasPrev());
		Assert.assertSame(alpha, stream.getPrev());
		Assert.assertSame(comment, stream.next());
		Assert.assertSame(beta, stream.prev());
		Assert.assertSame(beta, stream.expect(TokenType.Identifier));
		Assert.assertSame(comment, stream.expect(TokenType.Comment, TokenType.Semicolon));
		Assert.assertFalse(stream.hasMore());
		Assert.assertFalse(stream.hasNext());
		stream.resetIndex(0);
		Assert.assertSame(alpha, stream.expect("alpha"));
		Assert.assertTrue(stream.match(true, "beta", "other"));
		Assert.assertTrue(stream.match(Collections.singletonList("//COMMENT"), true, true));
		Assert.assertFalse(stream.match("none", false));
		Assert.assertFalse(stream.match(false, TokenType.Semicolon, TokenType.Colon));
		Assert.assertEquals(1, stream.comments().size());
	}

	@Test
	public void tokenStreamShouldReportNavigationAndExpectationFailures() {
		Token only = new Token(TokenType.Identifier, new Span("value"));
		TokenStream lastOnly = new TokenStream(Collections.singletonList(only));
		assertThrows(RuntimeException.class, lastOnly::next);
		Assert.assertSame(only, lastOnly.consume());
		Assert.assertFalse(lastOnly.hasMore());

		TokenStream stream = new TokenStream(Collections.singletonList(only));
		assertThrows(RuntimeException.class, stream::prev);
		assertThrows(RuntimeException.class, stream::getPrev);
		Assert.assertSame(only, stream.consume());
		assertThrows(RuntimeException.class, stream::consume);
		assertThrows(RuntimeException.class, stream::next);
		assertThrows(ScriptEvaluationException.class, () -> stream.expect(TokenType.Semicolon));
		assertThrows(ScriptEvaluationException.class,
				() -> stream.expect(TokenType.Semicolon, TokenType.Colon));
		assertThrows(ScriptEvaluationException.class, () -> stream.expect("missing", true));

		TokenStream mismatch = new TokenStream(Collections.singletonList(only));
		assertThrows(ScriptEvaluationException.class, () -> mismatch.expect(TokenType.Semicolon));
		assertThrows(ScriptEvaluationException.class,
				() -> mismatch.expect(TokenType.Semicolon, TokenType.Colon));
		assertThrows(ScriptEvaluationException.class, () -> mismatch.expect("missing"));
		Assert.assertFalse(mismatch.match(Collections.<String>emptyList(), false));
	}

	@Test
	public void tokenizerShouldCoverLanguagesTemplatesCommentsAndNumericForms() {
		TokenStream language = Tokenizer.tokenize("```java\nhello```");
		Assert.assertEquals("java", language.consume().getText());
		Assert.assertEquals("\nhello", language.consume().getText());
		assertThrows(ScriptEvaluationException.class, () -> Tokenizer.tokenize("``` no-language"));
		assertThrows(ScriptEvaluationException.class, () -> Tokenizer.tokenize("```java missing-end"));

		Token template = Tokenizer.tokenize("`head${1 + 2}tail`").consume();
		Assert.assertNotNull(template.getTokenStream());
		Assert.assertTrue(template.getTokenStream().hasMore());
		Assert.assertEquals(TokenType.StringLiteral, Tokenizer.tokenize("`a\\\\b`").consume().getType());
		assertThrows(ScriptEvaluationException.class, () -> Tokenizer.tokenize("`missing-end"));

		TokenStream comments = Tokenizer.tokenize("//one\n/*two*/value", true);
		Assert.assertEquals(2, comments.comments().size());
		Assert.assertEquals(TokenType.Comment, comments.consume().getType());
		Assert.assertEquals(TokenType.Comment, comments.consume().getType());

		TokenStream numbers = Tokenizer.tokenize("0xFFL 0b101L 0b 0x100 0x7F 1.5f 2M");
		List<TokenType> types = Arrays.asList(TokenType.LongLiteral, TokenType.LongLiteral,
				TokenType.ByteLiteral, TokenType.IntegerLiteral, TokenType.ByteLiteral,
				TokenType.FloatLiteral, TokenType.DecimalLiteral);
		for (TokenType type : types) {
			Assert.assertEquals(type, numbers.consume().getType());
		}
		assertThrows(ScriptEvaluationException.class, () -> Tokenizer.tokenize("1.2b"));
		assertThrows(ScriptEvaluationException.class, () -> Tokenizer.tokenize("1.2s"));
		assertThrows(ScriptEvaluationException.class, () -> Tokenizer.tokenize("1.2L"));
	}

	@Test
	public void tokenizerShouldCoverStringRegexpAndKeywordBoundaries() {
		assertThrows(ScriptEvaluationException.class, () -> Tokenizer.tokenize("'line\nbreak'"));
		assertThrows(ScriptEvaluationException.class, () -> Tokenizer.tokenize("\"missing-end"));
		TokenStream keywords = Tokenizer.tokenize("AND or instanceof null true false");
		Assert.assertEquals(TokenType.SqlAnd, keywords.consume().getType());
		Assert.assertEquals(TokenType.SqlOr, keywords.consume().getType());
		Assert.assertEquals(TokenType.InstanceOf, keywords.consume().getType());
		Assert.assertEquals(TokenType.NullLiteral, keywords.consume().getType());

		RegexpToken regexp = (RegexpToken) Tokenizer.tokenize("/[a/b]+/gimsuy").consume();
		Assert.assertEquals(TokenType.RegexpLiteral, regexp.getType());
		Assert.assertTrue(regexp.getFlag() != 0);
		assertThrows(ScriptEvaluationException.class, () -> Tokenizer.tokenize("/[abc/"));
		Assert.assertEquals(TokenType.ForwardSlash, Tokenizer.tokenize("/abc\n").consume().getType());
	}

	private static void assertThrows(Class<? extends Throwable> type, Runnable operation) {
		try {
			operation.run();
			Assert.fail("Expected " + type.getName());
		} catch (Throwable failure) {
			Assert.assertTrue("Unexpected failure: " + failure, type.isInstance(failure));
		}
	}
}
