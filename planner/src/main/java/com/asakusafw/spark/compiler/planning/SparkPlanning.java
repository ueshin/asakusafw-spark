/**
 * Copyright 2011-2019 Asakusa Framework Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.asakusafw.spark.compiler.planning;

import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asakusafw.info.graph.Input;
import com.asakusafw.info.graph.Node;
import com.asakusafw.info.graph.Output;
import com.asakusafw.info.operator.InputAttribute;
import com.asakusafw.info.operator.OperatorAttribute;
import com.asakusafw.info.operator.OperatorSpec;
import com.asakusafw.info.operator.OutputAttribute;
import com.asakusafw.info.plan.DataExchange;
import com.asakusafw.info.plan.PlanAttribute;
import com.asakusafw.info.plan.PlanInputSpec;
import com.asakusafw.info.plan.PlanOutputSpec;
import com.asakusafw.info.plan.PlanVertexSpec;
import com.asakusafw.lang.compiler.api.CompilerOptions;
import com.asakusafw.lang.compiler.api.JobflowProcessor;
import com.asakusafw.lang.compiler.common.AttributeContainer;
import com.asakusafw.lang.compiler.common.BasicDiagnostic;
import com.asakusafw.lang.compiler.common.Diagnostic;
import com.asakusafw.lang.compiler.common.DiagnosticException;
import com.asakusafw.lang.compiler.extension.operator.info.OperatorGraphConverter;
import com.asakusafw.lang.compiler.extension.operator.info.PlanAttributeStore;
import com.asakusafw.lang.compiler.model.description.TypeDescription;
import com.asakusafw.lang.compiler.model.graph.CoreOperator;
import com.asakusafw.lang.compiler.model.graph.CoreOperator.CoreOperatorKind;
import com.asakusafw.lang.compiler.model.graph.ExternalInput;
import com.asakusafw.lang.compiler.model.graph.ExternalOutput;
import com.asakusafw.lang.compiler.model.graph.Group;
import com.asakusafw.lang.compiler.model.graph.Jobflow;
import com.asakusafw.lang.compiler.model.graph.MarkerOperator;
import com.asakusafw.lang.compiler.model.graph.Operator;
import com.asakusafw.lang.compiler.model.graph.Operator.OperatorKind;
import com.asakusafw.lang.compiler.model.graph.OperatorArgument;
import com.asakusafw.lang.compiler.model.graph.OperatorGraph;
import com.asakusafw.lang.compiler.model.graph.OperatorInput;
import com.asakusafw.lang.compiler.model.graph.OperatorInput.InputUnit;
import com.asakusafw.lang.compiler.model.graph.OperatorOutput;
import com.asakusafw.lang.compiler.model.graph.OperatorProperty;
import com.asakusafw.lang.compiler.model.graph.Operators;
import com.asakusafw.lang.compiler.model.graph.UserOperator;
import com.asakusafw.lang.compiler.model.info.JobflowInfo;
import com.asakusafw.lang.compiler.optimizer.OperatorCharacterizers;
import com.asakusafw.lang.compiler.optimizer.OperatorRewriters;
import com.asakusafw.lang.compiler.optimizer.adapter.OptimizerContextAdapter;
import com.asakusafw.lang.compiler.optimizer.basic.OperatorClass;
import com.asakusafw.lang.compiler.optimizer.basic.OperatorClass.InputType;
import com.asakusafw.lang.compiler.planning.OperatorEquivalence;
import com.asakusafw.lang.compiler.planning.Plan;
import com.asakusafw.lang.compiler.planning.PlanAssembler;
import com.asakusafw.lang.compiler.planning.PlanDetail;
import com.asakusafw.lang.compiler.planning.PlanMarker;
import com.asakusafw.lang.compiler.planning.PlanMarkers;
import com.asakusafw.lang.compiler.planning.Planning;
import com.asakusafw.lang.compiler.planning.SubPlan;
import com.asakusafw.lang.compiler.planning.util.GraphStatistics;
import com.asakusafw.lang.utils.common.Invariants;
import com.asakusafw.spark.compiler.planning.PartitionGroupInfo.DataSize;
import com.asakusafw.spark.compiler.planning.PlanningContext.Option;
import com.asakusafw.utils.graph.Graph;
import com.asakusafw.utils.graph.Graphs;

/**
 * Utilities for execution planning on Spark compiler.
 * The elements in the created plan will have the following {@link AttributeContainer#getAttribute(Class) attributes}:
 * <ul>
 * <li> {@link SubPlanInfo} -
 *      for {@link SubPlan}
 * </li>
 * <li> {@link SubPlanInputInfo} -
 *      for {@link com.asakusafw.lang.compiler.planning.SubPlan.Input SubPlan.Input}
 * </li>
 * <li> {@link SubPlanOutputInfo} -
 *      for {@link com.asakusafw.lang.compiler.planning.SubPlan.Output SubPlan.Input}
 * </li>
 * <li> {@link BroadcastInfo} -
 *      for {@link com.asakusafw.lang.compiler.planning.SubPlan.Port SubPlan.(Input|Output)}
 *      ({@code BROADCAST} ports only)
 * </li>
 * <li> {@link IterativeInfo} -
 *      for {@link Plan}, {@link SubPlan}, and
 *      {@link com.asakusafw.lang.compiler.planning.SubPlan.Port SubPlan.(Input|Output)}
 * </li>
 * <li> {@link ParameterInfo} -
 *      for {@link Plan}
 * </li>
 * </ul>
 * @since 0.1.0
 * @version 0.5.2
 */
