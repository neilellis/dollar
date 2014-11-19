/*
 * Copyright (c) 2014 Neil Ellis
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.neilellis.dollar.script;

import com.google.common.collect.Range;
import com.google.common.io.ByteStreams;
import me.neilellis.dollar.*;
import me.neilellis.dollar.script.java.JavaScriptingSupport;
import me.neilellis.dollar.script.operators.*;
import org.codehaus.jparsec.*;
import org.codehaus.jparsec.error.ParserException;
import org.codehaus.jparsec.functors.Map;
import org.pegdown.Extensions;
import org.pegdown.PegDownProcessor;
import org.pegdown.ast.RootNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static me.neilellis.dollar.DollarStatic.*;
import static me.neilellis.dollar.script.DollarLexer.*;
import static me.neilellis.dollar.script.DollarScriptSupport.getVariable;
import static me.neilellis.dollar.script.DollarScriptSupport.wrapReactiveBinary;
import static me.neilellis.dollar.script.OperatorPriority.*;
import static me.neilellis.dollar.types.DollarFactory.fromLambda;
import static me.neilellis.dollar.types.DollarFactory.fromValue;
import static org.codehaus.jparsec.Parsers.*;

/**
 * @author <a href="http://uk.linkedin.com/in/neilellis">Neil Ellis</a>
 */
public class DollarParser {

    //Lexer

    public static final String NAMED_PARAMETER_META_ATTR = "__named_parameter";


    private final ClassLoader classLoader;
    private ThreadLocal<List<ScriptScope>> scopes = new ThreadLocal<List<ScriptScope>>() {
        @Override
        protected List<ScriptScope> initialValue() {
            ArrayList<ScriptScope> list = new ArrayList<>();
            list.add(new ScriptScope("ThreadTopLevel"));
            return list;
        }
    };
    private Parser<?> topLevelParser;
    private ParserErrorHandler errorHandler = new ParserErrorHandler();

    public DollarParser() {
        classLoader = DollarParser.class.getClassLoader();
    }

