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

import com.sillelien.dollar.api.DollarException;
import com.sillelien.dollar.api.exceptions.LambdaRecursionException;
import com.sillelien.dollar.api.script.SourceSegment;
import com.sillelien.dollar.api.types.DollarFactory;
import com.sillelien.dollar.api.types.ErrorType;
import com.sillelien.dollar.api.var;
import dollar.internal.runtime.script.api.ParserErrorHandler;
import dollar.internal.runtime.script.api.Scope;
import dollar.internal.runtime.script.api.exceptions.DollarAssertionException;
import dollar.internal.runtime.script.api.exceptions.DollarParserError;
import dollar.internal.runtime.script.api.exceptions.DollarScriptException;
import dollar.internal.runtime.script.api.exceptions.ErrorReporter;
import dollar.internal.runtime.script.api.exceptions.VariableNotFoundException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jparsec.error.Location;
import org.jparsec.error.ParseErrorDetails;
import org.jparsec.error.ParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.InvocationTargetException;

@SuppressWarnings("UseOfSystemOutOrSystemErr")
public class ParserErrorHandlerImpl implements ParserErrorHandler {
    private final boolean missingVariables;
    private final boolean failfast;
    private final boolean faultTolerant;

    @NotNull
    private static final Logger log = LoggerFactory.getLogger("ParserErrorHandlerImpl");

    public ParserErrorHandlerImpl() {
        missingVariables = true;
        failfast = true;
        faultTolerant = false;
    }


    @NotNull
    @Override
    public var handle(@NotNull Scope scope, @Nullable SourceSegment source, @NotNull DollarAssertionException e) {

        if (!faultTolerant) {
            return scope.handleError(e);
        } else {
            return DollarFactory.failure(e);
        }
    }

    @NotNull
    @Override
    public var handle(@NotNull Scope scope, @Nullable SourceSegment source, @NotNull DollarException e) {

        final Throwable throwable;
        if (source != null) {
            throwable = new DollarParserError(e.getMessage() + " at " + source.getSourceMessage(), e);
        } else {
            throwable = e;
        }
        if (((e instanceof VariableNotFoundException) && missingVariables) || failfast) {
            return scope.handleError(throwable);
        } else {
            return DollarFactory.failure(throwable);
        }

    }

    @Override
    @NotNull
    public var handle(@NotNull Scope scope, @Nullable SourceSegment source, @NotNull Exception e) {
        if (e instanceof LambdaRecursionException) {
            throw new DollarParserError(
                                               "Excessive recursion detected, this is usually due to a recursive definition of lazily defined " +
                                                       "expressions. The simplest way to solve this is to use the 'fix' operator or the '=' operator to " +
                                                       "reduce the amount of lazy evaluation. The error occured at " +
                                                       source);
        }

        if ((e instanceof DollarException) && (source != null)) {
            ((DollarException) e).addSource(source);
            throw (DollarException) e;
        } else if (source != null) {
            return DollarFactory.failureWithSource(ErrorType.EXCEPTION, unravel(e), source);
        } else {
            return DollarFactory.failure(ErrorType.EXCEPTION, unravel(e), false);
        }

    }

    private @NotNull
    Throwable unravel(@NotNull Throwable e) {
        if (e instanceof InvocationTargetException) {
            return e.getCause();
        } else {
            return e;
        }
    }

    @Override
    public void handleTopLevel(@NotNull Throwable t, @Nullable String name, @NotNull File file) throws Throwable {
        if (t instanceof DollarAssertionException) {
            System.out.println("An assertion failed");

        }
        if (t instanceof DollarException) {
            ErrorReporter.report(getClass(), t);
            log.error(t.getMessage(), t);
        }
        if (t instanceof ParserException) {
            Location location = ((ParserException) t).getLocation();
            ParseErrorDetails errorDetails = ((ParserException) t).getErrorDetails();
            System.out.println();
            if (file != null) {
                System.out.print("A parser (syntax) error occurred while parsing " + file + ":" + location.line);
            } else {
                if (name != null) {
                    System.out.print("A parser (syntax) error occurred while parsing " + name);
                } else {
                    System.out.print("Problem parsing this script");
                }
            }
            if (location != null) {
                if (errorDetails != null) {
                    System.out.println(
                            " the error occurred on line " + location.line + " column " + location.column + " near '" + errorDetails.getEncountered() + "'.");
                } else {
                    System.out.println(
                            " the error occurred on line " + location.line + " column " + location.column);
                }
            }
            System.out.println();
            if (t.getCause() instanceof DollarScriptException) {
                log.debug(t.getCause().getMessage(), t);
            } else {
                log.debug(t.getMessage(), t);
            }
            t.printStackTrace(System.err);
            System.exit(1);
        }
        if (t instanceof DollarScriptException) {
            ErrorReporter.report(getClass(), t);
            log.error(t.getMessage(), t);
        } else {
            throw t;
        }
    }

    private void log(@NotNull Throwable e) {
        e.printStackTrace();
    }
}
