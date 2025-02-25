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

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.js.nodes.NodeFactory;
import com.oracle.truffle.js.runtime.JSConfig;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.Strings;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.builtins.Builtin;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.objects.JSAttributes;

public final class JSBuiltin implements Builtin, JSFunctionData.CallTargetInitializer {
    private final TruffleString name;
    private final TruffleString fullName;
    private final Object key;
    private final int length;
    private final byte attributeFlags;
    private final byte ecmaScriptVersion;
    private final boolean annexB;

    private final BuiltinNodeFactory functionNodeFactory;
    private final BuiltinNodeFactory constructorNodeFactory;
    private final BuiltinNodeFactory newTargetConstructorNodeFactory;

    private static final int GETTER_FLAG = 1 << 3;
    private static final int SETTER_FLAG = 1 << 4;

    public JSBuiltin(TruffleString containerName, TruffleString functionName, Object key, int length, int attributeFlags, int ecmaScriptVersion,
                    boolean annexB, BuiltinNodeFactory functionNodeFactory, BuiltinNodeFactory constructorNodeFactory, BuiltinNodeFactory newTargetConstructorFactory) {
        assert isAllowedKey(key);
        assert (byte) ecmaScriptVersion == ecmaScriptVersion && (byte) attributeFlags == attributeFlags;
        this.name = functionName;
        this.fullName = (containerName == null) ? name : Strings.concatAll(containerName, Strings.DOT, name);
        this.key = key;
        this.length = length;
        this.ecmaScriptVersion = (byte) ecmaScriptVersion;
        this.attributeFlags = (byte) (attributeFlags | detectAccessor(functionName));
        this.annexB = annexB;
        this.functionNodeFactory = functionNodeFactory;
        this.constructorNodeFactory = constructorNodeFactory;
        this.newTargetConstructorNodeFactory = newTargetConstructorFactory;
    }

    public JSBuiltin(TruffleString containerName, TruffleString name, int length, int flags, BuiltinNodeFactory functionNodeFactory) {
        this(containerName, name, name, length, flags, 5, false, functionNodeFactory, null, null);
    }

    /**
     * Returns the simple name of the built-in. A simple name is the name of the corresponding
     * function, i.e., {@code sort} for {@code Array.prototype.sort} built-in.
     *
     * @return simple name of the built-in.
     */
    @Override
    public TruffleString getName() {
        return name;
    }

    /**
     * Returns the "fully-qualified" name of the built-in. The "fully-qualified" name contains also
     * the name of the owner/holder of the built-in ({@code Array.prototype.sort}, for example).
     *
     * @return "fully-qualified" name of the built-in.
     */
    public TruffleString getFullName() {
        return fullName;
    }

    @Override
    public Object getKey() {
        return key;
    }

    @Override
    public int getLength() {
        return length;
    }

    public boolean isConstructor() {
        return constructorNodeFactory != null;
    }

    public boolean hasSeparateConstructor() {
        return isConstructor() && constructorNodeFactory != functionNodeFactory;
    }

    public boolean hasNewTargetConstructor() {
        return isConstructor() && newTargetConstructorNodeFactory != null;
    }

    @Override
    public int getECMAScriptVersion() {
        return Math.max(5, ecmaScriptVersion);
    }

    @Override
    public boolean isAnnexB() {
        return annexB;
    }

    @Override
    public int getAttributeFlags() {
        return (attributeFlags & JSAttributes.ATTRIBUTES_MASK);
    }

    @Override
    public boolean isConfigurable() {
        return (attributeFlags & JSAttributes.NOT_CONFIGURABLE) == 0;
    }

    @Override
    public boolean isWritable() {
        return (attributeFlags & JSAttributes.NOT_WRITABLE) == 0;
    }

    @Override
    public boolean isEnumerable() {
        return (attributeFlags & JSAttributes.NOT_ENUMERABLE) == 0;
    }

    @Override
    public boolean isGetter() {
        return (attributeFlags & GETTER_FLAG) != 0;
    }

    @Override
    public boolean isSetter() {
        return (attributeFlags & SETTER_FLAG) != 0;
    }

    public static SourceSection getSourceSection() {
        return createSourceSection();
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return "JSBuiltin [name=" + name + ", length=" + length + "]";
    }

    private static boolean isAllowedKey(Object key) {
        if (key instanceof Symbol) {
            return true;
        } else if (Strings.isTString(key)) {
            // "get $_" is needed for JSRegExp
            TruffleString tsk = (TruffleString) key;
            return !Strings.isEmpty(tsk) && (!Strings.endsWith(tsk, Strings.UNDERSCORE) || Strings.equals(tsk, Strings.$_)) && !Strings.startsWith(tsk, Strings.UNDERSCORE) ||
                            (Strings.startsWith(tsk, Strings.UNDERSCORE_2) && Strings.endsWith(tsk, Strings.UNDERSCORE_2));
        }
        return false;
    }

