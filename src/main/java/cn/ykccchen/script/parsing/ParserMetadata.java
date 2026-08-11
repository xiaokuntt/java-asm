package cn.ykccchen.script.parsing;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static cn.ykccchen.script.parsing.TokenType.*;

/**
 * Immutable grammar metadata shared by parser instances.
 */
final class ParserMetadata {

	static final TokenType[][] BINARY_OPERATOR_PRECEDENCE = {
			{Assignment},
			{RShift2Equal, RShiftEqual, LShiftEqual, XorEqual, BitOrEqual, BitAndEqual, PercentEqual, ForwardSlashEqual, AsteriskEqual, MinusEqual, PlusEqual},
			{Or, SqlOr},
			{And, SqlAnd},
			{BitOr},
			{Xor},
			{BitAnd},
			{EqualEqualEqual, Equal, NotEqualEqual, NotEqual, SqlNotEqual},
			{Less, LessEqual, Greater, GreaterEqual, InstanceOf},
			{Plus, Minus},
			{LShift, RShift, Rshift2},
			{Asterisk, ForwardSlash, Percentage}
	};

	static final TokenType[] UNARY_OPERATORS = {MinusMinus, PlusPlus, BitNot, Minus, Plus, Not};

	static final Set<String> CAST_TYPES = immutableSet(
			"int", "long", "double", "float", "byte", "short", "char", "boolean",
			"Integer", "Long", "Double", "Float", "Byte", "Short", "Character", "Boolean", "String");

	static final Set<String> KEYWORDS = immutableSet(
			"import", "as", "var", "return", "break", "continue", "if", "for", "in", "new",
			"true", "false", "null", "else", "try", "catch", "finally", "do", "while", "switch",
			"assert", "const", "async", "await", "exit", "and", "or", "throw", ":");

	static final String[] KEYWORD_ARRAY = KEYWORDS.toArray(new String[0]);

	private ParserMetadata() {
	}

	private static Set<String> immutableSet(String... values) {
		return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
	}
}
