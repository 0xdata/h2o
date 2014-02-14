package water.exec;

import java.util.*;

import water.*;
import water.fvec.*;
import water.util.Utils;
import water.nbhm.NonBlockingHashMap;

/** Parse a generic R string and build an AST, in the context of an H2O Cloud
 *  @author cliffc@0xdata.com
 */

// --------------------------------------------------------------------------
// R's Apply.  Function is limited to taking a single column and returning
// a single column.  Double is limited to 1 or 2, statically determined.
class ASTRApply extends ASTOp {
  static final String VARS[] = new String[]{ "", "ary", "dbl1.2", "fcn"};
  ASTRApply( ) { super(VARS,
                       new Type[]{ Type.ARY, Type.ARY, Type.DBL, Type.fcn(new Type[]{Type.dblary(),Type.ARY}) },
                       OPF_PREFIX,
                       OPP_PREFIX,
                       OPA_RIGHT); }
  protected ASTRApply( String vars[], Type ts[], int form, int prec, int asso) { super(vars,ts,form,prec,asso); }
  @Override String opStr(){ return "apply";}
  @Override ASTOp make() {return new ASTRApply();}
  @Override void apply(Env env, int argcnt) {
    // Peek everything from the stack
    final ASTOp op = env.fcn(-1);    // ary->dblary but better be ary[,1]->dblary[,1]
    double d = env.dbl(-2);    // MARGIN: ROW=1, COLUMN=2 selector
    Frame fr = env.ary(-3);    // The Frame to work on
    if( d==2 || d== -1 ) {     // Work on columns?
      int ncols = fr.numCols();

      double ds[][] = null; // If results are doubles, gather in small array
      Frame fr2 = null;     // If the results are Vecs, gather them in this Frame
      String err = "apply requires that "+op+" return 1 column";
      if( op._t.ret().isDbl() ) ds = new double[ncols][1];
      else                     fr2 = new Frame(new String[0],new Vec[0]);

      // Apply the function across columns
      try {
        Vec vecs[] = fr.vecs();
        for( int i=0; i<ncols; i++ ) {
          env.push(op);
          env.push(new Frame(new String[]{fr._names[i]},new Vec[]{vecs[i]}));
          env.fcn(-2).apply(env, 2);
          if( ds != null ) {    // Doubles or Frame results?
            ds[i][0] = env.popDbl();
          } else {                // Frame results
            if( env.ary(-1).numCols() != 1 )
              throw new IllegalArgumentException(err);
            fr2.add(fr._names[i], env.popAry().theVec(err));
          }
        }
      } catch( IllegalArgumentException iae ) { 
        env.subRef(fr2,null); 
        throw iae; 
      }
      env.pop(4);
      if( ds != null ) env.push(TestUtil.frame(new String[]{"C1"},ds));
      else { env.push(1);  env._ary[env._sp-1] = fr2;  }
      assert env.isAry();
      return;
    }
    if( d==1 || d==-2) {      // Work on rows
      // apply on rows is essentially a map function
      Type ts[] = new Type[2];
      ts[0] = Type.unbound();
      ts[1] = Type.ARY;
      Type ft1 = Type.fcn(ts);
      Type ft2 = op._t.find();  // Should be a function type
      if( !ft1.union(ft2) ) {
        if( ft2._ts.length != 2 )
          throw new IllegalArgumentException("FCN " + op.toString() + " cannot accept one argument.");
        if( !ft2._ts[1].union(ts[1]) )
          throw new IllegalArgumentException("Arg " + op._vars[1] + " typed " + ft2._ts[1].find() + " but passed as " + ts[1]);
        assert false;
      }
      // find out return type
      final double[] rowin = new double[fr.vecs().length];
      for (int c = 0; c < rowin.length; c++) rowin[c] = fr.vecs()[c].at(0);
      final double[] rowout = op.map(env,rowin,null);
      final Env env0 = env;
      MRTask2 mrt = new MRTask2() {
        @Override
        public void map(Chunk[] cs, NewChunk[] ncs) {
          for (int i = 0; i < cs[0]._len; i++) {
            for (int c = 0; c < cs.length; c++) rowin[c] = cs[c].at0(i);
            op.map(env0, rowin, rowout);
            for (int c = 0; c < ncs.length; c++) ncs[c].addNum(rowout[c]);
          }
        }
      };
      String[] names = new String[rowout.length];
      for (int i = 0; i < names.length; i++) names[i] = "C"+(i+1);
      Frame res = mrt.doAll(rowout.length,fr).outputFrame(names, null);
      env.poppush(4,res,null);
      return;
    }
    throw new IllegalArgumentException("MARGIN limited to 1 (rows) or 2 (cols)");
  }
}

