/*
 * Copyright (c) 2014-2015 Neil Ellis
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

package me.neilellis.dollar.script.operators;

import me.neilellis.dollar.api.var;
import me.neilellis.dollar.script.DollarScriptSupport;
import me.neilellis.dollar.script.SourceSegmentValue;
import me.neilellis.dollar.script.api.Scope;
import org.codehaus.jparsec.Token;
import org.codehaus.jparsec.functors.Map;
import org.jetbrains.annotations.NotNull;

public class WriteOperator implements Map<Token, Map<? super var, ? extends var>> {
    private final Scope scope;

    public WriteOperator(Scope scope) {this.scope = scope;}

    @NotNull @Override
    public Map<? super var, ? extends var> map(@NotNull Token token) {
        Object[] objects = (Object[]) token.value();
        return new Map<var, var>() {
            @Override
            public var map(@NotNull var rhs) {
                return DollarScriptSupport.wrapReactive(scope,
                                                        () -> rhs.$write((var) objects[1],
                                                                         objects[2] != null,
                                                                         objects[3] !=
                                                                         null), new SourceSegmentValue(scope, token),
                                                        "write:" + objects[2] + ":" + objects[3], (var) objects[1],
                                                        rhs
                );
            }
        };
    }
}
