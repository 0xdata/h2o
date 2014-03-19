package hex;

import water.Iced;
import water.api.DocGen;
import water.api.Request.API;
import water.util.Utils;

import java.util.Arrays;
import java.util.Comparator;

public class VarImp extends Iced {
  static final int API_WEAVER = 1; // This file has auto-gen'd doc & json fields
  static public DocGen.FieldDoc[] DOC_FIELDS; // Initialized from Auto-Gen code.

  /** Variable importance measurement method. */
  enum VarImpMethod {
    PERMUTATION_IMPORTANCE("Mean decrease accuracy"),
    RELATIVE_IMPORTANCE("Relative importance");
    private final String title;
    VarImpMethod(String title) { this.title = title; }
    @Override public String toString() { return title; }
  }

  @API(help="Variable importance of individual variables.")
  public float[]  varimp;
  @API(help="Names of variables.")
  private String[] variables;
  @API(help="Variable importance measurement method.")
  public final VarImpMethod method;
  @API(help="Max. number of variables to show.")
  public final int max_var = 100;
  @API(help="Scaled measurements.")
  public final boolean scaled() { return false; }

  public VarImp(float[] varimp) { this(varimp, null, VarImpMethod.RELATIVE_IMPORTANCE); }
  public VarImp(float[] varimp, String[] variables) { this(varimp, variables, VarImpMethod.RELATIVE_IMPORTANCE); }
  protected VarImp(float[] varimp, String[] variables, VarImpMethod method) {
    this.varimp = varimp;
    this.variables = variables;
    this.method = method;
  }

  public String[] getVariables() { return variables; }
  public void setVariables(String[] variables) { this.variables = variables; }

  /** Generate variable importance HTML code. */
  public final StringBuilder toHTML(StringBuilder sb) {
    DocGen.HTML.section(sb,"Variable importance of input variables: " + method);
    DocGen.HTML.arrayHead(sb);
    // Create a sort order
    Integer[] sortOrder = getSortOrder();
    // Generate variable labels and raw scores
    if (variables != null) DocGen.HTML.tableLine(sb, "Variable", variables, sortOrder, Math.min(max_var, variables.length));
    if (varimp    != null) DocGen.HTML.tableLine(sb, method.toString(), varimp, sortOrder, Math.min(max_var, variables.length));
    // Print a specific information
    toHTMLAppendMoreTableLines(sb, sortOrder);
    DocGen.HTML.arrayTail(sb);
    // Generate nice graph ;-)
    toHTMLGraph(sb, sortOrder);
    // And return the result
    return sb;
  }

  protected StringBuilder toHTMLAppendMoreTableLines(StringBuilder sb, Integer[] sortOrder) {
    return sb;
  }

  protected StringBuilder toHTMLGraph(StringBuilder sb, Integer[] sortOrder) {
    Integer[] so = varimp.length > max_var ? sortOrder : null;
    // Generate a graph
    DocGen.HTML.graph(sb, "graphvarimp", "g_varimp",
        DocGen.HTML.toJSArray(new StringBuilder(), variables, so, Math.min(max_var, variables.length)),
        DocGen.HTML.toJSArray(new StringBuilder(), varimp,    so, Math.min(max_var, variables.length))
        );
    return sb;
  }
  /** By default provides a sort order according to raw scores stored in <code>varimp</code>. */
  protected Integer[] getSortOrder() {
    Integer[] sortOrder = new Integer[varimp.length];
    for(int i=0; i<sortOrder.length; i++) sortOrder[i] = i;
    Arrays.sort(sortOrder, new Comparator<Integer>() {
      @Override public int compare(Integer o1, Integer o2) { float f = varimp[o1]-varimp[o2]; return f<0 ? 1 : (f>0 ? -1 : 0); }
    });
    return sortOrder;
  }

  /** Variable importance measured as relative influence.
   * It provides raw values, scaled values, and summary. */
  public static class VarImpRI extends VarImp {
    static final int API_WEAVER = 1; // This file has auto-gen'd doc & json fields
    static public DocGen.FieldDoc[] DOC_FIELDS; // Initialized from Auto-Gen code.

    public VarImpRI(float[] varimp) {
      super(varimp);
    }

    @API(help = "Scaled values of raw scores with respect to maximal value (GBM call - relative.influnce(model, scale=T)).")
    public float[] scaled_values() {
      float[] scaled = new float[varimp.length];
      int maxVar = 0;
      for (int i=0; i<varimp.length; i++)
        if (varimp[i] > varimp[maxVar]) maxVar = i;
      float maxVal = varimp[maxVar];
      for (int var=0; var<varimp.length; var++)
        scaled[var] = varimp[var] / maxVal;
      return scaled;
    }

    @API(help = "Summary of values in percent (the same as produced by summary.gbm).")
    public float[] summary() {
      float[] summary = new float[varimp.length];
      float sum = Utils.sum(varimp);
      for (int var=0; var<varimp.length; var++)
        summary[var] = 100*varimp[var] / sum;
      return summary;
    }

    @Override protected StringBuilder toHTMLAppendMoreTableLines(StringBuilder sb, Integer[] sortOrder ) {
      StringBuilder ssb = super.toHTMLAppendMoreTableLines(sb, sortOrder);
      DocGen.HTML.tableLine(sb, "Scaled values",  scaled_values(), sortOrder, Math.min(max_var, varimp.length));
      DocGen.HTML.tableLine(sb, "Influence in %", summary(), sortOrder, Math.min(max_var, varimp.length));
      return ssb;
    }
  }

  /** Variable importance measured as mean decrease in accuracy.
   * It provides raw variable importance measures, SD and z-scores. */
  public static class VarImpMDA extends VarImp {
    static final int API_WEAVER = 1; // This file has auto-gen'd doc & json fields
    static public DocGen.FieldDoc[] DOC_FIELDS; // Initialized from Auto-Gen code.

    @API(help="Variable importance SD for individual variables.")
    public final float[]  varimpSD;

    /** Number of trees participating for producing variable importance measurements */
    private final int ntrees;

    public VarImpMDA(float[] varimp, float[] varimpSD, int ntrees) {
      super(varimp,null,VarImpMethod.PERMUTATION_IMPORTANCE);
      this.varimpSD = varimpSD;
      this.ntrees = ntrees;
    }

    @API(help = "Z-score for individual variables")
    public float[] z_score() {
      float[] zscores = new float[varimp.length];
      double rnt = Math.sqrt(ntrees);
      for(int v = 0; v < varimp.length ; v++) zscores[v] = (float) (varimp[v] / (varimpSD[v] / rnt));
      return zscores;
    }

    @Override protected StringBuilder toHTMLAppendMoreTableLines(StringBuilder sb, Integer[] sortOrder ) {
      StringBuilder ssb = super.toHTMLAppendMoreTableLines(sb, sortOrder);
      if (varimpSD!=null) {
        DocGen.HTML.tableLine(sb, "SD", varimpSD, sortOrder, Math.min(max_var, varimp.length));
        float[] zscores = z_score();
        DocGen.HTML.tableLine(sb, "Z-scores", zscores, sortOrder, Math.min(max_var, varimp.length));
      }
      return ssb;
    }
  }
}
