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

package dollar.internal.runtime.script;

import dollar.api.script.Source;
import dollar.api.var;
import dollar.internal.runtime.script.api.DollarParser;
import dollar.internal.runtime.script.parser.OpDef;
import dollar.internal.runtime.script.parser.OpDefType;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import static java.util.Collections.singletonList;

public class DollarUnaryOperator implements UnaryOperator<var>, Operator {
    @NotNull
    protected final OpDef operation;
    @NotNull
    private final BiFunction<var, Source, var> function;
    private final boolean immediate;
    private final boolean pure;
    @NotNull
    protected DollarParser parser;
    @NotNull
    protected Source source;

    public DollarUnaryOperator(@NotNull DollarParser parser,
                               @NotNull OpDef operation,
                               @NotNull BiFunction<var, Source, var> function,
                               boolean pure) {
        this.operation = operation;
        this.function = function;
        this.parser = parser;
        this.pure = pure;
        immediate = false;
        validate(operation);
    }

    public DollarUnaryOperator(boolean immediate,
                               @NotNull BiFunction<var, Source, var> function,
                               @NotNull OpDef operation,
                               @NotNull DollarParser parser, boolean pure) {
        this.operation = operation;
        this.immediate = immediate;
        this.function = function;
        this.parser = parser;
        this.pure = pure;
        validate(operation);
    }

    @NotNull
    @Override
    public var apply(@NotNull var from) {

        if (immediate) {
            return DollarUtilFactory.util().node(operation, pure, parser, source, singletonList(from),
                                                 vars -> function.apply(from, source));

        }

        //Lazy evaluation
        return DollarUtilFactory.util().reactiveNode(operation, pure, source, parser, from, args -> function.apply(from, source));

    }

    @Override
    public void setSource(@NotNull Source source) {
        this.source = source;
    }

    public void validate(@NotNull OpDef operation) {
        if (operation.reactive() == immediate) {
            throw new AssertionError("The operation " + operation.name() + " is marked as " + (operation.reactive()
                                                                                                       ? "reactive" : "unreactive") + " " +
                                             "yet this operator is set to be " + (immediate
                                                                                          ? "unreactive" : "reactive"));
        }
        if ((operation.type() != OpDefType.PREFIX) && (operation.type() != OpDefType.POSTFIX)) {
            throw new AssertionError("The operator " + operation.name() + " is not defined as a unary type but used in a unary " +
                                             "operator.");
        }
        if (pure && (operation.pure() != null) && !operation.pure()) {
            throw new AssertionError("The operation " + operation.name() + " is marked as " + (operation.pure() ? "pure" : "impure") + " yet this operator is set to be " + (pure ? "pure" : "impure"));
        }
    }
}
