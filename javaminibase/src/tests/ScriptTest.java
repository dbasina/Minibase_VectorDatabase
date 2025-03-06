package tests;

import global.AttrType;
import global.SystemDefs;
import global.TupleOrder;
import global.Vector100Dtype;
import heap.Heapfile;
import heap.Tuple;
import iterator.*;
import scripts.BatchInsert;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.stream.IntStream;

import static global.GlobalConst.NUMBUF;

public class ScriptTest {

//    1) SET TEST CONSTANTS HERE
    static String INPUT_FILE_PATH = "./javaminibase/src/tests/scriptTestDataFiles/combinedSampleData.txt";
    static String INPUT_FILE_NAME = Paths.get(INPUT_FILE_PATH).getFileName().toString();
    static String DB_NAME = "batchInsertTest1";
    static int VECTOR_lENGTH = 100;


    static AttrType[] attrTypes;
    static FldSpec[] projlist;
    static short[] string_lengths;
    static short num_attributes;
    static String targetVectorString = "4565 8373 27 4635 1491 6298 7433 9922 608 3908 3098 1048 9312 6420 8885 2242 5275 473 4824 766 2347 4009 5474 2706 5885 4529 2362 4247 9073 9235 5209 2376 3401 9946 2082 2997 78 1007 5763 2260 4856 7147 1830 5692 8322 908 5034 1413 526 1567 4363 7338 2412 8365 8098 2780 1302 7951 2061 6802 300 8272 8652 1225 6443 530 1004 2160 766 4723 4578 6777 8567 9665 9326 8125 5287 946 3089 5472 9485 3344 7538 1317 8692 8373 5006 5374 7018 1738 7045 8212 3542 3146 7298 9058 4077 9605 5734 1954";
    static Vector100Dtype target = new Vector100Dtype();
    static short sortFieldNumber = 2;

    public static void main(String[] args) throws Exception {

//        2) PICK A TEST/TESTS. COMMENT OUT REST
//        createNewDb();

        restartOldDb();

//        readHeapFile();
        testSortOnExistingDb();
    }

    private static void createNewDb() throws Exception {
        BatchInsert.main(new String[]{"2", "2", INPUT_FILE_PATH, DB_NAME});

        Heapfile hf = new Heapfile(INPUT_FILE_NAME);
        System.out.println("Record Count in Heap File = " + hf.getRecCnt());
    }

    private static void restartOldDb() throws Exception {
        String dbpath = "/tmp/"  + System.getProperty("user.name") + "."+ DB_NAME + "-db";
        SystemDefs.MINIBASE_RESTART_FLAG = true;
        SystemDefs systemDefs = new SystemDefs(dbpath, NUMBUF, NUMBUF, "Clock");

        Heapfile hf = new Heapfile(INPUT_FILE_NAME);
        System.out.println("Record Count in Heap File = " + hf.getRecCnt());
    }

    private static void readHeapFile() throws Exception {
        FileScan hfScan = perpareAndGetHeapFileScan();
        Tuple t = hfScan.get_next();

        int i = 1;
        while(t  != null) {
            System.out.println("\nTuple " + i);
            printTuple(t);
            t = hfScan.get_next();
            i++;
        }
    }

    public static void testSortOnExistingDb() throws Exception {
        prepareTargetVectorTypeFromString();
        FileScan hfScan = perpareAndGetHeapFileScan();

        Sort sort = new Sort(attrTypes, num_attributes, string_lengths, hfScan, sortFieldNumber, new TupleOrder(TupleOrder.Ascending), VECTOR_lENGTH, 12, target, 0);
        Tuple t = sort.get_next();

        Tuple targetTuple = new Tuple();
        targetTuple.setHdr((short) 1, new AttrType[]{new AttrType(AttrType.attrVector100D)}, new short[0]);
        targetTuple.set100DVectFld(1, target);

        while(t != null) {
            int distance = TupleUtils.CompareTupleWithTuple(new AttrType(AttrType.attrVector100D), targetTuple, 1, t, sortFieldNumber);
            System.out.println("\nDistance from Target = " + distance);
            printTuple(t);
            t = sort.get_next();
        }

    }

//    TEST HELPERS

    private static FileScan perpareAndGetHeapFileScan() throws Exception {
        prepareAttrTypesAndStrLengths();
        prepareProjList();

        return new FileScan(INPUT_FILE_NAME, attrTypes, string_lengths, num_attributes, num_attributes, projlist, null);
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
