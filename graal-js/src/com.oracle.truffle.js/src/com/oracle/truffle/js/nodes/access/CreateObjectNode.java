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
package com.oracle.truffle.js.nodes.access;

import java.util.Set;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Executed;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.CreateObjectNodeFactory.CreateObjectWithCachedPrototypeNodeGen;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.Properties;
import com.oracle.truffle.js.runtime.builtins.JSClass;
import com.oracle.truffle.js.runtime.builtins.JSDictionary;
import com.oracle.truffle.js.runtime.builtins.JSOrdinary;
import com.oracle.truffle.js.runtime.builtins.JSPromise;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.Null;

public abstract class CreateObjectNode extends JavaScriptBaseNode {
    protected final JSContext context;

    protected CreateObjectNode(JSContext context) {
        this.context = context;
    }

    @NeverDefault
    public static CreateObjectNode create(JSContext context) {
        return new CreateOrdinaryObjectNode(context);
    }

    @NeverDefault
    public static CreateObjectWithPrototypeNode createOrdinaryWithPrototype(JSContext context) {
        return createWithPrototype(context, null, JSOrdinary.INSTANCE);
    }

    public static CreateObjectWithPrototypeNode createOrdinaryWithPrototype(JSContext context, JavaScriptNode prototypeExpression) {
        return createWithPrototype(context, prototypeExpression, JSOrdinary.INSTANCE);
    }

    public static CreateObjectWithPrototypeNode createWithPrototype(JSContext context, JavaScriptNode prototypeExpression, JSClass jsclass) {
        return CreateObjectWithCachedPrototypeNode.create(context, prototypeExpression, jsclass);
    }

    static CreateObjectNode createDictionary(JSContext context) {
        return new CreateDictionaryObjectNode(context);
    }

    public JSObject execute(VirtualFrame frame) {
        return executeWithRealm(frame, getRealm());
    }

    public abstract JSObject executeWithRealm(VirtualFrame frame, JSRealm realm);

    protected abstract CreateObjectNode copyUninitialized(Set<Class<? extends Tag>> materializedTags);

    final JSContext getContext() {
        return context;
    }

    private static class CreateOrdinaryObjectNode extends CreateObjectNode {
        protected CreateOrdinaryObjectNode(JSContext context) {
            super(context);
        }

        @Override
        public JSObject executeWithRealm(VirtualFrame frame, JSRealm realm) {
            return JSOrdinary.create(context, realm);
        }

        @Override
        protected CreateObjectNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return new CreateOrdinaryObjectNode(context);
        }
    }

    public abstract static class CreateObjectWithPrototypeNode extends CreateObjectNode {
        @Child @Executed protected JavaScriptNode prototypeExpression;

        protected CreateObjectWithPrototypeNode(JSContext context, JavaScriptNode prototypeExpression) {
            super(context);
            this.prototypeExpression = prototypeExpression;
        }

        public abstract JSObject execute(JSDynamicObject prototype);

        @Override
        public final JSObject executeWithRealm(VirtualFrame frame, JSRealm realm) {
            return execute(frame);
        }

        @Override
        protected abstract CreateObjectWithPrototypeNode copyUninitialized(Set<Class<? extends Tag>> materializedTags);
    }

    protected abstract static class CreateObjectWithCachedPrototypeNode extends CreateObjectWithPrototypeNode {
        protected final JSClass jsclass;

        protected CreateObjectWithCachedPrototypeNode(JSContext context, JavaScriptNode prototypeExpression, JSClass jsclass) {
            super(context, prototypeExpression);
            this.jsclass = jsclass;
            assert isOrdinaryObject() || isPromiseObject();
        }

        protected static CreateObjectWithPrototypeNode create(JSContext context, JavaScriptNode prototypeExpression, JSClass jsclass) {
            return CreateObjectWithCachedPrototypeNodeGen.create(context, prototypeExpression, jsclass);
        }

        @Specialization(guards = {"!context.isMultiContext()", "isValidPrototype(cachedPrototype)", "prototype == cachedPrototype"}, limit = "1")
        final JSObject doCachedPrototype(@SuppressWarnings("unused") JSDynamicObject prototype,
                        @Cached("prototype") @SuppressWarnings("unused") JSDynamicObject cachedPrototype,
                        @Cached("getProtoChildShape(cachedPrototype)") Shape protoChildShape) {
            if (isPromiseObject()) {
                return JSPromise.create(context, protoChildShape);
            } else if (isOrdinaryObject()) {
                return JSOrdinary.create(context, protoChildShape);
            } else {
                throw Errors.unsupported("unsupported object type");
            }
        }

        @Specialization(guards = {"isOrdinaryObject()", "isValidPrototype(prototype)"}, replaces = "doCachedPrototype")
        final JSObject doOrdinaryInstancePrototype(JSDynamicObject prototype,
                        @CachedLibrary(limit = "3") @Shared("setProtoNode") DynamicObjectLibrary setProtoNode) {
            JSObject object = JSOrdinary.createWithoutPrototype(context);
            Properties.put(setProtoNode, object, JSObject.HIDDEN_PROTO, prototype);
            return object;
        }

        @Specialization(guards = {"isPromiseObject()", "isValidPrototype(prototype)"}, replaces = "doCachedPrototype")
        final JSObject doPromiseInstancePrototype(JSDynamicObject prototype,
                        @CachedLibrary(limit = "3") @Shared("setProtoNode") DynamicObjectLibrary setProtoNode) {
            JSObject object = JSPromise.createWithoutPrototype(context);
            Properties.put(setProtoNode, object, JSObject.HIDDEN_PROTO, prototype);
            return object;
        }

        @Specialization(guards = {"isOrdinaryObject() || isPromiseObject()", "!isValidPrototype(prototype)"})
        final JSObject doNotJSObjectOrNull(@SuppressWarnings("unused") Object prototype) {
            return JSOrdinary.create(context, getRealm());
        }

        @NeverDefault
        final Shape getProtoChildShape(JSDynamicObject prototype) {
            return prototype == Null.instance ? context.getEmptyShapeNullPrototype() : JSObjectUtil.getProtoChildShape(prototype, jsclass, context);
        }

        @Idempotent
        final boolean isOrdinaryObject() {
            return jsclass == JSOrdinary.INSTANCE;
        }

        @Idempotent
        final boolean isPromiseObject() {
            return jsclass == JSPromise.INSTANCE;
        }

        @Override
        protected CreateObjectWithPrototypeNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return create(context, JavaScriptNode.cloneUninitialized(prototypeExpression, materializedTags), jsclass);
        }
    }

    private static class CreateDictionaryObjectNode extends CreateObjectNode {
        protected CreateDictionaryObjectNode(JSContext context) {
            super(context);
        }

        @Override
        public JSObject executeWithRealm(VirtualFrame frame, JSRealm realm) {
            return JSDictionary.create(context, realm);
        }

        @Override
        protected CreateObjectNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
            return new CreateDictionaryObjectNode(context);
        }
    }
}
