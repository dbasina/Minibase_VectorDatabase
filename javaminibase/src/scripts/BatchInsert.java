package scripts;

import diskmgr.Pcounter;
import global.SystemDefs;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

import global.*;
import heap.*;
import LSHFIndex.*;

import static global.SystemDefs.JavabaseBM;

public class BatchInsert implements GlobalConst
{
    public static final String DB_DATA_METADATA_HEAP_FILE_NAME = "dataFileMetaData";
    public static final String DB_DATA_HEAP_FILE_NAME = "dataFile";
    public static final short MAX_STRING_LENGTH = 64;
    public static final int DB_SIZE_IN_PAGES = NUMBUF * 10;

    private static final String TEMP_QUERY_FILE_NAME = "tempNQuery.txt";
    private static final String TEMP_TARGET_FILE_NAME = "tempNTarget.txt";

    public static void main(String[] args) throws Exception {
        if (args.length != 4)
        {
            System.err.println("Batch insert requires 4 arguments.");
            System.exit(1);
        }

        // get arguments
        int bin_length = 1_000_000_000;
        int num_hashes = Integer.parseInt(args[0]);
        int num_layers = Integer.parseInt(args[1]);
        String dataFilePath = args[2];
        String database_name = args[3];


        // create the database with database_name.
        String dbpath = getDbFileSystemPath(database_name);

        new SystemDefs(dbpath, DB_SIZE_IN_PAGES, DB_SIZE_IN_PAGES, "Clock");
        Pcounter.initialize();

        short string_attribute_count = 0;
        final ArrayList<Integer> vectorFieldNumbers = new ArrayList<>();

        // Create a Buffered reader to read the file: data_file_name

        BufferedReader br = new BufferedReader(new FileReader(dataFilePath));

        // Read first line extract num_attributes
        String line = br.readLine();
        if ( line == null )
        {
            throw new IOException("Empty file");
        }

        // first line has num attributes per tuple.
        short num_attributes = Short.parseShort(line.trim());

        // Read second line. Get the attribute types.
        line = br.readLine();
        if ( line == null )
        {
            throw new IOException("Attribute types missing");
        }

        String[] attribute_types = line.split("\\s+");
        if (attribute_types.length != num_attributes)
        {
            throw new IOException("Attribute count and number of attribute types provided mismatch");
        }

        // Define attrTypes array based on each of the attributes for tuple.
        AttrType[] attrTypes = new AttrType[num_attributes];

        // Create metadata file and store the attribute types in each row.
        // Create tuple to insert into metadata file.
        Tuple metaDataTuple = new Tuple();
        metaDataTuple.setHdr((short)1, new AttrType[]{new AttrType(AttrType.attrInteger)}, null);

        // Create a metadata heapfile
        Heapfile dataFileMetaData = new Heapfile(DB_DATA_METADATA_HEAP_FILE_NAME);
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
                    vectorFieldNumbers.add(i+1);
                    break;
                default:
                    throw new IOException("Unknown attribute type"+type);
            }
            attrTypes[i] = new AttrType(type);
            metaDataTuple.setIntFld(1,type);
            dataFileMetaData.insertRecord(metaDataTuple.getTupleByteArray());
        }

        // Create dummy tuple to convert data from data_file_name to tuples.
        Tuple t = new Tuple();

        // For each string attribute, define max length of string attribute.
        short[] string_lengths = new short[string_attribute_count];
        for  (int i = 0; i < string_attribute_count; i++ )
        {
            string_lengths[i] = MAX_STRING_LENGTH;
        }

        // Set the tuple header based on the data_file_name specification.
        t.setHdr(num_attributes,attrTypes, string_lengths);

        // Create a Metadatafile and store the data's metadata:
        // 1 int attribute
        // row 1 : attrTypes[i].AttrType
        // Our tuple structure is ready now. We need to read in num_attribute batches
        // from data_file_name, and initialize tuples and insert into heapfile.

        // Create a new heap file called data_file
        Heapfile file = new Heapfile(DB_DATA_HEAP_FILE_NAME);
        RID rid = new RID();
        String tuple_value;
        boolean end_of_file = false;

        // For each 100Dvector attribute in the input
        // init it's LSHF index with num_hashes and num_layers
        final LSHFIndex[] lshfIndices = new LSHFIndex[vectorFieldNumbers.size()];
        for(int i=0; i < vectorFieldNumbers.size(); i++)
            lshfIndices[i] = new LSHFIndex("",num_layers, bin_length, num_hashes,vectorFieldNumbers.get(i));

        while (true)
        {
            // Create tuple for input into heapfile.
            // Read in batches of size num_attributes.
            ArrayList<Vector100Dtype> vectorsInDataLine = new ArrayList<>();
            for (int i = 0; i < num_attributes; i++)
            {
                tuple_value = br.readLine();
                if (tuple_value == null)
                {
                    end_of_file = true;
                    break;
                }

                tuple_value = tuple_value.trim();
                AttrType type = attrTypes[i];
                switch (type.attrType)
                {
                    case AttrType.attrInteger:
                        t.setIntFld(i+1, Integer.parseInt(tuple_value));
                        break;

                    case AttrType.attrReal:
                        t.setFloFld(i+1,Float.parseFloat(tuple_value));
                        break;

                    case AttrType.attrString:
                        t.setStrFld(i+1, tuple_value);
                        break;

                    case AttrType.attrVector100D:
                        Vector100Dtype input_vector100D = new Vector100Dtype();
                        vectorsInDataLine.add(input_vector100D);

                        String[] tupleValueSplit = tuple_value.split("\\s+");
                        for (int j = 0; j < 100; j++)
                        {
                            input_vector100D.vector[j] = (short) Float.parseFloat(tupleValueSplit[j]);
                        }
                        t.set100DVectFld(i+1, input_vector100D);
                        break;

                    default:
                        throw new IOException("Unknown attribute type"+type.attrType);

                }
            }

            // If we reach end of file while reading mid tuple, we break.
            if (end_of_file) break;
            rid = file.insertRecord(t.getTupleByteArray());

            for(int i = 0; i < lshfIndices.length; i++)
                lshfIndices[i].insertRecord(vectorsInDataLine.get(i), rid);
        }

        JavabaseBM.flushAllPages();

        System.out.println("DB Creation Complete.");
        Pcounter.printPcounter();
        System.out.println("---Ignore beyond this---");