public final class SparkPlanning {

    static final Logger LOG = LoggerFactory.getLogger(SparkPlanning.class);

    private static final int REDUCTION_STEP_LIMIT = 1_000;

    /**
     * The compiler property key prefix of planning options.
     * @see Option
     */
    public static final String KEY_OPTION_PREFIX = "spark.planning.option."; //$NON-NLS-1$

    private SparkPlanning() {
        return;
    }

    /**
     * Builds an execution plan for the target jobflow.
     * Note that, this does not modifies the operator graph in the target jobflow.
     * @param parent the current jobflow processing context
     * @param jobflow the target jobflow
     * @return the detail of created plan
     */
    public static PlanDetail plan(JobflowProcessor.Context parent, Jobflow jobflow) {
        return plan(parent, jobflow, jobflow.getOperatorGraph().copy());
    }

    /**
     * Builds an execution plan for the target operator graph.
     * Note that, the target operator graph will be modified in this invocation.
     * @param parent the current jobflow processing context
     * @param jobflow the target jobflow information
     * @param operators the target operator graph
     * @return the detail of created plan
     */
    public static PlanDetail plan(JobflowProcessor.Context parent, JobflowInfo jobflow, OperatorGraph operators) {
        PlanningContext context = createContext(parent, jobflow);
        return plan(context, operators);
    }

    /**
     * Creates a new planner context.
     * @param parent the current jobflow processing context
     * @param jobflow the target jobflow information
     * @return the created context
     */
    public static PlanningContext createContext(JobflowProcessor.Context parent, JobflowInfo jobflow) {
        Set<Option> options = getPlanningOptions(parent.getOptions());
        return createContext(parent, jobflow, options);
    }

    /**
     * Creates a new planner context.
     * @param parent the current jobflow processing context
     * @param jobflow the target jobflow information
     * @param options the planning options
     * @return the created context
     */
    public static PlanningContext createContext(
            JobflowProcessor.Context parent,
            JobflowInfo jobflow,
            Collection<PlanningContext.Option> options) {
        PlanningContext context = new PlanningContext(
                new OptimizerContextAdapter(parent, jobflow.getFlowId(), SparkOptimizerToolkit.INSTANCE),
                options);
        return context;
    }

    private static Set<Option> getPlanningOptions(CompilerOptions options) {
        Set<Option> results = EnumSet.noneOf(Option.class);
        for (Option option : Option.values()) {
            if (isEnabled(options, option)) {
                results.add(option);
            }
        }
        return results;
    }

    private static boolean isEnabled(CompilerOptions options, Option option) {
        String key = KEY_OPTION_PREFIX + option.getSymbol();
        boolean enabled = options.get(key, option.isDefaultEnabled());
        if (LOG.isTraceEnabled()) {
            LOG.trace("{}={}", key, options.get(key, null)); //$NON-NLS-1$
        }
        LOG.debug("planning option: {}={}", option, enabled); //$NON-NLS-1$
        return enabled;
    }

    /**
     * Builds an execution plan for the target operator graph.
     * Note that, the target operator graph will be modified in this invocation.
     * @param context the current context
     * @param operators the target operator graph
     * @return the detail of created plan
     */
    public static PlanDetail plan(PlanningContext context, OperatorGraph operators) {
        prepareOperatorGraph(context, operators);
        PlanDetail result = createPlan(context, operators);
        return result;
    }

