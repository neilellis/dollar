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

import me.neilellis.dollar.exceptions.LambdaRecursionException;
import me.neilellis.dollar.script.exceptions.DollarScriptException;
import me.neilellis.dollar.script.exceptions.VariableNotFoundException;
import me.neilellis.dollar.types.DollarFactory;
import me.neilellis.dollar.var;

import java.lang.reflect.InvocationTargetException;

import static me.neilellis.dollar.DollarStatic.$void;

/**
 * @author <a href="http://uk.linkedin.com/in/neilellis">Neil Ellis</a>
 */
public class ParserErrorHandler {
    private final boolean missingVariables;
    private final boolean failfast;
    private final boolean faultTolerant;

    public ParserErrorHandler() {
        missingVariables = true;
        failfast = true;
        faultTolerant = false;
    }


    public var handle(String source, AssertionError e) {
        AssertionError throwable = new AssertionError(e.getMessage() + " at " + source, e);
        if (!faultTolerant) {
            throw throwable;
        } else {
            log(e);
            return DollarFactory.failure(throwable);
        }
    }

    public var handle(String source, DollarScriptException e) {
        DollarParserException throwable = new DollarParserException(e.getMessage() + " at " + source, e);
        if ((e instanceof VariableNotFoundException && missingVariables) || failfast) {
            throw throwable;
        } else {
            log(e);
            return DollarFactory.failure(throwable);
        }
    }

    private Throwable unravel(Throwable e) {
        if (e instanceof InvocationTargetException) {
            return e.getCause();
        } else {
            return e;
        }
    }

    public var handle(String source, Exception e) {
        if (e instanceof LambdaRecursionException) {
            throw new DollarParserException("Excessive recursion detected, this is usually due to a recursive definition of lazily defined expressions. The simplest way to solve this is to use the 'fix' operator or the '=' operator to reduce the amount of lazy evaluation. The error occured at " + source);
        }

        DollarParserException throwable = new DollarParserException((unravel(e)).getMessage() + " at " + source, unravel(e));

        if (failfast) {
            throw throwable;
        } else if (faultTolerant) {
            log(e);
            return $void();
        } else {
            log(e);
            return DollarFactory.failure(throwable);
        }
    }

    private void log(Throwable e) {
        e.printStackTrace();
    }

    public void handleTopLevel(Throwable t) throws Throwable {
        if (t instanceof AssertionError) {
            System.err.println(t.getMessage());
        }
        if (t instanceof DollarParserException) {
            System.err.println(t.getMessage());
        }
        if (t instanceof DollarScriptException) {
            System.err.println(t.getMessage());
        } else throw t;
    }
}
