package hex.glm;

import water.*;
import water.api.DocGen;
import water.api.Request;
import water.api.RequestBuilders.Response;

public class GLMModelView extends Request2 {

  static final int API_WEAVER = 1; // This file has auto-gen'd doc & json fields
  static public DocGen.FieldDoc[] DOC_FIELDS; // Initialized from Auto-Gen code.

  @API(help="GLM Model Key", required=true, filter=GLMModelKeyFilter.class)
  Key _modelKey;
  class GLMModelKeyFilter extends H2OKey { public GLMModelKeyFilter() { super("",true); } }

  @API(help="GLM Model")
  GLMModel glm_model;

  public static String link(String txt, Key model) {
    return "<a href='GBMModelView.html?_modelKey=" + model + "'>" + txt + "</a>";
  }

  public static Response redirect(Request req, Key modelKey) {
    return Response.redirect(req, "GLMModelView", "_modelKey", modelKey);
  }

  public static Response redirect2(Request req, Key modelKey) {
    return Response.redirect(req, "/2/GLMModelView", "_modelKey", modelKey);
  }

  @Override public boolean toHTML(StringBuilder sb){
    glm_model.generateHTML("", sb);
    return true;
  }

  @Override protected Response serve() {
    glm_model = DKV.get(_modelKey).get();
    return Response.done(this);
  }
}