    /**
     * Saves execution plan information into the current package.
     * @param context the current context
     * @param plan the target execution plan
     * @since 0.4.2
     */
    public static void saveInfo(JobflowProcessor.Context context, Plan plan) {
        Node root = toInfo(plan);
        PlanAttributeStore.save(context, new PlanAttribute(root));
    }

    static Node toInfo(Plan plan) {
        Node root = new Node();
        Map<SubPlan.Input, Input> inputs = new HashMap<>();
        Map<SubPlan.Output, Output> outputs = new HashMap<>();
        try {
            plan.getElements().forEach(it -> convertPlanVertex(it, root.newElement(), inputs, outputs));
            outputs.forEach((upstream, output) -> upstream.getOpposites().stream()
                    .map(inputs::get)
                    .forEach(output::connect));
        } catch (RuntimeException e) {
            LOG.warn("error occurred while saving execution plan", e);
        }
        return root;
    }

    private static void convertPlanVertex(
            SubPlan sub, Node node,
            Map<SubPlan.Input, Input> inputs, Map<SubPlan.Output, Output> outputs) {
        SubPlanInfo info = sub.getAttribute(SubPlanInfo.class);
        node.withAttribute(new OperatorAttribute(PlanVertexSpec.of(
                NameInfo.getName(sub),
                info.getLabel(),
                Collections.emptyList())));
        sub.getInputs().stream()
            .sorted(Comparator.comparing(it -> NameInfo.getName(it)))
            .forEach(it -> inputs.put(it, node.newInput().withAttribute(new InputAttribute(
                        NameInfo.getName(it),
                        OperatorGraphConverter.convert(it.getOperator().getDataType()),
                        null, null))));
        sub.getOutputs().stream()
            .sorted(Comparator.comparing(it -> NameInfo.getName(it)))
            .forEach(it -> outputs.put(it, node.newOutput().withAttribute(new OutputAttribute(
                    NameInfo.getName(it),
                    OperatorGraphConverter.convert(it.getOperator().getDataType())))));

        Map<MarkerOperator, OperatorSpec> mapper = new HashMap<>();
        sub.getInputs().forEach(s -> {
            SubPlanInputInfo r = s.getAttribute(SubPlanInputInfo.class);
            mapper.put(s.getOperator(), PlanInputSpec.of(
                    NameInfo.getName(s),
                    toDataExchange(r),
                    OperatorGraphConverter.convert(r.getPartitionInfo())));
        });
        sub.getOutputs().forEach(s -> {
            SubPlanOutputInfo r = s.getAttribute(SubPlanOutputInfo.class);
            mapper.put(s.getOperator(), PlanOutputSpec.of(
                    NameInfo.getName(s),
                    toDataExchange(r),
                    OperatorGraphConverter.convert(r.getPartitionInfo()),
                    Optional.ofNullable(r.getAggregationInfo())
                        .filter(it -> it instanceof UserOperator)
                        .map(it -> (UserOperator) it)
                        .map(it -> Arrays.asList(
                                it.getMethod().getDeclaringClass().getSimpleName(),
                                it.getMethod().getName()))
                        .orElse(Collections.emptyList())));
        });
        OperatorGraph graph = new OperatorGraph(sub.getOperators());
        new OperatorGraphConverter(mapper::get).process(graph, node);
    }

    private static DataExchange toDataExchange(SubPlanInputInfo info) {
        switch (info.getInputType()) {
        case DONT_CARE:
            return DataExchange.MOVE;
        case PARTITIONED:
            return DataExchange.SHUFFLE;
        case VOID:
            return DataExchange.NOTHING;
        case BROADCAST:
            return DataExchange.BROADCAST;
        default:
            return DataExchange.UNKNOWN;
        }
    }

    private static DataExchange toDataExchange(SubPlanOutputInfo info) {
        switch (info.getOutputType()) {
        case DONT_CARE:
            return DataExchange.MOVE;
        case AGGREGATED:
            return DataExchange.AGGREGATE;
        case BROADCAST:
            return DataExchange.BROADCAST;
        case DISCARD:
            return DataExchange.NOTHING;
        case PARTITIONED:
            return DataExchange.SHUFFLE;
        default:
            return DataExchange.UNKNOWN;
        }
    }

