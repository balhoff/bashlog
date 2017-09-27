package common.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import common.parser.CompoundTerm;
import common.parser.Program;
import common.parser.Rule;
import common.parser.Term;
import common.parser.Variable;

public class LogicalPlanBuilder {

  private Map<String, PlanNode> planForRelation = new HashMap<>();

  private Map<String, List<Rule>> rulesByHeadRelation = new HashMap<>();

  public Map<String, PlanNode> getPlanForProgram(Program program, Set<String> builtin) {
    Set<String> relations = new HashSet<>();
    program.rules.forEach(rule -> {
      rulesByHeadRelation.computeIfAbsent(rule.head.signature(), (k) -> new ArrayList<>()).add(rule);
      relations.add(rule.head.signature());
      for (CompoundTerm ct : rule.body) {
        relations.add(ct.signature());
      }
    });

    Map<String, PlanNode> planNodes = new HashMap<>();
    relations.forEach(relation -> planNodes.put(relation, getPlanForRelation(relation, builtin)));
    return planNodes;
  }

  static final Pattern REMOVE_ARITY = Pattern.compile("\\/.*");

  private PlanNode createTableNode(String relation, Set<String> builtin) {
    String name = REMOVE_ARITY.matcher(relation).replaceAll("");
    if (builtin.contains(name)) {
      return new TableNode(relation + "TEST");
    }
    return new TableNode(relation);
  }

  private PlanNode getPlanForRelation(String relation, Set<String> builtin) {
    return planForRelation.computeIfAbsent(relation,
        (rel) -> rulesByHeadRelation.getOrDefault(rel, Collections.emptyList()).stream() //
            .map(r -> getPlanForRule(r, builtin)).reduce(UnionNode::new) //
            .map(node -> node.union(createTableNode(relation, builtin))).orElse(createTableNode(relation, builtin)));
  }

  private PlanNode getPlanForRule(Rule rule, Set<String> builtin) throws Error {
    //TODO: check if the rule is sane
    //a(X,Y,c) <- b(X,d), c(X,Y)
    //b.filter(1, d).map([0, -1]).join(c.map([0, 1])).project([0, 1], [null, null, c])

    //Variables integer encoding
    Map<Term, Integer> variablesEncoding = new HashMap<>();
    rule.body.stream().forEach(tuple -> Arrays.stream(tuple.args).flatMap(t -> t.getVariables()).forEach(arg -> {
      if (arg instanceof Variable) {
        variablesEncoding.computeIfAbsent(arg, (key) -> variablesEncoding.size());
      }
    }));

    //Body tuples filter and map
    PlanNode body = rule.body.stream().map(tuple -> {
      PlanNode tupleNode = getPlanForRelation(tuple.signature(), builtin);

      int[] projection = new int[variablesEncoding.size()];
      Arrays.fill(projection, -1);
      boolean[] mask = new boolean[variablesEncoding.size()];

      Map<Term, Integer> seenVariables = new HashMap<>();
      for (int i = 0; i < tuple.args.length; i++) {
        Term arg = tuple.args[i];
        if (arg instanceof Variable) {
          if (!builtin.contains(tuple.name) && seenVariables.containsKey(arg)) { //Already in a tuple, we are in a tuple(X,X) case
            tupleNode = tupleNode.equalityFilter(seenVariables.get(arg).intValue(), i);
          }
          projection[variablesEncoding.get(arg)] = i;
          mask[variablesEncoding.get(arg)] = true;
          seenVariables.put(arg, i);
        } else {
          if (!builtin.contains(tuple.name)) tupleNode = tupleNode.equalityFilter(i, arg);
        }
      }

      return new NodeWithMask(tupleNode.project(projection), mask);
    }).reduce((nm1, nm2) -> {
      boolean[] intersectionMask = new boolean[nm1.mask.length];
      boolean[] unionMask = new boolean[nm1.mask.length];
      for (int i = 0; i < nm2.mask.length; i++) {
        intersectionMask[i] = nm1.mask[i] && nm2.mask[i];
        unionMask[i] = nm1.mask[i] || nm2.mask[i];
      }
      return new NodeWithMask(new JoinNode(nm1.node, nm2.node, intersectionMask), unionMask);
    }).map(nm -> nm.node).orElseThrow(() -> new UnsupportedOperationException("Rule without body"));

    int[] resultProjection = new int[rule.head.args.length];
    Arrays.fill(resultProjection, -1);
    Object[] resultConstants = new Object[rule.head.args.length];
    for (int i = 0; i < rule.head.args.length; i++) {
      Term arg = rule.head.args[i];
      if (arg instanceof Variable) {
        resultProjection[i] = variablesEncoding.get(arg);
      } else {
        resultConstants[i] = arg;
      }
    }
    return body.project(resultProjection, resultConstants);
  }

  private static class NodeWithMask {

    private PlanNode node;

    private boolean[] mask;

    NodeWithMask(PlanNode node, boolean[] mask) {
      this.node = node;
      this.mask = mask;
    }
  }
}