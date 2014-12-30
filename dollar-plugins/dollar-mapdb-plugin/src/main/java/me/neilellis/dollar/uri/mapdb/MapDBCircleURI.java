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

package me.neilellis.dollar.uri.mapdb;

import me.neilellis.dollar.Pipeable;
import me.neilellis.dollar.execution.DollarExecutor;
import me.neilellis.dollar.plugin.Plugins;
import me.neilellis.dollar.types.DollarFactory;
import me.neilellis.dollar.uri.URI;
import me.neilellis.dollar.var;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mapdb.DB;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static me.neilellis.dollar.DollarStatic.$;
import static me.neilellis.dollar.DollarStatic.$void;

public class MapDBCircleURI extends AbstractMapDBURI {

    private static final ConcurrentHashMap<String, Future> subscribers = new ConcurrentHashMap<>();
    @Nullable private static final DollarExecutor executor = Plugins.sharedInstance(DollarExecutor.class);


    public MapDBCircleURI(String scheme, @NotNull URI uri) {
        super(uri, scheme);
        tx.execute((DB d) -> {
            if (!d.exists(getHost())) {
                d.createCircularQueue(getHost(), new VarSerializer(),
                                      Integer.parseInt(uri.paramWithDefault("size", "100").get(0)));
            }
        });
    }

    @NotNull @Override public var all() {
        DB d = tx.makeTx();
        final BlockingQueue<var> queue = getQueue(d);
        final ArrayList<var> objects = new ArrayList<>();
        queue.drainTo(objects);
        final var result = DollarFactory.fromValue(objects);
        d.rollback();
        return result;

    }

    @Override public var write(@NotNull var value, boolean blocking, boolean mutating) {
        if (value.isVoid()) {
            return $(false);
        }
        return tx.execute((DB d) -> {
            try {
                if (!blocking) {
                    return $(getQueue(d).offer(value._fixDeep()));
                } else {
                    getQueue(d).put(value._fixDeep());
                    return $(true);
                }

            } catch (InterruptedException e) {
                e.printStackTrace();
                return $(false);
            }
        });
    }

    @Override public var drain() {
        return tx.execute((DB d) -> {
            final BlockingQueue<var> queue = getQueue(d);
            final ArrayList<var> objects = new ArrayList<>();
            queue.drainTo(objects);
            final var result = DollarFactory.fromValue(objects);
            d.commit();
            return result;
        });
    }

    @NotNull @Override public var get(var key) {
        throw new UnsupportedOperationException();
    }

    @Override public var read(boolean blocking, boolean mutating) {
        return tx.execute((DB d) -> {
            try {
                if (blocking && mutating) {
                    return getQueue(d).take();
                } else if (blocking) {
                    return getQueue(d).poll(60, TimeUnit.SECONDS);
                } else if (mutating) {
                    return getQueue(d).poll();
                } else {
                    return getQueue(d).peek();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                return $void();
            }
        });
    }

    @NotNull @Override public var remove(var v) {
        throw new UnsupportedOperationException();
    }

    @Override public var removeValue(@NotNull var v) {
        return tx.execute((DB d) -> DollarFactory.fromValue(getQueue(d).remove(v._unwrap())));
    }

    @NotNull @Override public var set(var key, var value) {
        throw new UnsupportedOperationException();
    }

    @Override public int size() {
        return tx.execute((DB d) -> getQueue(d).size());
    }

    @Override public void subscribe(@NotNull Pipeable consumer, @NotNull String id) throws IOException {
        final Future schedule = executor.scheduleEvery(1000, () -> tx.execute((DB d) -> {
            Object o = MapDBCircleURI.this.getQueue(d).poll();
            if (o != null) {
                try {
                    consumer.pipe($(o));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }));
        subscribers.put(id, schedule);
    }

    @Override public void unsubscribe(@NotNull String subId) {
        subscribers.get(subId).cancel(false);
    }

    private BlockingQueue<var> getQueue(@NotNull DB d) {
        return d.getCircularQueue(getHost());
    }
}