    /**
     * Makes the target operator graph suitable for execution planning.
     * @param context the current context
     * @param graph the target operator graph
     */
    static void prepareOperatorGraph(PlanningContext context, OperatorGraph graph) {
        Planning.normalize(graph);
        optimize(context, graph);
        fixOperatorGraph(context, graph);
        insertPlanMarkers(context, graph);
        Planning.simplifyTerminators(graph);
        validate(graph);
    }

    private static void validate(OperatorGraph graph) {
        Graph<Operator> dependencies = Planning.toDependencyGraph(graph);
        Set<Set<Operator>> circuits = Graphs.findCircuit(dependencies);
        if (circuits.isEmpty() == false) {
            List<Diagnostic> diagnostics = new ArrayList<>();
            for (Set<Operator> loop : circuits) {
                diagnostics.add(new BasicDiagnostic(Diagnostic.Level.ERROR, MessageFormat.format(
                        "operator graph must be acyclic: {0}",
                        loop)).with(graph));
            }
            throw new DiagnosticException(diagnostics);
        }
    }

    private static void optimize(PlanningContext context, OperatorGraph graph) {
        int step = 0;
        boolean changed;
        do {
            step++;
            LOG.debug("optimize step#{}", step);
            Planning.removeDeadFlow(graph);
            if (step > REDUCTION_STEP_LIMIT) {
                LOG.warn(MessageFormat.format(
                        "the number of optimization steps exceeded limit: {0}",
                        REDUCTION_STEP_LIMIT));
                break;
            }
            OperatorGraph.Snapshot before = graph.getSnapshot();
            OperatorRewriters.apply(
                    context.getOptimizerContext(),
                    context.getEstimator(),
                    context.getRewriter(),
                    graph);
            OperatorGraph.Snapshot after = graph.getSnapshot();
            changed = before.equals(after) == false;
        } while (changed);
    }

    private static void fixOperatorGraph(PlanningContext context, OperatorGraph graph) {
        Map<Operator, OperatorClass> characteristics = OperatorCharacterizers.apply(
                context.getOptimizerContext(),
                context.getEstimator(),
                context.getClassifier(),
                graph.getOperators(false));
        graph.getOperators(false).stream()
                .filter(it -> it.getOperatorKind() == OperatorKind.USER)
                .sorted(Planning.OPERATOR_ORDER)
                .map(characteristics::get)
                .filter(it -> isFixTarget(it))
                .forEach(info -> {
                    LOG.debug("fixing operator: {}", info.getOperator());
                    Operator replacement = fixOperator(info);
                    Operators.replace(info.getOperator(), replacement);
                    graph.add(replacement);
                    graph.remove(info.getOperator());
                });
        graph.rebuild();
    }

    private static boolean isFixTarget(OperatorClass info) {
        for (OperatorInput port : info.getOperator().getInputs()) {
            InputUnit adjust = computeInputUnit(info, port);
            if (adjust != port.getInputUnit()) {
                return true;
            }
        }
        return false;
    }

    private static InputUnit computeInputUnit(OperatorClass info, OperatorInput port) {
        if (info.getSecondaryInputs().contains(port)) {
            return InputUnit.WHOLE;
        } else if (info.getPrimaryInputType() == InputType.RECORD) {
            return InputUnit.RECORD;
        } else if (info.getPrimaryInputType() == InputType.GROUP) {
            return InputUnit.GROUP;
        } else {
            return port.getInputUnit(); // don't care
        }
    }

    private static Operator fixOperator(OperatorClass info) {
        UserOperator operator = (UserOperator) info.getOperator();
        UserOperator.Builder builder = UserOperator
                .builder(operator.getAnnotation(), operator.getMethod(), operator.getImplementationClass());
        for (OperatorProperty property : operator.getProperties()) {
            switch (property.getPropertyKind()) {
            case INPUT: {
                OperatorInput port = (OperatorInput) property;
                builder.input(port, c -> c.unit(computeInputUnit(info, port)));
                break;
            }
            case OUTPUT:
                builder.output((OperatorOutput) property);
                break;
            case ARGUMENT:
                builder.argument((OperatorArgument) property);
                break;
            default:
                throw new AssertionError(property);
            }
        }
        operator.getAttributeEntries().forEach(builder::attribute);
        builder.constraint(operator.getConstraints());
        return builder.build();
    }

