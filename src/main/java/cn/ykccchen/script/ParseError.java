package cn.ykccchen.script;

import cn.ykccchen.script.exception.ExitException;
import cn.ykccchen.script.exception.ScriptEvaluationException;
import cn.ykccchen.script.exception.ScriptErrorCode;
import cn.ykccchen.script.exception.ScriptRuntimeException;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.TokenStream;
import cn.ykccchen.script.runtime.ScriptRuntime;

import java.util.ArrayList;
import java.util.List;

/**
 * All errors reported by the library go through the static functions of this class.
 */
public class ParseError {

	private ParseError() {
		// Prevent instantiation
	}

	/**
	 * <p>
	 * Create an error message based on the provided message and stream, highlighting the line on which the error happened. If the
	 * stream has more tokens, the next token will be highlighted. Otherwise the end of the source of the stream will be
	 * highlighted.
	 * </p>
	 *
	 * <p>
	 * Throws a {@link RuntimeException}
	 * </p>
	 */
	public static void error(String message, TokenStream stream) {
		if (stream.hasMore()) {
			error(message, stream.consume().getSpan());
		} else {
			error(message, stream.getPrev().getSpan());
		}
	}

	/**
	 * Create an error message based on the provided message and location, highlighting the location in the line on which the
	 * error happened. Throws a {@link ScriptEvaluationException}
	 **/
	public static void error(String message, Span location, Throwable cause) {
		error(null, message, location, cause, null);
	}

	public static void error(ScriptErrorCode code, String message, Span location, Throwable cause) {
		error(code, message, location, cause, null);
	}

	private static void error(ScriptErrorCode code, String message, Span location,
			Throwable cause, String sourceName) {
		cause = unwrap(cause);
		if (cause instanceof ExitException) {
			throw (ExitException) cause;
		}
		if (cause instanceof ScriptEvaluationException) {
			ScriptEvaluationException mse = ((ScriptEvaluationException) cause);
			if (mse.getLocation() == null) {
				error(code, message, location, cause.getCause(), sourceName);
				return;
			}
			throw mse;
		}
		String errorMessage = message;
		if (location != null) {
			Span.Line line = location.getLine();
			errorMessage += " at Row:";
			errorMessage += line.getLineNumber() + "~" + line.getEndLineNumber() + ",Col:";
			errorMessage += line.getStartCol() + "~" + line.getEndCol() + "\n\n";
			errorMessage += line.getText();
			errorMessage += "\n";
			int errorStart = location.getStart() - line.getStart();
			int errorEnd = errorStart + location.getText().length() - 1;
			for (int i = 0, n = line.getText().length(); i < n; i++) {
				boolean useTab = line.getText().charAt(i) == '\t';
				errorMessage += i >= errorStart && i <= errorEnd ? "^" : useTab ? "\t" : " ";
			}
		}
		ScriptErrorCode resolvedCode = code;
		if (resolvedCode == null && cause instanceof ScriptRuntimeException) {
			resolvedCode = ((ScriptRuntimeException) cause).getErrorCode();
		}
		if (resolvedCode == null) {
			resolvedCode = ScriptErrorCode.SCRIPT_RUNTIME_ERROR;
		}
		throw new ScriptEvaluationException(resolvedCode, errorMessage, message,
				cause, location, sourceName);
	}

	/**
	 * Create an error message based on the provided message and location, highlighting the location in the line on which the
	 * error happened. Throws a {@link ScriptEvaluationException}
	 **/
	public static void error(String message, Span location) {
		error(message, location, null);
	}

	public static Throwable unwrap(Throwable root) {
		Throwable parent = root;
		while (parent != null) {
			if (parent instanceof ScriptEvaluationException) {
				root = parent;
			}
			parent = parent.getCause();
		}
		return root;
	}

	public static void transfer(ScriptRuntime runtime, Throwable t) {
		transfer(runtime, t, null);
	}

	public static void transfer(ScriptRuntime runtime, Throwable t, String sourceName) {
		StackTraceElement[] elements = t.getStackTrace();
		Throwable cause = t;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		Span span = null;
		if(runtime != null){
			List<StackTraceElement> elementList = new ArrayList<>();
			String className = runtime.getClass().getName();
			for (StackTraceElement element : elements) {
				if (element.getLineNumber() > -1 && element.getClassName().equals(className)) {
					Span currentSpan = runtime.getSpan(element.getLineNumber());
					elementList.add(new StackTraceElement(element.getClassName(), element.getMethodName(), element.getFileName(), currentSpan.getLine().getLineNumber()));
					if(span == null){
						span = currentSpan;
					}
				} else {
					elementList.add(element);
				}
			}
			cause.setStackTrace(elementList.toArray(new StackTraceElement[0]));

		}
		error(null, t.getMessage(), span, cause, sourceName);
	}

}
