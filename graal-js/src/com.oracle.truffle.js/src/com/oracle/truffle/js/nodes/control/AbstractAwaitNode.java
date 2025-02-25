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
package com.oracle.truffle.js.nodes.control;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleStackTrace;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.JSReadFrameSlotNode;
import com.oracle.truffle.js.nodes.access.PropertyGetNode;
import com.oracle.truffle.js.nodes.access.PropertySetNode;
import com.oracle.truffle.js.nodes.arguments.AccessIndexedArgumentNode;
import com.oracle.truffle.js.nodes.function.FunctionRootNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.nodes.instrumentation.JSTags;
import com.oracle.truffle.js.nodes.promise.AsyncHandlerRootNode;
import com.oracle.truffle.js.nodes.promise.AsyncHandlerRootNode.AsyncStackTraceInfo;
import com.oracle.truffle.js.nodes.promise.AsyncRootNode;
import com.oracle.truffle.js.nodes.promise.NewPromiseCapabilityNode;
import com.oracle.truffle.js.nodes.promise.PerformPromiseThenNode;
import com.oracle.truffle.js.nodes.promise.PromiseResolveNode;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSConfig;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSFrameUtil;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.Strings;
import com.oracle.truffle.js.runtime.UserScriptException;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.builtins.JSFunctionObject;
import com.oracle.truffle.js.runtime.builtins.JSPromise;
import com.oracle.truffle.js.runtime.objects.Completion;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.PromiseCapabilityRecord;
import com.oracle.truffle.js.runtime.objects.PromiseReactionRecord;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.SimpleArrayList;

public abstract class AbstractAwaitNode extends JavaScriptNode implements ResumableNode, SuspendNode {

    protected final int stateSlot;
    @Child protected JavaScriptNode expression;
    @Child protected JSReadFrameSlotNode readAsyncResultNode;
    @Child protected JSReadFrameSlotNode readAsyncContextNode;
    @Child private NewPromiseCapabilityNode newPromiseCapabilityNode;
    @Child private PerformPromiseThenNode performPromiseThenNode;
    @Child private PromiseResolveNode promiseResolveNode;
    @Child private JSFunctionCallNode callPromiseResolveNode;
    @Child private PropertySetNode setPromiseIsHandledNode;
    @Child private PropertySetNode setAsyncContextNode;
    @Child private PropertySetNode setAsyncTargetNode;
    @Child private PropertySetNode setAsyncCallNode;
    @Child private PropertySetNode setAsyncGeneratorNode;
    protected final JSContext context;
    private final ConditionProfile asyncTypeProf = ConditionProfile.create();
    private final ConditionProfile resumptionTypeProf = ConditionProfile.create();
    private final BranchProfile saveStackBranch = BranchProfile.create();

    static final HiddenKey ASYNC_CONTEXT = new HiddenKey("AsyncContext");
    static final HiddenKey ASYNC_TARGET = new HiddenKey("AsyncTarget");
    static final HiddenKey ASYNC_GENERATOR = new HiddenKey("AsyncGenerator");
    static final HiddenKey ASYNC_CALL_NODE = new HiddenKey("AsyncCallNode");

    protected AbstractAwaitNode(JSContext context, int stateSlot, JavaScriptNode expression, JSReadFrameSlotNode readAsyncContextNode, JSReadFrameSlotNode readAsyncResultNode) {
        this.stateSlot = stateSlot;
        this.context = context;
        this.expression = expression;
        this.readAsyncResultNode = readAsyncResultNode;
        this.readAsyncContextNode = readAsyncContextNode;

        this.setAsyncContextNode = PropertySetNode.createSetHidden(ASYNC_CONTEXT, context);
        this.setAsyncTargetNode = PropertySetNode.createSetHidden(ASYNC_TARGET, context);
        this.setAsyncGeneratorNode = PropertySetNode.createSetHidden(ASYNC_GENERATOR, context);

        if (context.isOptionAsyncStackTraces() && expression != null && expression.hasTag(StandardTags.CallTag.class)) {
            this.setAsyncCallNode = PropertySetNode.createSetHidden(ASYNC_CALL_NODE, context);
        }

        this.performPromiseThenNode = PerformPromiseThenNode.create(context);
        if (context.usePromiseResolve()) {
            this.promiseResolveNode = PromiseResolveNode.create(context);
        } else {
            this.callPromiseResolveNode = JSFunctionCallNode.createCall();
        }
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        if (tag == JSTags.ControlFlowBranchTag.class || tag == JSTags.InputNodeTag.class) {
            return true;
        }
        return super.hasTag(tag);
    }

