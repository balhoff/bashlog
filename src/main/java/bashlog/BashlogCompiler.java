package bashlog;

import bashlog.plan.*;
import common.Tools;
import common.parser.CompoundTerm;
import common.parser.Constant;
import common.parser.ParserReader;
import common.parser.Program;
import common.plan.LogicalPlanBuilder;
import common.plan.node.*;
import common.plan.optimizer.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 
 * @author Thomas Rebele
 */
public class BashlogCompiler {

  private static final Logger LOG = LoggerFactory.getLogger(BashlogCompiler.class);

  /** Current index for temporary files. Increment when using it! */
  int tmpFileIndex = 0;

  /** Maps a recursion node to its temporary file index. Delta nodes use it to infer the filenames generated by the recursion node. */
  Map<RecursionNode, Integer> recursionNodeToIdx = new HashMap<>();

  /** Maps a materialization node to its temporary file. Reuse nodes use the filename of the materialized relation. */
  Map<MaterializationNode, String> matNodeToFilename = new HashMap<>();

  /** Maps a materialization node to its use count. Applies only if materialized a finite number of times */
  Map<MaterializationNode, AtomicInteger> matNodeToCount = new HashMap<>();

  /** Query plan which should be translated */
  PlanNode root;

  /** Save debug information (query plans)*/
  private String debug = "";

  public BashlogCompiler(PlanNode planNode) {
    if (planNode == null) {
      throw new IllegalArgumentException("cannot compile an empty plan");
    }
    root = planNode;
    debug += "orig\n";
    debug += root.toPrettyString() + "\n";

    root = new SimplifyPlan().apply(new SortNode(root, null));
    root = new PushDownFilterAndProject().apply(root);

    debug += "simplified\n";
    debug += root.toPrettyString() + "\n";

    root = new ReorderJoin().apply(root);
    root = new SimplifyPlan().apply(root);
    root = new PushDownFilterAndProject().apply(root);

    root = new SimplifyPlan().apply(root);
    root = new PushDownFilterAndProject().apply(root);
    //root = new SimplifyPlan().apply(root);
    //root = new PushDownFilterAndProject().apply(root);

    root = new CombineFilter(false).apply(root);

    debug += "optimized\n";
    debug += root.toPrettyString() + "\n";

    root = root.transform(this::transform);

    debug += "bashlog plan" + "\n";
    debug += root.toPrettyString() + "\n";

    root = new BashlogOptimizer().apply(root);
    root = new Materialize().apply(root);
    //root = new MultiFilterOptimizer(true).apply(root);

    debug += "optimized bashlog plan\n";
    debug += root.toPrettyString();
    debug = "#" + debug.replaceAll("\n", "\n# ");
  }

  public String compile() {
    return compile("", true);
  }

  public String compile(String indent, boolean comments) {
    Context ctx = new Context();
    ctx.comments = false;
    // we generate a bash script (shebang)
    ctx.append("#!/bin/bash\n");
    // set LC_ALL for efficiency and consistency between sort and join command
    ctx.append("export LC_ALL=C\n");
    // for temporary files
    ctx.append("mkdir -p tmp\n");
    // use mawk if possible for better performance
    ctx.append("if [ \"$awk\" == \"\" ]; then if type mawk > /dev/null; then awk=\"mawk\"; else awk=\"awk\"; fi fi\n");
    // tweak sort
    ctx.append("sort=\"sort -S64M --parallel=2 \"\n\n");

    compile(root, ctx);

    System.out.println(debugInfo());
    System.out.println(ctx.generate());
    return ctx.generate();
  }

  public String debugInfo() {
    return debug;
  }

  /** Adds extra column with dummy value */
  private PlanNode prepareSortCrossProduct(PlanNode p) {
    int[] proj = new int[p.getArity() + 1];
    Comparable<?>[] cnst = new Comparable[proj.length];
    for (int i = 0; i < p.getArity(); i++) {
      proj[i + 1] = i;
    }
    proj[0] = -1;
    cnst[0] = "_";
    return p.project(proj, cnst);
  }

  private PlanNode prepareSortJoin(PlanNode p, int[] columns) {
    if (columns.length == 1) {
      return new SortNode(p, columns);
    }
    CombinedColumnNode c = new CombinedColumnNode(p, columns);
    return new SortNode(c, new int[] { p.getArity() });
  }

