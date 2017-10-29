package common.plan;

import java.util.Objects;

import org.junit.Assert;
import org.junit.Test;

import common.parser.CompoundTerm;
import common.parser.TermList;
import common.parser.Variable;

public class PlanNodeTest {

  TermList args2 = new TermList(new Variable("X"), new Variable("Y"));

  TermList args3 = new TermList(new Variable("X"), new Variable("Y"), new Variable("Z"));

  TermList args4 = new TermList(new Variable("X"), new Variable("Y"), new Variable("Z"), new Variable("W"));

  TermList args5 = new TermList(new Variable("X"), new Variable("Y"), new Variable("Z"), new Variable("W"), new Variable("V"));

  @Test
  public void testPlanNode() {
    PlanNode foo = new BuiltinNode(new CompoundTerm("foo", args2));
    PlanNode bar = new BuiltinNode(new CompoundTerm("bar", args2));
    Assert.assertTrue((new VariableEqualityFilterNode(foo, 1, 1)).contains(foo));
    Assert.assertFalse((new VariableEqualityFilterNode(foo, 1, 1)).contains(bar));
    Assert.assertEquals(
            new VariableEqualityFilterNode(foo, 1, 1),
            (new VariableEqualityFilterNode(bar, 1, 1)).replace(bar, foo)
    );
  }

  @Test
  public void testSimplifier() {
    PlanSimplifier simplifier = new PlanSimplifier();
    PlanNode foo = new BuiltinNode(new CompoundTerm("foo", args2));
    PlanNode bar = new BuiltinNode(new CompoundTerm("bar", args2));
    PlanNode baz = new BuiltinNode(new CompoundTerm("bar", args4));
    Assert.assertEquals(
            new UnionNode(foo, bar),
            simplifier.apply(new UnionNode(foo, new UnionNode(bar, PlanNode.empty(2))))
    );
    Assert.assertEquals(
            foo,
            simplifier.apply(new UnionNode(foo, new UnionNode(foo, PlanNode.empty(2))))
    );

    Assert.assertEquals(
            foo,
            simplifier.apply(new ProjectNode(foo, new int[]{0, 1}))
    );
    Assert.assertEquals(
            PlanNode.empty(2),
            simplifier.apply(new ProjectNode(PlanNode.empty(2), new int[]{0, 0}))
    );
    Assert.assertEquals(
            new ProjectNode(baz, new int[]{0, 1, 3}),
            simplifier.apply(new ProjectNode(
                    new ProjectNode(baz, new int[]{3, 0, 1}),
                    new int[]{1, 2, 0}))
    );

    Assert.assertEquals(
            PlanNode.empty(4),
            simplifier.apply(new JoinNode(foo, PlanNode.empty(2), new int[]{0}, new int[]{0}))
    );
    Assert.assertEquals(
            simplifier.apply(new JoinNode(foo, bar, new int[]{0}, new int[]{0})),
            simplifier.apply(new JoinNode(foo, bar, new int[]{0}, new int[]{0}))
    );

    RecursionNode recursionNode = new RecursionNode(foo);
    recursionNode.addRecursivePlan(recursionNode.getDelta().join(bar, new int[]{0}, new int[]{0}).project(new int[]{0, 1}));
    Assert.assertTrue(simplifier.apply(recursionNode) instanceof RecursionNode);

    RecursionNode recursionNode2 = new RecursionNode(PlanNode.empty(2));
    recursionNode2.addRecursivePlan(recursionNode2.getDelta());
    Assert.assertEquals(PlanNode.empty(2), simplifier.apply(recursionNode2));

    RecursionNode recursionNode3 = new RecursionNode(foo);
    recursionNode3.addRecursivePlan(bar);
    Assert.assertEquals(foo.union(bar), simplifier.apply(recursionNode3));

    RecursionNode recursionNode4 = new RecursionNode(PlanNode.empty(2));
    recursionNode4.addRecursivePlan(foo.union(recursionNode4.getDelta()));
    Assert.assertEquals(foo, simplifier.apply(recursionNode4));
  }

