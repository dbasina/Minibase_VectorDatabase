package tests;

import LSHFIndex.LSHFIndex;
import global.AttrType;
import global.SystemDefs;
import global.TupleOrder;
import global.Vector100Dtype;
import heap.*;
import iterator.*;
import scripts.BatchInsert;
import scripts.Query;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.stream.IntStream;

import static global.GlobalConst.NUMBUF;

public class BatchInsertScriptTest {

//    1) SET TEST CONSTANTS HERE
    static String INPUT_FILE_PATH = "./javaminibase/src/tests/scriptTestDataFiles/sample75_000.txt";
    static String DB_NAME = "batchInsertTest1";
    static int VECTOR_lENGTH = 100;
    static String NUM_HASHES = "6";
    static String NUM_LAYERS = "6";


    static AttrType[] attrTypes;
    static FldSpec[] projlist;
    static short[] string_lengths;
    static short num_attributes;

    static String targetVectorString = "33 94 82 70 57 5 67 73 54 74 80 79 33 86 57 45 13 27 80 99 26 30 16 90 47 33 92 3 82 9 7 78 7 86 73 56 1 47 82 19 78 28 67 16 89 96 70 13 13 11 84 6 2 82 22 13 56 47 36 22 14 14 36 37 90 42 86 63 54 49 64 90 2 18 26 90 20 44 40 6 40 49 65 31 75 75 19 98 60 56 14 42 68 64 36 88 18 94 90 24";
    static Vector100Dtype target = new Vector100Dtype();
    static short[] vectorFieldNumbers = new short[]{2, 4};

    public static void main(String[] args) throws Exception {

//        2) PICK A TEST/TESTS. COMMENT OUT REST

//        DB Independent Tests
//        testLshfIndexPreservationAndRestore();

        createNewDb();
//        readHeapFile();

//        restartOldDb();

//        Query.numBuffersForSort = 12;
//
//        testSortOnExistingDb();
//        testHashGeneration();
//        testWriteDuringTupleHashAndReadBins();
//        testIndexUnion();
    }

    private static void createNewDb() throws Exception {
        System.out.println();

        BatchInsert.main(new String[]{NUM_HASHES, NUM_LAYERS, INPUT_FILE_PATH, DB_NAME});

        FileScan dbMetadataScan = new FileScan(BatchInsert.DB_DATA_METADATA_HEAP_FILE_NAME,
                new AttrType[]{new AttrType(AttrType.attrInteger)},
                null,
                (short)1,
                1,
                new FldSpec[] {new FldSpec(new RelSpec(RelSpec.outer), 1)},
                null
        );

        ArrayList<Integer> metadataAttrTypes = new ArrayList<>();
        Tuple t = dbMetadataScan.get_next();
        while(t != null) {
            metadataAttrTypes.add(t.getIntFld(1));
            t = dbMetadataScan.get_next();
        }
        dbMetadataScan.close();

        prepareAttrTypesAndStrLengths();
        if(metadataAttrTypes.size() != attrTypes.length)
            throw new RuntimeException("FAIL - createNewDb - metadata file atttr count - " + metadataAttrTypes.size() +
                    " mismatch with input data attr type count - " + attrTypes.length);

        for(int i = 0; i < metadataAttrTypes.size(); i++ ) {
            if(metadataAttrTypes.get(i) != attrTypes[i].attrType)
                throw new RuntimeException("FAIL - createNewDb - attrType mismatch between data file and metadata file. Index = " + i +
                        " attr type from data file - " + attrTypes[i] + " attr type from metadata file - " + metadataAttrTypes.get(i));
        }

        Heapfile hf = new Heapfile(BatchInsert.DB_DATA_HEAP_FILE_NAME);
        System.out.println("PASS - Create Db");
        System.out.println("Record Count in Heap File = " + hf.getRecCnt());
    }

    private static void restartOldDb() throws Exception {
        System.out.println();

        String dbpath = "/tmp/"  + System.getProperty("user.name") + "."+ DB_NAME + "-db";
        SystemDefs.MINIBASE_RESTART_FLAG = true;
        new SystemDefs(dbpath, NUMBUF, NUMBUF, "Clock");

        Heapfile hf = new Heapfile(BatchInsert.DB_DATA_HEAP_FILE_NAME);
        System.out.println("PASS - Restart Db");
        System.out.println("Record Count in Heap File = " + hf.getRecCnt());
    }

