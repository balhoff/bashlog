package bashlog.translation;

import java.util.Arrays;
import java.util.List;

import bashlog.BashlogCompiler;
import bashlog.command.Bash;
import common.plan.node.MultiFilterNode;
import common.plan.node.PlanNode;

public class MultiFilter implements Translator {

  @Override
  public Bash translate(PlanNode planNode, BashlogCompiler bc) {
      MultiFilterNode m = (MultiFilterNode) planNode;
      Bash.Command cmd = new Bash.Command(AwkHelper.AWK);

      StringBuilder arg = new StringBuilder();
      List<PlanNode> remaining = AwkHelper.complexAwkLine(m.getFilter(), null, arg);

      // process remaining filter nodes
      for (PlanNode c : remaining) {
        AwkHelper.simpleAwkLine(c, null, arg);
      }
      arg.append("' ");
      cmd.arg(arg.toString());
      cmd.file(bc.compile(m.getTable()));

      return cmd;
  }

  @Override
  public List<Class<?>> supports() {
    return Arrays.asList(MultiFilter.class);
  }

}