    static void insertPlanMarkers(PlanningContext context, OperatorGraph graph) {
        rewriteCheckpointOperators(graph);
        Map<Operator, OperatorClass> characteristics = OperatorCharacterizers.apply(
                context.getOptimizerContext(),
                context.getEstimator(),
                context.getClassifier(),
                graph.getOperators(false));
        for (OperatorClass info : characteristics.values()) {
            insertPlanMarkerForPreparingGroup(info);
            insertPlanMarkerForPreparingBroadcast(info);
            if (context.getOptions().contains(Option.CHECKPOINT_AFTER_EXTERNAL_INPUTS)) {
                insertPlanMarkerForEnsuringExternalInput(info);
            }
            if (context.getOptions().contains(Option.CHECKPOINT_BEFORE_EXTERNAL_OUTPUTS)) {
                insertPlanMarkerForPreparingExternalOutput(info);
            }
        }
        if (context.getOptions().contains(Option.REMOVE_CYCLIC_BROADCASTS)) {
            removeCyclicBroadcast(graph);
        }
        graph.rebuild();
    }

    private static void rewriteCheckpointOperators(OperatorGraph graph) {
        for (Operator operator : graph.getOperators(true)) {
            if (operator.getOperatorKind() != OperatorKind.CORE
                    || ((CoreOperator) operator).getCoreOperatorKind() != CoreOperatorKind.CHECKPOINT) {
                continue;
            }
            PlanMarkers.insert(PlanMarker.CHECKPOINT, operator.getInputs().get(0));
            Operators.remove(operator);
            graph.remove(operator);
        }
    }

    private static void insertPlanMarkerForPreparingGroup(OperatorClass info) {
        if (info.getPrimaryInputType() != OperatorClass.InputType.GROUP) {
            return;
        }
        for (OperatorInput port : info.getPrimaryInputs()) {
            if (isEmpty(port) == false) {
                boolean partial = info.getAttributes(port).contains(OperatorClass.InputAttribute.PARTIAL_REDUCTION);
                EdgeInfo edge = new EdgeInfo(
                        port.getDataType(),
                        port.getGroup(),
                        partial ? info.getOperator() : null);
                Operators.insert(MarkerOperator.builder(port.getDataType())
                        .attribute(PlanMarker.class, PlanMarker.GATHER)
                        .attribute(EdgeInfo.class, edge)
                        .build(), port);
            }
        }
    }

    private static void insertPlanMarkerForPreparingBroadcast(OperatorClass info) {
        for (OperatorInput port : info.getSecondaryInputs()) {
            if (isEmpty(port) == false) {
                EdgeInfo edge = new EdgeInfo(port.getDataType(), port.getGroup(), null);
                Operators.insert(MarkerOperator.builder(port.getDataType())
                        .attribute(PlanMarker.class, PlanMarker.BROADCAST)
                        .attribute(EdgeInfo.class, edge)
                        .build(), port);
            }
        }
    }

    private static boolean isEmpty(OperatorInput port) {
        for (Operator upstream : Operators.getPredecessors(Collections.singleton(port))) {
            PlanMarker marker = PlanMarkers.get(upstream);
            if (marker != PlanMarker.BEGIN) {
                return false;
            }
        }
        return true;
    }

    private static void insertPlanMarkerForEnsuringExternalInput(OperatorClass info) {
        if (info.getOperator().getOperatorKind() != OperatorKind.INPUT) {
            return;
        }
        ExternalInput input = (ExternalInput) info.getOperator();
        PlanMarkers.insert(PlanMarker.CHECKPOINT, input.getOperatorPort());
    }

    private static void insertPlanMarkerForPreparingExternalOutput(OperatorClass info) {
        if (info.getOperator().getOperatorKind() != OperatorKind.OUTPUT) {
            return;
        }
        ExternalOutput output = (ExternalOutput) info.getOperator();
        PlanMarkers.insert(PlanMarker.CHECKPOINT, output.getOperatorPort());
    }

    private static void removeCyclicBroadcast(OperatorGraph graph) {
        int step = 0;
        while (true) {
            step++;
            if (step > REDUCTION_STEP_LIMIT) {
                LOG.warn(MessageFormat.format(
                        "removing cyclic broadcast step was exceeded: {0}",
                        REDUCTION_STEP_LIMIT));
                break;
            }
            graph.rebuild();
            MarkerOperator target = Planning.findPotentiallyCyclicBroadcast(graph.getOperators(false));
            if (target == null) {
                break;
            }
            List<OperatorInput> targets = Operators.getSuccessors(target).stream()
                    .flatMap(it -> it.getInputs().stream())
                    .filter(it -> Operators.getPredecessors(Collections.singleton(it)).stream()
                            .noneMatch(PlanMarkers::exists))
                    .collect(Collectors.toList());
            Invariants.require(targets.isEmpty() == false);
            LOG.debug("resolving cyclic broadcast dependencies: {}", targets);
            targets.forEach(it -> PlanMarkers.insert(PlanMarker.CHECKPOINT, it));
        }
    }

