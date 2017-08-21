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
import com.sillelien.dollar.api.DollarStatic;
import com.sillelien.dollar.api.MetadataAware;
import com.sillelien.dollar.api.Pipeable;
import com.sillelien.dollar.api.TypePrediction;
import com.sillelien.dollar.api.VarInternal;
import com.sillelien.dollar.api.exceptions.LambdaRecursionException;
import com.sillelien.dollar.api.execution.DollarExecutor;
import com.sillelien.dollar.api.plugin.Plugins;
import com.sillelien.dollar.api.script.SourceSegment;
import com.sillelien.dollar.api.script.TypeLearner;
import com.sillelien.dollar.api.types.ConstraintViolation;
import com.sillelien.dollar.api.types.DollarFactory;
import com.sillelien.dollar.api.var;
import dollar.internal.runtime.script.api.DollarParser;
import dollar.internal.runtime.script.api.Scope;
import dollar.internal.runtime.script.api.exceptions.DollarAssertionException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.sillelien.dollar.api.DollarStatic.$;
import static dollar.internal.runtime.script.DollarScriptSupport.currentScope;

public class SourceNode implements java.lang.reflect.InvocationHandler {
    @NotNull
    private static final TypeLearner typeLearner = Objects.requireNonNull(Plugins.sharedInstance(TypeLearner.class));

    //    public static final String RETURN_SCOPE = "return-scope";
    @NotNull
    private static final Logger log = LoggerFactory.getLogger("DollarSource");
    @NotNull
    private static final List<String> nonScopeOperations = Arrays.asList(
            "dynamic", "_constrain");
    @Nullable
    private static final DollarExecutor executor = Plugins.sharedInstance(DollarExecutor.class);
    private static final int MAX_STACK_DEPTH = 100;
    @NotNull
    private static final ThreadLocal<List<SourceNode>> notifyStack = ThreadLocal.withInitial(ArrayList::new);
    @NotNull
    private static final ThreadLocal<List<SourceNode>> stack = ThreadLocal.withInitial(ArrayList::new);
    @NotNull
    private final ConcurrentHashMap<Object, Object> meta = new ConcurrentHashMap<>();
    @NotNull

    private final Pipeable lambda;
    @NotNull
    private final SourceSegment source;

    @NotNull
    private final ConcurrentHashMap<String, Pipeable> listeners = new ConcurrentHashMap<>();

    @NotNull
    private final List<var> inputs;

    @Nullable
    private final String operation;

    @Nullable
    private volatile TypePrediction prediction;

    @NotNull
    private final DollarParser parser;

    private final boolean newScope;

    private final boolean scopeClosure;
    @NotNull
    private final String id;
    private final boolean parallel;
    private final boolean pure;

    public SourceNode(@NotNull Pipeable lambda,
                      @NotNull SourceSegment source,
                      @NotNull List<var> inputs,
                      @NotNull String operation,
                      @NotNull DollarParser parser,
                      @NotNull SourceNodeOptions sourceNodeOptions,
                      @NotNull String id,
                      boolean pure) {
        parallel = sourceNodeOptions.isParallel();
        this.pure = pure;


        if (sourceNodeOptions.isScopeClosure()) {
            this.lambda = vars -> {
                List<Scope> attachedScopes = new ArrayList<>(DollarScriptSupport.scopes());
                return DollarScriptSupport.node(operation + "-closure", pure,
                                                new SourceNodeOptions(false, false, sourceNodeOptions.isParallel()),
                                                parser, source, inputs, vars2 -> {
                            for (Scope scope : attachedScopes) {
                                DollarScriptSupport.pushScope(scope);
                            }
                            try {
                                return lambda.pipe(vars);
                            } finally {
                                Collections.reverse(attachedScopes);
                                for (Scope scope : attachedScopes) {
                                    DollarScriptSupport.popScope(scope);
                                }

                            }

                        });
            };
        } else {
            this.lambda = lambda;
        }
        this.parser = parser;
        newScope = sourceNodeOptions.isNewScope();
        scopeClosure = sourceNodeOptions.isScopeClosure();
        this.id = id;
        this.inputs = inputs;
        this.operation = operation;
        this.source = source;
        meta.put("operation", operation);
        meta.put("id", id);
    }