//        Without this sample run (It must be without using an index), running Query.java later can throw a Heapfile creation exception later
//
//        This is an intermittent exception that occurs only when the allotted buffers are low in Query.java. The exception causes an irregular
//        termination and since Query.java flushes all pages, future runs are never successful as the tempFile directory page gets corrupt. This
//        causes all future calls to Sort to fail
//
//        However running a no-index query successfully (Allot a high number of buffers) seems to not cause this Exception to arise in future
//        runs. I am not sure why the Exception is raised and why this suppresses it
        System.out.println("DB Creation Complete. Running sample query...");
        createTempQueryAndTargetFiles(vectorFieldNumbers.get(0));
//        Query.main(new String[]{database_name, TEMP_QUERY_FILE_NAME, "N", String.valueOf(DB_SIZE_IN_PAGES)});
        deleteTempQueryAndTargetFiles();
    }

    public static String getDbFileSystemPath(String dbName) {
        return "/tmp/"  + System.getProperty("user.name") + "."+ dbName + "-db";
    }

    private static void createTempQueryAndTargetFiles(int vectorFieldNum) throws Exception {
        FileWriter targetWriter = new FileWriter(TEMP_TARGET_FILE_NAME);
        targetWriter.write("100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100 100\n");
        targetWriter.close();

        FileWriter queryWriter = new FileWriter(TEMP_QUERY_FILE_NAME);
        queryWriter.write("NN(" + vectorFieldNum +","+ TEMP_TARGET_FILE_NAME +", 5,"+ vectorFieldNum +")");
        queryWriter.close();
    }

    private static void deleteTempQueryAndTargetFiles() throws Exception {
        Files.deleteIfExists(Paths.get(TEMP_TARGET_FILE_NAME));
        Files.deleteIfExists(Paths.get(TEMP_QUERY_FILE_NAME));
    }
}