    private static void readHeapFile() throws Exception {
        System.out.println();

        FileScan hfScan = perpareAndGetHeapFileScan();
        Tuple t = hfScan.get_next();

        int i = 0;
        while(t  != null) {
//            System.out.println("\nTuple " + i);
//            printTuple(t);
            t = hfScan.get_next();
            i++;
        }

        System.out.println("PASS - readHeapFile - Record Count - " + i);
    }

    public static void testSortOnExistingDb() throws Exception {
        System.out.println();

        prepareTargetVectorTypeFromString();

        for(int i=0; i < vectorFieldNumbers.length; i++) {
            System.out.println("Sort Test - Sorting on fieldNumber - " + vectorFieldNumbers[i]);

            FileScan hfScan = perpareAndGetHeapFileScan();
            Sort sort = new Sort(attrTypes, num_attributes, string_lengths, hfScan, vectorFieldNumbers[i], new TupleOrder(TupleOrder.Ascending), VECTOR_lENGTH, 12, target, 0);
            Tuple t = sort.get_next();

            Tuple targetTuple = new Tuple();
            targetTuple.setHdr((short) 1, new AttrType[]{new AttrType(AttrType.attrVector100D)}, new short[0]);
            targetTuple.set100DVectFld(1, target);

            int prevDistance = Integer.MIN_VALUE;
            while (t != null) {
                int distance = TupleUtils.CompareTupleWithTuple(new AttrType(AttrType.attrVector100D), targetTuple, 1, t, vectorFieldNumbers[i]);
                System.out.println("Distance from Target = " + distance);
                if(distance < prevDistance)
                    throw new RuntimeException("FAIL - Sort Test - No longer in ascending order!!");
                prevDistance = distance;
//                printTuple(t);
                t = sort.get_next();
            }
            sort.close();
            hfScan.close();
        }

        System.out.println("PASS - Sort Test");
    }

    public static void testHashGeneration() throws Exception {
        System.out.println();

        int nLayers = 1;
        int nHashes = 5;
        int binLength = 1_000_000_000;
        HashMap<Integer, LSHFIndex> fieldNumberTolshfIndex = new HashMap<>();
        for(int vectorFieldNumber : vectorFieldNumbers)
            fieldNumberTolshfIndex.put(vectorFieldNumber, new LSHFIndex("",nLayers, binLength, nHashes, vectorFieldNumber));

        for(int vectorFieldNumber : vectorFieldNumbers) {
            FileScan hfScan = perpareAndGetHeapFileScan();
            Tuple t = hfScan.get_next();
            HashSet<String> uniqueAllLayerHashes = new HashSet<>();

            while(t != null) {
                Vector100Dtype vector = new Vector100Dtype(t.get100DVectFld(vectorFieldNumber).vector);

                String[] hash1 = fieldNumberTolshfIndex.get(vectorFieldNumber).getAllLayersHash(vector);
                String[] hash2 = fieldNumberTolshfIndex.get(vectorFieldNumber).getAllLayersHash(vector);
                if(! Arrays.equals(hash1, hash2)) {
                    System.out.println("Hash 1 - " + hash1);
                    System.out.println("Hash 1 - " + hash2);
                    throw new RuntimeException("FAIL - testHashGeneration - HASH MISMATCH!");
                }

                uniqueAllLayerHashes.add(String.join("_", hash1));
                t = hfScan.get_next();
            }

//            uniqueAllLayerHashes.forEach(System.out::println);
            System.out.println("Unique Hashes Count for fieldNumber - " + vectorFieldNumber + " = " + uniqueAllLayerHashes.size());
            hfScan.close();
        }
        System.out.println("PASS - testHashGeneration");
    }

    private static void testLshfIndexPreservationAndRestore() throws Exception {
        System.out.println();

        String dbpath = "/tmp/"  + System.getProperty("user.name") + ".lshfPreservationTest-db";
        Files.deleteIfExists(Paths.get(dbpath));
        new SystemDefs(dbpath, NUMBUF,NUMBUF, "Clock");

        LSHFIndex originalIndex1 = new LSHFIndex("",3, 10, 5, 1);
        LSHFIndex indexFromDisk1 = new LSHFIndex("",1);
        if(! originalIndex1.equals(indexFromDisk1)) {
            System.out.println("FAIL - Index PreservationTest - Single index not equal!!!");
            throw new RuntimeException("FAIL - Index PreservationTest");
        }

        LSHFIndex originalIndex2 = new LSHFIndex("",3, 7, 2, 2);
        LSHFIndex indexFromDisk2 = new LSHFIndex("",2);
        indexFromDisk1 = new LSHFIndex("",1);
        if(! originalIndex2.equals(indexFromDisk2) || ! originalIndex1.equals(indexFromDisk1)) {
            System.out.println("FAIL - Index PreservationTest - Multi index not equal!!!");
            throw new RuntimeException("FAIL - Index PreservationTest");

        }

        System.out.println("PASS - Index PreservationTest");
    }

