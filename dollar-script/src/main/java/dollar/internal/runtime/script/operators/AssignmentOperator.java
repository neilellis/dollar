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

import dollar.api.Pipeable;
import dollar.api.Scope;
import dollar.api.SubType;
import dollar.api.Type;
import dollar.api.VarFlags;
import dollar.api.VarKey;
import dollar.api.script.Source;
import dollar.api.var;
import dollar.internal.runtime.script.DollarUtilFactory;
import dollar.internal.runtime.script.SimpleSubType;
import dollar.internal.runtime.script.SourceCode;
import dollar.internal.runtime.script.api.DollarParser;
import dollar.internal.runtime.script.api.exceptions.DollarScriptException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jparsec.Token;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static dollar.api.DollarStatic.$;
import static dollar.api.DollarStatic.$void;
import static dollar.api.types.meta.MetaConstants.*;
import static dollar.internal.runtime.script.DollarUtil.MIN_PROBABILITY;
import static dollar.internal.runtime.script.SourceNodeOptions.NEW_SCOPE;
import static dollar.internal.runtime.script.parser.Symbols.*;
import static java.util.Collections.emptyList;

public class AssignmentOperator implements Function<Token, Function<? super var, ? extends var>> {
    @NotNull
    private static final Logger log = LoggerFactory.getLogger("AssignmentOperator");
    @NotNull
    private final DollarParser parser;
    private final boolean pure;

    public AssignmentOperator(boolean pure, @NotNull DollarParser parser) {
        this.pure = pure;
        this.parser = parser;
    }

    @Override
    @Nullable
    public Function<? super var, ? extends var> apply(@NotNull Token token) {
        @Nullable Type type;
        Object[] objects = (Object[]) token.value();
        var constraint = null;
        @Nullable final SubType constraintSource;

        if (objects[3] instanceof var) {
            SourceCode meta = ((var) objects[3]).meta(CONSTRAINT_SOURCE);
            constraintSource = (meta == null) ? null : new SimpleSubType(meta);
        } else {
            constraintSource = null;
        }

        if (objects[2] != null) {
            type = Type.of(objects[2].toString());
            constraint = DollarUtilFactory.util().node(ASSIGNMENT_CONSTRAINT, "assignment-constraint",
                                                       pure, NEW_SCOPE, parser,
                                                       new SourceCode(token), type, emptyList(),
                                                       i -> {
                                                           var it = DollarUtilFactory.util().currentScope().parameter(
                                                                   VarKey.IT).getValue();
                                                           return $(
                                                                   it.is(type) && ((objects[3] == null) || ((var) objects[3]).isTrue()));
                                                       });
        } else {
            type = null;
            if (objects[3] instanceof var) constraint = (var) objects[3];

        }

        boolean constant;
        boolean isVolatile;
        final Object mutability = objects[1];
        boolean declaration = (mutability != null) || (objects[2] instanceof var) || (objects[3] instanceof var);
        constant = (mutability != null) && "const".equals(mutability.toString());
        isVolatile = (mutability != null) && "volatile".equals(mutability.toString());
        if (((var) objects[4]).metaAttribute(IS_BUILTIN) != null) {
            throw new DollarScriptException(String.format(
                    "The variable '%s' cannot be assigned as this name is the name of a builtin function.", objects[4]));
        }
        final VarKey varName = VarKey.of((var) objects[4]);

        var finalConstraint = constraint;
        return (Function<var, var>) rhs -> {
            Scope scope = DollarUtilFactory.util().currentScope();
            DollarUtilFactory.util().checkLearntType(token, type, rhs, MIN_PROBABILITY);

            final String op = ((var) objects[5]).metaAttribute(ASSIGNMENT_TYPE);
            if ("when".equals(op) || "subscribe".equals(op)) {
                final SubType useSource;
                var useConstraint;
                if (finalConstraint != null) {
                    useConstraint = finalConstraint;
                    useSource = constraintSource;
                } else {
                    useConstraint = scope.constraintOf(varName);
                    useSource = scope.subTypeOf(varName);
                }
                List<var> inputs = Arrays.asList(rhs, DollarUtilFactory.util().constrain(scope, rhs, finalConstraint, useSource));
                if ("when".equals(op)) {
                    log.debug("DYNAMIC: {}", rhs.dynamic());

                    return DollarUtilFactory.util().node(WHEN_ASSIGN, pure, parser, token, inputs,
                                                         c -> {
                                                             var condition = (var) objects[5];
                                                             var initial = rhs.$fixDeep(false);
                                                             scope.set(varName, condition.isTrue() ? initial : $void(), null,
                                                                       useSource,
                                                                       new VarFlags(false, isVolatile, false, pure, false,
                                                                                    declaration));
                                                             return condition.$listen(
                                                                     args -> {
                                                                         if (condition.isTrue()) {
                                                                             var value = rhs.$fixDeep(false);
                                                                             DollarUtilFactory.util().setVariable(scope, varName,
                                                                                                                  value, parser,
                                                                                                                  token,
                                                                                                                  useConstraint,
                                                                                                                  useSource,
                                                                                                                  new VarFlags(false,
                                                                                                                               isVolatile,
                                                                                                                               false,
                                                                                                                               pure,
                                                                                                                               false,
                                                                                                                               false));

                                                                             return value;
                                                                         } else {
                                                                             return $void();
                                                                         }
                                                                     });
                                                         }
                    );

                } else if ("subscribe".equals(op)) {
                    scope.set(varName, $void(), null, useSource,
                              new VarFlags(false, true, true, pure, false, declaration));
                    return DollarUtilFactory.util().node(SUBSCRIBE_ASSIGN, pure, parser, token, inputs,
                                                         c -> $(rhs.$subscribe(
                                                                 i -> DollarUtilFactory.util().setVariable(scope, varName,
                                                                                                           DollarUtilFactory.util().fix(
                                                                                                                   i[0]),
                                                                                                           parser, token,
                                                                                                           useConstraint, useSource,
                                                                                                           new VarFlags(false, true,
                                                                                                                        false, pure,
                                                                                                                        false,
                                                                                                                        declaration)).getValue())));
                }
            }
            return assign(rhs, objects, finalConstraint, new VarFlags(constant, isVolatile, declaration, pure),
                          constraintSource, scope, token, type, new SourceCode(DollarUtilFactory.util().currentScope(), token));
        };
    }

