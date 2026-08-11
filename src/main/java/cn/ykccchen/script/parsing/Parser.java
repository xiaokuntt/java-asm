package cn.ykccchen.script.parsing;

import cn.ykccchen.script.ParseError;
import cn.ykccchen.script.parsing.ast.*;
import cn.ykccchen.script.parsing.ast.literal.*;
import cn.ykccchen.script.parsing.ast.statement.*;
import cn.ykccchen.script.parsing.ast.binary.AssigmentOperation;

import java.util.*;

import static cn.ykccchen.script.parsing.TokenType.*;


/**
 * 语法解析器
 **/
public class Parser {

    public static final String ANONYMOUS_VARIABLE = "-anonymous";
    private VarScope varNames = new VarScope();
    private VarScope rootvarNames = varNames;
    private final List<Span> spans = new ArrayList<>();
    private final Set<VarIndex> varIndices = new LinkedHashSet<>();
    private int varCount = 0;
    private boolean requiredNew = true;
    private TokenStream stream;
    private final List<String> defines = new ArrayList<>();

    public Set<VarIndex> getVarIndices() {
        return varIndices;
    }


    public List<Node> parse(String source) {
        List<Node> nodes = new ArrayList<>();
        push();
        stream = Tokenizer.tokenize(source);
        while (stream.hasMore()) {
            Node node = parseStatement();
            if (node != null) {
                validateNode(node);
                nodes.add(node);
            }
        }
        pop();
        return nodes;
    }

    private void validateNode(Node node) {
        if (node instanceof Literal) {
            ParseError.error("literal cannot be used alone", node.getSpan());
        }
    }

    private Node parseStatement() {
        return parseStatement(false);
    }

    private Node parseStatement(boolean expectRightCurly) {
        Node result = null;
        if (stream.match("import", false)) {
            result = parseImport();
        } else if (matchVarDefine()) {
            result = parseVariableDefine();
        } else if (stream.match("if", false)) {
            result = parseIfStatement();
        } else if (stream.match("return", false)) {
            result = parseReturn();
        } else if (stream.match("for", false)) {
            result = parseForStatement();
        } else if (stream.match("while", false)) {
            result = parseWhileStatement();
        } else if (stream.match("do", false)) {
            result = parseDoWhileStatement();
        } else if (stream.match("continue", false)) {
            result = new Continue(stream.consume().getSpan());
        } else if (stream.match("try", false)) {
            result = parseTryStatement();
        } else if (stream.match("break", false)) {
            result = new Break(stream.consume().getSpan());
        }else if (stream.match("switch", false)) {
            result = parseSwitchStatement();
        }  else if (stream.match("assert", false)) {
            result = parseAssert();
        } else if (stream.match("throw", false)) {
            result = parseThrow();
        } else if (stream.match("exit", false)) {
            result = parseExit();
        } else {
            int index = stream.makeIndex();
            if (matchTypeDefine()) {
                stream.resetIndex(index);
                result = parseVariableDefine();
            }
            if (result == null) {
                stream.resetIndex(index);
                result = parseExpression(expectRightCurly);
            }
        }
        // consume semi-colons as statement delimiters
        while (stream.match(";", true)) {
            ;
        }
        return result;
    }

    private boolean matchTypeDefine() {
        boolean typeDefine = stream.match(Identifier, true);
        if (!typeDefine) {
            return false;
        }
        int index = stream.makeIndex();
        try {
            if ("new".equals(stream.getPrev().getText())) {
                return false;
            }
            if (stream.match(Identifier, false)) {
                return true;
            }
            int end = stream.getPrev().getSpan().getEnd();
            if (stream.hasMore()) {
                Token nextTok = stream.consume();
                if (nextTok.getSpan().getStart() == end) {
                    // Immediately adjacent non-identifier token
                    if (nextTok.getType() == Less) {
                        // Generic type: TypeName<...> varName — skip generic params
                        int depth = 1;
                        while (stream.hasMore() && depth > 0) {
                            Token t = stream.consume();
                            if (t.getType() == Less) depth++;
                            else if (t.getType() == Greater || t.getType() == SqlNotEqual) depth--;
                        }
                        return stream.match(Identifier, false);
                    }
                    return false;
                }
            }
        } finally {
            stream.resetIndex(index);
        }
        // destructuring support
        boolean isMapAccess;
        if ((isMapAccess = stream.match(true, LeftCurly)) || stream.match(true, LeftBracket)) {
            do {
                if (!stream.match(true, Identifier)) {
                    return false;
                }
            } while (stream.match(true, Comma));
            if (isMapAccess) {
                return stream.match(true, RightCurly);
            } else {
                return stream.match(true, RightBracket);
            }
        }
        return false;
    }

    private boolean matchVarDefine() {
        return stream.match(false, "var");
    }