    private static void testWriteDuringTupleHashAndReadBins() throws Exception {
        System.out.println();

        HashMap<Integer, LSHFIndex> vectorFieldNumberToLshIndex = new HashMap<>();
        for(int vectorFieldNumber : vectorFieldNumbers)
            vectorFieldNumberToLshIndex.put(vectorFieldNumber, new LSHFIndex("",vectorFieldNumber));


        FileScan hfScan = perpareAndGetHeapFileScan();

        int dataHeapFileRecordSize = 0;

        Tuple t = hfScan.get_next();
        HashMap<Integer, HashMap<Integer, HashSet<String>>> vectorFieldNumberToLayerToUniqueHashesMap = new HashMap<>();
        while(t != null) {
            dataHeapFileRecordSize++;

            for(int vectorFieldNumber : vectorFieldNumbers) {
                HashMap<Integer, HashSet<String>> layerToUniqueHashesMap = vectorFieldNumberToLayerToUniqueHashesMap.compute(vectorFieldNumber, (k,v) -> (v == null) ? new HashMap<>() : v);
                LSHFIndex index = vectorFieldNumberToLshIndex.get(vectorFieldNumber);

                String[] hashes = index.getAllLayersHash(t.get100DVectFld(vectorFieldNumber));
                IntStream.range(0, hashes.length).forEach(i -> layerToUniqueHashesMap.compute(i, (k,v) -> (v==null) ? new HashSet<>() : v).add(hashes[i]));
            }
            t = hfScan.get_next();
        }
        hfScan.close();

        HashMap<Integer, HashMap<Integer, Integer>> vectorFieldNumberToLayerToNumberOfRecords = new HashMap<>();
        for(int vectorFieldNumber : vectorFieldNumbers) {
            HashMap<Integer, HashSet<String>> layerToUniqueHashesMap = vectorFieldNumberToLayerToUniqueHashesMap.get(vectorFieldNumber);
            for(int i = 0 ; i < Integer.parseInt(NUM_LAYERS); i ++) {
                for(String hash : layerToUniqueHashesMap.get(i)) {
//                    Heapfile hf = new Heapfile(LSHFIndex.generateBinHeapFileName(i, vectorFieldNumber, hash));
                    HashMap<Integer, Integer> layerToNumberOfRecords = vectorFieldNumberToLayerToNumberOfRecords.compute(vectorFieldNumber, (k,v) -> (v == null) ? new HashMap<>() : v);
//                    layerToNumberOfRecords.put(i, layerToNumberOfRecords.getOrDefault(i, 0) + hf.getRecCnt());
                }
            }
        }

        for(int vectorFieldNumber : vectorFieldNumbers) {
            HashMap<Integer, Integer> layerToNumberOfRecords = vectorFieldNumberToLayerToNumberOfRecords.get(vectorFieldNumber);
            for(Integer i : layerToNumberOfRecords.keySet()) {
                if (layerToNumberOfRecords.get(i) != dataHeapFileRecordSize) {
                    throw new RuntimeException("FAIL - testWriteDuringTupleHashAndReadBins - " +
                            "MISMATCH!! dataHeapFileRecordSize = " + dataHeapFileRecordSize +
                            " but layer " + i + " has only " + layerToNumberOfRecords.get(i) + " records for vectorFieldNumber "
                            + vectorFieldNumber);
                }
            }
        }

        System.out.println("PASS - testWriteDuringTupleHashAndReadBins");
    }

