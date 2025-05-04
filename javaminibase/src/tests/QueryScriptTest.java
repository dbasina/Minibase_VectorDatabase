package tests;

import LSHFIndex.LSHFIndex;
import bufmgr.PagePinnedException;
import global.AttrType;
import global.IndexType;
import global.Vector100Dtype;
import heap.Heapfile;
import heap.Tuple;
import index.NNIndexScan;
import index.RSIndexScan;
import iterator.FldSpec;
import iterator.RelSpec;
import iterator.TupleUtils;
import scripts.Query;

import java.util.stream.IntStream;

import static global.SystemDefs.JavabaseBM;

public class QueryScriptTest {

    static String QUERY_SPECIFICATION_FILE_PATH = "./javaminibase/src/tests/scriptTestDataFiles/queryDataFiles/nquery1.txt";
    static String NUM_BUFFERS = "1500";
    
//    RUN THIS AFTER RUNNING BatchInsertTests! DB file must be present for these tests to work

    public static void main(String[] args) throws Exception {
//        Pick 1) or 2). Comment out the other
//        1) Custom test
//        Query.main(new String[]{BatchInsertScriptTest.DB_NAME, QUERY_SPECIFICATION_FILE_PATH, "N", NUM_BUFFERS});

//        2) Predefined Tests
//        Query.restartDb(BatchInsertScriptTest.DB_NAME, Integer.parseInt(NUM_BUFFERS));
//
//        testNoIndexNnSearch();
//        testNoIndexRsSearch();
//        testWithIndexNnScan();
//        testWithIndexRsScan();

//        try {
//            JavabaseBM.flushAllPages();
//        } catch (PagePinnedException ignored) {}
    }

    private static void testNoIndexNnSearch() throws Exception {
        System.out.println();

        QUERY_SPECIFICATION_FILE_PATH = "./javaminibase/src/tests/scriptTestDataFiles/queryDataFiles/nquery1.txt";
        int nnAsked = 5;

        Heapfile unionDumpFile = null;
        unionDumpFile.deleteFile();

        int[] outputFieldNumbers = new int[]{1,2};
        FldSpec[] projList = new FldSpec[outputFieldNumbers.length];
        IntStream.range(0, outputFieldNumbers.length).forEach(i -> projList[i] = new FldSpec(new RelSpec(RelSpec.outer), outputFieldNumbers[i]));

        Vector100Dtype targetVector = Query.read_target_vector("./javaminibase/src/tests/scriptTestDataFiles/queryDataFiles/target1.txt");

        NNIndexScan scan = new NNIndexScan(
                null, null, null, Query.attrTypes, Query.strLengths, 0, outputFieldNumbers.length,
                projList, null, 2, targetVector, nnAsked
        );

        Tuple t = scan.get_next();

//        unionDumpFile = new Heapfile(LSHFIndex.UNION_DUMP_HEAP_FILE_NAME);
        if(unionDumpFile.getRecCnt() != 0) {
            scan.close();
            throw new RuntimeException("FAIL - testNoIndexNnSearch - UnionDump file should be empty as no index was used!");
        }

        int elemCount = 0;
        while(t != null) {
            elemCount++;
            t = scan.get_next();
        }
        scan.close();

        if(elemCount > 5)
            throw new RuntimeException("FAIL - testNoIndexNnSearch - Nearest Neighbors Asked - " + nnAsked + " Neighbors Returned - " + elemCount);

        System.out.println("PASS - testNoIndexNnSearch");
    }

    private static void testNoIndexRsSearch() throws Exception {
        System.out.println();

        QUERY_SPECIFICATION_FILE_PATH = "./javaminibase/src/tests/scriptTestDataFiles/queryDataFiles/rquery1.txt";
        int distance = 1;

        Heapfile unionDumpFile = null;
        unionDumpFile.deleteFile();

        int[] outputFieldNumbers = new int[]{1,2};
        FldSpec[] projList = new FldSpec[outputFieldNumbers.length];
        IntStream.range(0, outputFieldNumbers.length).forEach(i -> projList[i] = new FldSpec(new RelSpec(RelSpec.outer), outputFieldNumbers[i]));

        Vector100Dtype targetVector = Query.read_target_vector("./javaminibase/src/tests/scriptTestDataFiles/queryDataFiles/target1.txt");

        Tuple targetTuple = new Tuple();
        targetTuple.setHdr((short) 1, new AttrType[]{new AttrType(AttrType.attrVector100D)}, new short[0]);
        targetTuple.set100DVectFld(1, targetVector);

        RSIndexScan scan = new RSIndexScan(
                null,
                null, null, Query.attrTypes, Query.strLengths, 0, outputFieldNumbers.length, projList, null,
                2, targetVector, distance
        );

        Tuple t = scan.get_next();

        unionDumpFile = null;
        if(unionDumpFile.getRecCnt() != 0) {
            scan.close();
            throw new RuntimeException("FAIL - testNoIndexRsSearch - UnionDump file should be empty as no index was used!");
        }

        while(t != null) {
            int currDistance = TupleUtils.CompareTupleWithTuple(new AttrType(AttrType.attrVector100D), targetTuple, 1, t, 2);

            if(currDistance > distance) {
                scan.close();
                throw new RuntimeException("FAIL - testNoIndexRsSearch - Distance Asked - " + distance + " current tuple distance - " + currDistance);
            }
            t = scan.get_next();
        }
        scan.close();

        System.out.println("PASS - testNoIndexRsSearch");
    }