    public DollarParser(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    static Parser<var> dollarIdentifier(ScriptScope scope) {
        return term("$").next(Terminals.Identifier.PARSER).map(i -> fromLambda(j -> getVariable(scope, i, false)));
    }

    public void addScope(ScriptScope scope) {
        scopes.get().add(scope);
    }

    public ScriptScope currentScope() {
        return scopes.get().get(scopes.get().size() - 1);
    }

    public ScriptScope endScope() {
        return scopes.get().remove(scopes.get().size() - 1);
    }

//    private Parser<var> arrayElementExpression(Parser<var> expression1, Parser<var> expression2, ScriptScope scope) {
//        return expression1.infixl(term("[").next(expression2).followedBy(term("]")));
//    }

    public ParserErrorHandler getErrorHandler() {
        return errorHandler;
    }

    public <T> T inScope(ScriptScope currentScope, Function<ScriptScope, T> r) {
        ScriptScope newScope = new ScriptScope(currentScope, currentScope.getSource());
        addScope(newScope);
        try {
            return r.apply(newScope);
        } finally {
            ScriptScope poppedScope = endScope();
            if (poppedScope != newScope) {
                throw new IllegalStateException("Popped wrong scope");
            }
        }
    }

    public var parse(File file) throws IOException {
        if (file.getName().endsWith(".md") || file.getName().endsWith(".markdown")) {
            return parseMarkdown(file);
        } else {
            String source = new String(Files.readAllBytes(file.toPath()));
            return parse(new ScriptScope(this, source), source);
        }

    }

    public var parse(ScriptScope scope, File file) throws IOException {
        String source = new String(Files.readAllBytes(file.toPath()));
        return parse(new ScriptScope(scope, source), source);
    }

    public var parse(ScriptScope scope, InputStream in) throws IOException {
        String source = new String(ByteStreams.toByteArray(in));
        return parse(new ScriptScope(scope, source), source);
    }

    public var parse(InputStream in) throws IOException {
        String source = new String(ByteStreams.toByteArray(in));
        return parse(new ScriptScope(this, source), source);
    }

    public var parse(String s) throws IOException {
        return parse(new ScriptScope(this, s), s);
    }

    public var parse(ScriptScope scope, String source) throws IOException {
        addScope(new ScriptScope(scope, source));
        try {
            DollarStatic.context().setClassLoader(classLoader);
            scope.setDollarParser(this);
            Parser<?> parser = buildParser(new ScriptScope(scope, source));
            List<var> parse = (List<var>) parser.from(TOKENIZER, DollarLexer.IGNORED).parse(source);
            return $(parse.stream().map(i -> i.$()).collect(Collectors.toList()));
        } catch (ParserException e) {
            //todo: proper error handling
            if (e.getErrorDetails() != null) {
                final int index = e.getErrorDetails().getIndex();
                final int endIndex = (index < source.length() - 20) ? index + 20 : source.length() - 1;
                if (source.length() > 0 && index > 0 && index < endIndex) {
                    System.err.println(source.substring(index, endIndex));
                }
            }
            scope.handleError(e);
            throw e;
        } finally {
            endScope();
        }
    }

    public var parseMarkdown(File file) throws IOException {
        PegDownProcessor pegDownProcessor = new PegDownProcessor(Extensions.FENCED_CODE_BLOCKS);
        RootNode root =
                pegDownProcessor.parseMarkdown(com.google.common.io.Files.toString(file, Charset.forName("utf-8"))
                                                                         .toCharArray());
        root.accept(new CodeExtractionVisitor());
        return $();
    }

    public var parseMarkdown(String source) throws IOException {
        PegDownProcessor processor = new PegDownProcessor(Extensions.FENCED_CODE_BLOCKS);
        RootNode rootNode = processor.parseMarkdown(source.toCharArray());
        rootNode.accept(new CodeExtractionVisitor());
        return $();
    }

    public List<ScriptScope> scopes() {
        return scopes.get();
    }

    final Parser<var> java(ScriptScope scope) {
        return token(new TokenMap<String>() {
            @Override
            public String map(Token token) {
                final Object val = token.value();
                if (val instanceof Tokens.Fragment) {
                    Tokens.Fragment c = (Tokens.Fragment) val;
                    if (!c.tag().equals("java")) {
                        return null;
                    }
                    return c.text();
                } else { return null; }
            }

            @Override
            public String toString() {
                return "java";
            }
        }).map(new Map<String, var>() {
            @Override
            public var map(String s) {
                return JavaScriptingSupport.compile($void(), s, scope);
            }
        });
    }

    <T> Parser<T> op(T value, String name) {
        return op(value, name, null);
    }

    <T> Parser<T> op(T value, String name, String keyword) {
        Parser<?> parser;
        if (keyword == null) {
            parser = term(name);
        } else {
            parser = term(name, keyword);

        }
        return parser.token().map(new Map<Token, T>() {
            @Override
            public T map(Token token) {
                if (value instanceof Operator) {
                    ((Operator) value).setSource(() -> {
                        int index = token.index();
                        int length = token.length();
                        String theSource = currentScope().getSource();
                        int end = theSource.indexOf('\n', index + length);
                        int start = index > 10 ? index - 10 : 0;
                        String
                                highlightedSource =
                                "... " +
                                theSource.substring(start, index) +
                                " \u261E " +
                                theSource.substring(index, index + length) +
                                " \u261C " +
                                theSource.substring(index + length, end) +
                                " ...";
                        return highlightedSource.replaceAll("\n", "\\\\n");
                    });
                }
                return value;
            }
        });

    }

    private Parser<var> whenStatement(Parser<var> expression, ScriptScope scope) {
        Parser<Object[]> sequence = keywordThenNL("when").next(array(expression, expression));
        return sequence.map(new WhenOperator());
    }

    private Parser<var> everyStatement(Parser<var> expression, ScriptScope scope) {
        Parser<Object[]>
                sequence =
                keywordThenNL("every")
                        .next(array(expression,
                                    keyword("secs", "sec", "minute", "minutes", "hr", "hrs", "milli", "millis"),
                                    keyword("until").next(expression).optional(),
                                    keyword("unless").next(expression).optional(),
                                    expression));
        return sequence.map(new EveryOperator(this, scope));
    }

    private Parser<var> list(Parser<var> expression, ScriptScope scope) {
        return termThenNl("[")
                .next(expression.sepBy(COMMA_OR_NEWLINE_TERMINATOR))
                .followedBy(nlThenTerm("]")).map(i -> inScope(scope, newScope -> DollarStatic.$(i)));
    }

    private Parser<var> map(Parser<var> expression, ScriptScope scope) {
        Parser<List<var>>
                sequence =
                termThenNl("{").next(expression.sepBy1(COMMA_TERMINATOR))
                               .followedBy(nlThenTerm("}"));
        return sequence.map(new MapOperator(this, scope));
    }

    private Parser<var> block(Parser<var> parentParser, ScriptScope scope) {
        Parser.Reference<var> ref = Parser.newReference();

        //Now we do the complex part, the following will only return the last value in the
        //block when the block is evaluated, but it will trigger execution of the rest.
        //This gives it functionality like a conventional function in imperative languages
        Parser<var>
                or =
                (
                        or(parentParser, ref.lazy().between(termThenNl("{"), nlThenTerm("}"))).followedBy(
                                SEMICOLON_TERMINATOR)
                ).many1()

                 .map(new BlockOperator(
                         this,
                         scope));
        ref.set(or);
        return or;
    }

    private Parser<List<var>> script(ScriptScope scope) {
        return inScope(scope, newScope -> {
            Parser.Reference<var> ref = Parser.newReference();
            Parser<var> block = block(ref.lazy(), newScope).between(termThenNl("{"), nlThenTerm("}"));
            Parser<var> expression = expression(newScope);
            Parser<List<var>> parser = (TERMINATOR_SYMBOL.optional()).next(or(expression, block).followedBy(
                    TERMINATOR_SYMBOL).map(DollarStatic::fix).many1());
            ref.set(parser.map(DollarStatic::$));
            return parser;
        });
    }

    private Parser<var> expression(ScriptScope scope) {

        Parser.Reference<var> ref = Parser.newReference();
        Parser<var> main = ref.lazy()
                              .between(term("("), term(")"))
                              .or(or(list(ref.lazy(), scope), map(ref.lazy(), scope),
                                     whenStatement(ref.lazy(), scope), everyStatement(ref.lazy(), scope),
                                     java(scope), URL, DECIMAL_LITERAL, INTEGER_LITERAL, STRING_LITERAL,
                                     dollarIdentifier(scope), IDENTIFIER_KEYWORD, functionCall(ref, scope),
                                     identifier().map(new Map<var, var>() {
                                         @Override public var map(var var) {
                                             return fromLambda(i -> getVariable(scope, var.S(), false));
                                         }
                                     })))
                              .or(block(ref.lazy(), scope).between(termThenNl("{"), nlThenTerm("}")));

        Parser<var> parser = new OperatorTable<var>()
                .infixl(op(new BinaryOp((lhs, rhs) -> $(!lhs.equals(rhs)), scope), "!="), EQUIVALENCE_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> $(lhs.equals(rhs)), scope), "=="), EQUIVALENCE_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> $(lhs.isTrue() && rhs.isTrue()), scope), "&&", "and"),
                        LOGICAL_AND_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> $(lhs.isTrue() || rhs.isTrue()), scope), "||", "or"),
                        LOGICAL_OR_PRIORITY)
                .postfix(pipeOperator(ref, scope), PIPE_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> fromValue(Range.closed(lhs, rhs)), scope), ".."), RANGE_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> $(lhs.compareTo(rhs) < 0), scope), "<"), COMPARISON_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> $(lhs.compareTo(rhs) > 0), scope), ">"), EQUIVALENCE_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> $(lhs.compareTo(rhs) <= 0), scope), "<="), EQUIVALENCE_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> $(lhs.compareTo(rhs) >= 0), scope), ">="), EQUIVALENCE_PRIORITY)
                .infixl(op(new BinaryOp(NumericAware::$multiply, scope), "*"), MULTIPLY_DIVIDE_PRIORITY)
                .infixl(op(new BinaryOp(NumericAware::$divide, scope), "/"), MULTIPLY_DIVIDE_PRIORITY)
                .infixl(op(new BinaryOp(NumericAware::$modulus, scope), "%"), MULTIPLY_DIVIDE_PRIORITY)
                .infixl(op(new BinaryOp(var::$plus, scope), "+"), PLUS_MINUS_PRIORITY)
                .infixl(op(new BinaryOp(var::$minus, scope), "-"), PLUS_MINUS_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> $(lhs.$S(), rhs), scope), ":"), 30)
                .infixl(op(new BinaryOp((lhs, rhs) -> lhs.isTrue() ? rhs : lhs, scope), "?"), CONTROL_FLOW_PRIORITY)
                .prefix(op(new UnaryOp(false, i -> {
                    i.out();
                    return $void();
                }), "@@", "print"), LINE_PREFIX_PRIORITY)
                .prefix(op(new UnaryOp(false, i -> {
                    i.debug();
                    return $void();
                }), "!!", "debug"), LINE_PREFIX_PRIORITY)
                .prefix(op(new UnaryOp(false, i -> {
                    i.err();
                    return $void();
                }), "??", "err"), LINE_PREFIX_PRIORITY)
                .prefix(op(
                        new UnaryOp(false, v -> { if (v.isTrue()) { return v; } else { throw new AssertionError(); } }),
                        ".:", "assert"), LINE_PREFIX_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> {
                    if (lhs.equals(rhs)) {
                        return lhs;
                    } else {
                        throw new AssertionError(lhs.$S() + " != " + rhs.$S());
                    }
                }, scope), "<=>"), LINE_PREFIX_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> rhs.$dispatch(lhs), scope), "?>"), OUTPUT_PRIORITY)
                .prefix(sendOperator(ref, scope), OUTPUT_PRIORITY)
                .prefix(receiveOperator(ref, scope), OUTPUT_PRIORITY)
                .prefix(op(new UnaryOp(scope, i -> $(i.isTruthy())), "~", "truthy"), UNARY_PRIORITY)
                .prefix(op(new UnaryOp(scope, var::$size), "#", "size"), UNARY_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> rhs.$give(lhs), scope), "&>"), OUTPUT_PRIORITY)
                .prefix(op(new UnaryOp(scope, URIAware::$poll), "<&"), OUTPUT_PRIORITY)
                .prefix(op(new UnaryOp(scope, URIAware::$drain), "<--", "drain"), OUTPUT_PRIORITY)
                .prefix(op(new UnaryOp(scope, URIAware::$all), "<@", "all"), OUTPUT_PRIORITY)
                .infixl(op(new BinaryOp(
                        (lhs, rhs) -> { if (lhs.isBoolean() && lhs.isFalse()) { return rhs; } else { return lhs; } },
                        scope), "-:", "else"), IF_PRIORITY)
                .prefix(ifOperator(ref, scope), IF_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> rhs.$contains(lhs), scope), "€", "in"), IN_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> rhs.$push(lhs), scope), "+>"), OUTPUT_PRIORITY)
                .prefix(op(new UnaryOp(scope, URIAware::$pop), "<+"), OUTPUT_PRIORITY)
                .prefix(op(new UnaryOp(scope, scope::addErrorHandler), "!?#*!", "error"), LINE_PREFIX_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> rhs.$publish(lhs), scope), "*>", "publish"), OUTPUT_PRIORITY)
                .infixl(op(new SubscribeOperator(scope), "<*", "subscribe"), OUTPUT_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> rhs.$send(lhs), scope), ">>"), OUTPUT_PRIORITY)
                .postfix(isOperator(ref, scope), EQUIVALENCE_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> {
                            return lhs.$each(i -> inScope(scope, newScope -> {
                                newScope.setParameter("1", i);
                                return fix(rhs);
                            }));
                        }, scope), "*|*", "each"),
                        MULTIPLY_DIVIDE_PRIORITY)
                .infixl(op(new BinaryOp((lhs, rhs) -> {
                    return lhs.toList().stream().reduce((x, y) -> {
                        return inScope(scope, newScope -> {
                            newScope.setParameter("1", x);
                            newScope.setParameter("2", y);
                            return fix(rhs);
                        });
                    }).get();
                }, scope), "*|", "reduce"), MULTIPLY_DIVIDE_PRIORITY)
                .prefix(op(new SimpleReceiveOperator(scope), "<<"), OUTPUT_PRIORITY)
                .infixl(op(new ListenOperator(scope), "=>", "causes"), CONTROL_FLOW_PRIORITY)
                .infixl(op(new BinaryOp(ControlFlowAware::$choose, scope), "?*", "choose"), CONTROL_FLOW_PRIORITY)
                .infixl(op(new BinaryOp(var::$default, scope), "?:", "default"), CONTROL_FLOW_PRIORITY)
                .postfix(memberOperator(ref, scope), MEMBER_PRIORITY)
                .prefix(op(new UnaryOp(scope, v -> $(!v.isTrue())), "!", "not"), UNARY_PRIORITY)
                .postfix(op(new UnaryOp(scope, var::$dec), "--"), INC_DEC_PRIORITY)
                .postfix(op(new UnaryOp(scope, var::$inc), "++"), INC_DEC_PRIORITY)
                .prefix(op(new UnaryOp(scope, var::$negate), "-"), UNARY_PRIORITY)
                .prefix(forOperator(scope, ref), UNARY_PRIORITY)
                .prefix(importOperator(scope), UNARY_PRIORITY)
                .postfix(subscriptOperator(ref, scope), MEMBER_PRIORITY)
                .postfix(parameterOperator(ref, scope), MEMBER_PRIORITY)
                .prefix(op(new UnaryOp(true, v -> $((Object) v.$())), "&", "fix"), 1000)
                .prefix(variableUsageOperator(ref, scope), 1000)
                .postfix(castOperator(ref, scope), UNARY_PRIORITY)
                .prefix(op(new SleepOperator(scope), "-_-", "sleep"), ASSIGNMENT_PRIORITY)
                .prefix(assignmentOperator(scope, ref), ASSIGNMENT_PRIORITY)
                .prefix(declarationOperator(scope, ref), ASSIGNMENT_PRIORITY)
                .build(main);
        ref.set(parser);
        scope.setParser(parser);
        return parser;
    }

    private Parser<Map<? super var, ? extends var>> memberOperator(Parser.Reference<var> ref, ScriptScope scope) {
        return term(".").followedBy(term(".").not())
                        .next(ref.lazy().between(term("("), term(")")).or(IDENTIFIER))
                        .map(rhs -> lhs -> wrapReactiveBinary(scope, "", lhs, rhs, () -> lhs.$get(rhs.$S())));
    }

    private Parser<Map<var, var>> ifOperator(Parser.Reference<var> ref, ScriptScope scope) {
        return keywordThenNL("if").next(ref.lazy()).map(new IfOperator(scope));
    }

    private Parser<Map<? super var, ? extends var>> assignmentOperator(final ScriptScope scope,
                                                                       Parser.Reference<var> ref) {
        return array(keyword("const").optional(),
                     IDENTIFIER.between(term("<"), term(">")).optional(),
                     ref.lazy().between(term("("), term(")")).optional(),
                     term("$").next(ref.lazy().between(term("("), term(")")))
                              .or(IDENTIFIER),
                     term("=")).map(new AssignmentOperator(scope));
    }

    private Parser<Map<? super var, ? extends var>> declarationOperator(final ScriptScope scope,
                                                                        Parser.Reference<var> ref) {

        return array(IDENTIFIER.between(term("<"), term(">")).optional(),
                     ref.lazy().between(term("("), term(")")).optional(),
                     term("$").next(ref.lazy().between(term("("), term(")")))
                              .or(IDENTIFIER),
                     term(":=")).map(new DeclarationOperator(scope));
    }

    private Parser<Map<? super var, ? extends var>> forOperator(final ScriptScope scope, Parser.Reference<var> ref) {
        return array(keyword("for"), IDENTIFIER, keyword("in"), ref.lazy()).map(new ForOperator(this, scope));
    }

    private Parser<Map<? super var, ? extends var>> subscriptOperator(Parser.Reference<var> ref, ScriptScope scope) {
        return term("[").next(array(ref.lazy().followedBy(term("]")), term("=").next(ref.lazy()).optional()))
                        .map(new SubscriptOperator(scope));
    }

    private Parser<Map<? super var, ? extends var>> castOperator(Parser.Reference<var> ref, ScriptScope scope) {
        return keyword("as").next(IDENTIFIER).map(new CastOperator(scope));
    }

    private Parser<Map<? super var, ? extends var>> pipeOperator(Parser.Reference<var> ref, ScriptScope scope) {
        return term("|").next(IDENTIFIER.or(ref.lazy().between(term("("), term(")"))))
                        .map(new PipeOperator(this, scope));
    }

    private Parser<Map<? super var, ? extends var>> variableUsageOperator(Parser.Reference<var> ref,
                                                                          ScriptScope scope) {
        return or(term("$").followedBy(term("(").peek())
                           .map(new VariableUsageOperator(scope)),
                  term("$").followedBy(INTEGER_LITERAL.peek()).map(
                          lhs -> rhs -> fromLambda(i -> getVariable(scope, rhs.toString(), true))));
    }

    private Parser<Map<? super var, ? extends var>> receiveOperator(Parser.Reference<var> ref, ScriptScope scope) {
        return array(keyword("receive"), keyword("async").optional(), keyword("stateless").optional())
                .followedBy(keyword("from").optional())
                .map(new ReceiveOperator());
    }

    private Parser<Map<? super var, ? extends var>> sendOperator(Parser.Reference<var> ref, ScriptScope scope) {
        return array(keyword("send"), ref.lazy(), keyword("async").optional(), keyword("stateless").optional())
                .followedBy(keyword("to").optional())
                .map(new SendOperator(scope));
    }

    private Parser<Map<? super var, ? extends var>> isOperator(Parser.Reference<var> ref, ScriptScope scope) {
        return keyword("is").next(IDENTIFIER.sepBy(term(","))).map(new IsOperator(scope));
    }

    private Parser<UnaryOp> importOperator(ScriptScope scope) {
        return op(new UnaryOp(scope, i -> {
            String importName = i.$S();
            String[] parts = importName.split(":", 2);
            if (parts.length < 2) {
                throw new IllegalArgumentException("Import " + importName + " needs to have a scheme");
            }
            try {
                return fromLambda(in -> PipeableResolver.resolveModule(parts[0])
                                                        .resolve(parts[1],
                                                                 scope.getDollarParser().currentScope())
                                                        .pipe(in));
            } catch (Exception e) {
                return DollarStatic.logAndRethrow(e);
            }

        }), "\u2357", "import");
    }

    private Parser<var> functionCall(Parser.Reference<var> ref, ScriptScope scope) {
        return array(IDENTIFIER, parameterOperator(ref, scope)).map(new FunctionCallOperator());
    }

    private Parser<Map<? super var, ? extends var>> parameterOperator(Parser.Reference<var> ref, ScriptScope scope) {
        return term("(").next(array(IDENTIFIER.followedBy(term(":")).optional(), ref.lazy()).map(objects -> {
            //Is it a named parameter
            if (objects[0] != null) {
                //yes so let's add the name as metadata to the value
                var result = (var) objects[1];
                result.setMetaAttribute(NAMED_PARAMETER_META_ATTR, objects[0].toString());
                return result;
            } else {
                //no, just use the value
                return (var) objects[1];
            }
        }).sepBy(COMMA_TERMINATOR)).followedBy(term(")")).map(
                new ParameterOperator(this, scope));
    }

    private Parser<?> buildParser(ScriptScope scope) {
        topLevelParser = script(scope);
        return topLevelParser;
    }
}
