/*
 *    Copyright (c) 2014-2017 Neil Ellis
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package dollar.internal.runtime.script.operators;

import com.sillelien.dollar.api.BooleanAware;
import com.sillelien.dollar.api.Pipeable;
import com.sillelien.dollar.api.Type;
import com.sillelien.dollar.api.TypePrediction;
import com.sillelien.dollar.api.VarInternal;
import com.sillelien.dollar.api.script.SourceSegment;
import com.sillelien.dollar.api.var;
import dollar.internal.runtime.script.DollarScriptSupport;
import dollar.internal.runtime.script.SourceNodeOptions;
import dollar.internal.runtime.script.SourceSegmentValue;
import dollar.internal.runtime.script.api.DollarParser;
import dollar.internal.runtime.script.api.Scope;
import dollar.internal.runtime.script.api.exceptions.DollarScriptException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jparsec.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;

import static com.sillelien.dollar.api.DollarStatic.*;
import static dollar.internal.runtime.script.DollarScriptSupport.*;
import static dollar.internal.runtime.script.SourceNodeOptions.NO_SCOPE;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

public class AssignmentOperator implements Function<Token, Function<? super var, ? extends var>> {
    @NotNull
    private static final Logger log = LoggerFactory.getLogger("AssignmentOperator");
    private static final double MIN_PROBABILITY = 0.5;
    private final boolean pure;
    @NotNull
    private final DollarParser parser;

    public AssignmentOperator(boolean pure, @NotNull DollarParser parser) {
        this.pure = pure;
        this.parser = parser;
    }

    @NotNull
    private var assign(@NotNull var rhs,
                       @NotNull Object[] objects,
                       @Nullable var constraint,
                       boolean constant,
                       boolean isVolatile,
                       @Nullable String constraintSource,
                       @NotNull Scope scope,
                       @NotNull Token token,
                       boolean decleration) {

        final String varName = objects[4].toString();

        Pipeable pipeable = args -> {

            Scope currentScope = currentScope();
            final var useConstraint;
            final String useSource;
            Scope varScope = DollarScriptSupport.getScopeForVar(pure, varName, false, currentScope());
            if ((constraint != null) || (varScope == null)) {
                useConstraint = constraint;
                useSource = constraintSource;
            } else {
                useConstraint = varScope.getConstraint(varName);
                useSource = varScope.getConstraintSource(varName);
            }
            final var rhsFixed = rhs._fix(1, false);

            if (useConstraint != null) {
                inSubScope(true, pure, "assignment-constraint",
                           newScope -> {
                               newScope.setParameter("it", rhsFixed);
                               var value = newScope.get(varName);
                               assert value != null;
                               newScope.setParameter("previous", value);
                               if (useConstraint.isFalse()) {
                                   currentScope.handleError(
                                           new DollarScriptException("Constraint failed for variable " + varName + ""));
                               }
                               return null;
                           });
            }
            if (objects[0] != null) {
                parser.export(varName, rhsFixed);
            }
            setVariable(currentScope, varName, rhsFixed, constant,
                        constraint, useSource, isVolatile, constant, pure,
                        decleration, token, parser);
            return $void();

        };
        //        node.$listen(i -> scope.notify(varName));
        return node("assignment", pure, NO_SCOPE, parser, token,
                    singletonList(constrain(scope, rhs, constraint, constraintSource)), pipeable);
    }

    @Nullable
    public Function<? super var, ? extends var> apply(@NotNull Token token) {
        @Nullable Type type;
        Object[] objects = (Object[]) token.value();
        var constraint = null;
        @Nullable final String constraintSource;
        if (objects[3] instanceof var) {
            SourceSegment sourceSegment = ((VarInternal) objects[3])._source();
            assert sourceSegment != null;
            constraintSource = sourceSegment.getSourceSegment();
        } else {
            constraintSource = null;
        }
        if (objects[2] != null) {
            type = Type.valueOf(objects[2].toString().toUpperCase());
            constraint = node("constraint", pure, SourceNodeOptions.NEW_SCOPE, token, emptyList(), parser, i -> {
                var it = currentScope().getParameter("it");
                assert it != null;
                return $(it.is(type) && ((objects[3] == null) || ((BooleanAware) objects[3]).isTrue()));
            });
        } else {
            type = null;
            if (objects[3] instanceof var) constraint = (var) objects[3];

        }
        boolean constant;
        boolean isVolatile;
        final Object mutability = objects[1];
        boolean decleration = mutability != null;
        constant = (mutability != null) && "const".equals(mutability.toString());
        isVolatile = (mutability != null) && "volatile".equals(mutability.toString());
        if (((var) objects[4]).getMetaAttribute("__builtin") != null) {
            throw new DollarScriptException("The variable '" +
                                                    objects[4] +
                                                    "' cannot be assigned as this name is the name of a builtin function.");
        }
        final String varName = objects[4].toString();

        var finalConstraint = constraint;
        return (Function<var, var>) rhs -> {
            Scope scope = currentScope();
            final TypePrediction prediction = rhs._predictType();
            if ((type != null) && (prediction != null)) {
                final Double probability = prediction.probability(type);
                if ((probability < MIN_PROBABILITY) && !prediction.empty()) {
                    log.warn("Type assertion may fail, expected {} most likely type is {} ({}%) at {}", type,
                             prediction.probableType(),
                             (int) (prediction.probability(prediction.probableType()) * 100),
                             new SourceSegmentValue(currentScope(), token).getSourceMessage()
                    );
                }
            }

            final String op = objects[5].toString();
            if ("?=".equals(op) || "*=".equals(op)) {
                final String useSource;
                var useConstraint;
                if (finalConstraint != null) {
                    useConstraint = finalConstraint;
                    useSource = constraintSource;
                } else {
                    useConstraint = scope.getConstraint(varName);
                    useSource = scope.getConstraintSource(varName);
                }
                List<var> inputs = singletonList(constrain(scope, rhs, finalConstraint, useSource));
                if ("?=".equals(op)) {
                    scope.set(varName, rhs, false, null, useSource, isVolatile, false,
                              pure);
                    log.debug("DYNAMIC: {}", rhs.dynamic());

                    return node("listen-assign", pure, NO_SCOPE, parser, token, inputs,
                                c -> {
                                    Pipeable listener = args -> {
                                        var value = args[0]._fixDeep();
                                        setVariable(scope, varName, value, false, useConstraint, useSource,
                                                    isVolatile, false, pure, decleration, token, parser);
                                        return value;
                                    };
                                    return rhs.$listen(
                                            listener);
                                }
                    );

                } else if ("*=".equals(op)) {
                    scope.set(varName, $void(), false, null, useSource, true, true, pure);
                    return node("subscribe-assign", pure, NO_SCOPE, parser, token, inputs,
                                c -> {
                                    Pipeable subscriber = i -> setVariable(
                                            scope,
                                            varName,
                                            fix(i[0], false),
                                            false,
                                            useConstraint, useSource,
                                            true, false, pure,
                                            decleration, token,
                                            parser);
                                    return $(rhs.$subscribe(subscriber));
                                });
                }
            }
            return assign(rhs, objects, finalConstraint, constant, isVolatile, constraintSource,
                          scope, token, decleration);
        };
    }


}
