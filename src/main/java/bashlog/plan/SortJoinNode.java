package bashlog.plan;

import common.Tools;
import common.plan.node.JoinNode;
import common.plan.node.PlanNode;

import java.util.Arrays;

/** Join two sorted inputs based on ONE column */
public class SortJoinNode extends JoinNode {

  private final int[] outputProjection;

  public SortJoinNode(PlanNode left, PlanNode right, int[] leftJoinProjection, int[] rightJoinProjection) {
    this(left, right, leftJoinProjection, rightJoinProjection, Tools.sequence(left.getArity() + right.getArity()));
  }

  public SortJoinNode(PlanNode left, PlanNode right, int[] leftJoinProjection, int[] rightJoinProjection, int[] outputProjection) {
    super(left, right, leftJoinProjection, rightJoinProjection);
    if (leftJoinProjection.length > 1) throw new UnsupportedOperationException("sort join does not support sorting on more than one column");
    if (leftJoinProjection.length != rightJoinProjection.length) throw new UnsupportedOperationException("join requires one column on each child");
    this.outputProjection = outputProjection;
  }

  @Override
  public String operatorString() {
    return super.operatorString().replaceFirst("_", "_sort_") + " out " + Arrays.toString(outputProjection);
  }

  @Override
  public PlanNode transform(TransformFn fn, PlanNode originalParent) {
    return fn.apply(this,
        new SortJoinNode(getLeft().transform(fn, this), getRight().transform(fn, this), getLeftProjection(), getRightProjection(),
            outputProjection),
        originalParent);
  }

  public int[] getOutputProjection() {
    return outputProjection;
  }

  @Override
  public int getArity() {
    return outputProjection.length;
  }

}