  /** Replace certain common.plan.* nodes with their bashlog implementations */
  private PlanNode transform(PlanNode p) {
    if (p instanceof JoinNode) {
      // replace join node with sort join node
      JoinNode joinNode = (JoinNode) p;
      if (joinNode.getLeftProjection().length == 0) {
        // no join condition, so do a cross product
        // sort input and add a dummy column
        PlanNode left = prepareSortCrossProduct(joinNode.getLeft());
        PlanNode right = prepareSortCrossProduct(joinNode.getRight());
        PlanNode crossProduct = new SortJoinNode(left, right, new int[] { 0 }, new int[] { 0 });

        // remove extra columns
        int[] proj = new int[left.getArity() + right.getArity() - 2];
        for (int i = 1; i < left.getArity(); i++) {
          proj[i - 1] = i;
        }
        for (int i = 1; i < right.getArity(); i++) {
          proj[left.getArity() - 2 + i] = left.getArity() + i;
        }
        return crossProduct.project(proj);
      } else {
        // sort input and add combined column if necessary
        PlanNode left = prepareSortJoin(joinNode.getLeft(), joinNode.getLeftProjection());
        PlanNode right = prepareSortJoin(joinNode.getRight(), joinNode.getRightProjection());
        if (joinNode.getLeftProjection().length == 1) {
          // no combined column necessary, so we can directly return the join
          return new SortJoinNode(left, right, joinNode.getLeftProjection(), joinNode.getRightProjection());
        }
        // remove extra columns
        PlanNode join = new SortJoinNode(left, right, new int[] { left.getArity() - 1 }, new int[] { right.getArity() - 1 });
        int rightStart = left.getArity();
        return join.project(Tools.concat(Tools.sequence(left.getArity() - 1), Tools.sequence(rightStart, rightStart + right.getArity() - 1)));
      }

    } else if (p instanceof RecursionNode) {
      // use sorted recursion
      RecursionNode r = (RecursionNode) p;
      return new SortRecursionNode(new SortNode(r.getExitPlan(), null), new SortNode(r.getRecursivePlan(), null), r.getDelta(), r.getFull());

    } else if (p instanceof UnionNode) {
      UnionNode u = (UnionNode) p;
      List<PlanNode> children = u.children();
      if (children.size() == 0) return u;
      if (children.size() == 1) return children.get(0);
      // use sort union, so sort all inputs
      return new UnionNode(children.stream().map(i -> new SortNode(i, null)).collect(Collectors.toSet()), u.getArity());

    } else if (p instanceof BuiltinNode) {
      // instead of "<(cat file)", use file directly
      BuiltinNode b = (BuiltinNode) p;
      if ("bash_command".equals(b.compoundTerm.name)) {
        String cmd = (String) ((Constant<?>) b.compoundTerm.args[0]).getValue();
        if (cmd.startsWith("cat ")) {
          ParserReader pr = new ParserReader(cmd);
          pr.expect("cat ");
          pr.skipWhitespace();
          String file;
          if (pr.peek() == '\"' || pr.peek() == '\'') file = pr.readString();
          else file = pr.readWhile((c, s) -> !Character.isWhitespace(c));
          pr.skipWhitespace();
          if (pr.peek() == '\0') {
            return new TSVFileNode(file, p.getArity());
          }
        }
      }
      // check whether bashlog supports builtin predicate b is done in compile(...)
    }

    return p;
  }

  /** Awk colums used as key for join */
  private static String keyMask(int[] columns) {
    StringBuilder mask = new StringBuilder();
    for (int i = 0; i < columns.length; i++) {
      if (mask.length() > 0) mask.append(" FS ");
      mask.append("$");
      mask.append(columns[i] + 1);
    }
    return mask.toString();

  }

  /** Escape string for usage in awk */
  private static String escape(String str) {
    return str.replaceAll("\"", "\\\"").replaceAll("'", "'\\''");
  }

  final static String INDENT = "    ";

  final static String AWK = "$awk -v FS=$'\\t' '";

  /** Awk command which caches the left subtree, and joins it with the right subtree */
  private void leftHashJoin(JoinNode j, StringBuilder sb, String indent, Context ctx) {
    sb.append(indent + AWK + "NR==FNR { ");
    sb.append("key = ");
    sb.append(keyMask(j.getLeftProjection()));
    sb.append("; h[key] = $0; ");
    sb.append("next } \n");
    sb.append(" { ");
    sb.append("key = ");
    sb.append(keyMask(j.getRightProjection()));
    sb.append("; if (key in h) {");
    sb.append(" print h[key] FS $0");
    sb.append(" } }' \\\n");
    compile(j.getLeft(), ctx.file());
    sb.append(" \\\n");
    compile(j.getRight(), ctx.file());
  }