// --------------------------------------------------------------------------
// Same as "apply" but defaults to columns.  
class ASTSApply extends ASTRApply {
  static final String VARS[] = new String[]{ "", "ary", "fcn"};
  ASTSApply( ) { super(VARS,
                       new Type[]{ Type.ARY, Type.ARY, Type.fcn(new Type[]{Type.dblary(),Type.ARY}) },
                       OPF_PREFIX,
                       OPP_PREFIX,
                       OPA_RIGHT); }
  @Override String opStr(){ return "sapply";}
  @Override ASTOp make() {return new ASTSApply();}
  @Override void apply(Env env, int argcnt) {
    // Stack: SApply, ary, fcn
    //   -->: RApply, ary, 2, fcn
    assert env.isFcn(-3);
    env._fcn[env._sp-3] = new ASTRApply();
    ASTOp fcn = env.popFcn();   // Pop, no ref-cnt
    env.push(2.0);
    env.push(1);
    env._fcn[env._sp-1] = fcn;  // Push, no ref-cnt
    super.apply(env,argcnt+1);
  }
}

// --------------------------------------------------------------------------
// PLYR's DDPLY.  GroupBy by any other name.  Type signature:
//   #RxN  ddply(RxC,subC, 1xN function( subRxC ) { ... } ) 
//   R - Rows in original frame
//   C - Cols in original frame
//   subC - Subset of C; either a single column entry, or a 1 Vec frame with a list of columns.
//   subR - Subset of R, where all subC values are the same.
//   N - Return column(s).  Can be 1, and so fcn can return a dbl instead of 1xN
//  #R - # of unique combos in the original "subC" set

