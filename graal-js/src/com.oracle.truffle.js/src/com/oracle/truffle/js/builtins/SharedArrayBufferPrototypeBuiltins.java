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
package com.oracle.truffle.js.builtins;

import java.nio.ByteBuffer;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.js.builtins.ArrayBufferPrototypeBuiltins.JSArrayBufferAbstractSliceNode;
import com.oracle.truffle.js.builtins.SharedArrayBufferPrototypeBuiltinsFactory.ByteLengthGetterNodeGen;
import com.oracle.truffle.js.builtins.SharedArrayBufferPrototypeBuiltinsFactory.JSSharedArrayBufferSliceNodeGen;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.runtime.Boundaries;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.builtins.BuiltinEnum;
import com.oracle.truffle.js.runtime.builtins.JSArrayBuffer;
import com.oracle.truffle.js.runtime.builtins.JSSharedArrayBuffer;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;

/**
 * Contains builtins for {@linkplain JSSharedArrayBuffer}.prototype.
 */
public final class SharedArrayBufferPrototypeBuiltins extends JSBuiltinsContainer.SwitchEnum<SharedArrayBufferPrototypeBuiltins.SharedArrayBufferPrototype> {

    public static final JSBuiltinsContainer BUILTINS = new SharedArrayBufferPrototypeBuiltins();

    protected SharedArrayBufferPrototypeBuiltins() {
        super(JSSharedArrayBuffer.PROTOTYPE_NAME, SharedArrayBufferPrototype.class);
    }

    public enum SharedArrayBufferPrototype implements BuiltinEnum<SharedArrayBufferPrototype> {
        byteLength(0),
        slice(2);

        private final int length;

        SharedArrayBufferPrototype(int length) {
            this.length = length;
        }

        @Override
        public int getLength() {
            return length;
        }

        @Override
        public boolean isGetter() {
            return this == byteLength;
        }

    }

    @Override
    protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, SharedArrayBufferPrototype builtinEnum) {
        switch (builtinEnum) {
            case byteLength:
                return ByteLengthGetterNodeGen.create(context, builtin, args().withThis().createArgumentNodes(context));
            case slice:
                return JSSharedArrayBufferSliceNodeGen.create(context, builtin, args().withThis().fixedArgs(2).createArgumentNodes(context));
        }
        return null;
    }

    public abstract static class JSSharedArrayBufferSliceNode extends JSArrayBufferAbstractSliceNode {

        public JSSharedArrayBufferSliceNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        private JSDynamicObject constructNewSharedArrayBuffer(JSDynamicObject thisObj, int newLen) {
            JSDynamicObject defaultConstructor = getRealm().getSharedArrayBufferConstructor();
            JSDynamicObject constr = getArraySpeciesConstructorNode().speciesConstructor(thisObj, defaultConstructor);
            return (JSDynamicObject) getArraySpeciesConstructorNode().construct(constr, newLen);
        }

        private void checkErrors(JSDynamicObject resObj, JSDynamicObject thisObj, int newLen, InlinedBranchProfile errorBranch) {
            if (!JSSharedArrayBuffer.isJSSharedArrayBuffer(resObj)) {
                errorBranch.enter(this);
                throw Errors.createTypeError("SharedArrayBuffer expected");
            }
            if (resObj == thisObj) {
                errorBranch.enter(this);
                throw Errors.createTypeError("SameValue(new, O) is forbidden");
            }
            if (JSSharedArrayBuffer.getDirectByteBuffer(resObj).capacity() < newLen) {
                errorBranch.enter(this);
                throw Errors.createTypeError("insufficient length constructed");
            }
        }

        @Specialization(guards = "isJSSharedArrayBuffer(thisObj)")
        protected JSDynamicObject sliceSharedIntInt(JSDynamicObject thisObj, int begin, int end,
                        @Cached @Cached.Shared("errorBranch") InlinedBranchProfile errorBranch) {
            ByteBuffer byteBuffer = JSSharedArrayBuffer.getDirectByteBuffer(thisObj);
            int byteLength = JSArrayBuffer.getDirectByteLength(thisObj);
            int clampedBegin = clampIndex(begin, 0, byteLength);
            int clampedEnd = clampIndex(end, clampedBegin, byteLength);
            int newLen = clampedEnd - clampedBegin;

            JSDynamicObject resObj = constructNewSharedArrayBuffer(thisObj, newLen);
            checkErrors(resObj, thisObj, newLen, errorBranch);

            ByteBuffer resBuffer = JSArrayBuffer.getDirectByteBuffer(resObj);
            Boundaries.byteBufferPutSlice(resBuffer, 0, byteBuffer, clampedBegin, clampedEnd);
            return resObj;
        }

        @Specialization(guards = "isJSSharedArrayBuffer(thisObj)", replaces = "sliceSharedIntInt")
        protected JSDynamicObject sliceShared(JSDynamicObject thisObj, Object begin0, Object end0,
                        @Cached @Cached.Shared("errorBranch") InlinedBranchProfile errorBranch) {
            int len = JSSharedArrayBuffer.getDirectByteBuffer(thisObj).capacity();
            int begin = getStart(begin0, len);
            int end = getEnd(end0, len);
            return sliceSharedIntInt(thisObj, begin, end, errorBranch);
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isJSSharedArrayBuffer(thisObj)")
        protected static JSDynamicObject error(Object thisObj, Object begin0, Object end0) {
            throw Errors.createTypeErrorIncompatibleReceiver(thisObj);
        }
    }

    public abstract static class ByteLengthGetterNode extends JSBuiltinNode {

        public ByteLengthGetterNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization(guards = "isJSSharedArrayBuffer(thisObj)")
        protected static int sharedArrayBuffer(Object thisObj) {
            return JSArrayBuffer.getDirectByteLength(thisObj);
        }

        @Specialization(guards = "!isJSSharedArrayBuffer(thisObj)")
        protected static int error(Object thisObj) {
            throw Errors.createTypeErrorIncompatibleReceiver(thisObj);
        }
    }
}