    private boolean isCastStart() {
        return stream.hasMore() && stream.match(false,
                Identifier, IntegerLiteral, LongLiteral, DoubleLiteral, FloatLiteral,
                ByteLiteral, ShortLiteral, DecimalLiteral, StringLiteral, BooleanLiteral,
                NullLiteral, RegexpLiteral, LeftParantheses, LeftBracket, LeftCurly,
                Not, BitNot, Minus, Plus, PlusPlus, MinusMinus
        );
    }

    private VarIndex add(String name) {
        VarScope varIndices = varNames;
        do {
            for (int j = varIndices.size() - 1; j >= 0; j--) {
                VarIndex varIndex = varIndices.get(j);
                if (varIndex.getName().equals(name)) {
                    return defines.contains(name) ? varIndex.scoped() : varIndex;
                }
            }
        } while ((varIndices = varIndices.getParent()) != null);
        return add(new VarIndex(name, varCount++, true), true);
    }

    private VarIndex add(VarIndex varIndex) {
        return add(varIndex, false);
    }

    private VarIndex add(VarIndex varIndex, boolean isRoot) {
        if (defines.contains(varIndex.getName())) {
            varIndex = varIndex.scoped();
        }
        if (isRoot) {
            rootvarNames.add(varIndex);
        } else {
            varNames.add(varIndex);
        }
        varIndices.add(varIndex);
        return varIndex;
    }

    public VarIndex forceAdd(String name) {
        return forceAdd(name, false);
    }

    private VarIndex forceAdd(String name, boolean isConst) {
        return add(new VarIndex(name, varCount++, false, isConst));
    }

    private void push() {
        varNames = varNames.push();
    }

    private void pop() {
        varNames = varNames.pop();
    }

    private Span addSpan(Span opening, Span ending) {
        addSpan(opening);
        addSpan(ending);
        return addSpan(new Span(opening, ending));
    }

    private Span addSpan(String source, int start, int end) {
        return addSpan(new Span(source, start, end));
    }

    private Span addSpan(Span span) {
        this.spans.add(span);
        return span;
    }


    private Node parseThrow() {
        Span opening = stream.consume().getSpan();
        Expression expression = parseExpression();
        return new Throw(addSpan(opening, stream.getPrev().getSpan()), expression);
    }

    private Node parseExit() {
        Span opening = stream.expect("exit").getSpan();
        if (stream.match(false, Semicolon, RightCurly)) {
            return new Exit(opening, null);
        }
        List<Expression> expressions = new ArrayList<>();
        do {
            expressions.add(parseExpression());
        } while (stream.match(Comma, true));
        return new Exit(addSpan(opening, stream.getPrev().getSpan()), expressions);
    }

    private Node parseAssert() {
        int index = stream.makeIndex();
        try {
            Span opening = stream.expect("assert").getSpan();
            Expression condition = parseExpression();
            stream.expect(Colon);
            List<Expression> expressionList = new ArrayList<>();
            do {
                expressionList.add(parseExpression());
            } while (stream.match(Comma, true));
            return new Assert(addSpan(opening, stream.getPrev().getSpan()), condition, expressionList);
        } catch (Exception e) {
            stream.resetIndex(index);
            return parseExpression();
        }
    }


    private Import parseImport() {
        Span opening = stream.expect("import").getSpan();
        if (stream.hasMore()) {
            Token expected = stream.consume();
            String packageName = null;
            boolean isStringLiteral = expected.getType() == StringLiteral;
            if (isStringLiteral) {
                packageName = createStringLiteral(expected).getValue();
            } else if (expected.getType() == Identifier) {
                Span startSpan = expected.getSpan();
                packageName = startSpan.getText();
                while (stream.match(true, Period)) {
                    isStringLiteral = true;
                    if (stream.match(false, Asterisk)) {
                        expected = stream.consume();
                        break;
                    }
                    expected = stream.expect(Identifier);
                }
                if (isStringLiteral) {
                    packageName = new Span(startSpan, expected.getSpan()).getText();
                }
            } else {
                ParseError.error("Expected identifier or string, but got stream is " + expected.getType().getError(), stream.getPrev().getSpan());
            }
            String varName = packageName;
            if (isStringLiteral) {
                if (stream.match("as", true)) {
                    expected = stream.expect(Identifier);
                    checkKeyword(expected.getSpan());
                    varName = expected.getSpan().getText();
                } else {
                    String temp = packageName;
                    if (!temp.startsWith("@")) {
                        int index = temp.lastIndexOf(".");
                        if (index != -1) {
                            temp = temp.substring(index + 1);
                        }
                    } else {
                        ParseError.error("Expected as", stream);
                    }
                    varName = temp;
                }
            }
            return new Import(addSpan(opening, expected.getSpan()), packageName, forceAdd(varName), !isStringLiteral);
        }
        ParseError.error("Expected identifier or string, but got stream is EOF", stream.getPrev().getSpan());
        return null;
    }