class ASTddply extends ASTOp {
  static final String VARS[] = new String[]{ "#RxN", "RxC", "subC", "fcn_subRxC"};
  ASTddply( ) { super(VARS,
                      new Type[]{ Type.ARY, Type.ARY, Type.dblary(), Type.fcn(new Type[]{Type.dblary(),Type.ARY}) },
                      OPF_PREFIX,
                      OPP_PREFIX,
                      OPA_RIGHT); }
  @Override String opStr(){ return "ddply";}
  @Override ASTOp make() {return new ASTddply();}
  @Override void apply(Env env, int argcnt) {
    // Peek everything from the stack
    // Function to execute on the groups
    final ASTOp op = env.fcn(-1); // ary->dblary: subRxC -> 1xN
    
    Frame fr = env.ary(-3);    // The Frame to work on
    final int ncols = fr.numCols();

    // Either a single column, or a collection of columns to group on.
    int cols[];
    if( !env.isAry(-2) ) {      // Single column?
      if( Double.isNaN(env.dbl(-2)) ) throw new IllegalArgumentException("NA not a valid column");
      cols = new int[]{(int)env.dbl(-2)-1};
    } else {                    // Else a collection of columns?
      Frame cs = env.ary(-2);
      if( cs.numCols() !=  1  ) throw new IllegalArgumentException("Only one column-of-columns for column selection");
      if( cs.numRows() > 1000 ) throw new IllegalArgumentException("Too many columns selected");
      cols = new int[(int)cs.numRows()];
      Vec vec = cs.vecs()[0];
      for( int i=0; i<cols.length; i++ ) 
        if( vec.isNA(i) ) throw new IllegalArgumentException("NA not a valid column");
        else cols[i] = (int)vec.at8(i)-1;
    }
    // Another check for sane columns
    for( int c : cols )
      if( c < 0 || c >= fr.numCols() )
        throw new IllegalArgumentException("Column "+(c+1)+" out of range for frame columns "+fr.numCols());

    // Was pondering a SIMD-like execution model, running the fcn "once" - but
    // in parallel for all groups.  But this isn't going to work: each fcn
    // execution will take different control paths.  Also the functions side-
    // effects' must only happen once, and they will make multiple passes over
    // the Frame passed in.  
    //
    // GroupIDs' can vary from 1 group to 1-per-row.  Are formed by the cross-
    // product of the selection cols.  Will be hashed to find Group - NBHML
    // mapping row-contents to group.  Index is a sample row.  NBHML per-node,
    // plus roll-ups.  Result/Value is Group structure pointing to NewChunks
    // holding row indices.  

    // Pass 1: Find Groups.
    // Build a NBHSet of unique double[]'s holding selection cols.
    // These are the unique groups, found per-node, rolled-up globally
    // Record the rows belonging to each group, locally.
    ddplyPass1 p1 = new ddplyPass1(cols).doAll(fr);

    // Pass 2: Build Groups.
    // Wrap Vec headers around all the local row-counts.
    ddplyPass2 p2 = new ddplyPass2(p1).invokeOnAllNodes();
    // vecs[] iteration order exactly matches p1._grpoups.keySet()
    Vec vecs[] = p2.close();
    // Push the execution env around the cluster
    Key envkey = Key.make();
    UKV.put(envkey,env);
    
    // Pass 3: Send Groups 'round the cluster
    // Single-threaded per-group work.
    // Send each group to some remote node for execution
    int csz = H2O.CLOUD.size();
    Futures fs = new Futures();
    int grpnum=0; // vecs[] iteration order exactly matches p1._groups.keySet()
    for( Group g : p1._groups.keySet() ) {
      // vecs[] iteration order exactly matches p1._grpoups.keySet()
      Vec rows = vecs[grpnum++]; // Rows for this Vec
      Vec[] data = fr.vecs();    // Full data columns
      Vec[] gvecs = new Vec[data.length];
      Key[] keys = rows.group().addVecs(data.length);
      for( int c=0; c<data.length; c++ )
        gvecs[c] = new SubsetVec(rows._key,data[c]._key,keys[c],rows._espc);
      Frame fg = new Frame(fr._names,gvecs);
      // Non-blocking, send a group to a remote node for execution
      fs.add(RPC.call(H2O.CLOUD._memary[g.hashCode()%csz],new RemoteExec(g._ds,fg,envkey)));
    }
    fs.blockForPending();

    // Delete the group row vecs
    for( Vec v : vecs ) UKV.remove(v._key);

    env.pop(4);
    // Push empty frame for debugging
    env.push(new Frame(new String[0],new Vec[0]));
  }

  // ---
  // Group descrption: unpacked selected double columns
  private static class Group extends Iced {
    public double _ds[];
    public int _hash;
    Group( int len ) { _ds = new double[len]; }
    Group( double ds[] ) { _ds = ds; _hash=hash(); }
    // Efficiently allow groups to be hashed & hash-probed
    private void fill( int row, Chunk chks[], int cols[] ) {
      for( int c=0; c<cols.length; c++ ) // For all selection cols
        _ds[c] = chks[cols[c]].at0(row); // Load into working array
      _hash = hash();
    }
    private int hash() {
      long h=0;                 // hash is sum of field bits
      for( double d : _ds ) h += Double.doubleToRawLongBits(d);
      // Doubles are lousy hashes; mix up the bits some
      h ^= (h>>>20) ^ (h>>>12);
      h ^= (h>>> 7) ^ (h>>> 4);
      return (int)((h^(h>>32))&0x7FFFFFFF);
    }
    @Override public boolean equals( Object o ) {  
      return o instanceof Group && Arrays.equals(_ds,((Group)o)._ds); }
    @Override public int hashCode() { return _hash; }
    @Override public String toString() { return Arrays.toString(_ds); }
  }


