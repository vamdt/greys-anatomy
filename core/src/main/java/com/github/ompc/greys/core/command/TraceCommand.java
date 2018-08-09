package com.github.ompc.greys.core.command;

import com.github.ompc.greys.core.Advice;
import com.github.ompc.greys.core.advisor.AdviceListener;
import com.github.ompc.greys.core.advisor.ReflectAdviceTracingListenerAdapter;
import com.github.ompc.greys.core.command.annotation.Cmd;
import com.github.ompc.greys.core.command.annotation.IndexArg;
import com.github.ompc.greys.core.command.annotation.NamedArg;
import com.github.ompc.greys.core.exception.ExpressException;
import com.github.ompc.greys.core.server.Session;
import com.github.ompc.greys.core.textui.TTree;
import com.github.ompc.greys.core.textui.ext.TObject;
import com.github.ompc.greys.core.util.InvokeCost;
import com.github.ompc.greys.core.util.PointCut;
import com.github.ompc.greys.core.util.matcher.ClassMatcher;
import com.github.ompc.greys.core.util.matcher.GaMethodMatcher;
import com.github.ompc.greys.core.util.matcher.PatternMatcher;
import org.apache.commons.lang3.StringUtils;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.ompc.greys.core.util.Express.ExpressFactory.newExpress;
import static com.github.ompc.greys.core.util.GaStringUtils.getThreadInfo;
import static com.github.ompc.greys.core.util.GaStringUtils.tranClassName;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * 调用跟踪命令<br/>
 * 负责输出一个类中的所有方法调用路径 Created by oldmanpushcart@gmail.com on 15/5/27.
 */
@Cmd(name = "trace", sort = 6, summary = "Display the detailed thread stack of specified class and method",
        eg = {
                "trace -E org\\.apache\\.commons\\.lang\\.StringUtils isBlank",
                "trace org.apache.commons.lang.StringUtils isBlank",
                "trace *StringUtils isBlank",
                "trace *StringUtils isBlank params[0].length==1",
                "trace *StringUtils isBlank '#cost>100'",
                "trace -n 2 *StringUtils isBlank",
        })
public class TraceCommand implements Command {

    @IndexArg(index = 0, name = "class-pattern", summary = "Path and classname of Pattern Matching")
    private String classPattern;

    @IndexArg(index = 1, name = "method-pattern", summary = "Method of Pattern Matching")
    private String methodPattern;

    @IndexArg(index = 2, name = "condition-express", isRequired = false,
            summary = "Conditional expression by OGNL",
            description = "" +
                    "FOR EXAMPLE" +
                    "\n" +
                    "     TRUE : 1==1\n" +
                    "     TRUE : true\n" +
                    "    FALSE : false\n" +
                    "     TRUE : params.length>=0\n" +
                    "    FALSE : 1==2\n" +
                    "\n" +
                    "THE STRUCTURE" +
                    "\n" +
                    "          target : the object \n" +
                    "           clazz : the object's class\n" +
                    "          method : the constructor or method\n" +
                    "    params[0..n] : the parameters of method\n" +
                    "       returnObj : the returned object of method\n" +
                    "        throwExp : the throw exception of method\n" +
                    "        isReturn : the method ended by return\n" +
                    "         isThrow : the method ended by throwing exception"
    )
    private String conditionExpress;

    @NamedArg(name = "E", summary = "Enable regular expression to match (wildcard matching by default)")
    private boolean isRegEx = false;

    @NamedArg(name = "n", hasValue = true, summary = "Threshold of execution times")
    private Integer threshold;