    private TryStatement parseTryStatement() {
        Token opening = stream.expect("try");
        push();
        List<VariableDefine> tryResources = new ArrayList<>();
        if (stream.match(LeftParantheses, true)) {
            if (stream.match(RightParantheses, false)) {
                // 空的 try-with-resource
            } else {
                while (!stream.match(RightParantheses, false)) {
                    if (stream.match(Semicolon, true)) {
                        continue;
                    }
                    VariableDefine result = null;
                    if (matchVarDefine()) {
                        result = parseVariableDefine();
                    } else {
                        if (stream.match(false, ParserMetadata.KEYWORD_ARRAY)) {
                            ParseError.error("try 括号中只允许写赋值语句", stream.consume().getSpan());
                        }
                        int index = stream.makeIndex();
                        if (matchTypeDefine()) {
                            stream.resetIndex(index);
                            result = parseVariableDefine();
                        }
                        if (result == null) {
                            stream.resetIndex(index);
                            ParseError.error("try 括号中只允许写赋值语句", stream.consume().getSpan());
                        }
                    }
                    tryResources.add(result);
                }
            }
            stream.expect(RightParantheses);
        }
        List<Node> tryBlocks = parseFunctionBody();
        pop();
        List<Node> catchBlocks = new ArrayList<>();
        List<Node> finallyBlocks = new ArrayList<>();
        VarIndex exceptionVarNode = null;
        if (stream.match("catch", true)) {
            push();
            if (stream.match(LeftParantheses, true)) {
                exceptionVarNode = add(stream.expect(Identifier).getText());
                defines.add(exceptionVarNode.getName());
                stream.expect(RightParantheses);
            }
            catchBlocks.addAll(parseFunctionBody());
            pop();
        }
        if (stream.match("finally", true)) {
            push();
            finallyBlocks.addAll(parseFunctionBody());
            pop();
        }
        return new TryStatement(addSpan(opening.getSpan(), stream.getPrev().getSpan()), exceptionVarNode, tryBlocks, tryResources, catchBlocks, finallyBlocks);
    }

    private List<Node> parseFunctionBody() {
        stream.expect(LeftCurly);
        List<Node> blocks = new ArrayList<>();
        while (stream.hasMore() && !stream.match(RightCurly, false)) {
            Node node = parseStatement(true);
            if (node != null) {
                validateNode(node);
                blocks.add(node);
            }
        }
        expectClose();
        return blocks;
    }

    private Expression parseNewExpression(Span opening) {
        Expression expression = parseAccessOrCall(Identifier, true);
        if (expression instanceof MethodCall) {
            MethodCall call = (MethodCall) expression;
            Span span = addSpan(opening.getSource(), opening.getStart(), stream.getPrev().getSpan().getEnd());
            return parseAccessOrCall(new NewStatement(span, call.getMethod(), call.getArguments()));
        } else if (expression instanceof FunctionCall) {
            FunctionCall call = (FunctionCall) expression;
            Span span = addSpan(opening.getSource(), opening.getStart(), stream.getPrev().getSpan().getEnd());
            return parseAccessOrCall(new NewStatement(span, call.getFunction(), call.getArguments()));
        }
        ParseError.error("Expected MethodCall or FunctionCall or LambdaFunction", stream.getPrev().getSpan());
        return null;
    }

