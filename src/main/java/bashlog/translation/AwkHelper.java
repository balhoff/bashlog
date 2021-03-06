package bashlog.translation;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import common.plan.node.*;

/** Stores helper functions that are used during bashlog compilation */
public class AwkHelper {

  /** Escape string for usage in awk */
  public static String escape(String str) {
    return str.replaceAll("\"", "\\\"").replaceAll("'", "'\\''");
  }

  public static String awkProject(ProjectNode p) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < p.getProjection().length; i++) {
      if (i != 0) sb.append(" FS ");
      if (p.getProjection()[i] >= 0) {
        sb.append("$");
        sb.append(p.getProjection()[i] + 1);
      } else {
        p.getConstant(i).ifPresent(cnst -> sb.append("\"" + escape(cnst.toString()) + "\""));
      }
    }
    return sb.toString();
  }

  public static void awkEquality(EqualityFilterNode planNode, StringBuilder init, StringBuilder cond) {
    if (planNode instanceof ConstantEqualityFilterNode) {
      ConstantEqualityFilterNode n = (ConstantEqualityFilterNode) planNode;
      cond.append("$");
      cond.append(n.getField() + 1);
      cond.append(" == \"");
      cond.append(escape(n.getValue().toString()));
      cond.append("\"");
    } else if (planNode instanceof VariableEqualityFilterNode) {
      VariableEqualityFilterNode n = (VariableEqualityFilterNode) planNode;
      cond.append("$");
      cond.append(n.getField1() + 1);
      cond.append(" == $");
      cond.append(n.getField2() + 1);
    }
  }


  private static <T> String joinColStr(Stream<T> cols, String delimiter) {
    return cols.map(t -> t.toString()).collect(Collectors.joining(delimiter));
  }

  private static <T> String joinColStr(Collection<T> outCols, String delimiter) {
    return joinColStr(outCols.stream(), delimiter);
  }

  /**
  * Get projection array, and columns and constants for filters
  * @param node
  * @param projCols accumulator
  * @param filterCols accumulator
  * @param outputConstToInteger map a constant of a projection to a integer &lt; 0
  * @return inner plan node
  */
  private static PlanNode getCols(PlanNode node, List<Integer> projCols, Map<Integer, Comparable<?>> filterCols, Map<Comparable<?>, Integer> outputConstToInteger) {
    if (node instanceof ConstantEqualityFilterNode) {
      ConstantEqualityFilterNode eq = (ConstantEqualityFilterNode) node;
      filterCols.put(eq.getField(), eq.getValue());
      return getCols(eq.getTable(), projCols, filterCols, outputConstToInteger);
    }
    if (node instanceof VariableEqualityFilterNode) {
      // make getCols return null (checked below)
      return null;
    }
    if (node instanceof ProjectNode) {
      // may only have one projection!
      if (!projCols.isEmpty()) throw new UnsupportedOperationException(((ProjectNode) node).getTable().toString());
      ProjectNode p = (ProjectNode) node;
      int[] proj = p.getProjection();
      Comparable<?>[] cnst = p.getConstants();
      
      for(int col=0; col<p.getArity(); col++) {
        int id = proj[col];
        if(id < 0) {
          id = outputConstToInteger.computeIfAbsent(cnst[col], k -> -outputConstToInteger.size() -2);
        }
        projCols.add(id);
      }
      
      return getCols(p.getTable(), projCols, filterCols, outputConstToInteger);
    }
    return null;
  }

  /**
   * Build an AWK program that uses hash table look up instead of "==" if conditions
   * @param plans containing all the same leaf (sub-tree)
   * @param output which file the output should be written
   * @param awkProg accumulator for the awk program
   * @return remaining all plan nodes that couldn't be processed that way
   */
  public static List<PlanNode> complexAwkLine(Collection<PlanNode> plans, int idx, String output, StringBuilder awkProg) {
    // replace value-filters by associative array lookup
    // first, collect all filters that can be combined
    Map<List<Integer>, Map<List<Integer>, List<List<Comparable<?>>>>> outputColToFilteredColToValues = new HashMap<>();
    Map<Comparable<?>, Integer> outputConstToInteger = new HashMap<>();

    List<PlanNode> remaining = plans.stream().filter(pn -> {
      //return true;
      Map<Integer, Comparable<?>> filterCols = new TreeMap<>();
      List<Integer> outputCols = new ArrayList<>();
      getCols(pn, outputCols, filterCols, outputConstToInteger);
      if (outputCols == null || filterCols.size() == 0) return true;
  
      outputColToFilteredColToValues.computeIfAbsent( //
          outputCols, //
          k -> new HashMap<>()) //
          .computeIfAbsent(new ArrayList<>(filterCols.keySet()), k -> new ArrayList<>())//
          .add(new ArrayList<Comparable<?>>(filterCols.values()));
  
      return false;
    }).collect(Collectors.toList());

    if (outputColToFilteredColToValues.size() > 0) {
      // create arrays outCOLS_condCOLS[VAL] = "1";
      // where COLS looks like 0c1c2

      awkProg.append("\n  BEGIN { \n ");
      outputColToFilteredColToValues.forEach((outCols, map) -> {
        map.forEach((filterCols, values) -> {
          values.forEach(vals -> {
            awkProg.append("  ");
            if (output != null) {
              awkProg.append(output.replace("tmp/", "")).append("_");
            }
            awkProg.append("out").append(joinColStr(outCols, "c").replace("-", "_"));
            awkProg.append("_cond").append(joinColStr(filterCols, "c").replace("-", "_"));
            awkProg.append("[\"").append(joinColStr(vals, "\" FS \"")).append("\"] = \"1\"; \n ");
          });
        });
      });
      awkProg.append(" }\n\n ");

      
    Map<Integer, Comparable<?>> integerToOutputConst = new HashMap<>();
    outputConstToInteger.forEach((k,v) -> integerToOutputConst.put(v, k));
    
      // filter lines using arrays
      outputColToFilteredColToValues.forEach((outCols, map) -> {
        String conditions = map.keySet().stream().map(filterCols -> {
          String condition = (String) "(" + joinColStr(filterCols.stream().map(i -> "$" + (i + 1)), " FS ") + ")" + //
          " in ";
          if (output != null) {
            condition += output.replace("tmp/", "") + "_";
          }
          condition += ("out" + joinColStr(outCols, "c") + "_cond" + joinColStr(filterCols, "c")).replace("-", "_");
  
          return condition;
        }).collect(Collectors.joining(" || ")).trim();

        if (conditions.length() > 0) {
          awkProg.append("(").append(conditions).append(")");
        }
        Stream<String> awkOutCols = outCols.stream().map(i -> i<0 ? "\"" + integerToOutputConst.get(i) + "\"" :  "$" + (i + 1));
        awkProg.append("{ print ").append(joinColStr(awkOutCols, " FS "));
        if (output != null) {
          awkProg.append(" >> \"").append(output).append("\"");
        }
        awkProg.append(" } \n ");
      });
    }
    return remaining;
  }


  public static void multioutAwkLine(PlanNode plan, int idx, String output, StringBuilder arg) {
    if (!(plan instanceof UnionNode)) {
      simpleAwkLine(plan, output, arg);
      return;
    }
    Map<String, Set<String>> projectToConditions = new HashMap<>();
    UnionNode u = (UnionNode) plan;
    /*for (PlanNode p : u.children()) {
      StringBuilder project = new StringBuilder(), condition = new StringBuilder();
      simpleAwkLine(p, output, arg, project, condition);
      projectToConditions.computeIfAbsent(project.toString(), k -> new HashSet<>()).add(condition.toString());
    }*/
    List<PlanNode> remaining = complexAwkLine(u.children(), idx, output, arg);
    
    arg.append(projectToConditions.entrySet().stream().map(e -> {
      return e.getValue().stream().collect(Collectors.joining(" || ")) + e.getKey();
    }).collect(Collectors.joining(" \n ")));
    
    remaining.forEach(p -> simpleAwkLine(p, output, arg));
  }

  /**
   * Translate select/project to awk, and return first plan that cannot be translated this way
   * @param plan consisting of selections and (at most one) projections
   * @param output if null output to stdout, otherwise output to file
   * @return
   */
  public static PlanNode simpleAwkLine(PlanNode plan, String output, StringBuilder arg) {
    StringBuilder project = new StringBuilder(), condition = new StringBuilder();
    PlanNode result = simpleAwkLine(plan, output, arg, project, condition);
    arg.append(condition).append(project);
    return result;
  }

  public static PlanNode simpleAwkLine(PlanNode plan, String output, StringBuilder arg, StringBuilder project, StringBuilder condition) {
    ProjectNode p = null;
    List<String> init = new ArrayList<>();
    List<String> conditions = new ArrayList<>();
    do {
      if (plan instanceof ProjectNode) {
        if (p != null) throw new IllegalStateException("currently only one projection supported");
        p = (ProjectNode) plan;
        plan = p.getTable();
      } else if (plan instanceof EqualityFilterNode) {
        StringBuilder initLine = new StringBuilder(), condLine = new StringBuilder();
        awkEquality((EqualityFilterNode) plan, initLine, condLine);
        conditions.add(condLine.toString());
        plan = ((EqualityFilterNode) plan).getTable();
      } else {
        break;
      }
    } while (true);
    if (init.size() > 0) {
      arg.append("BEGIN { \n");
      arg.append("    ").append(init.stream().collect(Collectors.joining(" ")));
      arg.append(" }\n");
    }
    String awkConditions = conditions.stream().collect(Collectors.joining(" && ")).trim();
    if (awkConditions.length() > 0) {
      condition.append("(").append(awkConditions).append(")");
    }
    project.append(" { print ");
    if (p == null) {
      project.append("$0");
    } else {
      project.append(awkProject(p));
    }
    if (output != null) {
      project.append(" >> \"").append(output).append("\" ");
    }
    project.append("} \n ");
    return plan;
  }

  public final static String AWK = "$awk -v FS=$'\\t' '";

}
