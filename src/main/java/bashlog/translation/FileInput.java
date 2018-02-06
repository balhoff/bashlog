package bashlog.translation;

import java.util.Arrays;
import java.util.List;

import bashlog.BashlogCompiler;
import bashlog.command.Bash;
import bashlog.plan.TSVFileNode;
import common.plan.node.PlanNode;

public class FileInput implements Translator {

  @Override
  public Bash translate(PlanNode planNode, BashlogCompiler bc) {
    TSVFileNode file = (TSVFileNode) planNode;
    return new Bash.BashFile(file.getPath());
  }

  @Override
  public List<Class<?>> supports() {
    return Arrays.asList(TSVFileNode.class);
  }

}
