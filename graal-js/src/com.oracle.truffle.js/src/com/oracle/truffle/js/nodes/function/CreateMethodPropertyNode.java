/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.nodes.function;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.access.IsJSObjectNode;
import com.oracle.truffle.js.nodes.access.PropertySetNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.objects.JSAttributes;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.PropertyDescriptor;

public abstract class CreateMethodPropertyNode extends JavaScriptBaseNode {
    protected final JSContext context;
    protected final Object key;
    @Child protected IsJSObjectNode isObject;

    protected CreateMethodPropertyNode(JSContext context, Object key) {
        this.context = context;
        this.key = key;
        this.isObject = IsJSObjectNode.create();
    }

    @NeverDefault
    public static CreateMethodPropertyNode create(JSContext context, Object key) {
        return CreateMethodPropertyNodeGen.create(context, key);
    }

    public abstract void executeVoid(Object object, Object value);

    @Specialization(guards = {"context.getPropertyCacheLimit() > 0", "isObject.executeBoolean(object)"})
    protected static void doCached(Object object, Object value,
                    @Cached("makeDefinePropertyCache()") PropertySetNode propertyCache) {
        propertyCache.setValue(object, value);
    }

    @Specialization(guards = {"context.getPropertyCacheLimit() == 0", "isJSObject(object)"})
    protected final void doUncached(JSDynamicObject object, Object value) {
        JSObject.defineOwnProperty(object, key, PropertyDescriptor.createData(value, false, true, true));
    }

    @Specialization(guards = "!isJSObject(object)")
    protected final void doNonObject(Object object, @SuppressWarnings("unused") Object value) {
        throw Errors.createTypeErrorNotAnObject(object, this);
    }

    @NeverDefault
    protected final PropertySetNode makeDefinePropertyCache() {
        return PropertySetNode.createImpl(key, false, context, true, true, JSAttributes.getDefaultNotEnumerable());
    }
}