  /**
   * Sort the output of n on columns; might need to introduce a new column
   * @param s
   * @param ctx
   * @return column which is sorted (1-based index)
   */
  private void sort(SortNode s, Context ctx) {
    int[] cols = s.sortColumns();
    ctx.startPipe();
    compile(s.children().get(0), ctx.pipe());
    ctx.append(" \\\n");
    ctx.info(s);
    ctx.append(" | $sort -t $'\\t' ");
    boolean supportsUniq = cols == null;
    if (cols != null) {
      int used[] = new int[s.getTable().getArity()];
      Arrays.fill(used, 0);
      for (int col : cols) {
        ctx.append("-k ");
        ctx.append(col + 1);
        ctx.append(" ");
        used[col] = 1;
      }
      if (Arrays.stream(used).allMatch(k -> k == 1)) {
        supportsUniq = true;
      }
    }
    if (supportsUniq) {
      ctx.append("-u ");
    }
    ctx.endPipe();
  }

  /** Sort left and right tree, and join with 'join' command */
  private void sortJoin(SortJoinNode j, Context ctx) {
    ctx.startPipe();
    int colLeft, colRight;
    colLeft = j.getLeftProjection()[0] + 1;
    colRight = j.getRightProjection()[0] + 1;

    ctx.append("join -t $'\\t' -1 ");
    ctx.append(colLeft);
    ctx.append(" -2 ");
    ctx.append(colRight);
    ctx.append(" -o ");

    for (int i = 0; i < j.getOutputProjection().length; i++) {
      if (i > 0) ctx.append(",");
      int dst = j.getOutputProjection()[i];
      if (dst < j.getLeft().getArity()) {
        ctx.append("1." + (dst + 1));
      } else {
        ctx.append("2." + (dst - j.getLeft().getArity() + 1));
      }
    }
    ctx.append(" ");

    compile(j.getLeft(), ctx.file());
    compile(j.getRight(), ctx.file());
    ctx.append("");
    ctx.endPipe();
  }

  /** Remove all lines from pipe that occur in filename */
  private void setMinusInMemory(String filename, Context ctx) {
    ctx.append(" | grep -v -F -f ");
    ctx.append(filename);
  }

  private void setMinusSorted(String filename, Context ctx) {
    ctx.append(" | comm --nocheck-order -23 - ");
    ctx.append(filename);
  }

  private void recursionSorted(RecursionNode rn, Context ctx, String fullFile, String deltaFile, String newDeltaFile) {
    ctx.startPipe();
    compile(rn.getRecursivePlan(), ctx.pipe());
    ctx.append(" \\\n" + INDENT);
    //setMinusInMemory(fullFile, sb);
    setMinusSorted(fullFile, ctx);
    ctx.append(" > " + newDeltaFile + "\n");

    ctx.info(rn, "continued");
    ctx.append(INDENT + "mv " + newDeltaFile + " " + deltaFile + "; \n");
    ctx.append(INDENT + "$sort -u --merge -o " + fullFile + " " + fullFile + " <($sort " + deltaFile + ")\n");
    ctx.endPipe();
  }

  private String awkEquality(EqualityFilterNode planNode) {
    StringBuilder sb = new StringBuilder();
    if (planNode instanceof ConstantEqualityFilterNode) {
      ConstantEqualityFilterNode n = (ConstantEqualityFilterNode) planNode;
      sb.append("$");
      sb.append(n.getField() + 1);
      sb.append(" == \"");
      sb.append(escape(n.getValue().toString()));
      sb.append("\"");
    } else if (planNode instanceof VariableEqualityFilterNode) {
      VariableEqualityFilterNode n = (VariableEqualityFilterNode) planNode;
      sb.append("$");
      sb.append(n.getField1() + 1);
      sb.append(" == $");
      sb.append(n.getField2() + 1);
    }
    return sb.toString();
  }