    static PlanDetail createPlan(PlanningContext context, OperatorGraph normalized) {
        PlanDetail primitive = createPrimitivePlan(context, normalized);
        PlanDetail unified = unifySubPlans(context, primitive);

        SubPlanAnalyzer analyzer = SubPlanAnalyzer.newInstance(context, unified, normalized);
        decoratePlan(context, unified.getPlan(), analyzer);
        return unified;
    }

    private static PlanDetail createPrimitivePlan(PlanningContext context, OperatorGraph graph) {
        PlanDetail primitive = Planning.createPrimitivePlan(graph);
        return primitive;
    }

    private static PlanDetail unifySubPlans(PlanningContext context, PlanDetail primitive) {
        PlanAssembler assembler = Planning.startAssemblePlan(primitive)
                .withTrivialOutputElimination(true)
                .withRedundantOutputElimination(true)
                .withDuplicateCheckpointElimination(true)
                .withUnionPushDown(true)
                .withSortResult(true);
        OperatorEquivalence equivalence = PlanAssembler.DEFAULT_EQUIVALENCE;
        if (context.getOptions().contains(Option.UNIFY_SUBPLAN_IO)) {
            equivalence = new CustomEquivalence();
        }
        assembler.withCustomEquivalence(equivalence);
        Collection<SubPlanGroup> groups = classify(primitive);
        for (SubPlanGroup group : groups) {
            assembler.add(group.elements);
        }
        PlanDetail plan = assembler.build();
        return plan;
    }

    private static Collection<SubPlanGroup> classify(PlanDetail primitive) {
        List<SubPlanGroup> groups = new ArrayList<>();
        for (SubPlan subplan : primitive.getPlan().getElements()) {
            groups.add(SubPlanGroup.of(primitive, subplan));
        }
        groups = combineGroups(groups);

        Graph<SubPlan> dependencies = Planning.toDependencyGraph(primitive.getPlan());
        groups = splitGroups(dependencies, groups);

        return groups;
    }

    private static List<SubPlanGroup> combineGroups(List<SubPlanGroup> groups) {
        Map<Set<Operator>, SubPlanGroup> map = new LinkedHashMap<>();
        for (SubPlanGroup group : groups) {
            SubPlanGroup buddy = map.get(group.commonSources);
            if (buddy == null) {
                map.put(group.commonSources, group);
            } else {
                buddy.elements.addAll(group.elements);
            }
        }
        return new ArrayList<>(map.values());
    }

    private static List<SubPlanGroup> splitGroups(Graph<SubPlan> dependencies, List<SubPlanGroup> groups) {
        List<SubPlanGroup> results = new ArrayList<>(groups);
        List<SubPlanGroup> purged = new ArrayList<>();
        while (true) {
            for (SubPlanGroup group : results) {
                SubPlanGroup g = splitGroup(dependencies, group);
                if (g != null) {
                    purged.add(g);
                }
            }
            if (purged.isEmpty()) {
                break;
            } else {
                results.addAll(purged);
                purged.clear();
            }
        }
        return results;
    }

    private static SubPlanGroup splitGroup(Graph<SubPlan> dependencies, SubPlanGroup group) {
        assert group.elements.isEmpty() == false;
        if (group.elements.size() <= 1) {
            return null;
        }
        Set<SubPlan> blockers = computeBlockers(dependencies, group);
        List<SubPlan> purged = new ArrayList<>();
        for (Iterator<SubPlan> iter = group.elements.iterator(); iter.hasNext();) {
            SubPlan element = iter.next();
            if (blockers.contains(element)) {
                purged.add(element);
                iter.remove();
            }
        }
        assert group.elements.isEmpty() == false;
        if (purged.isEmpty()) {
            return null;
        }
        SubPlanGroup result = new SubPlanGroup(group.commonSources);
        result.elements.addAll(purged);
        return result;
    }