    private static int detectAccessor(TruffleString functionName) {
        if (Strings.startsWith(functionName, Strings.GET_SPC)) {
            return GETTER_FLAG;
        } else if (Strings.startsWith(functionName, Strings.SET_SPC)) {
            return SETTER_FLAG;
        }
        return 0;
    }

    @Override
    public JSFunctionData createFunctionData(JSContext context) {
        JSFunctionData cached = context.getBuiltinFunctionData(this);
        if (cached != null) {
            return cached;
        }

        JSFunctionData functionData = JSFunctionData.create(context, getLength(), getName(), isConstructor(), false, false, true);

        if (JSConfig.LazyFunctionData) {
            functionData.setLazyInit(this);
        } else {
            initializeRoot(functionData);
            initializeCallTargets(functionData);
        }

        context.putBuiltinFunctionData(this, functionData);
        return functionData;
    }

    JSBuiltinNode createNode(JSContext context, boolean construct, boolean newTarget) {
        JSBuiltinNode builtinNode = createNodeImpl(context, construct, newTarget);
        builtinNode.construct = construct;
        builtinNode.newTarget = newTarget;
        return builtinNode;
    }

    private JSBuiltinNode createNodeImpl(JSContext context, boolean construct, boolean newTarget) {
        if (newTarget && newTargetConstructorNodeFactory != null) {
            return newTargetConstructorNodeFactory.createNode(context, this);
        } else if (construct && constructorNodeFactory != null) {
            return constructorNodeFactory.createNode(context, this);
        } else {
            return functionNodeFactory.createNode(context, this);
        }
    }

    public static SourceSection createSourceSection() {
        return JSFunction.BUILTIN_SOURCE_SECTION;
    }

    private static void initializeFunctionData(JSFunctionData functionData, JSBuiltin builtin) {
        JSContext context = functionData.getContext();
        JSBuiltinNode functionRoot = JSBuiltinNode.createBuiltin(context, builtin, false, false);
        FrameDescriptor frameDescriptor = null;
        FunctionRootNode callRoot = FunctionRootNode.create(functionRoot, frameDescriptor, functionData, getSourceSection(), builtin.getFullName());
        // Note: there's no lock being held here; first thread to set the root node wins.
        // Built-in nodes are relatively cheap to create, so creating throwaway nodes is fine.
        functionData.setRootNode(callRoot);
    }

    private static void initializeFunctionDataCallTarget(JSFunctionData functionData, JSBuiltin builtin, JSFunctionData.Target target, CallTarget callTarget) {
        JSContext context = functionData.getContext();
        NodeFactory factory = NodeFactory.getDefaultInstance();
        FrameDescriptor frameDescriptor = null;
        if (target == JSFunctionData.Target.Call) {
            functionData.setCallTarget(callTarget);
        } else if (target == JSFunctionData.Target.Construct) {
            RootNode constructRoot;
            if (builtin.hasSeparateConstructor()) {
                JSBuiltinNode constructNode = JSBuiltinNode.createBuiltin(context, builtin, true, false);
                constructRoot = FunctionRootNode.create(constructNode, frameDescriptor, functionData, getSourceSection(), builtin.getFullName());
            } else {
                constructRoot = factory.createConstructorRootNode(functionData, callTarget, false);
            }
            functionData.setConstructTarget(constructRoot.getCallTarget());
        } else if (target == JSFunctionData.Target.ConstructNewTarget) {
            JavaScriptRootNode constructNewTargetRoot;
            if (builtin.hasNewTargetConstructor()) {
                AbstractBodyNode constructNewTargetNode = JSBuiltinNode.createBuiltin(context, builtin, true, true);
                constructNewTargetRoot = FunctionRootNode.create(constructNewTargetNode, frameDescriptor, functionData, getSourceSection(), builtin.getFullName());
            } else {
                CallTarget constructTarget = functionData.getConstructTarget();
                constructNewTargetRoot = factory.createDropNewTarget(functionData.getContext(), constructTarget);
            }
            functionData.setConstructNewTarget(constructNewTargetRoot.getCallTarget());
        }
    }

    @Override
    public void initializeRoot(JSFunctionData functionData) {
        initializeFunctionData(functionData, this);
    }

    @Override
    public void initializeCallTarget(JSFunctionData functionData, JSFunctionData.Target target, CallTarget callTarget) {
        initializeFunctionDataCallTarget(functionData, this, target, callTarget);
    }
}
