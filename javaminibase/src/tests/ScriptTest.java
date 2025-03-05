package tests;

import global.AttrType;
import global.SystemDefs;
import heap.Heapfile;
import heap.Tuple;
import iterator.FileScan;
import iterator.FldSpec;
import iterator.RelSpec;
import scripts.BatchInsert;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.stream.IntStream;

import static global.GlobalConst.NUMBUF;

public class ScriptTest {

//    1) SET TEST CONSTANTS HERE
    static String INPUT_FILE_PATH = "./javaminibase/src/tests/scriptTestDataFiles/sample_data_1.txt";
    static String DB_NAME = "batchInsertTest1";


    static AttrType[] attrTypes;
    static FldSpec[] projlist;
    static short[] string_lengths;
    static short num_attributes;

    public static void main(String[] args) throws Exception {

//        2) PICK A TEST/TESTS. COMMENT OUT REST
//        createNewDb();
        restartOldDb();
        readHeapFile();
    }

    private static void createNewDb() throws Exception {
        BatchInsert.main(new String[]{"2", "2", INPUT_FILE_PATH, DB_NAME});

        Heapfile hf = new Heapfile(Paths.get(INPUT_FILE_PATH).getFileName().toString());
        System.out.println("Record Count in Heap File = " + hf.getRecCnt());
    }

    private static void restartOldDb() throws Exception {
        String dbpath = "/tmp/"  + System.getProperty("user.name") + "."+ DB_NAME + "-db";
        SystemDefs.MINIBASE_RESTART_FLAG = true;
        SystemDefs systemDefs = new SystemDefs(dbpath, NUMBUF, NUMBUF, "Clock");

        Heapfile hf = new Heapfile(Paths.get(INPUT_FILE_PATH).getFileName().toString());
        System.out.println("Record Count in Heap File = " + hf.getRecCnt());
    }

    private static void readHeapFile() throws Exception {
        Heapfile hf = new Heapfile(Paths.get(INPUT_FILE_PATH).getFileName().toString());
        prepareTupleDescriptorData();

        FileScan hfScan = new FileScan("sample_data_1.txt", attrTypes, string_lengths, num_attributes, num_attributes, projlist, null);
        Tuple t = hfScan.get_next();

        int i = 1;
        while(t  != null) {
            System.out.println("\nTuple " + i);

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
            t = hfScan.get_next();
            i++;
        }
    }

    private static void prepareTupleDescriptorData() throws IOException{
        prepareAttrTypesAndStrLengths();
        prepareProjList();
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

}