    @Override
    public Action getAction() {

        return new GetEnhancerAction() {

            @Override
            public GetEnhancer action(Session session, Instrumentation inst, final Printer printer) throws Throwable {
                return new GetEnhancer() {

                    @Override
                    public PointCut getPointCut() {
                        return new PointCut(
                                new ClassMatcher(new PatternMatcher(isRegEx, classPattern)),
                                new GaMethodMatcher(new PatternMatcher(isRegEx, methodPattern)),

                                // don't include the sub class when tracing...
                                // fixed for #94
                                // GlobalOptions.isTracingSubClass

                                // include sub class when tracing now
                                true
                        );
                    }

                    @Override
                    public AdviceListener getAdviceListener() {
                        return new ReflectAdviceTracingListenerAdapter() {

                            private final AtomicInteger timesRef = new AtomicInteger();
                            private final InvokeCost invokeCost = new InvokeCost();
                            private final ThreadLocal<Trace> traceRef = new ThreadLocal<Trace>();

                            @Override
                            public void tracingInvokeBefore(
                                    Integer tracingLineNumber,
                                    String tracingClassName,
                                    String tracingMethodName,
                                    String tracingMethodDesc) throws Throwable {
                                final Trace trace = traceRef.get();
                                if (null == tracingLineNumber) {
                                    trace.tTree.begin(tranClassName(tracingClassName) + ":" + tracingMethodName + "()");
                                } else {
                                    trace.tTree.begin(tranClassName(tracingClassName) + ":" + tracingMethodName + "(@" + tracingLineNumber + ")");
                                }

                            }

                            @Override
                            public void tracingInvokeAfter(
                                    Integer tracingLineNumber,
                                    String tracingClassName,
                                    String tracingMethodName,
                                    String tracingMethodDesc) throws Throwable {
                                final Trace trace = traceRef.get();
                                if (!trace.tTree.isTop()) {
                                    trace.tTree.end();
                                }

                            }

                            @Override
                            public void tracingInvokeThrowing(
                                    Integer tracingLineNumber,
                                    String tracingClassName,
                                    String tracingMethodName,
                                    String tracingMethodDesc,
                                    String throwException) throws Throwable {
                                final Trace trace = traceRef.get();
                                if (!trace.tTree.isTop()) {
                                    trace.tTree.set(trace.tTree.get() + "[throw " + throwException + "]").end();
                                }

                            }

                            private String getTitle(final Advice advice) {
                                final StringBuilder titleSB = new StringBuilder("Tracing for : ")
                                        .append(getThreadInfo());
                                if (advice.isTraceSupport()) {
                                    titleSB.append(";traceId=").append(advice.getTraceId()).append(";");
                                }
                                return titleSB.toString();
                            }

                            @Override
                            public void before(Advice advice) throws Throwable {
                                StringBuilder builder = new StringBuilder(advice.getClazz().getName());
                                builder.append(':');
                                builder.append(advice.getClazz().getName());
                                builder.append("()");
                                if (advice.params != null) {
                                    for (int i = 0; i < advice.params.length; i++) {
                                        builder.append("\nparams[");
                                        builder.append(i);
                                        builder.append("]: ");
                                        builder.append(new TObject(advice.params[i], 1).rendering());
                                    }
                                }
                                invokeCost.begin();
                                traceRef.set(
                                        new Trace(
                                                new TTree(true, getTitle(advice))
                                                        .begin(builder.toString())
                                        )
                                );
                            }

                            @Override
                            public void afterReturning(Advice advice) throws Throwable {
                                final Trace trace = traceRef.get();
                                if (!trace.tTree.isTop()) {
                                    String returnStr = "\nreturnObj: " + new TObject(advice.returnObj, 1).rendering();
                                    trace.tTree.set(trace.tTree.get() + returnStr);
                                    trace.tTree.end();
                                }
                            }

                            @Override
                            public void afterThrowing(Advice advice) throws Throwable {
                                final Trace trace = traceRef.get();
                                trace.tTree.begin("throw:" + advice.throwExp.getClass().getName() + "()").end();
                                if (!trace.tTree.isTop()) {
                                    trace.tTree.end();
                                }

                                // 这里将堆栈的end全部补上
                                //while (entity.tracingDeep-- >= 0) {
                                //    entity.tTree.end();
                                //}

                            }

                            private boolean isInCondition(Advice advice, long cost) {
                                try {
                                    return isBlank(conditionExpress)
                                            || newExpress(advice).bind("cost", cost).is(conditionExpress);
                                } catch (ExpressException e) {
                                    return false;
                                }
                            }

                            private boolean isOverThreshold(int currentTimes) {
                                return null != threshold
                                        && currentTimes >= threshold;
                            }

                            @Override
                            public void afterFinishing(Advice advice) throws Throwable {
                                final long cost = invokeCost.cost();
                                if (isInCondition(advice, cost)) {
                                    final Trace trace = traceRef.get();
                                    printer.println(trace.tTree.rendering());
                                    if (isOverThreshold(timesRef.incrementAndGet())) {
                                        printer.finish();
                                    }
                                }
                            }

                        };
                    }
                };
            }

        };

    }

    private class Trace {
        private final TTree tTree;

        private Trace(TTree tTree) {
            this.tTree = tTree;
        }
    }

}