    private VariableDefine parseVariableDefine() {
        Token openToken = stream.consume();
        Span opening = openToken.getSpan();
        boolean isMapAccess;
        if ((isMapAccess = stream.match(false, LeftCurly)) ||  stream.match(false, LeftBracket)) {
            stream.expect(LeftCurly, LeftBracket);
            List<Token> tokens = new ArrayList<>();
            do {
                Token token = stream.expect(Identifier);
                tokens.add(token);
            } while (stream.match(true, Comma));
            if (isMapAccess) {
                stream.expect(RightCurly);
            } else{
                stream.expect(RightBracket);
            }
            stream.match(Assignment, true);
            boolean isConst = false;
            VariableDestructuringDefine destructuring = new VariableDestructuringDefine(addSpan(opening, stream.getPrev().getSpan()), tokens.size(), parseExpression(), isMapAccess);
            for (Token token : tokens) {
                String variableName = token.getSpan().getText();
                VarIndex varIndex = forceAdd(variableName, isConst);
                defines.add(variableName);
                VariableDefine variableDefine = new VariableDefine(addSpan(token.getSpan(), stream.getPrev().getSpan()), varIndex, null);
                destructuring.add(variableDefine);
            }
            return destructuring;
        }
        // Skip generic type parameters if present (e.g., Function<String, String> varName)
        if (stream.match(false, Less)) {
            int depth = 1;
            stream.consume(); // consume <
            while (stream.hasMore() && depth > 0) {
                Token t = stream.consume();
                if (t.getType() == Less) depth++;
                else if (t.getType() == Greater || t.getType() == SqlNotEqual) depth--;
            }
        }
        Token token = stream.expect(Identifier);
        boolean isConst = false;
        checkKeyword(token.getSpan());
        String variableName = token.getSpan().getText();
        if (stream.match(Assignment, true)) {
            VarIndex varIndex = forceAdd(variableName, isConst);
            defines.add(variableName);
            Expression expression = parseExpression();
            VariableDefine variableDefine = new VariableDefine(addSpan(opening, stream.getPrev().getSpan()), varIndex, expression);
            // 如果是逗号，一直初始化

            if (stream.match(Comma, false)){
                List<VariableDefine> destructuringList = new ArrayList<>();
                destructuringList.add(variableDefine);
                while (stream.match(Comma,true)){
                    Token traceToken = stream.expect(Identifier);
                    VarIndex traceVarIndex = forceAdd(traceToken.getSpan().getText());
                    defines.add(traceToken.getSpan().getText());
                    checkKeyword(traceToken.getSpan());
                    if (stream.match(Assignment, true)){
                        destructuringList.add(new VariableDefine(addSpan(traceToken.getSpan(), stream.getPrev().getSpan()), traceVarIndex, parseExpression()));
                    }else {
                        destructuringList.add(new VariableDefine(addSpan(traceToken.getSpan(), stream.getPrev().getSpan()), traceVarIndex, null));
                    }
                }
                return new VariableArrayDefine(addSpan(opening, stream.getPrev().getSpan()), destructuringList);
            }
            return variableDefine;
        } else if (isConst) {
            ParseError.error("const修饰的变量需要给初始值", stream.getPrev().getSpan());
        }
        return new VariableDefine(addSpan(opening, stream.getPrev().getSpan()), forceAdd(variableName), null);
    }

    private void checkKeyword(Span span) {
        if (ParserMetadata.KEYWORDS.contains(span.getText())) {
            ParseError.error("变量名不能定义为关键字", span);
        }
    }

    private WhileStatement parseWhileStatement() {
        Span openingWhile = stream.expect("while").getSpan();
        requiredNew = false;
        Expression condition = parseExpression();
        requiredNew = true;
        push();
        List<Node> trueBlock = parseFunctionBody();
        Span closingEnd = stream.getPrev().getSpan();
        pop();

        return new WhileStatement(addSpan(openingWhile, closingEnd), condition, trueBlock);
    }
    private WhileStatement parseDoWhileStatement() {
        Span openingWhile = stream.expect("do").getSpan();
        push();
        List<Node> trueBlock = parseFunctionBody();
        requiredNew = false;
        stream.expect("while");
        Expression condition = parseExpression();
        requiredNew = true;
        Span closingEnd = stream.getPrev().getSpan();
        pop();
        return new DoWhileStatement(addSpan(openingWhile, closingEnd), condition, trueBlock);

    }

    private ForStatement parseForStatement() {
        Span openingFor = stream.expect("for").getSpan();
        stream.expect(LeftParantheses);
        push();
        int index = stream.makeIndex();
        // 如果有初始化函数
        List<Node> variableDefineList = new ArrayList<>();
        if (!stream.match(Semicolon, false)) {
            if (matchVarDefine()) {
                variableDefineList.add(parseVariableDefine());
            } else if (matchTypeDefine()) {
                stream.resetIndex(index);
                variableDefineList.add(parseVariableDefine());
            }else {
                stream.resetIndex(index);
                do {
                    variableDefineList.add(parseExpression());
                } while (stream.match(true, Comma));
            }
        }
        // 如果内容是分号说明为 for(x;x;x)的循环体
        if (stream.match(Semicolon, true)) {
            Expression booleanCondition = parseExpression();
            stream.expect(Semicolon);
            // 这是后置处理逻辑
            List<Expression> afterHandlerList = new ArrayList<>();
            if (!stream.match(RightParantheses,false)){
                do{
                    afterHandlerList.add(parseExpression());
                } while (stream.match(Comma,true));
            }
            stream.expect(RightParantheses);
            List<Node> body = parseFunctionBody();
            pop();
            return new ForStatement(addSpan(openingFor, stream.getPrev().getSpan()), variableDefineList, booleanCondition, afterHandlerList, body);
        }else if (!variableDefineList.isEmpty() && variableDefineList.get(0) instanceof VariableDefine){
            // 如果是 for in 的场景
            stream.expect(":");
            Expression mapOrArray = parseExpression();
            stream.expect(RightParantheses);
            List<Node> body = parseFunctionBody();
            pop();
            return new ForEachStatement(addSpan(openingFor, stream.getPrev().getSpan()), ((VariableDefine)variableDefineList.get(0)).getVarIndex(), forceAdd(ANONYMOUS_VARIABLE), mapOrArray, body);
        }
        ParseError.error("表达式异常，无法解析当前逻辑：", stream.getPrev().getSpan());
        return null;
    }