    protected final Object suspendAwait(VirtualFrame frame, Object value) {
        Object[] initialState = (Object[]) readAsyncContextNode.execute(frame);
        CallTarget resumeTarget = (CallTarget) initialState[AsyncRootNode.CALL_TARGET_INDEX];
        Object generatorOrCapability = initialState[AsyncRootNode.GENERATOR_OBJECT_OR_PROMISE_CAPABILITY_INDEX];
        MaterializedFrame asyncContext = (MaterializedFrame) initialState[AsyncRootNode.ASYNC_FRAME_INDEX];

        if (asyncTypeProf.profile(generatorOrCapability instanceof PromiseCapabilityRecord)) {
            JSDynamicObject parentPromise = ((PromiseCapabilityRecord) generatorOrCapability).getPromise();
            context.notifyPromiseHook(-1 /* parent info */, parentPromise);
        }

        JSDynamicObject promise = promiseResolve(value);
        JSFunctionObject onFulfilled = createAwaitFulfilledFunction(resumeTarget, asyncContext, generatorOrCapability);
        JSFunctionObject onRejected = createAwaitRejectedFunction(resumeTarget, asyncContext, generatorOrCapability);
        PromiseCapabilityRecord throwawayCapability = newThrowawayCapability();

        fillAsyncStackTrace(frame, onFulfilled, onRejected);
        context.notifyPromiseHook(-1 /* parent info */, promise);

        echoInput(frame, promise);
        performPromiseThenNode.execute(promise, onFulfilled, onRejected, throwawayCapability);
        throw YieldException.AWAIT_NULL; // value is ignored
    }