    private static Set<SubPlan> computeBlockers(Graph<SubPlan> dependencies, SubPlanGroup group) {
        Set<SubPlan> saw = new HashSet<>();
        Deque<SubPlan> work = new ArrayDeque<>(group.elements);
        while (work.isEmpty() == false) {
            SubPlan first = work.removeFirst();
            for (SubPlan blocker : dependencies.getConnected(first)) {
                if (saw.contains(blocker)) {
                    continue;
                }
                saw.add(blocker);
                work.add(blocker);
            }
        }
        return saw;
    }

    private static void decoratePlan(PlanningContext context, Plan plan, SubPlanAnalyzer analyzer) {
        attachCoreInfo(plan, analyzer);
        attachNameInfo(plan);
        attachBroadcastInfo(plan, analyzer);
        if (context.getOptions().contains(Option.SIZE_ESTIMATION)) {
            attachSizeInfo(context, plan);
            attachPartitionGroupInfo(context, plan);
        }
        if (context.getOptions().contains(Option.ITERATIVE_EXTENSION)) {
            IterativeOperationAnalyzer.attach(plan);
        }
        if (context.getOptions().contains(Option.PARAMETER_DETECTION)) {
            attachParameterInfo(plan);
        }
        if (context.getOptions().contains(Option.GRAPH_STATISTICS)) {
            attachGraphStatistics(plan);
        }
        if (context.getOptions().contains(Option.PLAN_STATISTICS)) {
            attachPlanStatistics(plan);
        }
    }

    private static void attachCoreInfo(Plan plan, SubPlanAnalyzer analyzer) {
        for (SubPlan sub : plan.getElements()) {
            SubPlanInfo info = analyzer.analyze(sub);
            assert info.getOrigin() == sub;
            sub.putAttribute(SubPlanInfo.class, info);
        }
        for (SubPlan sub : plan.getElements()) {
            for (SubPlan.Input input : sub.getInputs()) {
                SubPlanInputInfo info = analyzer.analyze(input);
                assert info.getOrigin() == input;
                input.putAttribute(SubPlanInputInfo.class, info);
            }
            for (SubPlan.Output output : sub.getOutputs()) {
                SubPlanOutputInfo info = analyzer.analyze(output);
                assert info.getOrigin() == output;
                output.putAttribute(SubPlanOutputInfo.class, info);
            }
        }

    }

    private static void attachNameInfo(Plan plan) {
        Util.computeIds("v", Util.sortElements(plan)).forEach(NameInfo::bind);
        Util.computeIds("i", plan.getElements(), Util::sortInputs).forEach(NameInfo::bind);
        Util.computeIds("o", plan.getElements(), Util::sortOutputs).forEach(NameInfo::bind);
    }

    private static void attachBroadcastInfo(Plan plan, SubPlanAnalyzer analyzer) {
        for (SubPlan sub : plan.getElements()) {
            for (SubPlan.Input port : sub.getInputs()) {
                BroadcastInfo info = analyzer.analyzeBroadcast(port);
                if (info != null) {
                    port.putAttribute(BroadcastInfo.class, info);
                }
            }
            for (SubPlan.Output port : sub.getOutputs()) {
                BroadcastInfo info = analyzer.analyzeBroadcast(port);
                if (info != null) {
                    port.putAttribute(BroadcastInfo.class, info);
                }
            }
        }
    }

    private static void attachSizeInfo(PlanningContext context, Plan plan) {
        PlanEstimator estimator = new PlanEstimator(context.getEstimator(), context.getOptimizerContext());
        for (SubPlan sub : plan.getElements()) {
            for (SubPlan.Input port : sub.getInputs()) {
                SizeInfo info = estimator.estimate(port);
                assert info != null;
                port.putAttribute(SizeInfo.class, info);
            }
            for (SubPlan.Output port : sub.getOutputs()) {
                SizeInfo info = estimator.estimate(port);
                assert info != null;
                port.putAttribute(SizeInfo.class, info);
            }
        }
    }

    private static void attachPartitionGroupInfo(PlanningContext context, Plan plan) {
        CompilerOptions options = context.getOptimizerContext().getOptions();
        Map<PartitionGroupInfo.DataSize, Double> limits = PartitionGroupAnalyzer.loadLimitMap(options);
        Map<SubPlan, DataSize> explicits = PartitionGroupAnalyzer.loadExplicitSizeMap(options, plan);
        PartitionGroupAnalyzer groupAnalyzer = new PartitionGroupAnalyzer(limits, explicits);
        for (SubPlan sub : plan.getElements()) {
            for (SubPlan.Input port : sub.getInputs()) {
                PartitionGroupInfo info = groupAnalyzer.analyze(port);
                if (info != null) {
                    port.putAttribute(PartitionGroupInfo.class, info);
                }
            }
            for (SubPlan.Output port : sub.getOutputs()) {
                PartitionGroupInfo info = groupAnalyzer.analyze(port);
                if (info != null) {
                    port.putAttribute(PartitionGroupInfo.class, info);
                }
            }
        }
    }