  // ---
  // Pass1: Find unique groups, based on a subset of columns.
  // Collect rows-per-group, locally.
  private static class ddplyPass1 extends MRTask2<ddplyPass1> {
    // INS:
    public int _cols[];   // Selection columns
    public Key _uniq;     // Unique Key for this entire ddply pass
    ddplyPass1( int cols[] ) { _cols = cols; _uniq=Key.make(); }
    // OUTS: mapping from groups to row#s that are in that group
    public NonBlockingHashMap<Group,NewChunk> _groups;

    // *Local* results from ddplyPass1 are kept locally in this tmp structure.
    // Pass2 reads them out & reclaims the space.
    private static NonBlockingHashMap<Key,ddplyPass1> PASS1TMP = new NonBlockingHashMap<Key,ddplyPass1>();

    // Make a NewChunk to hold rows, that has a random Key and is not
    // associated with any Vec.  We'll fold these into a Vec later when we know
    // cluster-wide what the Groups (and hence Vecs) are.
    private static NewChunk makeNC( Chunk C ) { return new NewChunk(null,H2O.SELF.index()); }
    // Build a Map mapping Groups to a NewChunk of row #'s
    @Override public void map( Chunk chks[] ) {
      _groups = new NonBlockingHashMap<Group,NewChunk>();
      Group g = new Group(_cols.length);
      Chunk C = chks[_cols[0]];
      NewChunk nc = makeNC(C);
      int len = C._len;
      long start = C._start;
      for( int row=0; row<len; row++ ) {
        // Temp array holding the column-selection data
        g.fill(row,chks,_cols);
        NewChunk nc_old = _groups.putIfAbsent(g,nc);
        if( nc_old==null ) {    // Add group signature if not already present
          nc_old = nc;          // Jammed 'nc' into the table to hold rows
          g = new Group(_cols.length); // Need a new <Group,NewChunk> pair
          nc = makeNC(C);
        }
        nc_old.addNum(start+row,0); // Append rows into the existing group
      }
    }
    // Fold together two Group/NewChunk Maps.  For the same Group, append
    // NewChunks (hence gathering rows together).  Since the custom serializers
    // do not send the rows over the wire, we have only *local* row-counts.
    @Override public void reduce( ddplyPass1 p1 ) {
      assert _groups != p1._groups;
      // Fold 2 hash tables together.
      // Get the larger hash table in m0, smaller in m1
      NonBlockingHashMap<Group,NewChunk> m0 =    _groups;
      NonBlockingHashMap<Group,NewChunk> m1 = p1._groups;
      if( m0.size() < m1.size() ) { NonBlockingHashMap<Group,NewChunk> tmp=m0; m0=m1; m1=tmp; }
      // Iterate over smaller table, folding into larger table.
      for( Group g : m1.keySet() ) {
        NewChunk nc0 = m0.get(g);
        NewChunk nc1 = m1.get(g);
        if( nc0 == null ) m0.put(g,nc1);
        // unimplemented: expected to blow out on large row counts, where we
        // actually need a collection of chunks, not 1 uber-chunk
        else {
          // All longs are monotonically in-order.  Not sure if this is needed
          // but it's an easy invariant to keep and it makes reading row#s easier.
          if( nc0._len > 0 && nc1._len > 0 && // len==0 for reduces from remotes (since no rows sent)
              nc0.at8_impl(nc0._len-1) >= nc1.at8_impl(0) )   nc0.addr(nc1);
          else                                                nc0.add (nc1);
        }
      }
      _groups = m0;
      p1._groups = null;
    }
    @Override public String toString() { return _groups.toString(); }
    // Save local results for pass2
    @Override public void closeLocal() { PASS1TMP.put(_uniq,this); }

    // Custom serialization for NBHM.  Much nicer when these are auto-gen'd.
    // Only sends Groups over the wire, NOT NewChunks with rows.
    @Override public AutoBuffer write( AutoBuffer ab ) {
      super.write(ab);
      ab.putA4(_cols);
      ab.put(_uniq);
      if( _groups == null ) return ab.put4(0);
      ab.put4(_groups.size());
      for( Group g : _groups.keySet() ) ab.put(g);
      return ab;
    }
    