    private void fillAsyncStackTrace(VirtualFrame frame, JSDynamicObject onFulfilled, JSDynamicObject onRejected) {
        if (setAsyncCallNode != null) {
            setAsyncCallNode.setValue(onFulfilled, expression);
            setAsyncCallNode.setValue(onRejected, expression);
        }
        if (context.isOptionAsyncStackTraces()) {
            Object[] asyncContext = (Object[]) readAsyncContextNode.execute(frame);
            int asyncStackDepth = 0;
            if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY,
                            asyncContext[AsyncRootNode.STACK_TRACE_INDEX] == null && (asyncStackDepth = context.getLanguage().getAsyncStackDepth()) > 0)) {
                saveStackBranch.enter();
                asyncContext[AsyncRootNode.STACK_TRACE_INDEX] = captureStackTrace(this, asyncStackDepth);
            }
        }
    }

    @TruffleBoundary
    private static List<TruffleStackTraceElement> captureStackTrace(Node callNode, int asyncStackDepth) {
        List<TruffleStackTraceElement> stackTrace = TruffleStackTrace.getStackTrace(UserScriptException.create(Undefined.instance, callNode, asyncStackDepth));
        List<TruffleStackTraceElement> filteredStackTrace = new ArrayList<>();
        boolean seenThis = false;
        for (TruffleStackTraceElement s : stackTrace) {
            RootNode rootNode = s.getTarget().getRootNode();
            if (!seenThis) {
                if (rootNode == callNode.getRootNode()) {
                    seenThis = true;
                }
                continue;
            }
            if (rootNode instanceof FunctionRootNode && ((FunctionRootNode) rootNode).getFunctionData().isAsync() && !((FunctionRootNode) rootNode).getFunctionData().isGenerator()) {
                continue;
            } else if (rootNode instanceof JavaScriptRootNode && (((JavaScriptRootNode) rootNode).isFunction() || ((JavaScriptRootNode) rootNode).isResumption())) {
                filteredStackTrace.add(s);
            }
        }
        return filteredStackTrace;
    }

    private JSDynamicObject promiseResolve(Object value) {
        if (context.usePromiseResolve()) {
            return promiseResolveNode.execute(getRealm().getPromiseConstructor(), value);
        } else {
            PromiseCapabilityRecord promiseCapability = newPromiseCapability();
            Object resolve = promiseCapability.getResolve();
            callPromiseResolveNode.executeCall(JSArguments.createOneArg(Undefined.instance, resolve, value));
            return promiseCapability.getPromise();
        }
    }

    private PromiseCapabilityRecord newThrowawayCapability() {
        if (context.getEcmaScriptVersion() >= JSConfig.ECMAScript2019) {
            return null;
        }
        if (setPromiseIsHandledNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            setPromiseIsHandledNode = insert(PropertySetNode.createSetHidden(JSPromise.PROMISE_IS_HANDLED, context));
        }
        PromiseCapabilityRecord throwawayCapability = newPromiseCapability();
        setPromiseIsHandledNode.setValueBoolean(throwawayCapability.getPromise(), true);
        return throwawayCapability;
    }

    @Override
    public Object getNodeObject() {
        return JSTags.createNodeObjectDescriptor("type", JSTags.ControlFlowBranchTag.Type.Await.name());
    }

    protected final Object resumeAwait(VirtualFrame frame) {
        // We have been restored at this point. The frame contains the resumption state.
        Completion result = (Completion) readAsyncResultNode.execute(frame);
        echoInput(frame, result.getValue());
        if (resumptionTypeProf.profile(result.isNormal())) {
            return result.getValue();
        } else {
            assert result.isThrow();
            Object reason = result.getValue();
            throw UserScriptException.create(reason, this, context.getContextOptions().getStackTraceLimit());
        }
    }

    private PromiseCapabilityRecord newPromiseCapability() {
        if (newPromiseCapabilityNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            newPromiseCapabilityNode = insert(NewPromiseCapabilityNode.create(context));
        }
        return newPromiseCapabilityNode.executeDefault();
    }

    private JSFunctionObject createAwaitFulfilledFunction(CallTarget resumeTarget, MaterializedFrame asyncContext, Object generator) {
        JSFunctionData functionData = context.getOrCreateBuiltinFunctionData(JSContext.BuiltinFunctionKey.AwaitFulfilled, (c) -> createAwaitFulfilledImpl(c));
        JSFunctionObject function = JSFunction.create(getRealm(), functionData);
        setAsyncTargetNode.setValue(function, resumeTarget);
        setAsyncContextNode.setValue(function, asyncContext);
        setAsyncGeneratorNode.setValue(function, generator);
        return function;
    }

    static class AwaitSettledRootNode extends JavaScriptRootNode implements AsyncHandlerRootNode {
        @Child private JavaScriptNode valueNode;
        @Child private PropertyGetNode getAsyncTarget;
        @Child private PropertyGetNode getAsyncContext;
        @Child private PropertyGetNode getAsyncGenerator;
        @Child private AwaitResumeNode awaitResumeNode;

        AwaitSettledRootNode(JSContext context, boolean rejected) {
            this.valueNode = AccessIndexedArgumentNode.create(0);
            this.getAsyncTarget = PropertyGetNode.createGetHidden(ASYNC_TARGET, context);
            this.getAsyncContext = PropertyGetNode.createGetHidden(ASYNC_CONTEXT, context);
            this.getAsyncGenerator = PropertyGetNode.createGetHidden(ASYNC_GENERATOR, context);
            this.awaitResumeNode = AwaitResumeNode.create(rejected);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            JSDynamicObject functionObject = JSFrameUtil.getFunctionObject(frame);
            CallTarget asyncTarget = (CallTarget) getAsyncTarget.getValue(functionObject);
            Object asyncContext = getAsyncContext.getValue(functionObject);
            Object generator = getAsyncGenerator.getValue(functionObject);
            Object value = valueNode.execute(frame);
            return awaitResumeNode.execute(asyncTarget, asyncContext, generator, value);
        }

        @Override
        public AsyncStackTraceInfo getAsyncStackTraceInfo(JSFunctionObject handlerFunction) {
            assert JSFunction.isJSFunction(handlerFunction) && ((RootCallTarget) JSFunction.getFunctionData(handlerFunction).getCallTarget()).getRootNode() == this;
            RootCallTarget asyncTarget = (RootCallTarget) JSObjectUtil.getHiddenProperty(handlerFunction, ASYNC_TARGET);
            if (asyncTarget.getRootNode() instanceof AsyncRootNode) {
                MaterializedFrame asyncContextFrame = (MaterializedFrame) JSObjectUtil.getHiddenProperty(handlerFunction, ASYNC_CONTEXT);
                Node callNode = (Node) JSObjectUtil.getHiddenProperty(handlerFunction, AbstractAwaitNode.ASYNC_CALL_NODE);
                TruffleStackTraceElement asyncStackTraceElement = TruffleStackTraceElement.create(callNode, asyncTarget, asyncContextFrame);
                JSDynamicObject asyncPromise = ((AsyncRootNode) asyncTarget.getRootNode()).getAsyncFunctionPromise(asyncContextFrame);
                return new AsyncStackTraceInfo(asyncPromise, asyncStackTraceElement);
            }
            return new AsyncStackTraceInfo();
        }
    }

    private static JSFunctionData createAwaitFulfilledImpl(JSContext context) {
        class AwaitFulfilledRootNode extends AwaitSettledRootNode {
            AwaitFulfilledRootNode() {
                super(context, false);
            }
        }
        return JSFunctionData.createCallOnly(context, new AwaitFulfilledRootNode().getCallTarget(), 1, Strings.EMPTY_STRING);
    }

    private JSFunctionObject createAwaitRejectedFunction(CallTarget resumeTarget, MaterializedFrame asyncContext, Object generator) {
        JSFunctionData functionData = context.getOrCreateBuiltinFunctionData(JSContext.BuiltinFunctionKey.AwaitRejected, (c) -> createAwaitRejectedImpl(c));
        JSFunctionObject function = JSFunction.create(getRealm(), functionData);
        setAsyncTargetNode.setValue(function, resumeTarget);
        setAsyncContextNode.setValue(function, asyncContext);
        setAsyncGeneratorNode.setValue(function, generator);
        return function;
    }

    private static JSFunctionData createAwaitRejectedImpl(JSContext context) {
        class AwaitRejectedRootNode extends AwaitSettledRootNode {
            AwaitRejectedRootNode() {
                super(context, true);
            }
        }
        return JSFunctionData.createCallOnly(context, new AwaitRejectedRootNode().getCallTarget(), 1, Strings.EMPTY_STRING);
    }

    @SuppressWarnings("unused")
    protected void echoInput(VirtualFrame frame, Object value) {
    }

    public static List<TruffleStackTraceElement> findAsyncStackFramesFromPromise(JSDynamicObject promise) {
        List<TruffleStackTraceElement> stackTrace = new ArrayList<>(4);
        collectAsyncStackFramesFromPromise(promise, stackTrace);
        return stackTrace;
    }

    private static void collectAsyncStackFramesFromPromise(JSDynamicObject startPromise, List<TruffleStackTraceElement> stackTrace) {
        JSDynamicObject nextPromise = startPromise;
        do {
            JSDynamicObject currPromise = nextPromise;
            nextPromise = null;

            Object fulfillReactions = null;
            if (JSPromise.isPending(currPromise)) {
                // only pending promises have reactions
                fulfillReactions = JSObjectUtil.getHiddenProperty(currPromise, JSPromise.PROMISE_FULFILL_REACTIONS);
            }
            if (fulfillReactions instanceof SimpleArrayList<?> && ((SimpleArrayList<?>) fulfillReactions).size() == 1) {
                SimpleArrayList<?> fulfillList = (SimpleArrayList<?>) fulfillReactions;
                PromiseReactionRecord reaction = (PromiseReactionRecord) fulfillList.get(0);
                Object handler = reaction.getHandler();
                if (JSFunction.isJSFunction(handler)) {
                    JSFunctionObject handlerFunction = (JSFunctionObject) handler;
                    RootNode rootNode = ((RootCallTarget) JSFunction.getCallTarget(handlerFunction)).getRootNode();
                    if (rootNode instanceof AsyncHandlerRootNode) {
                        AsyncStackTraceInfo result = ((AsyncHandlerRootNode) rootNode).getAsyncStackTraceInfo(handlerFunction);
                        if (result.stackTraceElement != null) {
                            stackTrace.add(result.stackTraceElement);
                        }
                        nextPromise = result.promise;
                        continue;
                    }
                }
                if (reaction.getCapability() != null) {
                    // follow the promise chain
                    nextPromise = reaction.getCapability().getPromise();
                    continue;
                }
            }
        } while (nextPromise != null);
    }

    public static List<TruffleStackTraceElement> findAsyncStackFramesFromHandler(JSFunctionObject handlerFunction) {
        List<TruffleStackTraceElement> stackTrace = new ArrayList<>(4);
        RootNode rootNode = ((RootCallTarget) JSFunction.getCallTarget(handlerFunction)).getRootNode();
        if (rootNode instanceof AsyncHandlerRootNode) {
            AsyncStackTraceInfo result = ((AsyncHandlerRootNode) rootNode).getAsyncStackTraceInfo(handlerFunction);
            JSDynamicObject promise = result.promise;
            if (promise != null) {
                collectAsyncStackFramesFromPromise(promise, stackTrace);
            }
        }
        return stackTrace;
    }
}