    @NotNull
    private var assign(@NotNull var rhs,
                       @NotNull Object[] objects,
                       @Nullable var constraint,
                       @NotNull VarFlags varFlags,
                       @Nullable SubType constraintSource,
                       @NotNull Scope scope,
                       @NotNull Token token,
                       @Nullable Type type,
                       @NotNull Source source) {

        final VarKey varName = VarKey.of((var) objects[4]);

        Pipeable pipeable = args -> {

            Scope currentScope = DollarUtilFactory.util().currentScope();
            final var useConstraint;
            final SubType useSource;
            Scope varScope = DollarUtilFactory.util().getScopeForVar(pure, varName, false, DollarUtilFactory.util().currentScope());
            if ((constraint != null) || (varScope == null)) {
                useConstraint = constraint;
                useSource = constraintSource;
            } else {
                useConstraint = varScope.constraintOf(varName);
                useSource = varScope.subTypeOf(varName);
            }
            //Don't change this value, 2 is the 'instinctive' depth a programmer would expect
            final var rhsFixed = rhs.$fix(2, false);

            if (rhsFixed.$type() != null && type != null) {
                if (!rhsFixed.$type().canBe(type)) {
                    throw new DollarScriptException("Type mismatch expected " + type + " got " + rhsFixed.$type(), source);
                }

            }
            if (useConstraint != null) {
                DollarUtilFactory.util().inSubScope(true, pure, "assignment-constraint",
                                                    newScope -> {
                                                        newScope.parameter(VarKey.IT, rhsFixed);
                                                        var value = newScope.get(varName);
                                                        assert value != null;
                                                        newScope.parameter(VarKey.PREVIOUS, value);
                                                        if (useConstraint.isFalse()) {
                                                            newScope.handleError(
                                                                    new DollarScriptException("Constraint failed for variable " + varName + "",
                                                                                              source));
                                                        }
                                                        return null;
                                                    });
            }
            if (objects[0] != null) {
                parser.export(varName, rhsFixed);
            }
            DollarUtilFactory.util().setVariable(currentScope, varName, rhsFixed, parser, token, constraint, useSource, varFlags);
            return $void();

        };
        //        node.$listen(i -> scope.notify(varName));
        return DollarUtilFactory.util().node(ASSIGNMENT, pure, parser, token, Arrays.asList(rhs, DollarUtilFactory.util().constrain(
                scope, rhs, constraint, constraintSource)),
                                             pipeable);
    }


}