    @Override public ddplyPass1 read( AutoBuffer ab ) {
      super.read(ab);
      assert _groups == null;
      _cols = ab.getA4();
      _uniq = ab.get();
      int len = ab.get4();
      if( len == 0 ) return this;
      _groups = new NonBlockingHashMap<Group,NewChunk>();
      for( int i=0; i<len; i++ )
        _groups.put(ab.get(Group.class),new NewChunk(null,-99));
      return this;
    }
    @Override public void copyOver( DTask dt ) {
      ddplyPass1 that = (ddplyPass1)dt;
      super.copyOver(that);
      this._cols   = that._cols;
      this._uniq   = that._uniq;
      this._groups = that._groups;
    }
  }

  // ---
  // Pass 2: Build Groups.
  // Wrap Frame/Vec headers around all the local row-counts.
  private static class ddplyPass2 extends DRemoteTask<ddplyPass2> {
    // Key uniquely identifying a pass1 collection of NewChunks
    Key _p1key;
    // One new Vec per Group, holding just rows
    AppendableVec _avs[];
    // The Group descripters
    double _dss[][];

    ddplyPass2( ddplyPass1 p1 ) {
      _p1key = p1._uniq;        // Key to finding the pass1 data
      // One new Vec per Group, holding just rows
      _avs = new AppendableVec[p1._groups.size()];
      _dss = new double       [p1._groups.size()][];
      int i=0;
      for( Group g : p1._groups.keySet() ) {
        _dss[i] = g._ds;
        _avs[i++] = new AppendableVec(new Vec.VectorGroup().addVec());
      }
    }

    // Local (per-Node) work.  Gather the chunks together into the Vecs
    @Override public void lcompute() {
      ddplyPass1 p1 = ddplyPass1.PASS1TMP.remove(_p1key);
      Futures fs = new Futures();
      int cidx = H2O.SELF.index();
      for( int i=0; i<_dss.length; i++ ) { // For all possible groups
        // Get the newchunk of local rows for a group
        Group g = new Group(_dss[i]);
        NewChunk nc = p1._groups == null ? null : p1._groups.get(g);
        if( nc != null && nc._len > 0 ) { // Fill in fields we punted on during construction
          nc._vec = _avs[i];  // Assign a proper vector
          nc.close(cidx,fs);  // Close & compress chunk
        } else {              // All nodes have a chunk, even if its empty
          DKV.put(_avs[i].chunkKey(cidx), new C0LChunk(0,0),fs);
        }
      }
      fs.blockForPending();
      _p1key = null;            // No need to return these
      _dss = null;
      tryComplete();
    }
    @Override public void reduce( ddplyPass2 p2 ) {
      for( int i=0; i<_avs.length; i++ )
        _avs[i].reduce(p2._avs[i]);
    }
    // Close all the AppendableVecs & return normal Vecs.
    Vec[] close() {
      Futures fs = new Futures();
      Vec vs[] = new Vec[_avs.length];
      for( int i=0; i<_avs.length; i++ ) vs[i] = _avs[i].close(fs);
      fs.blockForPending();
      return vs;
    }
  }

  private static class RemoteExec extends DTask<RemoteExec> implements Freezable {
    // INS
    public double _ds[];        // Displayable name for this group
    public Frame _fr;           // Frame for this group
    public Key _envkey;         // Key for the execution environment
    RemoteExec( double ds[], Frame fr, Key envkey ) { _ds=ds; _fr=fr; _envkey=envkey; }
    @Override public void compute2() {
      Env shared_env = UKV.get(_envkey);
      // Clone a private copy of the environment for local execution
      Env env = shared_env.capture(true);
      ASTOp op = env.fcn(-1);

      System.out.println("ddply on group "+Arrays.toString(_ds)+" rows="+_fr.numRows()+", env="+env+", op="+op);
      env.push(op);
      env.push(_fr);
      op.apply(env,2/*1-arg function*/);
      System.out.println("ddply on group "+Arrays.toString(_ds)+", env="+env);
      
      _fr.delete();
      _fr = null;
      _ds = null;
      _envkey= null;
      tryComplete();
    }
  }

}