    private Span expectClose() {
        if (!stream.hasMore()) {
            ParseError.error("Did not find closing }.", stream.prev().getSpan());
        }
        return stream.expect(RightCurly).getSpan();
    }

    private Node parseIfStatement() {
        Span openingIf = stream.expect("if").getSpan();
        requiredNew = false;
        Expression condition = parseExpression();
        requiredNew = true;
        push();
        List<Node> trueBlock = parseFunctionBody();
        pop();
        List<IfStatement> elseIfs = new ArrayList<>();
        List<Node> falseBlock = new ArrayList<>();
        while (stream.hasMore() && stream.match("else", true)) {
            if (stream.hasMore() && stream.match("if", false)) {
                Span elseIfOpening = stream.expect("if").getSpan();
                Expression elseIfCondition = parseExpression();
                push();
                List<Node> elseIfBlock = parseFunctionBody();
                Span elseIfSpan = addSpan(elseIfOpening, !elseIfBlock.isEmpty() ? elseIfBlock.get(elseIfBlock.size() - 1).getSpan() : elseIfOpening);
                pop();
                elseIfs.add(new IfStatement(elseIfSpan, elseIfCondition, elseIfBlock, new ArrayList<>(), new ArrayList<>()));
            } else {
                push();
                falseBlock.addAll(parseFunctionBody());
                pop();
                break;
            }
        }
        Span closingEnd = stream.getPrev().getSpan();

        return new IfStatement(addSpan(openingIf, closingEnd), condition, trueBlock, elseIfs, falseBlock);
    }
    private Node parseSwitchStatement() {
        Span openingIf = stream.expect("switch").getSpan();
        push();
        // 获取switch表达式
        Expression switchExpression = parseExpression();
        stream.expect(LeftCurly);
        pop();
        List<SwitchStatement> caseList = new ArrayList<>();
        while (stream.hasMore() && stream.match("case", true)) {
            push();
            Expression expression = parseExpression();
            stream.expect(Colon);

            List<Node> caseBody;
            if (stream.match(LeftCurly, false)){
                caseBody = parseFunctionBody();
            }else if (stream.match("case", false)){
                //case穿透
                caseBody = Collections.emptyList();
            }else {
                caseBody = Collections.singletonList(parseExpression());
            }
            // 消耗;符号
            while (stream.match(Semicolon, true)) {
                // 'while' statement has empty body
            }
            Node breakNode = null;
            if (stream.match("break", true)) {
                breakNode = new Break(stream.consume().getSpan());
            }
            pop();
            caseList.add(new SwitchStatement(addSpan(expression.getSpan(), stream.getPrev().getSpan()), expression,  Collections.emptyList(), caseBody,  Collections.emptyList(), breakNode));
        }
        // 处理default
        List<Node> defaultList = null;
        if (stream.match("default", true)) {
            stream.expect(Colon);
            if (stream.match(LeftCurly, false)){
                defaultList = parseFunctionBody();
            }else {
                defaultList = Collections.singletonList(parseExpression());
            }
        }
        stream.expect(RightCurly);
        Span closingEnd = stream.getPrev().getSpan();

        return new SwitchStatement(addSpan(openingIf, closingEnd), switchExpression, caseList, Collections.emptyList(), defaultList, null);
    }
    private Node parseReturn() {
        Span returnSpan = stream.expect("return").getSpan();
        if (stream.match(false, Semicolon, RightCurly)) {
            return new Return(returnSpan, null);
        }
        Expression returnValue = parseExpression();
        return new Return(addSpan(returnSpan, returnValue.getSpan()), returnValue);
    }

    public Expression parseExpression() {
        return parseTernaryOperator();
    }

    public Expression parseExpression(boolean expectRightCurly) {
        return parseTernaryOperator(expectRightCurly);
    }

    private Expression parseTernaryOperator(boolean expectRightCurly) {
        Expression condition = parseBinaryOperator(0, expectRightCurly);
        if (stream.match(QuestionMark, true)) {
            Expression trueExpression = parseTernaryOperator(expectRightCurly);
            stream.expect(Colon);
            Expression falseExpression = parseTernaryOperator(expectRightCurly);
            if (condition instanceof AssigmentOperation) {
                AssigmentOperation operation = (AssigmentOperation) condition;
                operation.setRightOperand(new TernaryOperation(operation.getRightOperand(), trueExpression, falseExpression));
                return operation;
            }
            return new TernaryOperation(condition, trueExpression, falseExpression);
        } else {
            return condition;
        }
    }