    private static void attachParameterInfo(Plan plan) {
        plan.putAttribute(ParameterInfo.class, ParameterInfo.of(plan));
    }

    private static void attachGraphStatistics(Plan plan) {
        plan.putAttribute(GraphStatistics.class, GraphStatistics.of(Planning.toDependencyGraph(plan)));
        for (SubPlan sub : plan.getElements()) {
            sub.putAttribute(GraphStatistics.class, GraphStatistics.of(Planning.toDependencyGraph(sub)));
        }
    }

    private static void attachPlanStatistics(Plan plan) {
        plan.putAttribute(PlanStatistics.class, PlanStatistics.of(plan));
    }

    private static class SubPlanGroup {

        final Set<Operator> commonSources;

        final List<SubPlan> elements = new LinkedList<>();

        SubPlanGroup(Set<Operator> commonSources) {
            this.commonSources = Collections.unmodifiableSet(commonSources);
        }

        public static SubPlanGroup of(PlanDetail detail, SubPlan source) {
            Set<Operator> sources = new LinkedHashSet<>();
            for (SubPlan.Input input : source.getInputs()) {
                PlanMarker marker = PlanMarkers.get(input.getOperator());
                if (marker != PlanMarker.BROADCAST) {
                    sources.add(detail.getSource(input.getOperator()));
                }
            }
            assert sources.isEmpty() == false;
            SubPlanGroup group = new SubPlanGroup(sources);
            group.elements.add(source);
            return group;
        }
    }

    private static class CustomEquivalence implements OperatorEquivalence {

        CustomEquivalence() {
            return;
        }

        @Override
        public Object extract(SubPlan owner, Operator operator) {
            SubPlan.Input input = owner.findInput(operator);
            if (input != null) {
                return extract(input);
            }
            SubPlan.Output output = owner.findOutput(operator);
            if (output != null) {
                return extract(output);
            }
            return PlanAssembler.DEFAULT_EQUIVALENCE.extract(owner, operator);
        }

        private Object extract(SubPlan.Input port) {
            MarkerOperator operator = port.getOperator();
            PlanMarker marker = PlanMarkers.get(operator);
            assert marker != null;
            switch (marker) {
            case CHECKPOINT:
                return operator.getDataType();
            case BROADCAST:
                assert operator.getAttribute(EdgeInfo.class) != null;
                return operator.getAttribute(EdgeInfo.class);
            default:
                return PlanAssembler.DEFAULT_EQUIVALENCE.extract(port.getOwner(), operator);
            }
        }

        private Object extract(SubPlan.Output port) {
            MarkerOperator operator = port.getOperator();
            PlanMarker marker = PlanMarkers.get(operator);
            assert marker != null;
            switch (marker) {
            case CHECKPOINT:
                return operator.getDataType();
            case GATHER:
            case BROADCAST:
                assert operator.getAttribute(EdgeInfo.class) != null;
                return operator.getAttribute(EdgeInfo.class);
            default:
                return PlanAssembler.DEFAULT_EQUIVALENCE.extract(port.getOwner(), operator);
            }
        }
    }

    private static class EdgeInfo {

        private final TypeDescription type;

        private final Group partition;

        private final Object aggregation;

        EdgeInfo(TypeDescription type, Group partition, Operator aggregation) {
            assert type != null;
            this.type = type;
            this.partition = partition;
            this.aggregation = aggregation == null ? null : aggregation.getOriginalSerialNumber();
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + Objects.hashCode(type);
            result = prime * result + Objects.hashCode(partition);
            result = prime * result + Objects.hashCode(aggregation);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            EdgeInfo other = (EdgeInfo) obj;
            if (!Objects.equals(type, other.type)) {
                return false;
            }
            if (!Objects.equals(partition, other.partition)) {
                return false;
            }
            if (!Objects.equals(aggregation, other.aggregation)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return MessageFormat.format(
                    "EdgeInfo(group={0}, partial={1})", //$NON-NLS-1$
                    partition,
                    aggregation != null);
        }
    }
}