    private static void testIndexUnion() throws Exception {
        System.out.println();
        prepareTargetVectorTypeFromString();
        prepareAttrTypesAndStrLengths();
        prepareProjList();

        int totalRecordsInDataFile = new Heapfile(BatchInsert.DB_DATA_HEAP_FILE_NAME).getRecCnt();

        Tuple targetTuple = new Tuple();
        targetTuple.setHdr((short) 1, new AttrType[]{new AttrType(AttrType.attrVector100D)}, new short[0]);
        targetTuple.set100DVectFld(1, target);

        for(int vectorFieldNumber : vectorFieldNumbers) {
            LSHFIndex index = new LSHFIndex("",vectorFieldNumber);

            Heapfile unionFile = index.union(target, new Heapfile(BatchInsert.DB_DATA_HEAP_FILE_NAME));
            System.out.println("Union File Record Count - " + unionFile.getRecCnt());
            if(unionFile.getRecCnt() > totalRecordsInDataFile)
//                Duplicate elimination failing or you are not deleting and recreating union file before new union
                throw new RuntimeException("FAIL - testIndexUnion - unionFile has more records than data file! UnionFileRecCount - " +
                        unionFile.getRecCnt() + " dataFileRecCount - " + totalRecordsInDataFile);

            FileScan scan = null;
            Sort sort = new Sort(attrTypes, num_attributes, string_lengths, scan, vectorFieldNumber, new TupleOrder(TupleOrder.Ascending), VECTOR_lENGTH, 12, target, 0);

            Tuple t = sort.get_next();
            int prevDistance = Integer.MIN_VALUE;
            Vector100Dtype prevVector = null;
            while(t  != null) {
                int distance = TupleUtils.CompareTupleWithTuple(new AttrType(AttrType.attrVector100D), targetTuple, 1, t, vectorFieldNumber);
                System.out.println("Distance from Target = " + distance);

                if(distance < prevDistance)
                    throw new RuntimeException("FAIL - testIndexUnion - No longer in ascending order!! vectorFieldNumber - " + vectorFieldNumber);
                prevDistance = distance;

//                Use sample_data_1.txt as it has no duplicates. Else comment out this check if data file itself has duplicates
                Vector100Dtype currentVector = t.get100DVectFld(vectorFieldNumber);
                if(currentVector.equals(prevVector))
                    throw new RuntimeException("FAIL - testIndexUnion - Duplicate detected!! vectorFieldNumber - " + vectorFieldNumber);
                prevVector = currentVector;

//            printTuple(t);
                t = sort.get_next();
            }
            sort.close();
            scan.close();
        }
        System.out.println("PASS - testIndexUnion");
    }

//    TEST HELPERS

    private static FileScan perpareAndGetHeapFileScan() throws Exception {
        prepareAttrTypesAndStrLengths();
        prepareProjList();

        return new FileScan(BatchInsert.DB_DATA_HEAP_FILE_NAME, attrTypes, string_lengths, num_attributes, num_attributes, projlist, null);
    }

    private static void prepareAttrTypesAndStrLengths() throws IOException {
        short string_attribute_count = 0;
        short vector_attribute_count = 0;

        BufferedReader br = new BufferedReader(new FileReader(INPUT_FILE_PATH));

        String line = br.readLine();
        num_attributes = Short.parseShort(line.trim());

        line = br.readLine();
        String[] attribute_types = line.split("\\s+");

        attrTypes = new AttrType[num_attributes];

        for (int i = 0; i < num_attributes; i++ )
        {
            int type = Integer.parseInt(attribute_types[i].trim());

            // Map the integers from datafile to attribute types for tuple.
            switch (type)
            {
                case 1:
                    // Integer
                    type = 1;
                    break;
                case 2:
                    // real
                    type = 2;
                    break;
                case 3:
                    // string
                    type = 0;
                    string_attribute_count++;
                    break;
                case 4:
                    // 100D-vector.
                    type = 5;
                    vector_attribute_count++;
                    break;
                default:
                    throw new IOException("Unknown attribute type"+type);
            }
            attrTypes[i] = new AttrType(type);
        }

        short max_string_length = 64;
        string_lengths = new short[string_attribute_count];
        for  (int i = 0; i < string_attribute_count; i++ )
        {
            string_lengths[i] = max_string_length;
        }
    }

    private static void prepareProjList() {
        projlist= new FldSpec[num_attributes];
        IntStream.range(0, num_attributes).forEach(i -> projlist[i] = new FldSpec(new RelSpec(RelSpec.outer), i+1));
    }

    private static void prepareTargetVectorTypeFromString() {
        String[] splittargetVectorString = targetVectorString.split(" ");
        IntStream.range(0, splittargetVectorString.length).forEach(i -> target.vector[i] = Short.parseShort(splittargetVectorString[i]));
    }

    private static void printTuple(Tuple t) throws Exception {
        for(int j=0; j < attrTypes.length; j++) {
            switch (attrTypes[j].attrType) {
                case AttrType.attrInteger:
                    System.out.println("Integer = " + t.getIntFld(j+1));
                    break;
                case AttrType.attrString:
                    System.out.println("String = " + t.getStrFld(j+1));
                    break;
                case AttrType.attrReal:
                    System.out.println("Real = " + t.getFloFld(j+1));
                    break;
                case AttrType.attrVector100D:
                    System.out.println("Vector 1st Element = " + t.get100DVectFld(j+1).vector[0]);
                    break;
            }
        }
    }
}