    private Expression parseTernaryOperator() {
        return parseTernaryOperator(false);
    }

    private Expression parseBinaryOperator(TokenType[][] precedence, int level, boolean expectRightCurly) {
        int nextLevel = level + 1;
        Expression left = nextLevel == precedence.length ? parseUnaryOperator(expectRightCurly) : parseBinaryOperator(nextLevel, expectRightCurly);

        TokenType[] operators = precedence[level];
        while (stream.hasMore() && stream.match(false, operators)) {
            Token operator = stream.consume();
            Expression right = nextLevel == precedence.length ? parseUnaryOperator(expectRightCurly) : parseBinaryOperator(nextLevel, expectRightCurly);
            left = BinaryOperation.create(left, operator, right);
        }
        addSpan(left.getSpan());
        return left;
    }

    private Expression parseBinaryOperator(int level, boolean expectRightCurly) {
        return parseBinaryOperator(ParserMetadata.BINARY_OPERATOR_PRECEDENCE, level, expectRightCurly);
    }


    private Expression parseUnaryOperator(boolean expectRightCurly) {
		if (stream.match("await", false)) {
			Span opening = stream.expect("await").getSpan();
			Expression expression = parseUnaryOperator(expectRightCurly);
			return new AwaitExpression(addSpan(opening, expression.getSpan()), expression);
		}
		if (stream.match("async", false)) {
			return parseAsync();
		}
        if (stream.match(false, ParserMetadata.UNARY_OPERATORS)) {
            Token operator = stream.consume();
            Expression operand = parseUnaryOperator(expectRightCurly);
            if (operator.getType() == TokenType.Minus && operand instanceof NumberLiteral) {
                ((NumberLiteral) operand).useNeg();
                return operand;
            }
            return new UnaryOperation(operator, operand);
        } else {
            if (stream.match(LeftParantheses, false)) {    //(
                Span openSpan = stream.expect(LeftParantheses).getSpan();
                int index = stream.makeIndex();
                List<VarIndex> parameters = new ArrayList<>();
                push();
                try {
                    while (stream.match(Identifier, false)) {
                        Token identifier = stream.expect(Identifier);
                        checkKeyword(identifier.getSpan());
                        if (requiredNew) {
                            parameters.add(forceAdd(identifier.getSpan().getText()));
                        } else {
                            parameters.add(add(identifier.getSpan().getText()));
                        }
                        if (stream.match(Comma, true)) { //,
                            continue;
                        }
                        if (stream.match(RightParantheses, true)) {  //)
                            if (stream.match(Lambda, true)) {   // -> or =>
                                return parseLambdaBody(openSpan, parameters);
                            }
                            break;
                        }
                    }
                    if (stream.match(RightParantheses, true) && stream.match(Lambda, true)) {
                        return parseLambdaBody(openSpan, parameters);
                    }
                } finally {
                    pop();
                }
                stream.resetIndex(index);
                Expression expression = parseExpression();
                stream.expect(RightParantheses);
                Expression result = parseAccessOrCall(expression);
                // 检测强转语法：(TypeName)expr，如 (int)x、(double)x
                if (result instanceof VariableAccess && ParserMetadata.CAST_TYPES.contains(result.getSpan().getText()) && isCastStart()) {
                    Expression castTarget = parseUnaryOperator(expectRightCurly);
                    return new CastExpression(addSpan(result.getSpan(), castTarget.getSpan()), result.getSpan().getText(), castTarget);
                }
                return result;
            } else {
                Expression expression = parseAccessOrCallOrLiteral(expectRightCurly);
                if (expression instanceof VariableSetter) {
                    if (stream.match(false, PlusPlus, MinusMinus)) {
                        return new UnaryOperation(stream.consume(), expression, true);
                    }
                }

                return expression;
            }
        }
    }

	private Expression parseAsync() {
		Span opening = stream.expect("async").getSpan();
		boolean previousRequiredNew = requiredNew;
		requiredNew = false;
		Expression expression;
		try {
			expression = parseExpression();
		} finally {
			requiredNew = previousRequiredNew;
		}
		if (expression instanceof MethodCall
				|| expression instanceof FunctionCall
				|| expression instanceof LambdaFunction) {
			return new AsyncCall(addSpan(opening, expression.getSpan()), expression);
		}
		ParseError.error("Expected MethodCall or FunctionCall or LambdaFunction", expression.getSpan());
		return null;
	}