    private static void testWithIndexNnScan() throws Exception {
        System.out.println();

        QUERY_SPECIFICATION_FILE_PATH = "./javaminibase/src/tests/scriptTestDataFiles/queryDataFiles/nquery1.txt";
        int nnAsked = 5;

        int[] outputFieldNumbers = new int[]{1,2};
        FldSpec[] projList = new FldSpec[outputFieldNumbers.length];
        IntStream.range(0, outputFieldNumbers.length).forEach(i -> projList[i] = new FldSpec(new RelSpec(RelSpec.outer), outputFieldNumbers[i]));

        Vector100Dtype targetVector = Query.read_target_vector("./javaminibase/src/tests/scriptTestDataFiles/queryDataFiles/target1.txt");

        NNIndexScan scan = new NNIndexScan(
                new IndexType(IndexType.Lsh), null, null, Query.attrTypes, Query.strLengths, 0, outputFieldNumbers.length,
                projList, null, 2, targetVector, nnAsked
        );

        Tuple t = scan.get_next();

        Heapfile unionDumpFile = null;
        if(unionDumpFile.getRecCnt() == 0) {
            scan.close();
            throw new RuntimeException("FAIL - testWithIndexNnScan - UnionDump file should not be empty as index was used!");
        }

        int elemCount = 0;
        while(t != null) {
            elemCount++;
            t = scan.get_next();
        }
        scan.close();

        if(elemCount > 5)
            throw new RuntimeException("FAIL - testWithIndexNnScan - Nearest Neighbors Asked - " + nnAsked + " Neighbors Returned - " + elemCount);

        System.out.println("PASS - testWithIndexNnScan");
    }

    private static void testWithIndexRsScan() throws Exception {
        System.out.println();

        QUERY_SPECIFICATION_FILE_PATH = "./javaminibase/src/tests/scriptTestDataFiles/queryDataFiles/rquery1.txt";
        int distance = 1;

        int[] outputFieldNumbers = new int[]{1,2};
        FldSpec[] projList = new FldSpec[outputFieldNumbers.length];
        IntStream.range(0, outputFieldNumbers.length).forEach(i -> projList[i] = new FldSpec(new RelSpec(RelSpec.outer), outputFieldNumbers[i]));

        Vector100Dtype targetVector = Query.read_target_vector("./javaminibase/src/tests/scriptTestDataFiles/queryDataFiles/target1.txt");

        Tuple targetTuple = new Tuple();
        targetTuple.setHdr((short) 1, new AttrType[]{new AttrType(AttrType.attrVector100D)}, new short[0]);
        targetTuple.set100DVectFld(1, targetVector);

        RSIndexScan scan = new RSIndexScan(
                new IndexType(IndexType.Lsh), null, null, Query.attrTypes, Query.strLengths, 0,
                outputFieldNumbers.length, projList, null, 2, targetVector, distance
        );

        Tuple t = scan.get_next();

        Heapfile unionDumpFile = null;
        if(unionDumpFile.getRecCnt() == 0) {
            scan.close();
            throw new RuntimeException("FAIL - testWithIndexRsScan - UnionDump file should not be empty as index was used!");
        }

        while(t != null) {
            int currDistance = TupleUtils.CompareTupleWithTuple(new AttrType(AttrType.attrVector100D), targetTuple, 1, t, 2);

            if(currDistance > distance) {
                scan.close();
                throw new RuntimeException("FAIL - testWithIndexRsScan - Distance Asked - " + distance + " current tuple distance - " + currDistance);
            }
            t = scan.get_next();
        }
        scan.close();

        System.out.println("PASS - testWithIndexRsScan");
    }

}
