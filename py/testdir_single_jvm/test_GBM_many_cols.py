import unittest, random, sys, time
sys.path.extend(['.','..','py'])
import h2o, h2o_cmd, h2o_hosts, h2o_browse as h2b, h2o_import as h2i, h2o_gbm, h2o_jobs as h2j

DO_PLOT = False

def write_syn_dataset(csvPathname, rowCount, colCount, SEED):
    r1 = random.Random(SEED)
    dsf = open(csvPathname, "w+")

    for i in range(rowCount):
        rowData = []
        for j in range(colCount):
            # ri = r1.randint(0,1)
            # get some reals, to get a bigger file?
            ri = round(r1.uniform(0,1),2)
            rowData.append("%0.2f" % ri)

        ri = r1.randint(0,1)
        rowData.append(ri)

        rowDataCsv = ",".join(map(str,rowData))
        dsf.write(rowDataCsv + "\n")

    dsf.close()

class Basic(unittest.TestCase):
    def tearDown(self):
        h2o.check_sandbox_for_errors()

    @classmethod
    def setUpClass(cls):
        global SEED, localhost
        SEED = h2o.setup_random_seed()
        localhost = h2o.decide_if_localhost()
        if (localhost):
            # fails
            # h2o.build_cloud(1,java_heap_MB=100, enable_benchmark_log=True)
            # 400 fails
            # jea = '-Xloggc:log.txt'
            # h2o.build_cloud(1,java_heap_GB=12, enable_benchmark_log=True, java_extra_args=jea)
            h2o.build_cloud(1,java_heap_GB=12, enable_benchmark_log=True)
        else:
            h2o_hosts.build_cloud_with_hosts(enable_benchmark_log=True)

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud()

    def test_GBM_many_cols(self):
        SYNDATASETS_DIR = h2o.make_syn_dir()

        if localhost:
            tryList = [
                # (100000, 400, 'cA', 300), 
                (100000, 4000, 'cI', 1500), 
                ]
        else:
            tryList = [
                # (10000, 10, 'cB', 300), 
                # (10000, 50, 'cC', 300), 
                (100000, 4000, 'cI', 1500), 
                # (100000, 1000, 'cI', 300), 
                # (100000, 500, 'cG', 300), 
                # (100000, 200, 'cE', 300), 
                # (100000, 100, 'cD', 300), 
                ]

        ### h2b.browseTheCloud()
        for (rowCount, colCount, hex_key, timeoutSecs) in tryList:
            SEEDPERFILE = random.randint(0, sys.maxint)
            # csvFilename = 'syn_' + str(SEEDPERFILE) + "_" + str(rowCount) + 'x' + str(colCount) + '.csv'
            csvFilename = 'syn_' + "binary" + "_" + str(rowCount) + 'x' + str(colCount) + '.csv'
            csvPathname = SYNDATASETS_DIR + '/' + csvFilename
            print "Creating random", csvPathname
            write_syn_dataset(csvPathname, rowCount, colCount, SEEDPERFILE)

            # PARSE train****************************************
            h2o.beta_features = False #turn off beta_features
            start = time.time()
            xList = []
            eList = []
            fList = []

            h2o.beta_features = False
            modelKey = 'GBMModelKey'

            # Parse (train)****************************************
            if h2o.beta_features:
                print "Parsing to fvec directly! Have to noPoll=true!, and doSummary=False!"
            parseTrainResult = h2i.import_parse(bucket=None, path=csvPathname, schema='put',
                hex_key=hex_key, timeoutSecs=timeoutSecs, noPoll=h2o.beta_features, doSummary=False)
            # hack
            if h2o.beta_features:
                h2j.pollWaitJobs(timeoutSecs=timeoutSecs, pollTimeoutSecs=timeoutSecs)
                print "Filling in the parseTrainResult['destination_key'] for h2o"
                parseTrainResult['destination_key'] = trainKey

            elapsed = time.time() - start
            print "train parse end on ", csvPathname, 'took', elapsed, 'seconds',\
                "%d pct. of timeout" % ((elapsed*100)/timeoutSecs)
            print "train parse result:", parseTrainResult['destination_key']

            # Logging to a benchmark file
            algo = "Parse"
            # l = '{:d} jvms, {:d}GB heap, {:s} {:s} {:6.2f} secs'.format(
                # len(h2o.nodes), h2o.nodes[0].java_heap_GB, algo, csvFilename, elapsed)
            l = '{:d} jvms, {:d}MB heap, {:s} {:s} {:6.2f} secs'.format(
                len(h2o.nodes), h2o.nodes[0].java_heap_GB, algo, csvFilename, elapsed)
            print l
            h2o.cloudPerfH2O.message(l)

            # if you set beta_features here, the fvec translate will happen with the Inspect not the GBM
            # h2o.beta_features = True
            inspect = h2o_cmd.runInspect(key=parseTrainResult['destination_key'])
            print "\n" + csvPathname, \
                "    num_rows:", "{:,}".format(inspect['num_rows']), \
                "    num_cols:", "{:,}".format(inspect['num_cols'])
            num_rows = inspect['num_rows']
            num_cols = inspect['num_cols']
            h2o_cmd.infoFromInspect(inspect, csvPathname)

            ### h2o_cmd.runSummary(key=parsTraineResult['destination_key'])

            # GBM(train iterate)****************************************
            h2o.beta_features = True
            # was failing with 100 trees
            # ntrees = 100
            # for max_depth in [5,10,20,40]:
            ntrees = 10
            for max_depth in [5]:
                params = {
                    'learn_rate': .2,
                    'nbins': 100, # 1024 fail
                    'ntrees': ntrees,
                    'max_depth': max_depth,
                    'min_rows': 10,
                    'response': num_cols-1,
                    'ignored_cols_by_name': None,
                }
                print "Using these parameters for GBM: ", params
                kwargs = params.copy()
                h2o.beta_features = True

                trainStart = time.time()
                gbmTrainResult = h2o_cmd.runGBM(parseResult=parseTrainResult,
                    noPoll=h2o.beta_features, timeoutSecs=timeoutSecs, destination_key=modelKey, **kwargs)
                # hack
                if h2o.beta_features:
                    h2j.pollWaitJobs(timeoutSecs=timeoutSecs, pollTimeoutSecs=timeoutSecs)
                trainElapsed = time.time() - trainStart
                print "GBM training completed in", trainElapsed, "seconds. On dataset: ", csvPathname

                # Logging to a benchmark file
                algo = "GBM " + " ntrees=" + str(ntrees) + " max_depth=" + str(max_depth)
                l = '{:d} jvms, {:d}GB heap, {:s} {:s} {:6.2f} secs'.format(
                    len(h2o.nodes), h2o.nodes[0].java_heap_GB, algo, csvFilename, trainElapsed)
                print l
                h2o.cloudPerfH2O.message(l)

                gbmTrainView = h2o_cmd.runGBMView(model_key=modelKey)
                # errrs from end of list? is that the last tree?
                errsLast = gbmTrainView['gbm_model']['errs'][-1]
                print "GBM 'errsLast'", errsLast

                cm = gbmTrainView['gbm_model']['cm']
                pctWrongTrain = h2o_gbm.pp_cm_summary(cm);
                print "Last line of this cm might be NAs, not CM"
                print "\nTrain\n==========\n"
                print h2o_gbm.pp_cm(cm)

                # xList.append(ntrees)
                xList.append(max_depth)
                eList.append(pctWrongTrain)
                fList.append(trainElapsed)

        h2o.beta_features = False
        # just plot the last one
        if DO_PLOT:
            xLabel = 'max_depth'
            eLabel = 'pctWrong'
            fLabel = 'trainElapsed'
            eListTitle = ""
            fListTitle = ""
            h2o_gbm.plotLists(xList, xLabel, eListTitle, eList, eLabel, fListTitle, fList, fLabel)

if __name__ == '__main__':
    h2o.unit_main()
