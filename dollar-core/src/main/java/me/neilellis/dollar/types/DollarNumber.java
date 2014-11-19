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

package me.neilellis.dollar.types;

import me.neilellis.dollar.DollarStatic;
import me.neilellis.dollar.Type;
import me.neilellis.dollar.var;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static java.lang.Math.abs;

/**
 * @author <a href="http://uk.linkedin.com/in/neilellis">Neil Ellis</a>
 */
public class DollarNumber extends AbstractDollarSingleValue<Number> {

    public DollarNumber(@NotNull List<Throwable> errors, @NotNull Number value) {
        super(errors, value);
    }

    @NotNull
    @Override
    public Number $() {
        return value;
    }

    @Override
    public var $as(Type type) {
        switch (type) {
            case BOOLEAN:
                return DollarStatic.$(value.intValue() != 0);
            case STRING:
                return DollarStatic.$(S());
            case LIST:
                return DollarStatic.$(Arrays.asList(this));
            case MAP:
                return DollarStatic.$("value", this);
            case NUMBER:
                return this;
            case VOID:
                return DollarStatic.$void();
            default:
                throw new UnsupportedOperationException();
        }
    }

    @Override
    @NotNull
    public Integer I() {
        return value.intValue();
    }

    @NotNull
    @Override
    public Number N() {
        return value;
    }

    @Override
    public boolean is(Type... types) {
        for (Type type : types) {
            if (type == Type.NUMBER) {
                return true;
            }
        }
        return false;
    }

    @Override
    @NotNull
    public Number number(@NotNull String key) {
        return value;
    }

    @NotNull
    @Override
    public var $dec(@NotNull var amount) {
        return new DollarNumber(errors(), value.longValue() - amount.L());
    }

    @NotNull
    @Override
    public var $inc(@NotNull var amount) {
        return new DollarNumber(errors(), value.longValue() + amount.L());
    }

    @NotNull
    @Override
    public var $negate() {
        if (isInteger()) {
            return DollarFactory.fromValue(-value.longValue());
        } else {
            return DollarFactory.fromValue(-value.doubleValue());
        }
    }

    @NotNull
    @Override
    public var $multiply(@NotNull var v) {
        if (isInteger()) {
            return DollarFactory.fromValue(value.longValue() * v.L());
        } else {
            return DollarFactory.fromValue(value.doubleValue() * v.D());
        }
    }

    @NotNull
    @Override
    public var $divide(@NotNull var v) {
        if (isInteger()) {
            return DollarFactory.fromValue(value.longValue() / v.L());
        } else {
            return DollarFactory.fromValue(value.doubleValue() / v.D());
        }
    }

    @NotNull
    @Override
    public var $modulus(@NotNull var v) {
        if (isInteger()) {
            return DollarFactory.fromValue(value.longValue() % v.L());
        } else {
            return DollarFactory.fromValue(value.doubleValue() % v.D());
        }
    }

    @NotNull
    @Override
    public var $abs() {
        if (isInteger()) {
            return DollarFactory.fromValue(abs(value.longValue()));
        } else {
            return DollarFactory.fromValue(abs(value.doubleValue()));
        }
    }

    @NotNull
    @Override
    public var $plus(Object newValue) {
        if (newValue instanceof var) {
            if (isInteger()) {
                return DollarFactory.fromValue(errors(), value.longValue() + ((var) newValue).L());
            } else {
                return DollarFactory.fromValue(errors(), value.doubleValue() + ((var) newValue).D());
            }
        }
        return super.$plus(newValue);

    }

    @NotNull
    @Override
    public var $minus(Object newValue) {
        if (newValue instanceof var) {
            if (((var) newValue).isInteger()) {
                return DollarFactory.fromValue(errors(), value.longValue() - ((var) newValue).L());
            }
            if (((var) newValue).isDecimal()) {
                return DollarFactory.fromValue(errors(), value.doubleValue() - ((var) newValue).D());
            }
        }
        return super.$plus(newValue);

    }

    @NotNull
    @Override
    public Double D() {
        return value.doubleValue();
    }

    @Nullable
    @Override
    public Long L() {
        return value.longValue();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof var) {
            var unwrapped = ((var) obj)._unwrap();
            if (unwrapped instanceof DollarNumber) {
                return $equals(unwrapped);
            } else {
                return value.toString().equals(obj.toString());
            }
        } else {
            return value.toString().equals(obj.toString());
        }
    }

    public boolean $equals(var other) {
        if (isInteger()) {
            return value.longValue() == other.L();
        } else {
            return value.doubleValue() == other.D();
        }
    }

    @Override
    public boolean isDecimal() {
        return value instanceof BigDecimal || value instanceof Float || value instanceof Double;
    }

    @Override
    public boolean isInteger() {
        return value instanceof Long || value instanceof Integer;
    }

    @Override
    public boolean isNumber() {
        return true;
    }

    @Override
    public int compareTo(var o) {
        return $minus(o).I();
    }

    @Override
    public boolean isBoolean() {
        return false;
    }

    @Override
    public boolean isTrue() {
        return false;
    }

    @Override
    public boolean isTruthy() {
        return value.intValue() != 0;
    }

    @Override
    public boolean isFalse() {
        return false;
    }

    @Override
    public boolean isNeitherTrueNorFalse() {
        return true;
    }
}