  @Test
  public void testPushDownFilterOptimizer() {
    Optimizer optimizer = new PushDownFilterOptimizer();
    PlanNode foo = new BuiltinNode(new CompoundTerm("foo", args2));
    PlanNode bar = new BuiltinNode(new CompoundTerm("bar", args2));
    PlanNode fooFilter = new ConstantEqualityFilterNode(foo, 0, "foo");
    PlanNode barFilter = new ConstantEqualityFilterNode(bar, 0, "foo");

    Assert.assertEquals(
            new UnionNode(fooFilter, barFilter),
            optimizer.apply(new ConstantEqualityFilterNode(new UnionNode(foo, bar), 0, "foo"))
    );

    Assert.assertEquals(
            new ProjectNode(fooFilter, new int[]{1, 0}),
            optimizer.apply(new ConstantEqualityFilterNode(new ProjectNode(foo, new int[]{1, 0}), 1, "foo"))
    );
    Assert.assertEquals(
            new ProjectNode(foo, new int[]{0, -1}, new Comparable[]{null, "foo"}),
            optimizer.apply(new ConstantEqualityFilterNode(new ProjectNode(foo, new int[]{0, -1}, new Comparable[]{null, "foo"}), 1, "foo"))
    );
    Assert.assertEquals(
            PlanNode.empty(2),
            optimizer.apply(new ConstantEqualityFilterNode(new ProjectNode(foo, new int[]{0, -1}, new Comparable[]{null, "bar"}), 1, "foo"))
    );

    Assert.assertEquals(
            new JoinNode(fooFilter, bar, new int[]{1}, new int[]{1}),
            optimizer.apply(new ConstantEqualityFilterNode(new JoinNode(foo, bar, new int[]{1}, new int[]{1}), 0, "foo"))
    );
    Assert.assertEquals(
            new JoinNode(foo, barFilter, new int[]{1}, new int[]{1}),
            optimizer.apply(new ConstantEqualityFilterNode(new JoinNode(foo, bar, new int[]{1}, new int[]{1}), 2, "foo"))
    );
    Assert.assertEquals(
            new JoinNode(fooFilter, barFilter, new int[]{0}, new int[]{0}),
            optimizer.apply(new ConstantEqualityFilterNode(new JoinNode(foo, bar, new int[]{0}, new int[]{0}), 0, "foo"))
    );
    Assert.assertEquals(
            new JoinNode(fooFilter, barFilter, new int[]{0}, new int[]{0}),
            optimizer.apply(new ConstantEqualityFilterNode(new JoinNode(foo, bar, new int[]{0}, new int[]{0}), 2, "foo"))
    );
  }

  @Test
  public void testPushDownFilterOptimizer2() {
    Optimizer optimizer = new PushDownFilterOptimizer();

    PlanNode baz = new BuiltinNode(new CompoundTerm("baz", args3));
    PlanNode bazFilter1 = new ConstantEqualityFilterNode(baz, 1, "foo1");
    PlanNode bazFilter2 = new ConstantEqualityFilterNode(baz, 1, "foo2");
    Assert.assertEquals(new UnionNode(//
        new ProjectNode(bazFilter1, new int[] { 0 }), //
        new ProjectNode(bazFilter2, new int[] { 0 }) //
    ), //
        optimizer.apply(new ProjectNode(new UnionNode(//
        new ProjectNode(bazFilter1, new int[] { 2, 0 }), //
        new ProjectNode(bazFilter2, new int[] { 2, 0 }) //
    ), new int[] { 1 }))
    );
  }

  @Test
  public void testPushDownProject() {
    Optimizer optimizer = new PushDownProject();

    PlanNode baz = new BuiltinNode(new CompoundTerm("baz", args5));

    assertEquals(new JoinNode(//
            baz.project(new int[] { 1, 2, 3, 4 }), //
            baz.project(new int[] { 1, 2, 3 }), //
            new int[] { 3, 1 }, new int[] { 0, 2 })//
                .project(new int[] { 0, 2, 5 }), //
            optimizer.apply(new JoinNode(baz, baz, //
                new int[] { 4, 2 }, new int[] { 1, 3 })//
                    .project(new int[] { 1, 3, 7 })));
  }

  private void assertEquals(PlanNode expected, PlanNode actual) {
    if (!Objects.equals(expected, actual)) {
      System.out.println("expected:");
      System.out.println(expected.toPrettyString());
      System.out.println("\nactual:");
      System.out.println(actual.toPrettyString());
    }
    Assert.assertEquals(expected, actual);
  }

}
