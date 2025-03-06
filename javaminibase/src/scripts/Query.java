package scripts;

import java.io.FileReader;
import java.io.IOException;
import java.io.BufferedReader;
import java.util.Arrays;

import global.*;
public class Query
{
    public static void main(String[] args) throws IOException
    {
        if (args.length != 4)
        {
            System.err.println("query requires 4 arguments.");
            System.exit(1);
        }
        String db_name = args[0];
        String query_specification_file_name = args[1];
        String index_option =  args[2];
        int num_buffers = Integer.parseInt(args[3]);

        try
        {
            BufferedReader br = new BufferedReader(new FileReader(query_specification_file_name));
            String query_specification = br.readLine();
            if (query_specification == null)
            {
                System.err.println("query_specificaion is null.");
            }
            query_specification = query_specification.trim();
            
            if (query_specification.startsWith("Range("))
            {
                String specifications = query_specification.substring("Range(".length(), query_specification.length() - 1);
                String[] parameters =  specifications.split(",");
                if (parameters.length < 3)
                {
                    throw new IOException("query_specification requires at least 3 parameters.");
                }

                // Extract the parameters from the query specification.
                int vector_field_number = Integer.parseInt(parameters[0]);
                String target_vector_file_name = parameters[1];
                int distance = Integer.parseInt(parameters[2]);
                int[] output_fields = new int[parameters.length-3];
                for(int i = 3; i < parameters.length; i++)
                {
                    output_fields[i-3] = Integer.parseInt(parameters[i].trim());
                }

                // Extract the target vector from the target vector file
                Vector100Dtype target_vector = read_target_vector(target_vector_file_name);

                // Print the query details
                System.out.println("Range Query Parsed:");
                System.out.println("QA: " + vector_field_number + ", D: " + distance + ", target vector: " + Arrays.toString(target_vector.vector));
                System.out.println("Output fields: " + Arrays.toString(output_fields));

                if (index_option.equals("Y"))
                {
                    // TO DO:
                    // USE LSHFindex for processing.
                }
                else
                {
                    // TO DO:
                    // DO NOT USE LSHF index for processing.
                }

            }
            else if (query_specification.startsWith("NN("))
            {
                String specifications = query_specification.substring("NN(".length(), query_specification.length() - 1);
                String[] parameters =  specifications.split(",");
                if (parameters.length < 3)
                {
                    throw new IOException("query_specification requires at least 3 parameters.");
                }

                // Extract the parameters from the query specification.
                int vector_field_number = Integer.parseInt(parameters[0]);
                String target_vector_file_name = parameters[1];
                int number_of_nearest_neighbors = Integer.parseInt(parameters[2]);
                int[] output_fields = new int[parameters.length-3];
                for(int i = 3; i < parameters.length; i++)
                {
                    output_fields[i-3] = Integer.parseInt(parameters[i].trim());
                }

                // Extract the target vector from the target vector file
                Vector100Dtype target_vector = read_target_vector(target_vector_file_name);

                // Print the query details
                System.out.println("Range Query Parsed:");
                System.out.println("QA: " + vector_field_number + ", K: " + number_of_nearest_neighbors + ", target vector: " + Arrays.toString(target_vector.vector));
                System.out.println("Output fields: " + Arrays.toString(output_fields));

                if (index_option.equals("Y"))
                {
                    // TO DO:
                    // USE LSHFindex for processing.
                }
                else
                {
                    // TO DO:
                    // DO NOT USE LSHF index for processing.
                }
            }
            else
            {
                System.err.println("query_specificaion is not a valid query_specification.");
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static Vector100Dtype read_target_vector(String target_vector_file_name)
    {
        short[] vector = new short[100];
        Vector100Dtype target_vector;
        try (BufferedReader br = new BufferedReader(new FileReader(target_vector_file_name)))
        {
            String line = br.readLine();
            if (line == null)
            {
                throw new IOException("Target vector file " + target_vector_file_name + " is empty.");
            }

            String[] target_vector_strings = line.trim().split("\\s+");
            if (target_vector_strings.length != 100)
            {
                throw new IOException("Target vector file " + target_vector_file_name + " must contain exactly 100 integers");
            }
            for (int i = 0; i < 100; i++)
            {
                vector[i] = Short.parseShort(target_vector_strings[i]);
            }
            target_vector = new Vector100Dtype(vector);
            return target_vector;

        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }


    }
}
