/*
 * Copyright (c) 2021, 2023, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.js.builtins.temporal;

import static com.oracle.truffle.js.runtime.util.TemporalConstants.AUTO;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.CALENDAR;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.DAY;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.MONTH;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.OVERFLOW;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.REJECT;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.TIME_ZONE;
import static com.oracle.truffle.js.runtime.util.TemporalConstants.TRUNC;

import java.util.EnumSet;
import java.util.List;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.js.builtins.JSBuiltinsContainer;
import com.oracle.truffle.js.builtins.temporal.TemporalPlainYearMonthPrototypeBuiltinsFactory.JSTemporalPlainYearMonthAddNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalPlainYearMonthPrototypeBuiltinsFactory.JSTemporalPlainYearMonthEqualsNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalPlainYearMonthPrototypeBuiltinsFactory.JSTemporalPlainYearMonthGetISOFieldsNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalPlainYearMonthPrototypeBuiltinsFactory.JSTemporalPlainYearMonthGetterNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalPlainYearMonthPrototypeBuiltinsFactory.JSTemporalPlainYearMonthSinceNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalPlainYearMonthPrototypeBuiltinsFactory.JSTemporalPlainYearMonthSubtractNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalPlainYearMonthPrototypeBuiltinsFactory.JSTemporalPlainYearMonthToLocaleStringNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalPlainYearMonthPrototypeBuiltinsFactory.JSTemporalPlainYearMonthToPlainDateNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalPlainYearMonthPrototypeBuiltinsFactory.JSTemporalPlainYearMonthToStringNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalPlainYearMonthPrototypeBuiltinsFactory.JSTemporalPlainYearMonthUntilNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalPlainYearMonthPrototypeBuiltinsFactory.JSTemporalPlainYearMonthValueOfNodeGen;
import com.oracle.truffle.js.builtins.temporal.TemporalPlainYearMonthPrototypeBuiltinsFactory.JSTemporalPlainYearMonthWithNodeGen;
import com.oracle.truffle.js.nodes.access.EnumerableOwnPropertyNamesNode;
import com.oracle.truffle.js.nodes.cast.JSToBooleanNode;
import com.oracle.truffle.js.nodes.cast.JSToIntegerThrowOnInfinityNode;
import com.oracle.truffle.js.nodes.cast.JSToNumberNode;
import com.oracle.truffle.js.nodes.cast.JSToStringNode;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.nodes.temporal.TemporalCalendarDateFromFieldsNode;
import com.oracle.truffle.js.nodes.temporal.TemporalCalendarFieldsNode;
import com.oracle.truffle.js.nodes.temporal.TemporalCalendarGetterNode;
import com.oracle.truffle.js.nodes.temporal.TemporalGetOptionNode;
import com.oracle.truffle.js.nodes.temporal.TemporalRoundDurationNode;
import com.oracle.truffle.js.nodes.temporal.TemporalYearMonthFromFieldsNode;
import com.oracle.truffle.js.nodes.temporal.ToLimitedTemporalDurationNode;
import com.oracle.truffle.js.nodes.temporal.ToTemporalYearMonthNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.builtins.BuiltinEnum;
import com.oracle.truffle.js.runtime.builtins.JSOrdinary;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalDuration;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalDurationObject;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalDurationRecord;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalPlainDateObject;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalPlainYearMonth;
import com.oracle.truffle.js.runtime.builtins.temporal.JSTemporalPlainYearMonthObject;
import com.oracle.truffle.js.runtime.objects.JSDynamicObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.TemporalConstants;
import com.oracle.truffle.js.runtime.util.TemporalErrors;
import com.oracle.truffle.js.runtime.util.TemporalUtil;
import com.oracle.truffle.js.runtime.util.TemporalUtil.RoundingMode;
import com.oracle.truffle.js.runtime.util.TemporalUtil.ShowCalendar;
import com.oracle.truffle.js.runtime.util.TemporalUtil.Unit;

public class TemporalPlainYearMonthPrototypeBuiltins extends JSBuiltinsContainer.SwitchEnum<TemporalPlainYearMonthPrototypeBuiltins.TemporalPlainYearMonthPrototype> {

    public static final JSBuiltinsContainer BUILTINS = new TemporalPlainYearMonthPrototypeBuiltins();

    protected TemporalPlainYearMonthPrototypeBuiltins() {
        super(JSTemporalPlainYearMonth.PROTOTYPE_NAME, TemporalPlainYearMonthPrototype.class);
    }

    public enum TemporalPlainYearMonthPrototype implements BuiltinEnum<TemporalPlainYearMonthPrototype> {
        // getters
        calendar(0),
        year(0),
        month(0),
        monthCode(0),
        daysInMonth(0),
        daysInYear(0),
        monthsInYear(0),
        inLeapYear(0),

        // methods
        with(1),
        add(1),
        subtract(1),
        until(1),
        since(1),
        equals(1),
        toString(0),
        toLocaleString(0),
        toJSON(0),
        valueOf(0),
        toPlainDate(1),
        getISOFields(0);

        private final int length;

        TemporalPlainYearMonthPrototype(int length) {
            this.length = length;
        }

        @Override
        public int getLength() {
            return length;
        }

        @Override
        public boolean isGetter() {
            return EnumSet.of(calendar, year, month, monthCode, daysInMonth, daysInYear, monthsInYear, inLeapYear).contains(this);
        }

    }

    @Override
    protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, TemporalPlainYearMonthPrototype builtinEnum) {
        switch (builtinEnum) {
            case calendar:
            case year:
            case month:
            case monthCode:
            case daysInMonth:
            case daysInYear:
            case monthsInYear:
            case inLeapYear:
                return JSTemporalPlainYearMonthGetterNodeGen.create(context, builtin, builtinEnum, args().withThis().createArgumentNodes(context));
            case add:
                return JSTemporalPlainYearMonthAddNodeGen.create(context, builtin, args().withThis().fixedArgs(2).createArgumentNodes(context));
            case subtract:
                return JSTemporalPlainYearMonthSubtractNodeGen.create(context, builtin, args().withThis().fixedArgs(2).createArgumentNodes(context));
            case until:
                return JSTemporalPlainYearMonthUntilNodeGen.create(context, builtin, args().withThis().fixedArgs(2).createArgumentNodes(context));
            case since:
                return JSTemporalPlainYearMonthSinceNodeGen.create(context, builtin, args().withThis().fixedArgs(2).createArgumentNodes(context));
            case with:
                return JSTemporalPlainYearMonthWithNodeGen.create(context, builtin, args().withThis().fixedArgs(2).createArgumentNodes(context));
            case equals:
                return JSTemporalPlainYearMonthEqualsNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case toPlainDate:
                return JSTemporalPlainYearMonthToPlainDateNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case getISOFields:
                return JSTemporalPlainYearMonthGetISOFieldsNodeGen.create(context, builtin, args().withThis().createArgumentNodes(context));
            case toString:
                return JSTemporalPlainYearMonthToStringNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case toLocaleString:
            case toJSON:
                return JSTemporalPlainYearMonthToLocaleStringNodeGen.create(context, builtin, args().withThis().createArgumentNodes(context));
            case valueOf:
                return JSTemporalPlainYearMonthValueOfNodeGen.create(context, builtin, args().withThis().createArgumentNodes(context));
        }
        return null;
    }

    public abstract static class JSTemporalPlainYearMonthGetterNode extends JSBuiltinNode {

        protected final TemporalPlainYearMonthPrototype property;

        protected JSTemporalPlainYearMonthGetterNode(JSContext context, JSBuiltin builtin, TemporalPlainYearMonthPrototype property) {
            super(context, builtin);
            this.property = property;
        }

        @Specialization
        protected Object dateGetter(JSTemporalPlainYearMonthObject temporalYM,
                        @Cached("create(getContext())") TemporalCalendarGetterNode calendarGetterNode) {
            switch (property) {
                case calendar:
                    return temporalYM.getCalendar();
                case year:
                    return TemporalUtil.calendarYear(calendarGetterNode, temporalYM.getCalendar(), temporalYM);
                case month:
                    return TemporalUtil.calendarMonth(calendarGetterNode, temporalYM.getCalendar(), temporalYM);
                case monthCode:
                    return TemporalUtil.calendarMonthCode(calendarGetterNode, temporalYM.getCalendar(), temporalYM);
                case daysInYear:
                    return TemporalUtil.calendarDaysInYear(calendarGetterNode, temporalYM.getCalendar(), temporalYM);
                case daysInMonth:
                    return TemporalUtil.calendarDaysInMonth(calendarGetterNode, temporalYM.getCalendar(), temporalYM);
                case monthsInYear:
                    return TemporalUtil.calendarMonthsInYear(calendarGetterNode, temporalYM.getCalendar(), temporalYM);
                case inLeapYear:
                    return TemporalUtil.calendarInLeapYear(calendarGetterNode, temporalYM.getCalendar(), temporalYM);
            }
            throw Errors.shouldNotReachHere();
        }

        @Specialization(guards = "!isJSTemporalYearMonth(thisObj)")
        protected static Object invalidReceiver(@SuppressWarnings("unused") Object thisObj) {
            throw TemporalErrors.createTypeErrorTemporalPlainYearMonthExpected();
        }
    }

    // 4.3.20
    public abstract static class JSTemporalPlainYearMonthToString extends JSTemporalBuiltinOperation {

        protected JSTemporalPlainYearMonthToString(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected TruffleString toString(JSTemporalPlainYearMonthObject yearMonth, Object optParam,
                        @Cached TruffleString.EqualNode equalNode,
                        @Cached TemporalGetOptionNode getOptionNode,
                        @Cached InlinedBranchProfile errorBranch,
                        @Cached InlinedConditionProfile optionUndefined) {
            JSDynamicObject options = getOptionsObject(optParam, this, errorBranch, optionUndefined);
            ShowCalendar showCalendar = TemporalUtil.toShowCalendarOption(options, getOptionNode, equalNode);
            return JSTemporalPlainYearMonth.temporalYearMonthToString(yearMonth, showCalendar);
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isJSTemporalYearMonth(thisObj)")
        protected static Object invalidReceiver(Object thisObj, Object optParam) {
            throw TemporalErrors.createTypeErrorTemporalPlainYearMonthExpected();
        }
    }

    public abstract static class JSTemporalPlainYearMonthToLocaleString extends JSTemporalBuiltinOperation {

        protected JSTemporalPlainYearMonthToLocaleString(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected TruffleString toLocaleString(JSTemporalPlainYearMonthObject yearMonth) {
            return JSTemporalPlainYearMonth.temporalYearMonthToString(yearMonth, ShowCalendar.AUTO);
        }

        @Specialization(guards = "!isJSTemporalYearMonth(thisObj)")
        protected static Object invalidReceiver(@SuppressWarnings("unused") Object thisObj) {
            throw TemporalErrors.createTypeErrorTemporalPlainYearMonthExpected();
        }
    }

    public abstract static class JSTemporalPlainYearMonthValueOf extends JSTemporalBuiltinOperation {

        protected JSTemporalPlainYearMonthValueOf(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object valueOf(@SuppressWarnings("unused") Object thisObj) {
            throw Errors.createTypeError("Not supported.");
        }
    }

    public abstract static class JSTemporalPlainYearMonthToPlainDateNode extends JSTemporalBuiltinOperation {

        protected JSTemporalPlainYearMonthToPlainDateNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected JSTemporalPlainDateObject toPlainDate(JSTemporalPlainYearMonthObject yearMonth, Object item,
                        @Cached("createKeys(getContext())") EnumerableOwnPropertyNamesNode namesNode,
                        @Cached("create(getContext())") TemporalCalendarFieldsNode calendarFieldsNode,
                        @Cached("create(getContext())") TemporalCalendarDateFromFieldsNode dateFromFieldsNode,
                        @Cached InlinedBranchProfile errorBranch) {
            if (!JSRuntime.isObject(item)) {
                errorBranch.enter(this);
                throw TemporalErrors.createTypeErrorTemporalPlainYearMonthExpected();
            }
            JSDynamicObject calendar = yearMonth.getCalendar();
            List<TruffleString> receiverFieldNames = calendarFieldsNode.execute(calendar, TemporalUtil.listMCY);
            JSDynamicObject fields = TemporalUtil.prepareTemporalFields(getContext(), yearMonth, receiverFieldNames, TemporalUtil.listEmpty);
            List<TruffleString> inputFieldNames = calendarFieldsNode.execute(calendar, TemporalUtil.listD);
            JSDynamicObject inputFields = TemporalUtil.prepareTemporalFields(getContext(), TemporalUtil.toJSDynamicObject(item, this, errorBranch), inputFieldNames, TemporalUtil.listEmpty);
            JSDynamicObject mergedFields = TemporalUtil.calendarMergeFields(getContext(), calendar, fields,
                            inputFields, namesNode, this, errorBranch);
            List<TruffleString> mergedFieldNames = TemporalUtil.listJoinRemoveDuplicates(receiverFieldNames, inputFieldNames);
            mergedFields = TemporalUtil.prepareTemporalFields(getContext(), mergedFields, mergedFieldNames, TemporalUtil.listEmpty);
            JSDynamicObject options = JSOrdinary.createWithNullPrototype(getContext());
            TemporalUtil.createDataPropertyOrThrow(getContext(), options, OVERFLOW, REJECT);
            return dateFromFieldsNode.execute(calendar, mergedFields, options);
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isJSTemporalYearMonth(thisObj)")
        protected static Object invalidReceiver(Object thisObj, Object item) {
            throw TemporalErrors.createTypeErrorTemporalPlainYearMonthExpected();
        }
    }

    public abstract static class JSTemporalPlainYearMonthGetISOFields extends JSTemporalBuiltinOperation {

        protected JSTemporalPlainYearMonthGetISOFields(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected JSObject getISOFields(JSTemporalPlainYearMonthObject ym) {
            JSObject obj = JSOrdinary.create(getContext(), getRealm());
            TemporalUtil.createDataPropertyOrThrow(getContext(), obj, CALENDAR, ym.getCalendar());
            TemporalUtil.createDataPropertyOrThrow(getContext(), obj, TemporalConstants.ISO_DAY, ym.getDay());
            TemporalUtil.createDataPropertyOrThrow(getContext(), obj, TemporalConstants.ISO_MONTH, ym.getMonth());
            TemporalUtil.createDataPropertyOrThrow(getContext(), obj, TemporalConstants.ISO_YEAR, ym.getYear());
            return obj;
        }

        @Specialization(guards = "!isJSTemporalYearMonth(thisObj)")
        protected static Object invalidReceiver(@SuppressWarnings("unused") Object thisObj) {
            throw TemporalErrors.createTypeErrorTemporalPlainYearMonthExpected();
        }
    }

    public abstract static class JSTemporalPlainYearMonthEqualsNode extends JSTemporalBuiltinOperation {

        protected JSTemporalPlainYearMonthEqualsNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected boolean equals(JSTemporalPlainYearMonthObject yearMonth, Object otherParam,
                        @Cached JSToStringNode toStringNode,
                        @Cached("create(getContext())") ToTemporalYearMonthNode toTemporalYearMonthNode) {
            JSTemporalPlainYearMonthObject other = toTemporalYearMonthNode.execute(otherParam, Undefined.instance);
            if (yearMonth.getMonth() != other.getMonth()) {
                return false;
            }
            if (yearMonth.getDay() != other.getDay()) {
                return false;
            }
            if (yearMonth.getYear() != other.getYear()) {
                return false;
            }
            return TemporalUtil.calendarEquals(yearMonth.getCalendar(), other.getCalendar(), toStringNode);
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isJSTemporalYearMonth(thisObj)")
        protected static Object invalidReceiver(Object thisObj, Object otherParam) {
            throw TemporalErrors.createTypeErrorTemporalPlainYearMonthExpected();
        }
    }

    public abstract static class JSTemporalPlainYearMonthWithNode extends JSTemporalBuiltinOperation {

        protected JSTemporalPlainYearMonthWithNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected JSTemporalPlainYearMonthObject with(JSTemporalPlainYearMonthObject ym, Object temporalYearMonthLike, Object optParam,
                        @Cached("createKeys(getContext())") EnumerableOwnPropertyNamesNode namesNode,
                        @Cached("create(getContext())") TemporalYearMonthFromFieldsNode yearMonthFromFieldsNode,
                        @Cached("create(getContext())") TemporalCalendarFieldsNode calendarFieldsNode,
                        @Cached InlinedBranchProfile errorBranch,
                        @Cached InlinedConditionProfile optionUndefined) {
            if (!isObjectNode.executeBoolean(temporalYearMonthLike)) {
                errorBranch.enter(this);
                throw Errors.createTypeError("Object expected");
            }
            JSDynamicObject ymLikeObj = (JSDynamicObject) temporalYearMonthLike;
            TemporalUtil.rejectTemporalCalendarType(ymLikeObj, this, errorBranch);
            Object calendarProperty = JSObject.get(ymLikeObj, CALENDAR);
            if (calendarProperty != Undefined.instance) {
                errorBranch.enter(this);
                throw TemporalErrors.createTypeErrorUnexpectedCalendar();
            }
            Object timezoneProperty = JSObject.get(ymLikeObj, TIME_ZONE);
            if (timezoneProperty != Undefined.instance) {
                errorBranch.enter(this);
                throw TemporalErrors.createTypeErrorUnexpectedTimeZone();
            }
            JSDynamicObject calendar = ym.getCalendar();
            List<TruffleString> fieldNames = calendarFieldsNode.execute(calendar, TemporalUtil.listMMCY);
            JSDynamicObject partialMonthDay = TemporalUtil.preparePartialTemporalFields(getContext(), ymLikeObj, fieldNames);
            JSDynamicObject options = getOptionsObject(optParam, this, errorBranch, optionUndefined);
            JSDynamicObject fields = TemporalUtil.prepareTemporalFields(getContext(), ym, fieldNames, TemporalUtil.listEmpty);
            fields = TemporalUtil.calendarMergeFields(getContext(), calendar, fields,
                            partialMonthDay, namesNode, this, errorBranch);
            fields = TemporalUtil.prepareTemporalFields(getContext(), fields, fieldNames, TemporalUtil.listEmpty);
            return yearMonthFromFieldsNode.execute(calendar, fields, options);
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isJSTemporalYearMonth(thisObj)")
        protected static Object invalidReceiver(Object thisObj, Object temporalYearMonthLike, Object optParam) {
            throw TemporalErrors.createTypeErrorTemporalPlainYearMonthExpected();
        }
    }

    public abstract static class PlainYearMonthOperation extends JSTemporalBuiltinOperation {
        protected PlainYearMonthOperation(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        protected JSTemporalPlainYearMonthObject addDurationToOrSubtractDurationFromPlainYearMonth(int operation, JSTemporalPlainYearMonthObject ym, Object temporalDurationLike, Object optParam,
                        EnumerableOwnPropertyNamesNode namesNode, ToLimitedTemporalDurationNode toLimitedTemporalDurationNode, TemporalYearMonthFromFieldsNode yearMonthFromFieldsNode,
                        TemporalCalendarFieldsNode calendarFieldsNode, TemporalCalendarGetterNode calendarGetterNode, JSToIntegerThrowOnInfinityNode toIntNode,
                        TemporalCalendarDateFromFieldsNode dateFromFieldsNode,
                        Node node, InlinedBranchProfile errorBranch, InlinedConditionProfile optionUndefined) {
            JSTemporalDurationRecord duration = toLimitedTemporalDurationNode.execute(temporalDurationLike, TemporalUtil.listEmpty);
            if (operation == -1) {
                duration = TemporalUtil.createNegatedTemporalDuration(duration);
            }
            JSTemporalDurationRecord balanceResult = TemporalUtil.balanceDuration(getContext(), namesNode, duration.getDays(), duration.getHours(), duration.getMinutes(), duration.getSeconds(),
                            duration.getMilliseconds(), duration.getMicroseconds(), duration.getNanoseconds(), Unit.DAY);
            JSDynamicObject options = getOptionsObject(optParam, node, errorBranch, optionUndefined);
            JSDynamicObject calendar = ym.getCalendar();
            List<TruffleString> fieldNames = calendarFieldsNode.execute(calendar, TemporalUtil.listMCY);
            JSDynamicObject fields = TemporalUtil.prepareTemporalFields(getContext(), ym, fieldNames, TemporalUtil.listEmpty);
            int sign = TemporalUtil.durationSign(duration.getYears(), duration.getMonths(), duration.getWeeks(), balanceResult.getDays(), 0, 0, 0, 0, 0, 0);
            int day = 0;
            if (sign < 0) {
                Object dayFromCalendar = TemporalUtil.calendarDaysInMonth(calendarGetterNode, calendar, ym);
                day = TemporalUtil.toPositiveIntegerConstrainInt(dayFromCalendar, toIntNode, node, errorBranch);
            } else {
                day = 1;
            }
            TemporalUtil.createDataPropertyOrThrow(getContext(), fields, TemporalConstants.DAY, day);
            JSDynamicObject date = dateFromFieldsNode.execute(calendar, fields, Undefined.instance);
            JSDynamicObject durationToAdd = JSTemporalDuration.createTemporalDuration(getContext(), duration.getYears(), duration.getMonths(), duration.getWeeks(), balanceResult.getDays(), 0,
                            0, 0, 0, 0, 0, node, errorBranch);
            JSDynamicObject addedDate = TemporalUtil.calendarDateAdd(calendar, date, durationToAdd, options, Undefined.instance);
            JSDynamicObject addedDateFields = TemporalUtil.prepareTemporalFields(getContext(), addedDate, fieldNames, TemporalUtil.listEmpty);
            return yearMonthFromFieldsNode.execute(calendar, addedDateFields, options);
        }

        protected JSTemporalDurationObject differenceTemporalPlainYearMonth(int sign, JSTemporalPlainYearMonthObject ym, Object otherParam, Object optParam,
                        Node node, InlinedConditionProfile unitIsMonth,
                        JSToStringNode toStringNode, JSToNumberNode toNumberNode, EnumerableOwnPropertyNamesNode namesNode, TruffleString.EqualNode equalNode,
                        TemporalRoundDurationNode roundDurationNode, ToTemporalYearMonthNode toTemporalYearMonthNode, TemporalCalendarFieldsNode calendarFieldsNode,
                        TemporalCalendarDateFromFieldsNode dateFromFieldsNode, TemporalGetOptionNode getOptionNode,
                        InlinedBranchProfile errorBranch, InlinedConditionProfile optionUndefined) {
            JSTemporalPlainYearMonthObject other = toTemporalYearMonthNode.execute(otherParam, Undefined.instance);
            JSDynamicObject calendar = ym.getCalendar();
            if (!TemporalUtil.calendarEquals(calendar, other.getCalendar(), toStringNode)) {
                errorBranch.enter(node);
                throw TemporalErrors.createRangeErrorIdenticalCalendarExpected();
            }
            JSDynamicObject options = getOptionsObject(optParam, node, errorBranch, optionUndefined);
            List<TruffleString> disallowedUnits = TemporalUtil.listWDHMSMMN;
            Unit smallestUnit = toSmallestTemporalUnit(options, disallowedUnits, MONTH, equalNode, getOptionNode, node, errorBranch);
            Unit largestUnit = toLargestTemporalUnit(options, disallowedUnits, AUTO, Unit.YEAR, equalNode, getOptionNode, node, errorBranch);
            TemporalUtil.validateTemporalUnitRange(largestUnit, smallestUnit);
            RoundingMode roundingMode = toTemporalRoundingMode(options, TRUNC, equalNode, getOptionNode);
            if (sign == TemporalUtil.SINCE) {
                roundingMode = TemporalUtil.negateTemporalRoundingMode(roundingMode);
            }
            double roundingIncrement = TemporalUtil.toTemporalRoundingIncrement(options, null, false, isObjectNode, toNumberNode);
            List<TruffleString> fieldNames = calendarFieldsNode.execute(calendar, TemporalUtil.listMCY);
            JSDynamicObject otherFields = TemporalUtil.prepareTemporalFields(getContext(), other, fieldNames, TemporalUtil.listEmpty);
            TemporalUtil.createDataPropertyOrThrow(getContext(), otherFields, DAY, 1);
            JSDynamicObject otherDate = dateFromFieldsNode.execute(calendar, otherFields, Undefined.instance);
            JSDynamicObject thisFields = TemporalUtil.prepareTemporalFields(getContext(), ym, fieldNames, TemporalUtil.listEmpty);
            TemporalUtil.createDataPropertyOrThrow(getContext(), thisFields, DAY, 1);
            JSTemporalPlainDateObject thisDate = dateFromFieldsNode.execute(calendar, thisFields, Undefined.instance);
            JSDynamicObject untilOptions = TemporalUtil.mergeLargestUnitOption(getContext(), namesNode, options, largestUnit);
            JSTemporalDurationObject result = TemporalUtil.calendarDateUntil(calendar, thisDate, otherDate, untilOptions, Undefined.instance);
            if (unitIsMonth.profile(node, Unit.MONTH == smallestUnit && roundingIncrement == 1)) {
                return JSTemporalDuration.createTemporalDuration(getContext(), sign * result.getYears(), sign * result.getMonths(), 0, 0, 0, 0, 0, 0, 0, 0, node, errorBranch);
            } else {
                JSTemporalDurationRecord result2 = roundDurationNode.execute(result.getYears(), result.getMonths(), 0, 0, 0, 0, 0, 0, 0, 0, (long) roundingIncrement,
                                smallestUnit, roundingMode, thisDate);
                return JSTemporalDuration.createTemporalDuration(getContext(), sign * result2.getYears(), sign * result2.getMonths(), 0, 0, 0, 0, 0, 0, 0, 0, node, errorBranch);
            }
        }
    }

    public abstract static class JSTemporalPlainYearMonthAddNode extends PlainYearMonthOperation {

        protected JSTemporalPlainYearMonthAddNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected JSTemporalPlainYearMonthObject add(JSTemporalPlainYearMonthObject ym, Object temporalDurationLike, Object optParam,
                        @Cached("createKeys(getContext())") EnumerableOwnPropertyNamesNode namesNode,
                        @Cached ToLimitedTemporalDurationNode toLimitedTemporalDurationNode,
                        @Cached("create(getContext())") TemporalYearMonthFromFieldsNode yearMonthFromFieldsNode,
                        @Cached("create(getContext())") TemporalCalendarFieldsNode calendarFieldsNode,
                        @Cached("create(getContext())") TemporalCalendarGetterNode calendarGetterNode,
                        @Cached JSToIntegerThrowOnInfinityNode toIntNode,
                        @Cached("create(getContext())") TemporalCalendarDateFromFieldsNode dateFromFieldsNode,
                        @Cached InlinedBranchProfile errorBranch,
                        @Cached InlinedConditionProfile optionUndefined) {
            return addDurationToOrSubtractDurationFromPlainYearMonth(TemporalUtil.ADD, ym, temporalDurationLike, optParam,
                            namesNode, toLimitedTemporalDurationNode, yearMonthFromFieldsNode,
                            calendarFieldsNode, calendarGetterNode, toIntNode, dateFromFieldsNode, this, errorBranch, optionUndefined);
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isJSTemporalYearMonth(thisObj)")
        protected static Object invalidReceiver(Object thisObj, Object temporalDurationLike, Object optParam) {
            throw TemporalErrors.createTypeErrorTemporalPlainYearMonthExpected();
        }
    }

    public abstract static class JSTemporalPlainYearMonthSubtractNode extends PlainYearMonthOperation {

        protected JSTemporalPlainYearMonthSubtractNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected JSTemporalPlainYearMonthObject subtract(JSTemporalPlainYearMonthObject ym, Object temporalDurationLike, Object optParam,
                        @Cached("createKeys(getContext())") EnumerableOwnPropertyNamesNode namesNode,
                        @Cached ToLimitedTemporalDurationNode toLimitedTemporalDurationNode,
                        @Cached("create(getContext())") TemporalYearMonthFromFieldsNode yearMonthFromFieldsNode,
                        @Cached("create(getContext())") TemporalCalendarFieldsNode calendarFieldsNode,
                        @Cached("create(getContext())") TemporalCalendarGetterNode calendarGetterNode,
                        @Cached JSToIntegerThrowOnInfinityNode toIntNode,
                        @Cached("create(getContext())") TemporalCalendarDateFromFieldsNode dateFromFieldsNode,
                        @Cached InlinedBranchProfile errorBranch,
                        @Cached InlinedConditionProfile optionUndefined) {
            return addDurationToOrSubtractDurationFromPlainYearMonth(TemporalUtil.SUBTRACT, ym, temporalDurationLike, optParam,
                            namesNode, toLimitedTemporalDurationNode, yearMonthFromFieldsNode, calendarFieldsNode, calendarGetterNode, toIntNode, dateFromFieldsNode, this, errorBranch,
                            optionUndefined);
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isJSTemporalYearMonth(thisObj)")
        protected static Object invalidReceiver(Object thisObj, Object temporalDurationLike, Object optParam) {
            throw TemporalErrors.createTypeErrorTemporalPlainYearMonthExpected();
        }
    }

    public abstract static class JSTemporalPlainYearMonthUntilNode extends PlainYearMonthOperation {

        protected JSTemporalPlainYearMonthUntilNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @SuppressWarnings("unused")
        @Specialization
        protected JSTemporalDurationObject until(JSTemporalPlainYearMonthObject thisYearMonth, Object otherParam, Object optParam,
                        @Cached InlinedConditionProfile unitIsMonth,
                        @Cached JSToStringNode toStringNode,
                        @Cached(inline = true) JSToBooleanNode toBooleanNode,
                        @Cached JSToNumberNode toNumberNode,
                        @Cached("createKeys(getContext())") EnumerableOwnPropertyNamesNode namesNode,
                        @Cached TemporalGetOptionNode getOptionNode,
                        @Cached TruffleString.EqualNode equalNode,
                        @Cached("create(getContext())") TemporalRoundDurationNode roundDurationNode,
                        @Cached("create(getContext())") ToTemporalYearMonthNode toTemporalYearMonthNode,
                        @Cached("create(getContext())") TemporalCalendarFieldsNode calendarFieldsNode,
                        @Cached("create(getContext())") TemporalCalendarDateFromFieldsNode dateFromFieldsNode,
                        @Cached InlinedBranchProfile errorBranch,
                        @Cached InlinedConditionProfile optionUndefined) {
            return differenceTemporalPlainYearMonth(TemporalUtil.UNTIL, thisYearMonth, otherParam, optParam,
                            this, unitIsMonth, toStringNode, toNumberNode, namesNode, equalNode, roundDurationNode,
                            toTemporalYearMonthNode, calendarFieldsNode, dateFromFieldsNode, getOptionNode, errorBranch, optionUndefined);
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isJSTemporalYearMonth(thisObj)")
        protected static Object invalidReceiver(Object thisObj, Object otherParam, Object optParam) {
            throw TemporalErrors.createTypeErrorTemporalPlainYearMonthExpected();
        }
    }

    public abstract static class JSTemporalPlainYearMonthSinceNode extends PlainYearMonthOperation {

        protected JSTemporalPlainYearMonthSinceNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected JSTemporalDurationObject since(JSTemporalPlainYearMonthObject thisYearMonth, Object otherParam, Object optParam,
                        @Cached InlinedConditionProfile unitIsMonth,
                        @Cached JSToNumberNode toNumberNode,
                        @Cached("createKeys(getContext())") EnumerableOwnPropertyNamesNode namesNode,
                        @Cached JSToStringNode toStringNode,
                        @Cached TruffleString.EqualNode equalNode,
                        @Cached TemporalGetOptionNode getOptionNode,
                        @Cached("create(getContext())") TemporalRoundDurationNode roundDurationNode,
                        @Cached("create(getContext())") ToTemporalYearMonthNode toTemporalYearMonthNode,
                        @Cached("create(getContext())") TemporalCalendarFieldsNode calendarFieldsNode,
                        @Cached("create(getContext())") TemporalCalendarDateFromFieldsNode dateFromFieldsNode,
                        @Cached InlinedBranchProfile errorBranch,
                        @Cached InlinedConditionProfile optionUndefined) {
            return differenceTemporalPlainYearMonth(TemporalUtil.SINCE, thisYearMonth, otherParam, optParam,
                            this, unitIsMonth, toStringNode, toNumberNode, namesNode, equalNode, roundDurationNode,
                            toTemporalYearMonthNode, calendarFieldsNode, dateFromFieldsNode, getOptionNode, errorBranch, optionUndefined);
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isJSTemporalYearMonth(thisObj)")
        protected static Object invalidReceiver(Object thisObj, Object otherParam, Object optParam) {
            throw TemporalErrors.createTypeErrorTemporalPlainYearMonthExpected();
        }
    }
}