    private Expression parseLambdaBody(Span openSpan, List<VarIndex> parameters) {
        defines.clear();
        int index = stream.makeIndex();
        List<Node> childNodes = new ArrayList<>();
        try {
            Expression expression = parseExpression();
            childNodes.add(new Return(new Span("return", 0, 6), expression));
            return new LambdaFunction(addSpan(openSpan, expression.getSpan()), parameters, childNodes);
        } catch (Exception e) {
            stream.resetIndex(index);
            if (stream.match(LeftCurly, true)) {
                while (stream.hasMore() && !stream.match(false, RightCurly)) {
                    Node node = parseStatement(true);
                    validateNode(node);
                    childNodes.add(node);
                }
                Span closeSpan = expectClose();
                return new LambdaFunction(addSpan(openSpan, closeSpan), parameters, childNodes);
            } else {
                Node node = parseStatement();
                childNodes.add(new Return(addSpan("return", 0, 6), node));
                return new LambdaFunction(addSpan(openSpan, node.getSpan()), parameters, childNodes);
            }
        } finally {
            defines.clear();
        }
    }


    private Expression parseAccessOrCallOrLiteral(boolean expectRightCurly) {
        Expression expression = null;
        if (expectRightCurly && stream.match(RightCurly, false)) {
            return null;
        } else if (stream.match(Identifier, false)) {
            expression = parseAccessOrCall(TokenType.Identifier, false);
        } else if (stream.match(LeftCurly, false)) {
            expression = parseMapLiteral();
        } else if (stream.match(LeftBracket, false)) {
            expression = parseListLiteral();
        } else if (stream.match(StringLiteral, false)) {
            expression = createStringLiteral(stream.expect(StringLiteral));
        } else if (stream.match(BooleanLiteral, false)) {
            expression = new BooleanLiteral(stream.expect(BooleanLiteral).getSpan());
        } else if (stream.match(DoubleLiteral, false)) {
            expression = new DoubleLiteral(stream.expect(DoubleLiteral).getSpan());
        } else if (stream.match(FloatLiteral, false)) {
            expression = new FloatLiteral(stream.expect(FloatLiteral).getSpan());
        } else if (stream.match(ByteLiteral, false)) {
            Token token = stream.expect(ByteLiteral);
            expression = token.getValue() != null ? new ByteLiteral(token.getSpan(), token.getValue()) : new ByteLiteral(token.getSpan());
        } else if (stream.match(ShortLiteral, false)) {
            expression = new ShortLiteral(stream.expect(ShortLiteral).getSpan());
        } else if (stream.match(IntegerLiteral, false)) {
            Token token = stream.expect(IntegerLiteral);
            expression = token.getValue() != null ? new IntegerLiteral(token.getSpan(), token.getValue()) : new IntegerLiteral(token.getSpan());
        } else if (stream.match(LongLiteral, false)) {
            Token token = stream.expect(LongLiteral);
            expression = token.getValue() != null ? new LongLiteral(token.getSpan(), token.getValue()) : new LongLiteral(token.getSpan());
        } else if (stream.match(DecimalLiteral, false)) {
            expression = new BigDecimalLiteral(stream.expect(DecimalLiteral).getSpan());
        } else if (stream.match(RegexpLiteral, false)) {
            Token token = stream.expect(RegexpLiteral);
            expression = new RegexpLiteral(token.getSpan(), token);
        } else if (stream.match(NullLiteral, false)) {
            expression = new NullLiteral(stream.expect(NullLiteral).getSpan());
        }
        if (expression == null) {
            ParseError.error("Expected a variable, field, map, array, function or method call, or literal.", stream);
        }
        return parseAccessOrCall(expression);
    }

    private StringLiteral createStringLiteral(Token token) {
        if (token.getTokenStream() == null) {
            return new StringLiteral(token);
        }
        TokenStream tempStream = this.stream;
        this.stream = token.getTokenStream();
        List<Expression> expressionList = new ArrayList<>();
        while (this.stream.hasMore()) {
            expressionList.add(parseExpression());
        }
        this.stream = tempStream;
        return new StringLiteral(token, expressionList);
    }


    private Expression parseMapLiteral() {
        Span openCurly = stream.expect(LeftCurly).getSpan();

        List<Expression> keys = new ArrayList<>();
        List<Expression> values = new ArrayList<>();
        while (stream.hasMore() && !stream.match(RightCurly, false)) {
            Expression key;
            if (stream.hasPrev()) {
                Token prev = stream.getPrev();
            }
            boolean isStringKey;
            if (isStringKey = stream.match(StringLiteral, false)) {    // "key" 'key' """key"""
                key = createStringLiteral(stream.expect(StringLiteral));
            } else if (stream.match(LeftBracket, true)) {    // [key]
                key = parseExpression();
                stream.expect(RightBracket);
            } else {    // key
                key = createStringLiteral(stream.expect(Identifier));
            }
            keys.add(key);
            if (stream.match(false, Comma, RightCurly)) {
                stream.match(Comma, true);
                if (!isStringKey && !(key instanceof VariableAccess)) {
                    values.add(new VariableAccess(key.getSpan(), add(key.getSpan().getText())));
                } else {
                    values.add(key);
                }
            } else {
                stream.expect(Colon);
                values.add(parseExpression());
                if (!stream.match(RightCurly, false)) {
                    stream.expect(Comma);
                }
            }
        }
        Span closeCurly = stream.expect(RightCurly).getSpan();
        return new MapLiteral(addSpan(openCurly, closeCurly), keys, values);
    }