  private String awkProject(ProjectNode p) {
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

  private void recursionInMemory(RecursionNode rn, Context ctx, String fullFile, String deltaFile, String newDeltaFile) {
    ctx.startPipe();
    compile(rn.getRecursivePlan(), ctx.pipe());
    ctx.append(" \\\n");
    ctx.append(INDENT);
    setMinusInMemory(fullFile, ctx);
    ctx.append(" | tee -a " + fullFile);
    ctx.append(" > " + newDeltaFile + "\n");
    ctx.append(INDENT + "mv " + newDeltaFile + " " + deltaFile + "; \n");
    ctx.endPipe();
  }

  private void compile(PlanNode planNode, Context ctx) {
    if (planNode instanceof MaterializationNode) {
      ctx.startPipe();
      MaterializationNode m = (MaterializationNode) planNode;
      String matFile = "tmp/mat" + tmpFileIndex++;
      matNodeToFilename.putIfAbsent(m, matFile);

      boolean asFile = m.getReuseCount() <= 1;
      asFile = true;
      ctx.append("\n");
      ctx.info(planNode);

      if (!asFile) {
        matNodeToCount.put(m, new AtomicInteger(0));
        ctx.append("mkfifo");
        for (int i = 0; i < m.getReuseCount(); i++) {
          ctx.append(" " + matFile + "_" + i);
        }
        ctx.append("\n");
      }
      compile(m.getReusedPlan(), ctx.pipe());
      ctx.append(" \\\n");
      if (asFile) {
        ctx.append(" > ");
        ctx.append(matFile);
      } else {
        for (int i = 0; i < m.getReuseCount(); i++) {
          ctx.append(i < m.getReuseCount() - 1 ? " | tee " : " > ");
          ctx.append(matFile + "_" + i);
        }
        ctx.append(" &");
      }
      ctx.append("\n");

      if (!(m.getMainPlan() instanceof MaterializationNode)) {
        ctx.append("# plan\n");
      }
      compile(m.getMainPlan(), ctx.pipe());
      ctx.endPipe();

    } else if (planNode instanceof SortNode) {
      SortNode s = (SortNode) planNode;
      sort(s, ctx);
    } else if (planNode instanceof SortJoinNode) {
      ctx.info(planNode);
      SortJoinNode j = (SortJoinNode) planNode;
      //leftHashJoin(j, sb, indent);
      sortJoin(j, ctx);
    } else if (planNode instanceof CombinedColumnNode) {
      CombinedColumnNode c = (CombinedColumnNode) planNode;
      ctx.startPipe();
      compile(c.getTable(), ctx.pipe());
      ctx.append(" \\\n");
      ctx.info(planNode);
      ctx.append(" | " + AWK + "{ print $0 FS ");
      for (int i = 0; i < c.getColumns().length; i++) {
        if (i > 0) {
          ctx.append(" \"\\002\" ");
        }
        ctx.append("$");
        ctx.append(c.getColumns()[i] + 1);
      }
      ctx.append("}'");
      ctx.endPipe();
    } else if (planNode instanceof ProjectNode) {
      // TODO: filtering of duplicates might be necessary
      ctx.startPipe();
      ProjectNode p = ((ProjectNode) planNode);
      compile(p.getTable(), ctx.pipe());
      ctx.append(" \\\n");
      ctx.info(planNode);
      ctx.append(" | " + AWK + "{ print ");
      ctx.append(awkProject(p));
      ctx.append("}'");
      ctx.endPipe();
    } else if (planNode instanceof EqualityFilterNode) {
      ctx.startPipe();
      ctx.append(AWK);
      ctx.append(awkEquality((EqualityFilterNode) planNode));
      ctx.append(" { print $0 }' ");
      compile(((EqualityFilterNode) planNode).getTable(), ctx.file());
      ctx.endPipe();
    } else if (planNode instanceof MultiFilterNode) {
      MultiFilterNode m = (MultiFilterNode) planNode;
      ctx.startPipe();
      ctx.append(AWK);

      for (PlanNode c : m.getFilter()) {
        // do we need this actually?
        c = new PushDownFilterAndProject().apply(c);
        ProjectNode p = null;
        List<String> conditions = new ArrayList<>();
        do {
          if (c instanceof ProjectNode) {
            if (p != null) throw new IllegalStateException("currently only one projection supported");
            p = (ProjectNode) c;
            c = p.getTable();
          } else if (c instanceof EqualityFilterNode) {
            conditions.add(awkEquality((EqualityFilterNode) c));
            c = ((EqualityFilterNode) c).getTable();
          } else {
            break;
          }
        } while (true);
        ctx.append(conditions.stream().collect(Collectors.joining(" && ")));
        ctx.append(" { print ");
        if (p == null) ctx.append("$0");
        else ctx.append(awkProject(p));
        ctx.append("} ");
      }

      ctx.append("' ");
      compile(m.getInnerTable(), ctx.file());
      ctx.endPipe();
    } else if (planNode instanceof BuiltinNode) {
      CompoundTerm ct = ((BuiltinNode) planNode).compoundTerm;
      if ("bash_command".equals(ct.name)) {
        ctx.startPipe();
        ctx.append(((Constant<?>) ct.args[0]).getValue());
        ctx.endPipe();
      } else {
        throw new UnsupportedOperationException("predicate not supported: " + ct.getRelation());
      }
    } else if (planNode instanceof TSVFileNode) {
      TSVFileNode file = (TSVFileNode) planNode;
      if (!ctx.isFile()) {
        ctx.append("cat ");
      }
      ctx.append(file.getPath());
    } else if (planNode instanceof UnionNode) {
      ctx.startPipe();
      ctx.append("$sort -u -m ");
      for (PlanNode child : ((UnionNode) planNode).getChildren()) {
        compile(child, ctx.file());
      }
      ctx.endPipe();
    } else if (planNode instanceof SortRecursionNode) {
      ctx.startPipe();
      ctx.info(planNode);
      RecursionNode rn = (RecursionNode) planNode;
      int idx = tmpFileIndex++;
      String deltaFile = "tmp/delta" + idx;
      String newDeltaFile = "tmp/new" + idx;
      String fullFile = "tmp/full" + idx;
      recursionNodeToIdx.put(rn, idx);
      compile(rn.getExitPlan(), ctx.pipe());
      ctx.append(" | tee " + fullFile + " > " + deltaFile + "\n");

      // "do while" loop in bash
      ctx.indent().append("while \n");

      recursionSorted(rn, ctx.pipe(), fullFile, deltaFile, newDeltaFile);
      //recursionInMemory(rn, sb, indent, fullFile, deltaFile, newDeltaFile);
      ctx.append("[ -s " + deltaFile + " ]; \n");
      ctx.append("do continue; done\n");
      ctx.append("rm " + deltaFile + " " + newDeltaFile + "\n");
      ctx.append("cat " + fullFile);
      ctx.endPipe();
    } else if (planNode instanceof PlaceholderNode) {
      PlanNode parent = ((PlaceholderNode) planNode).getParent();
      if (parent instanceof RecursionNode) {
        RecursionNode rec = (RecursionNode) parent;
        String file;
        if (Objects.equals(planNode, rec.getDelta())) {
          file = "tmp/delta" + recursionNodeToIdx.get(rec);
        } else if (Objects.equals(planNode, rec.getFull())) {
          file = "tmp/full" + recursionNodeToIdx.get(rec);
        } else {
          throw new UnsupportedOperationException("token of recursion must be either full or delta node");
        }
        if (ctx.isFile()) {
          ctx.append(file);
        } else {
          ctx.startPipe();
          ctx.append("cat " + file);
          ctx.endPipe();
        }
      } else if (parent instanceof MaterializationNode) {
        ctx.append("\\\n");
        ctx.info(planNode);
        String matFile = matNodeToFilename.get(parent);
        if (!ctx.isFile()) {
          ctx.startPipe();
          ctx.append("cat ");
        }
        ctx.append(matFile);
        AtomicInteger useCount = matNodeToCount.get(parent);
        if (useCount != null) {
          ctx.append("_").append(useCount.getAndIncrement());
        }
        if (!ctx.isFile()) {
          ctx.endPipe();
        }
      }
    } else {
      LOG.error("compilation of " + planNode.getClass() + " not yet supported");
    }
  }

  /** Transform datalog program and query relation to a bash script. */
  public static String compileQuery(Program p, String query) throws IOException {
    BashlogCompiler bc = prepareQuery(p, query);
    try {
      String bash = bc.compile("", false);
      return bash + "\n\n"; //+ bc.debugInfo();
    } catch (Exception e) {
      LOG.error(bc.debugInfo());
      throw (e);
    }
  }

  /** Initialize bashlog compiler with program and query relation. */
  public static BashlogCompiler prepareQuery(Program p, String query) {
    Set<String> builtin = new HashSet<>();
    builtin.add("bash_command");
    Map<String, PlanNode> plan = new LogicalPlanBuilder(builtin).getPlanForProgram(p);

    PlanNode pn = plan.get(query);
    BashlogCompiler bc = new BashlogCompiler(pn);
    return bc;
  }

}
