package common.plan.node;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Returns tuple in left such that there exists no tuple in right with the left projection of left is the same as the right projection of right
 */
public class AntiJoinNode implements PlanNode {

  private final PlanNode left;

  private final PlanNode right;

  private final int[] leftProjection;


  public AntiJoinNode(PlanNode left, PlanNode right, int[] leftProjection) {
    if (leftProjection.length != right.getArity()) {
      throw new IllegalArgumentException("The left projection and the right plan for anti join should have the same size");
    }
    if (Arrays.stream(leftProjection).anyMatch(i -> i >= left.getArity())) {
      throw new IllegalArgumentException("Invalid left projection: try to project a not existing field");
    }

    this.left = left;
    this.right = right;
    this.leftProjection = leftProjection;
  }

  public PlanNode getLeft() {
    return left;
  }

  public PlanNode getRight() {
    return right;
  }

  public int[] getLeftProjection() {
    return leftProjection;
  }

  @Override
  public int getArity() {
    return left.getArity();
  }

  @Override
  public String toString() {
    return left.toString() + operatorString() + right.toString();
  }

  @Override
  public String operatorString() {
    return "▷_{" + IntStream.range(0, leftProjection.length).mapToObj(i -> leftProjection[i] + "=" + i)
            .collect(Collectors.joining(", ")) + "}";
  }

  @Override
  public List<PlanNode> children() {
    return Arrays.asList(left, right);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj.getClass() == getClass())) {
      return false;
    }
    AntiJoinNode node = (AntiJoinNode) obj;
    return (left.equals(node.left) && right.equals(node.right) && Arrays.equals(leftProjection, node.leftProjection));
  }

  @Override
  public int hashCode() {
    return left.hashCode() ^ right.hashCode() ^ Arrays.hashCode(leftProjection);
  }

  @Override
  public PlanNode transform(TransformFn fn, PlanNode originalParent) {
    return fn.apply(this, new AntiJoinNode(left.transform(fn, this), right.transform(fn, this), leftProjection), originalParent);
  }
}