    private Expression parseListLiteral() {
        Span openBracket = stream.expect(LeftBracket).getSpan();

        List<Expression> values = new ArrayList<>();
        while (stream.hasMore() && !stream.match(RightBracket, false)) {
            values.add(parseExpression());
            if (!stream.match(RightBracket, false)) {
                stream.expect(Comma);
            }
        }

        Span closeBracket = stream.expect(RightBracket).getSpan();
        return new ListLiteral(addSpan(openBracket, closeBracket), values);
    }


    private Expression parseAccessOrCall(TokenType tokenType, boolean isNew) {
        Token token = stream.expect(tokenType);
        Span identifier = token.getSpan();
        if (tokenType == Identifier && "new".equals(identifier.getText())) {
            return parseNewExpression(identifier);
        }
        if (tokenType == Identifier && stream.match(Lambda, true)) {
            push();
            String name = identifier.getText();
            Expression expression = parseLambdaBody(identifier, Collections.singletonList(requiredNew ? forceAdd(name) : add(name)));
            pop();
            return expression;
        }
        Expression result = tokenType == StringLiteral ? createStringLiteral(token) : new VariableAccess(identifier, add(identifier.getText()));
        return parseAccessOrCall(result, isNew);
    }

    private Expression parseAccessOrCall(Expression target) {
        return parseAccessOrCall(target, false);
    }

    private Expression parseAccessOrCall(Expression target, boolean isNew) {
        // 泛型擦除
        stream.match(true, SqlNotEqual);
        if (isNew && stream.hasMore() && stream.match(true, Less)){
            int countLess = 1;
            while (stream.hasMore()){
                if (stream.match(true, SqlNotEqual)){
                    // <> already consumed
                } else if (stream.match(true, Less)){
                    countLess++;
                } else if (stream.match(true, Greater)){
                    countLess--;
                    if (countLess == 0) {
                        break;
                    }
                } else {
                    // 无条件遍历未识别的泛型参数 token
                    stream.consume();
                }
            }
        }
        while (stream.hasMore() && stream.match(false, LeftParantheses, LeftBracket, Period, QuestionPeriod, DoubleColon)) {
            if (stream.match(LeftParantheses, false)) {
                List<Expression> arguments = parseArguments();
                Span closingSpan = stream.expect(RightParantheses).getSpan();
                if (target instanceof VariableAccess || target instanceof MapOrArrayAccess) {
                    target = new FunctionCall(addSpan(target.getSpan(), closingSpan), target, arguments);
                } else if (target instanceof MemberAccess) {
                    target = new MethodCall(addSpan(target.getSpan(), closingSpan), (MemberAccess) target, arguments);
                } else {
                    ParseError.error("Expected a variable, field or method.", stream);
                }
                if (isNew) {
                    break;
                }
            }

            // map or array access
            else if (stream.match(LeftBracket, true)) {
                Expression keyOrIndex = parseExpression();
                Span closingSpan = stream.expect(RightBracket).getSpan();
                target = new MapOrArrayAccess(addSpan(target.getSpan(), closingSpan), target, keyOrIndex);
            }

            // field or method access
            else if (stream.match(false, Period, QuestionPeriod)) {
                boolean optional = stream.consume().getType() == QuestionPeriod;
                target = new MemberAccess(target, optional, stream.expect(Identifier, SqlAnd, SqlOr).getSpan(), false);
            }

            // bound method reference: target::method
            else if (stream.match(DoubleColon, true)) {
                Span methodName = stream.expect(Identifier, SqlAnd, SqlOr).getSpan();
                target = new MethodReference(new Span(target.getSpan(), methodName), target, methodName);
            }
        }
        return target;
    }

    /**
     * Does not consume the closing parentheses.
     **/
    private List<Expression> parseArguments() {
        stream.expect(LeftParantheses);
        List<Expression> arguments = new ArrayList<Expression>();
        while (stream.hasMore() && !stream.match(RightParantheses, false)) {
            arguments.add(parseExpression());
            if (!stream.match(RightParantheses, false)) {
                stream.expect(Comma);
            }
        }
        return arguments;
    }
}
