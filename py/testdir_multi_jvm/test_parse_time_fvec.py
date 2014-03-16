import unittest, random, time, sys
sys.path.extend(['.','..','py'])
import h2o, h2o_hosts, h2o_cmd, h2o_browse as h2b, h2o_import as h2i, h2o_util

# some dates are "wrong"..i.e. the date should be constrained
# depending on month and year.. Assume 1-31 is legal
months = [
    ['Jan', 'JAN'],
    ['Feb', 'FEB'],
    ['Mar', 'MAR'],
    ['Apr', 'APR'],
    ['May', 'MAY'],
    ['Jun', 'JUN'],
    ['Jul', 'JUL'],
    ['Aug', 'AUG'],
    ['Sep', 'SEP'],
    ['Oct', 'OCT'],
    ['Nov', 'NOV'],
    ['Dec', 'DEC']
    ]

# increase weight for Feb
monthWeights = [1 if i!=1 else 5 for i in range(len(months))]

days = map(str, range(1,32))
# increase weight for picking near end of month
dayWeights = [1 if i<27 else 8 for i in range(len(days))]

years = map(str, range(100))

def getRandomDate():
    # assume leading zero is option
    day = days[h2o_util.weighted_choice(dayWeights)]
    if random.randint(0,1) == 1:
        day = day.zfill(2) 

    year = random.choice(years)
    if random.randint(0,1) == 1:
        year = year.zfill(2) 

    # randomly decide on number or translation for month
    ### if random.randint(0,1) == 1:
    # FIX! H2O currently only supports the translate months
    if 1==1:
        month = random.choice(months[h2o_util.weighted_choice(monthWeights)])
    else:
        month = str(random.randint(1,12))
        if random.randint(0,1) == 1:
            month = month.zfill(2) 

    a  = "%s-%s-%s" % (day, month, year)
    return a

def rand_rowData(colCount=6):
    a = [getRandomDate() for fields in range(colCount)]
    # put a little white space in!
    b = ", ".join(map(str,a))
    return b

def write_syn_dataset(csvPathname, rowCount, colCount, headerData=None, rowData=None):
    dsf = open(csvPathname, "w+")
    if headerData is not None:
        dsf.write(headerData + "\n")
    for i in range(rowCount):
        rowData = rand_rowData(colCount)
        dsf.write(rowData + "\n")
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
            h2o.build_cloud(2,java_heap_GB=10,use_flatfile=True)
        else:
            h2o_hosts.build_cloud_with_hosts()

    @classmethod
    def tearDownClass(cls):
        ### time.sleep(3600)
        h2o.tear_down_cloud(h2o.nodes)
    
    def test_parse_time(self):
        h2o.beta_features = True
        SYNDATASETS_DIR = h2o.make_syn_dir()
        csvFilename = "syn_time.csv"
        csvPathname = SYNDATASETS_DIR + '/' + csvFilename

        headerData = None
        colCount = 6
        rowCount = 1000
        write_syn_dataset(csvPathname, rowCount, colCount, headerData)

        for trial in range (20):
            rowData = rand_rowData()
            # make sure all key names are unique, when we re-put and re-parse (h2o caching issues)
            src_key = csvFilename + "_" + str(trial)
            hex_key = csvFilename + "_" + str(trial) + ".hex"

            start = time.time()
            parseResultA = h2i.import_parse(path=csvPathname, schema='put', src_key=src_key, hex_key=hex_key)
            print "\nA trial #", trial, "parse end on ", csvFilename, 'took', time.time() - start, 'seconds'

            inspect = h2o_cmd.runInspect(key=hex_key)
            missingValuesListA = h2o_cmd.infoFromInspect(inspect, csvPathname)
            print "missingValuesListA", missingValuesListA

            numColsA = inspect['numCols']
            numRowsA = inspect['numRows']
            byteSizeA = inspect['byteSize']

            self.assertEqual(missingValuesListA, [], "missingValuesList should be empty")
            self.assertEqual(numColsA, colCount)
            self.assertEqual(numRowsA, rowCount)

            # do a little testing of saving the key as a csv
            csvDownloadPathname = SYNDATASETS_DIR + "/csvDownload.csv"
            h2o.nodes[0].csv_download(src_key=hex_key, csvPathname=csvDownloadPathname)

            # remove the original parsed key. source was already removed by h2o
            h2o.nodes[0].remove_key(hex_key)
            # interesting. what happens when we do csv download with time data?
            start = time.time()
            parseResultB = h2i.import_parse(path=csvDownloadPathname, schema='put', src_key=src_key, hex_key=hex_key)
            print "B trial #", trial, "parse end on ", csvFilename, 'took', time.time() - start, 'seconds'
            inspect = h2o_cmd.runInspect(key=hex_key)
            missingValuesListB = h2o_cmd.infoFromInspect(inspect, csvPathname)
            print "missingValuesListB", missingValuesListB

            numColsB = inspect['numCols']
            numRowsB = inspect['numRows']
            byteSizeB = inspect['byteSize']

            self.assertEqual(missingValuesListA, missingValuesListB,
                "missingValuesList mismatches after re-parse of downloadCsv result")
            self.assertEqual(numColsA, numColsB,
                "numCols mismatches after re-parse of downloadCsv result")
            # H2O adds a header to the csv created. It puts quotes around the col numbers if no header
            # so I guess that's okay. So allow for an extra row here.
            self.assertEqual(numRowsA, numRowsB,
                "numRowsA: %s numRowsB: %s mismatch after re-parse of downloadCsv result" % (numRowsA, numRowsB) )
            print "H2O writes the internal format (number) out for time."

            # ==> syn_time.csv <==
            # 31-Oct-49, 25-NOV-10, 08-MAR-44, 23-Nov-34, 19-Feb-96, 23-JUN-30
            # 31-Oct-49, 25-NOV-10, 08-MAR-44, 23-Nov-34, 19-Feb-96, 23-JUN-30

            # ==> csvDownload.csv <==
            # "0","1","2","3","4","5"
            # 2.5219584E12,1.293264E12,2.3437116E12,2.0504736E12,3.9829788E12,1.9110204E12

            if 1==0:
                # extra line for column headers?
                self.assertEqual(byteSizeA, byteSizeB,
                    "byteSize mismatches after re-parse of downloadCsv result %d %d" % (byteSizeA, byteSizeB) )

            # FIX! should do some comparison of values? 
            # maybe can use exec to checksum the columns and compare column list.
            # or compare to expected values? (what are the expected values for the number for time inside h2o?)

            # FIX! should compare the results of the two parses. The infoFromInspect result?
            ### h2b.browseJsonHistoryAsUrlLastMatch("Inspect")
            h2o.check_sandbox_for_errors()

if __name__ == '__main__':
    h2o.unit_main()

    


