package hex;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import water.Iced;
import water.api.DocGen;
import water.api.Request.API;

import java.util.Arrays;

public class ConfusionMatrix extends Iced {
  static final int API_WEAVER = 1; // This file has auto-gen'd doc & json fields
  static public DocGen.FieldDoc[] DOC_FIELDS; // Initialized from Auto-Gen code.
  @API(help="Confusion matrix (Actual/Predicted)")
  public long[][] _arr; // [actual][predicted]
  @API(help = "Prediction error by class")
  public final double[] _classErr;
  @API(help = "Prediction error")
  public final double _predErr;

  @Override public ConfusionMatrix clone() {
    ConfusionMatrix res = new ConfusionMatrix(0);
    res._arr = _arr.clone();
    for( int i = 0; i < _arr.length; ++i )
      res._arr[i] = _arr[i].clone();
    return res;
  }

  public enum ErrMetric {
    MAXC, SUMC, TOTAL;

    public double computeErr(ConfusionMatrix cm) {
      double[] cerr = cm.classErr();
      double res = 0;
      switch( this ) {
        case MAXC:
          res = cerr[0];
          for( double d : cerr )
            if( d > res )
              res = d;
          break;
        case SUMC:
          for( double d : cerr )
            res += d;
          break;
        case TOTAL:
          res = cm.err();
          break;
        default:
          throw new RuntimeException("unexpected err metric " + this);
      }
      return res;
    }

  }

  public ConfusionMatrix(int n) {
    _arr = new long[n][n];
    _classErr = classErr();
    _predErr = err();
  }

  public ConfusionMatrix(long[][] value) {
    _arr = value;
    _classErr = classErr();
    _predErr = err();
  }

  public void add(int i, int j) {
    _arr[i][j]++;
  }

  public double[] classErr() {
    double[] res = new double[_arr.length];
    for( int i = 0; i < res.length; ++i )
      res[i] = classErr(i);
    return res;
  }

  public final int size() {
    return _arr.length;
  }

  public final double classErr(int c) {
    long s = 0;
    for( long x : _arr[c] )
      s += x;
    if( s == 0 )
      return 0.0;    // Either 0 or NaN, but 0 is nicer
    return (double) (s - _arr[c][c]) / s;
  }

  public double err() {
    long n = 0;
    for( int a = 0; a < _arr.length; ++a )
      for( int p = 0; p < _arr[a].length; ++p )
        n += _arr[a][p];
    long err = n;
    for( int d = 0; d < _arr.length; ++d )
      err -= _arr[d][d];
    return (double) err / n;
  }

  public void add(ConfusionMatrix other) {
    water.util.Utils.add(_arr, other._arr);
  }

  public double precisionAndRecall() {
    return precisionAndRecall(_arr);
  }

  /**
   * Returns the F-measure which combines precision and recall. <br>
   * C.f. end of http://en.wikipedia.org/wiki/Precision_and_recall.
   */
  public static double precisionAndRecall(long[][] cm) {
    assert cm.length == 2 && cm[0].length == 2 && cm[1].length == 2;
    double tp = cm[0][0];
    double fp = cm[1][0];
    double fn = cm[0][1];
    double precision = tp / (tp + fp);
    double recall = tp / (tp + fn);
    double f = 2 * (precision * recall) / (precision + recall);
    return f;
  }

  @Override public String toString() {
    StringBuilder sb = new StringBuilder();
    for( long[] r : _arr )
      sb.append(Arrays.toString(r) + "\n");
    return sb.toString();
  }

  public JsonArray toJson() {
    JsonArray res = new JsonArray();
    JsonArray header = new JsonArray();
    header.add(new JsonPrimitive("Actual / Predicted"));
    for( int i = 0; i < _arr.length; ++i )
      header.add(new JsonPrimitive("class " + i));
    header.add(new JsonPrimitive("Error"));
    res.add(header);
    for( int i = 0; i < _arr.length; ++i ) {
      JsonArray row = new JsonArray();
      row.add(new JsonPrimitive("class " + i));
      long s = 0;
      for( int j = 0; j < _arr.length; ++j ) {
        s += _arr[i][j];
        row.add(new JsonPrimitive(_arr[i][j]));
      }
      double err = s - _arr[i][i];
      err /= s;
      row.add(new JsonPrimitive(err));
      res.add(row);
    }
    JsonArray totals = new JsonArray();
    totals.add(new JsonPrimitive("Totals"));
    long S = 0;
    long DS = 0;
    for( int i = 0; i < _arr.length; ++i ) {
      long s = 0;
      for( int j = 0; j < _arr.length; ++j )
        s += _arr[j][i];
      totals.add(new JsonPrimitive(s));
      S += s;
      DS += _arr[i][i];
    }
    double err = (S - DS) / (double) S;
    totals.add(new JsonPrimitive(err));
    res.add(totals);
    return res;
  }
}
