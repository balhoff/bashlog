package bashlog;

import bashlog.command.Bash;
import bashlog.command.Bash.Pipe;
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
import java.util.stream.Stream;

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

  /** Maps a materialization node to its temporary file. Reuse nodes use the filename of the materialized relation. */
  Map<PlaceholderNode, String> placeholderToFilename = new HashMap<>();

  /** Query plan which should be translated */
  PlanNode root;

  /** Save debug information (query plans)*/
  private String debug = "";

  private boolean profile;

  public static enum ParallelMaterializationMethod {
    NONE, FLOCK, PIPE
  }

  private ParallelMaterializationMethod parallelMaterialization = ParallelMaterializationMethod.PIPE;

  /** Stores the compiled bash script */
  private String bash = null;

  private List<List<Optimizer>> stages = Arrays.asList(//
      Arrays.asList(new SimplifyRecursion(), new PushDownJoin(), new ReorderJoinLinear(), new PushDownFilterAndProject(), new SimplifyRecursion(),
          new PushDownFilterAndProject()),
      Arrays.asList(r -> r.transform(this::transform), new BashlogOptimizer(), new MultiOutput(), new CombineFilter(false), new Materialize(),
          new CombineFilter(false)));

  public BashlogCompiler(PlanNode planNode) {
    if (planNode == null) {
      throw new IllegalArgumentException("cannot compile an empty plan");
    }
    root = planNode;
    debug += "orig\n";
    debug += root.toPrettyString() + "\n";
    root = new SortNode(root, null);

    List<String> stageNames = Arrays.asList("simplification", "optimization", "transforming to bashlog plan");
    Iterator<String> it = stageNames.iterator();
    PlanValidator check = new PlanValidator();
    for (List<Optimizer> stage : stages) {
      debug += "\n\n" + (it.hasNext() ? it.next() : "") + "\n";
      for (Optimizer o : stage) {
        root = o.apply(root);

        debug += "applied " + o.getClass() + " \n";
        debug += root.toPrettyString() + "\n";

        try {
          check.apply(root);
        } catch (Exception e) {
          LOG.error(e.getMessage());
          debug += "WARNING: " + e.getMessage();
        }
      }
    }
    debug = "#" + debug.replaceAll("\n", "\n# ");
  }

  public String compile() {
    if (bash == null) {
      bash = compile("", true);
    }
    return bash;
  }

  public String compile(String indent, boolean comments) {
    StringBuilder header = new StringBuilder();
    // we generate a bash script (shebang)
    header.append("#!/bin/bash\n");
    // set LC_ALL for efficiency and consistency between sort and join command
    header.append("export LC_ALL=C\n");
    // for temporary files
    header.append("mkdir -p tmp\n");
    header.append("rm tmp/*\n");
    // use mawk if possible for better performance
    header.append("if type mawk > /dev/null; then awk=\"mawk\"; else awk=\"awk\"; fi\n");
    // tweak sort
    header.append("sort=\"sort -S25% --parallel=2 \"\n\n");

    if (profile) {
      header.append("PATH=$PATH:.\n");
      header.append("mkdir /tmp/ttime/\n");
      header.append("rm /tmp/ttime/*\n");
      header.append("if type mawk > /dev/null; then awk=\"ttime mawk\"; else awk=\"ttime awk\"; fi\n");
      header.append("sort=\"ttime sort -S64M --parallel=2 \"\n\n");
    }

    Bash e = compile(root);
    String result = header.toString() + e.generate(profile);

    return result;
  }

  public void enableProfiling() {
    this.profile = true;
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

    } else if (p instanceof AntiJoinNode) {
      AntiJoinNode ajn = (AntiJoinNode) p;
      PlanNode left = prepareSortJoin(ajn.getLeft(), ajn.getLeftProjection());
      PlanNode right = prepareSortJoin(ajn.getRight(), Tools.sequence(ajn.getRight().getArity()));

      if (ajn.getLeftProjection().length == 1) {
        // no combined column necessary, so we can directly return the join
        return new SortAntiJoinNode(left, right, ajn.getLeftProjection());
      }
      // remove extra columns
      PlanNode antijoin = new SortAntiJoinNode(left, right.project(new int[] { right.getArity() - 1 }), new int[] { left.getArity() - 1 });
      return antijoin.project(Tools.sequence(left.getArity() - 1));

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
      //return children.stream().map(i -> (PlanNode) new SortNode(i, null)).reduce(PlanNode.empty(u.getArity()), PlanNode::union);
      return u;

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
  private Bash leftHashJoin(JoinNode j, StringBuilder sb, String indent) {
    Bash.Command cmd = new Bash.Command(AWK);
    cmd.arg("NR==FNR { ");
    cmd.arg("key = ");
    cmd.arg(keyMask(j.getLeftProjection()));
    cmd.arg("; h[key] = $0; ");
    cmd.arg("next } \n");
    cmd.arg(" { ");
    cmd.arg("key = ");
    cmd.arg(keyMask(j.getRightProjection()));
    cmd.arg("; if (key in h) {");
    cmd.arg(" print h[key] FS $0");
    cmd.arg(" } }' \\\n");
    cmd.file(compile(j.getLeft()));
    cmd.arg(" \\\n");
    cmd.file(compile(j.getRight()));
    return cmd;
  }

  /**
   * Sort the output of n on columns; might need to introduce a new column
   * @param s
   * @return column which is sorted (1-based index)
   */
  private Bash sort(SortNode s) {
    int[] cols = s.sortColumns();
    Bash prev = compile(s.children().get(0));
    Bash.Pipe result = new Bash.Pipe(prev);
    Bash.Command cmd = result.cmd("$sort").arg("-t $'\\t'");

    boolean supportsUniq = cols == null;
    if (cols != null) {
      int used[] = new int[s.getTable().getArity()];
      Arrays.fill(used, 0);
      for (int col : cols) {
        cmd.arg("-k " + (col + 1));
        used[col] = 1;
      }
      if (Arrays.stream(used).allMatch(k -> k == 1)) {
        supportsUniq = true;
      }
    }
    if (supportsUniq) {
      cmd.arg("-u");
    }
    cmd.file(prev);

    return waitFor(cmd, s.children());
  }

  /** Sort left and right tree, and join with 'join' command */
  private Bash sortJoin(SortJoinNode j, String additionalArgs) {
    int colLeft, colRight;
    colLeft = j.getLeftProjection()[0] + 1;
    colRight = j.getRightProjection()[0] + 1;

    Bash.Command result = new Bash.Command("join");
    if (profile) result = new Bash.Command("ttime join");
    result.arg(additionalArgs);
    result.arg("-t $'\\t'");
    result.arg("-1 " + colLeft);
    result.arg("-2 " + colRight);

    StringBuilder outCols = new StringBuilder();
    for (int i = 0; i < j.getOutputProjection().length; i++) {
      if (i > 0) {
        outCols.append(",");
      }
      int dst = j.getOutputProjection()[i];
      if (dst < j.getLeft().getArity()) {
        outCols.append("1." + (dst + 1));
      } else {
        outCols.append("2." + (dst - j.getLeft().getArity() + 1));
      }
    }
    result.arg("-o " + outCols);

    result.file(compile(j.getLeft()));
    result.file(compile(j.getRight()));
    return result;
  }

  /** Remove all lines from pipe that occur in filename */
  private Bash setMinusInMemory(Bash prev, String filename) {
    Bash.Pipe result = prev.pipe();
    result.cmd("grep")//
        .arg("-v").arg("-F").arg("-f")//
        .file(filename);
    return result;
  }

  private Bash setMinusSorted(Bash prev, String filename) {
    Bash.Pipe result = prev.pipe();
    result.cmd("comm")//
        .arg("--nocheck-order").arg("-23").arg("-")//
        .file(filename);
    return result;
  }

  private Bash recursionSorted(RecursionNode rn, String fullFile, String deltaFile, String newDeltaFile) {
    Bash prev = compile(rn.getRecursivePlan());
    //setMinusInMemory(fullFile, sb);
    Bash delta = setMinusSorted(prev, fullFile);
    delta = delta.wrap("", " > " + newDeltaFile + ";");

    Bash.CommandSequence result = new Bash.CommandSequence();
    result.add(delta);
    result.info(rn, "continued");
    result.cmd("mv").file(newDeltaFile).file(deltaFile).arg("; ");
    result.cmd("$sort")//
        .arg("-u").arg("--merge").arg("-o")//
        .file(fullFile).file(fullFile).file(deltaFile).arg("; ");

    return result;
  }

  private void awkEquality(EqualityFilterNode planNode, StringBuilder init, StringBuilder cond) {
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

  private Bash waitFor(Bash bash, List<PlanNode> children) {
    Bash result = bash;
    if (parallelMaterialization != ParallelMaterializationMethod.NONE) {
      for (PlanNode child : children) {
        if (child instanceof PlaceholderNode) {
          PlanNode parent = ((PlaceholderNode) child).getParent();
          if (parent instanceof MaterializationNode) {
            String matFile = matNodeToFilename.get(parent);
            if (parallelMaterialization == ParallelMaterializationMethod.FLOCK) {
              result = new Bash.Command("flock").arg("-s " + matFile + " ").other(result);
            } else if (parallelMaterialization == ParallelMaterializationMethod.PIPE) {
              result = new Bash.Command("cat").arg(matFile.replace("tmp/", "tmp/lock_")).arg("1>&2").arg("; ").other(result);
            }
          }
        }
      }
    }
    return result;
  }

  private Bash recursionInMemory(RecursionNode rn, String fullFile, String deltaFile, String newDeltaFile) {
    // untested
    Bash prev = compile(rn.getRecursivePlan());
    prev = setMinusInMemory(prev, fullFile);
    Bash.Pipe pipe = prev.pipe();
    pipe.cmd("tee").arg("-a").file(fullFile);
    pipe.wrap("", " > " + newDeltaFile + "\n");
    Bash.CommandSequence cmd = new Bash.CommandSequence();
    cmd.add(pipe);
    cmd.cmd("mv").file(newDeltaFile).file(deltaFile);
    return cmd;
  }

  private Bash compile(PlanNode planNode) {
    return waitFor(compileIntern(planNode), planNode.children());
  }

  /**
   * @param planNode
   * @return
   */
  private Bash compileIntern(PlanNode planNode) {
    if (planNode instanceof MaterializationNode) {
      MaterializationNode m = (MaterializationNode) planNode;
      String matFile = "tmp/mat" + tmpFileIndex++;
      matNodeToFilename.putIfAbsent(m, matFile);
      Bash.CommandSequence result = new Bash.CommandSequence();
      result.comment(planNode, "");

      boolean asFile = m.getReuseCount() <= 1;
      asFile = true;
      result.info(planNode, "");

      /*if (!asFile) {
        matNodeToCount.put(m, new AtomicInteger(0));
        Bash.Command cmd = result.cmd("mkfifo");
        ctx.append("mkfifo");
        for (int i = 0; i < m.getReuseCount(); i++) {
          cmd.file(matFile + "_" + i);
          ctx.append(" " + matFile + "_" + i);
        }
        ctx.append("\n");
      }
      //...
         for (int i = 0; i < m.getReuseCount(); i++) {
          ctx.append(i < m.getReuseCount() - 1 ? " | tee " : " > ");
          ctx.append(matFile + "_" + i);
          ctx.append(" &");
        }     
      */

      Bash reused = compile(m.getReusedPlan());
      if (asFile) {
        if (parallelMaterialization == ParallelMaterializationMethod.FLOCK) {
          reused = reused.wrap("(flock 1; (", "; \\\n flock --unlock 1)& )");
          reused = reused.wrap("", " 1> " + matFile);
        } else if (parallelMaterialization == ParallelMaterializationMethod.PIPE) {
          String lockFile = matFile.replaceAll("tmp/", "tmp/lock_");
          String doneFile = matFile.replaceAll("tmp/", "tmp/done_");
          reused = reused.wrap("mkfifo " + lockFile + "; ( ", //
              " > " + matFile + //
                  "; mv " + lockFile + " " + doneFile + //
                  "; cat " + doneFile + " > /dev/null & " + //
                  "exec 3> " + doneFile + "; exec 3>&-;" + //
                  " ) & ");
        } else {
          reused = reused.wrap("", " > " + matFile);
        }
        result.add(reused);
      } else {
        throw new UnsupportedOperationException();
      }

      if (!(m.getMainPlan() instanceof MaterializationNode)) {
        result.other("\n# plan");
      }
      result.add(compile(m.getMainPlan()));
      return result;

    } else if (planNode instanceof MultiOutputNode) {
      Bash.CommandSequence result = new Bash.CommandSequence();
      Bash.Command touch = result.cmd("touch");
      Bash.Command cmd = result.cmd(AWK);
      MultiOutputNode mo = (MultiOutputNode) planNode;

      StringBuilder arg = new StringBuilder();
      List<PlanNode> plans = mo.reusedPlans(), nodes = mo.reuseNodes();
      for (int i = 0; i < plans.size(); i++) {
        PlanNode plan = plans.get(i), node = nodes.get(i);

        String matFile = "tmp/mat" + tmpFileIndex++;
        touch.file(matFile);
        placeholderToFilename.putIfAbsent((PlaceholderNode) node, matFile);

        //TODO: if there are more conditions on one output file:
        // if (!complexAwkLine(Arrays.asList(plan), matFile, arg).isEmpty()) { ... }
        simpleAwkLine(plan, matFile, arg);
      }
      cmd.arg(arg.toString()).arg("'");
      cmd.file(compile(mo.getLeaf()));
      result.add(compile(mo.getMainPlan()));
      return result;

    } else if (planNode instanceof SortNode) {
      return sort((SortNode) planNode);

      // check for sort anti join node first, as it's also a sort join node
    } else if (planNode instanceof SortAntiJoinNode) {
      SortAntiJoinNode j = (SortAntiJoinNode) planNode;
      return sortJoin(j, " -v 1 ");

    } else if (planNode instanceof SortJoinNode) {
      SortJoinNode j = (SortJoinNode) planNode;
      //leftHashJoin(j, sb, indent);
      return sortJoin(j, "");

    } else if (planNode instanceof CombinedColumnNode) {
      CombinedColumnNode c = (CombinedColumnNode) planNode;
      Bash prev = compile(c.getTable());

      Bash.Pipe result = new Bash.Pipe(prev);
      Bash.Command cmd = result.cmd(AWK);
      StringBuilder sb = new StringBuilder();
      sb.append("{ print $0 FS ");
      for (int i = 0; i < c.getColumns().length; i++) {
        if (i > 0) {
          sb.append(" \"\\002\" ");
        }
        sb.append("$" + (c.getColumns()[i] + 1));
      }
      sb.append("}'");
      cmd.arg(sb.toString());
      return result;

    } else if (planNode instanceof ProjectNode || planNode instanceof EqualityFilterNode) {
      StringBuilder awk = new StringBuilder();
      PlanNode inner = simpleAwkLine(planNode, null, awk);

      StringBuilder advAwk = new StringBuilder();
      if (complexAwkLine(Arrays.asList(planNode), null, advAwk).isEmpty()) {
        awk = advAwk;
      }

      return new Bash.Command(AWK).arg(awk.toString()).arg("'").file(compile(inner));
    }

    /*else if (planNode instanceof ProjectNode) {
      // TODO: filtering of duplicates might be necessary
      ProjectNode p = ((ProjectNode) planNode);
      Bash prev = compile(p.getTable());
      Pipe result = new Bash.Pipe(prev);
      Bash.Command cmd = result.cmd(AWK);
      cmd.arg("{ print " + awkProject(p) + "}'");
      return result;
    
    } else if (planNode instanceof EqualityFilterNode) {
      Bash.Command cmd = new Bash.Command(AWK);
      cmd.arg(awkEquality((EqualityFilterNode) planNode));
      cmd.arg(" { print $0 }' ");
      cmd.file(compile(((EqualityFilterNode) planNode).getTable()));
      return cmd;
    
    }*/ else if (planNode instanceof MultiFilterNode) {
      MultiFilterNode m = (MultiFilterNode) planNode;
      Bash.Command cmd = new Bash.Command(AWK);

      StringBuilder arg = new StringBuilder();
      List<PlanNode> remaining = complexAwkLine(m.getFilter(), null, arg);

      // process remaining filter nodes
      for (PlanNode c : remaining) {
        simpleAwkLine(c, null, arg);
      }
      arg.append("' ");
      cmd.arg(arg.toString());
      cmd.file(compile(m.getTable()));

      return cmd;
    } else if (planNode instanceof BuiltinNode)

    {
      CompoundTerm ct = ((BuiltinNode) planNode).compoundTerm;
      if ("bash_command".equals(ct.name)) {
        String command = ((Constant<?>) ct.args[0]).getValue().toString();
        // remove newline from command
        return new Bash.Command(command.trim());
      } else {
        throw new UnsupportedOperationException("predicate not supported: " + ct.getRelation());
      }

    } else if (planNode instanceof TSVFileNode) {
      TSVFileNode file = (TSVFileNode) planNode;
      return new Bash.BashFile(file.getPath());

    } else if (planNode instanceof UnionNode) {
      if (planNode.children().size() == 0) {
        return new Bash.Command("echo").arg("-n");
      } else {
        /*Bash.Command result = new Bash.Command("$sort").arg("-u").arg("-m");
        for (PlanNode child : ((UnionNode) planNode).getChildren()) {
          result.file(compile(child));
        }
        return result;*/
        Bash.Command result = new Bash.Command("$sort").arg("-u");
        for (PlanNode child : ((UnionNode) planNode).getChildren()) {
          result.file(compile(child));
        }
        return result;
      }

    } else if (planNode instanceof SortRecursionNode) {
      RecursionNode rn = (RecursionNode) planNode;
      int idx = tmpFileIndex++;
      String deltaFile = "tmp/delta" + idx;
      String newDeltaFile = "tmp/new" + idx;
      String fullFile = "tmp/full" + idx;
      recursionNodeToIdx.put(rn, idx);

      Bash.CommandSequence result = new Bash.CommandSequence();
      Bash b = compile(rn.getExitPlan());
      Bash.Pipe pipe = b.pipe();
      Bash.Command cmd = pipe.cmd("tee");
      cmd.file(fullFile);
      result.add(pipe.wrap("", " > " + deltaFile));

      // "do while" loop in bash
      result.cmd("while \n");

      result.add(recursionSorted(rn, fullFile, deltaFile, newDeltaFile));
      //recursionInMemory(rn, sb, indent, fullFile, deltaFile, newDeltaFile);
      result.cmd("[ -s " + deltaFile + " ]; ");
      result.cmd("do continue; done\n");
      result.cmd("rm").file(deltaFile).wrap("", "\n");
      if (profile) result.cmd("ttime cat").file(fullFile);
      else result.cmd("cat").file(fullFile);
      return result;

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

        return new Bash.BashFile(file);
      } else if (parent instanceof MaterializationNode) {
        String matFile = matNodeToFilename.get(parent);
        if (matFile == null) {
          matNodeToFilename.forEach((m, f) -> System.err.println(m.operatorString() + "  " + f));
          throw new IllegalStateException("no file assigned to " + planNode.operatorString() + " for materialization " + parent.operatorString());
        }
        AtomicInteger useCount = matNodeToCount.get(parent);
        if (useCount != null) {
          matFile = matFile + "_" + useCount.getAndIncrement();
        }
        return new Bash.BashFile(matFile);
      } else if (parent instanceof MultiOutputNode) {
        String matFile = placeholderToFilename.get(planNode);
        return new Bash.BashFile(matFile);
      } else {
        LOG.error("compilation of " + planNode.getClass() + " not yet supported");
        return null;
      }
    } else {
      LOG.error("compilation of " + planNode.getClass() + " not yet supported");
      return null;
    }
  }

  /**
   * Translate select/project to awk, and return first plan that cannot be translated this way
   * @param plan consisting of selections and (at most one) projections
   * @param output if null output to stdout, otherwise output to file
   * @return
   */
  private PlanNode simpleAwkLine(PlanNode plan, String output, StringBuilder arg) {
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
      arg.append("BEGIN { ");
      arg.append(init.stream().collect(Collectors.joining(" ")));
      arg.append(" }");
    }
    arg.append(conditions.stream().collect(Collectors.joining(" && ")));
    arg.append(" { print ");
    if (p == null) {
      arg.append("$0");
    } else {
      arg.append(awkProject(p));
    }
    if (output != null) {
      arg.append(" >> \"").append(output).append("\"");
    }
    arg.append("} ");
    return plan;
  }

  /**
   * Build an AWK program that uses hash table look up instead of "==" if conditions
   * @param plans containing all the same leaf (sub-tree)
   * @param output which file the output should be written
   * @param awkProg accumulator for the awk program
   * @return remaining all plan nodes that couldn't be processed that way
   */
  private List<PlanNode> complexAwkLine(Collection<PlanNode> plans, String output, StringBuilder awkProg) {
    // replace value-filters by associative array lookup
    // first, collect all filters that can be combined
    Map<List<Integer>, Map<List<Integer>, List<List<Comparable<?>>>>> outputColToFilteredColToValues = new HashMap<>();
    List<PlanNode> remaining = plans.stream().filter(pn -> {
      Map<Integer, Comparable<?>> filterCols = new TreeMap<>();
      List<Integer> outputCols = new ArrayList<>();
      getCols(pn, outputCols, filterCols);
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

      awkProg.append("BEGIN { ");
      outputColToFilteredColToValues.forEach((outCols, map) -> {
        map.forEach((filterCols, values) -> {
          values.forEach(vals -> {
            if (output != null) {
              awkProg.append(output.replace("tmp/", ""));
            }
            awkProg.append("out").append(joinStr(outCols, "c"));
            awkProg.append("_cond").append(joinStr(filterCols, "c"));
            awkProg.append("[\"").append(joinStr(vals, "\" FS \"")).append("\"] = \"1\"; ");
          });
        });
      });
      awkProg.append(" } ");

      // filter lines using arrays
      outputColToFilteredColToValues.forEach((outCols, map) -> {
        awkProg.append("(");
        awkProg.append(map.keySet().stream().map(filterCols -> {
          String condition = (String) "(" + joinStr(filterCols.stream().map(i -> "$" + (i + 1)), " FS ") + ")" + //
          " in ";
          if (output != null) {
            condition += output.replace("tmp/", "");
          }
          condition += "out" + joinStr(outCols, "c") + "_cond" + joinStr(filterCols, "c");

          return condition;
        }).collect(Collectors.joining(" || ")));

        awkProg.append(") ");
        awkProg.append("{ print ").append(joinStr(outCols.stream().map(i -> "$" + (i + 1)), " FS "));
        if (output != null) {
          awkProg.append(" >> \"").append(output).append("\"");
        }
        awkProg.append(" } ");
      });
    }
    return remaining;
  }

  private <T> String joinStr(Collection<T> outCols, String delimiter) {
    return joinStr(outCols.stream(), delimiter);
  }

  private <T> String joinStr(Stream<T> outCols, String delimiter) {
    return outCols.map(t -> t.toString()).collect(Collectors.joining(delimiter));
  }

  /**
   * Get projection array, and columns and constants for filters
   * @param node
   * @param projCols accumulator
   * @param filterCols accumulator
   * @return inner plan node
   */
  private PlanNode getCols(PlanNode node, List<Integer> projCols, Map<Integer, Comparable<?>> filterCols) {
    if (node instanceof ConstantEqualityFilterNode) {
      ConstantEqualityFilterNode eq = (ConstantEqualityFilterNode) node;
      filterCols.put(eq.getField(), eq.getValue());
      return getCols(eq.getTable(), projCols, filterCols);
    }
    if (node instanceof VariableEqualityFilterNode) {
      // make getCols return null (checked below)
      return null;
    }
    if (node instanceof ProjectNode) {
      // may only have one projection!
      if (!projCols.isEmpty()) throw new UnsupportedOperationException(((ProjectNode) node).getTable().toString());
      ProjectNode p = (ProjectNode) node;
      Arrays.stream(p.getProjection()).forEach(i -> projCols.add(i));
      return getCols(p.getTable(), projCols, filterCols);
    }
    return null;
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

  /*public static void main(String[] args) {
    PlanNode table = new TSVFileNode("abc", 5);
    MultiFilterNode mfn = new MultiFilterNode(new HashSet<>(Arrays.asList(
        //table.equalityFilter(1, 2).project(new int[] { 1, 2 }), //
        table.equalityFilter(3, "abc").project(new int[] { 1, 2 }), //
        table.equalityFilter(3, "def").project(new int[] { 1, 2 }), //
        table.equalityFilter(3, "ghi").project(new int[] { 1, 2 }), //
        table.equalityFilter(1, 2).project(new int[] { 3, 4 }))), table, 2);
  
    BashlogCompiler bc = new BashlogCompiler(mfn);
    Bash b = bc.compileIntern(mfn);
    System.out.println(b.generate());
  }*/

}
