import global.SystemDefs;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import global.*;
import heap.*;
import LSHFIndex.*;

public class batchinsert implements GlobalConst
{
    public static void main(String[] args) throws IOException
    {
        if (args.length != 4)
        {
            System.err.println("Batch insert requires 4 arguments.");
            System.exit(1);
        }

        // get arguments
        int num_hashes = Integer.parseInt(args[0]);
        int num_layers = Integer.parseInt(args[1]);
        String data_file_name = args[2];
        String database_name = args[3];


        // create the database with database_name.
        String dbpath;
        String logpath;

        dbpath = "/tmp/"  + System.getProperty("user.name") + "."+ database_name + "-db";
        logpath = "/tmp/" + System.getProperty("user.name") + "." + database_name+ "-log";

        SystemDefs systemDefs = new SystemDefs(dbpath, NUMBUF,NUMBUF, "Clock");

        short string_attribute_count = 0;
        short vector_attribute_count = 0;
        LSHFIndex[] lshfIndices;

        // Create a Buffered reader to read the file: data_file_name
        try
        {
            BufferedReader br = new BufferedReader(new FileReader(data_file_name));

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

            // Create dummy tuple to convert data from data_file_name to tuples.
            Tuple t = new Tuple();

            // We may have to take this from user input.
            short max_string_length = 64;

            // For each string attribute, define max length of string attribute.
            short[] string_lengths = new short[string_attribute_count];
            for  (int i = 0; i < string_attribute_count; i++ )
            {
                string_lengths[i] = max_string_length;
            }

            // Set the tuple header based on the data_file_name specification.
            try
            {
                t.setHdr(num_attributes,attrTypes, string_lengths);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            // Our tuple structure is ready now. We need to read in num_attribute batches
            // from data_file_name, and initialize tuples and insert into heapfile.

            // Create a new heap file called data_file
            try
            {
                Heapfile file = new Heapfile(data_file_name);
                RID rid = new RID();
                String tuple_value;
                boolean end_of_file = false;
                while (true)
                {
                    // Create tuple for input into heapfile.
                    // Read in batches of size num_attributes.
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
                                try
                                {
                                    t.setIntFld(i, Integer.parseInt(tuple_value));
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }
                                break;

                            case AttrType.attrReal:
                                try
                                {
                                    t.setFloFld(i,Float.parseFloat(tuple_value));
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }
                                break;

                            case AttrType.attrString:
                                try
                                {
                                    t.setStrFld(i, tuple_value);
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }
                                break;

                            case AttrType.attrVector100D:
                                try
                                {
                                    Vector100Dtype input_vector100D = new Vector100Dtype();
                                    for (int j = 0; j < 100; j++)
                                    {
                                        input_vector100D.vector[j] = Short.parseShort(tuple_value);
                                    }
                                    t.set100DVectFld(i, input_vector100D);
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }
                                break;

                            default:
                                throw new IOException("Unknown attribute type"+type.attrType);

                        }
                    }

                    // If we reach end of file while reading mid tuple, we break.
                    if (end_of_file) break;
                    rid = file.insertRecord(t.getTupleByteArray());

                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

        }

        catch (Exception e)
        {
            e.printStackTrace();
        }

        // TO DO:
        // init the LSHF index with num_hashes and num_layers
        // for each of the 100Dvector attributes
        lshfIndices = new LSHFIndex[vector_attribute_count];
        for(int i = 0; i<vector_attribute_count; i++)
        {
            // Create an array of LSHF indexes
            // The constructor should initialize each of the random attributes
            // for the hash functions in the layer.
            // Then we store these random attributes for all the layers into a heapfile as tuples.
            // Need to figure out the details for this implementaiton in order to use the Index abstractions.
            lshfIndices[i] = new LSHFIndex(num_layers, num_hashes);
        }

    }
}