    @Nullable
    @Override
    public Object invoke(@NotNull Object proxy, @NotNull Method method, @Nullable Object[] args) throws Throwable {
        try {
            if (Objects.equals(method.getName(), "_source")) {
                return source;
            }
            if (Objects.equals(method.getName(), "dynamic")) {
                return true;
            }

            if (Objects.equals(method.getName(), "_constrain")) {
                if (args != null) {
                    return _constrain((var) proxy, (var) args[0], String.valueOf(args[1]));
                }
            }

            if (Objects.equals(method.getName(), "_predictType")) {
                if (prediction == null) {
                    prediction = typeLearner.predict(operation, source, inputs);
                }
                return prediction;
            }

            Object result = null;
            //Some operations do not require a valid scope
            if ((!method.getName().startsWith("_fix") && (nonScopeOperations.contains(
                    method.getName()) || method.getDeclaringClass().equals(
                    MetadataAware.class) || method.getDeclaringClass().equals(
                    VarInternal.class)))) {
                //This method does not require a valid scope for execution
                try {
                    result = invokeMain(proxy, method, args);
                } catch (Throwable throwable) {
                    log.debug(throwable.getMessage(), throwable);
                    throw new DollarException(throwable);
                }
            } else {
                //This method does require a scope
                Scope useScope;

                Object attachedScopes = meta.get("scopes");
                if (attachedScopes != null) {
                    List<Scope> scopes = new ArrayList<>((List<Scope>) attachedScopes);
                    for (Scope scope : scopes) {
                        DollarScriptSupport.pushScope(scope);
                    }
                }
                try {
                    if (newScope) {
                        useScope = new ScriptScope(currentScope(), operation, false);
                    } else {
                        useScope = currentScope();
                    }

                    result = DollarScriptSupport.inScope(true, useScope, newScope -> {
                        if (DollarStatic.getConfig().debugScope()) {
                            log.info("EXE: {} {} for {}", operation, method.getName(), source.getShortSourceMessage());
                        }
                        try {
                            return invokeMain(proxy, method, args);
                        } catch (Throwable throwable) {
                            log.debug(throwable.getMessage(), throwable);
                            throw new DollarException(throwable);
                        }
                    });
                } finally {

                    if (attachedScopes != null) {
                        List<Scope> scopes = new ArrayList<>((List<Scope>) attachedScopes);
                        Collections.reverse(scopes);
                        for (Scope scope : scopes) {
                            DollarScriptSupport.popScope(scope);
                        }
                    }
                }

            }


            if (method.getName().startsWith("_fixDeep") && (result != null) && (result instanceof var)) {
                typeLearner.learn(operation, source, inputs, ((var) result).$type());
            }
            return result;
        } catch (DollarAssertionException e) {
            log.warn(e.getMessage(), e);
            throw e;
        } catch (DollarException e) {
            log.warn(e.getMessage(), e);
            return parser.getErrorHandler().handle(currentScope(), source, e);
        } catch (Exception e) {
            log.warn(e.getMessage(), e);
            return parser.getErrorHandler().handle(currentScope(), source, e);
        }
    }

    @Nullable

    public Object invokeMain(@Nullable Object proxy, @NotNull Method method, @Nullable Object[] args) throws Throwable {
        if (stack.get().size() > MAX_STACK_DEPTH) {
            throw new LambdaRecursionException(stack.get().size());

        }
        try {
            if (proxy == null) {
                return null;
            } else if (Objects.equals(Object.class, method.getDeclaringClass())) {
                String name = method.getName();
                if ("equals".equals(name)) {
                    return execute().equals(args[0]);
                } else if ("hashCode".equals(name)) {
                    return execute().hashCode();
                } else if ("toString".equals(name)) {
                    return execute().toString();
                } else {
                    throw new IllegalStateException(String.valueOf(method));
                }

            } else if ("_unwrap".equals(method.getName())) {
                return proxy;
//                return lambda.pipe(in)._unwrap();
            } else if ("_fix".equals(method.getName())) {

                if (args.length == 1) {
                    return lambda.pipe(DollarFactory.fromValue(args[0]))._fix((Boolean) args[0]);
                } else {
                    return lambda.pipe(DollarFactory.fromValue(args[1]))._fix((int) args[0], (Boolean) args[1]);

                }

            } else if ("_fixDeep".equals(method.getName())) {

                if ((args == null) || (args.length == 0)) {
                    return lambda.pipe(DollarFactory.fromValue(parallel))._fixDeep();
                } else {
                    return lambda.pipe(DollarFactory.fromValue(args[0]))._fixDeep((Boolean) args[0]);
                }


            } else if ("_constraintFingerprint".equals(method.getName())) {
                return meta.get("constraintFingerprint");
            } else if ("getMetaAttribute".equals(method.getName())) {
                return meta.get(args[0].toString());
            } else if ("setMetaAttribute".equals(method.getName())) {
                meta.put(String.valueOf(args[0]), String.valueOf(args[1]));
                return null;
            } else if ("setMetaAttribute".equals(method.getName())) {
                meta.put(String.valueOf(args[0]), String.valueOf(args[1]));
                return null;
            } else if ("getMetaObject".equals(method.getName())) {
                return meta.get(args[0]);
            } else if ("setMetaObject".equals(method.getName())) {
                meta.put(args[0], args[1]);
                return null;
            } else if ("$listen".equals(method.getName())) {
                String listenerId = UUID.randomUUID().toString();
                if (args.length == 2) {
                    listenerId = String.valueOf(args[1]);
                }
                listeners.put(listenerId, (Pipeable) args[0]);
                return $(listenerId);
            } else if ("$notify".equals(method.getName())) {
                if (notifyStack.get().contains(this)) {
                    //throw new IllegalStateException("Recursive notify loop detected");
                    return proxy;
                }
                notifyStack.get().add(this);
                final var value = execute();
                for (Pipeable listener : listeners.values()) {
                    listener.pipe(value);
                }
                notifyStack.get().remove(this);
                return proxy;
            } else if ("$remove".equals(method.getName())) {
                //noinspection SuspiciousMethodCalls
                listeners.remove(args[0]);
                return args[0];
            } else if ("hasErrors".equals(method.getName())) {
                return false;
            } else if ("$copy".equals(method.getName()) || "copy".equals(method.getName())) {
                return proxy;
            } else {
//            System.err.println(method);

                var out = execute();
                if (out == null) {
                    return null;
                }
                try {
                    stack.get().add(this);
                    return method.invoke(out, args);
                } finally {
                    stack.get().remove(stack.get().size() - 1);
                }
            }
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @NotNull
    public var _constrain(@NotNull var source, @Nullable var constraint, @Nullable String constraintSource) {
        if ((constraint == null) || (constraintSource == null)) {
            return source;
        }
        String constraintFingerprint = (String) meta.get("constraintFingerprint");
        if ((constraintFingerprint == null) || constraintSource.equals(constraintFingerprint)) {
            meta.put("constraintFingerprint", constraintSource);
            return source;
        } else {
            throw new ConstraintViolation(source, constraint, constraintFingerprint, constraintSource);
        }
    }

    @NotNull
    public var execute() throws Exception {return executor.executeNow(() -> lambda.pipe($(parallel))).get();}
}
