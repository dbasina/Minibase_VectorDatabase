package scripts;

import java.io.FileReader;
import java.io.IOException;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.IntStream;

import global.*;
import heap.Tuple;
import index.NNIndexScan;
import index.RSIndexScan;
import iterator.FldSpec;
import iterator.Iterator;
import iterator.RelSpec;
import iterator.TupleUtils;
import scripts.phaseThree.DbmsEntry;


public class Query
{
    public static AttrType[] attrTypes;
    public static short[] strLengths;
    private static int[] outputFieldNumbers;
    private static Iterator scan;
    public static int numBuffersForSort;

    public static Vector100Dtype read_target_vector(String target_vector_file_name) throws
                                                                                    Exception
    {
        short[] vector = new short[100];
        Vector100Dtype target_vector;
        BufferedReader br = new BufferedReader(new FileReader(target_vector_file_name));

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

    public static void queryHandler(String querySpecificationFileName, String numBuf, String relName, AttrType[] relAttrTypes) throws
                                                                                                                               Exception
    {
        String query_specification_file_name = querySpecificationFileName;

        int num_buffers = Integer.parseInt(numBuf);

//        Allocate 1/4th total buffers for sort
        numBuffersForSort = num_buffers / 4;

//        Set static fields for query processing
        attrTypes = relAttrTypes;
        int numAttributes = relAttrTypes.length;
        strLengths = TupleUtils.getStrFieldLengthsForConstantStrSizes(attrTypes);

        BufferedReader br = new BufferedReader(new FileReader(query_specification_file_name));
        String query_specification = br.readLine();
        if (query_specification == null)
        {
            System.out.println("query_specification is null.");
            return;
        }
        query_specification = query_specification.trim();

        if (query_specification.startsWith("Range("))
        {
            Optional<Iterator> scanOptional = validateAndPrepareRangeScan(query_specification, relName, attrTypes, num_buffers);
            if (scanOptional.isEmpty())
                return;
            scan = scanOptional.get();
        }
        else if (query_specification.startsWith("NN("))
        {
            Optional<Iterator> scanOptional = validateAndPrepareNNScan(query_specification, relName, attrTypes, num_buffers);
            if (scanOptional.isEmpty())
                return;
            scan = scanOptional.get();
        }
        else if (query_specification.startsWith("Sort("))
        {
            String specifications = query_specification.substring("Sort(".length(), query_specification.length() - 1);
            String[] parameters = specifications.split(",");

            // Extract the parameters from the query specification.
            int vector_field_number = Integer.parseInt(parameters[0].trim());
            String target_vector_file_name = parameters[1].trim();
            int distance = Integer.parseInt(parameters[2].trim());

            if (attrTypes[vector_field_number - 1].attrType != AttrType.attrVector100D)
            {
                System.out.println("Sort query is not possible on a non-100D vector column.");
                return;
            }

            // Identify output fields
            if (parameters[3].trim().equals("*"))
            {
                outputFieldNumbers = new int[attrTypes.length];
                IntStream.range(0, attrTypes.length).forEach(i -> outputFieldNumbers[i] = i + 1);
            }
            else
            {
                outputFieldNumbers = new int[parameters.length - 3];
                for (int i = 3; i < parameters.length; i++)
                {
                    outputFieldNumbers[i - 3] = Integer.parseInt(parameters[i].trim());
                }
            }

            // Extract the target vector from the target vector file
            Vector100Dtype target_vector = read_target_vector(target_vector_file_name);

            // Print the query details
            System.out.println("Sort Query Parsed:");
            System.out.println("QA: " + vector_field_number + ", D: " + distance + ", target vector: " + Arrays.toString(target_vector.vector));
            System.out.println("Output fields: " + Arrays.toString(outputFieldNumbers));

            // Define projList
            FldSpec[] projList = new FldSpec[attrTypes.length];
            IntStream.range(0, attrTypes.length).forEach(i -> projList[i] = new FldSpec(new RelSpec(RelSpec.outer), i+1));

            scan = new RSIndexScan(null, relName, relName, attrTypes, strLengths, numAttributes, numAttributes, projList, null, vector_field_number, target_vector, distance);
        }
        else
        {
            System.out.println("query_specification is not a valid query_specification.");
            return;
        }

        System.out.println("\n ---Output Tuples---");

//        Iterate over scan
        try
        {
            Tuple t = scan.get_next();
            while (t != null)
            {
                TupleUtils.printFieldsFromTuple(t, attrTypes, outputFieldNumbers);
                System.out.println();
                t = scan.get_next();
            }
        }
        finally
        {
            scan.close();
        }

        System.out.println("\n ---End Output---");
    }

    public static Optional<Iterator> validateAndPrepareNNScan(String query_specification, String relName, AttrType[] attrTypes, int numBuf) throws
                                                                                                                                                                                                Exception
    {
        String specifications = query_specification.substring("NN(".length(), query_specification.length() - 1);
        String[] parameters = specifications.split(",");
        numBuffersForSort = numBuf / 4;
        strLengths = TupleUtils.getStrFieldLengthsForConstantStrSizes(attrTypes);

        // Extract the parameters from the query specification.
        int vector_field_number = Integer.parseInt(parameters[0].trim());
        String target_vector_file_name = parameters[1].trim();
        int number_of_nearest_neighbors = Integer.parseInt(parameters[2].trim());
        String indexOption = parameters[3].trim();
        if (attrTypes[vector_field_number - 1].attrType != AttrType.attrVector100D)
        {
            System.out.println("NN query is not possible on a non-100D vector column.");
            return Optional.empty();
        }
        if (indexOption.equals("Y") && !DbmsEntry.checkIfIndexExistsInDbMetaDataFile(relName, vector_field_number))
        {
            System.out.println("Index option is Y but index does not exist for relation = " + relName + " on fieldNumber = " + vector_field_number + ". Pls create an index before using it");
            return Optional.empty();
        }

        // Define output fields
        if (parameters[4].trim().equals("*"))
        {
            outputFieldNumbers = new int[attrTypes.length];
            IntStream.range(0, attrTypes.length).forEach(i -> outputFieldNumbers[i] = i + 1);
        }
        else
        {
            outputFieldNumbers = new int[parameters.length - 4];
            for (int i = 4; i < parameters.length; i++)
            {
                outputFieldNumbers[i - 4] = Integer.parseInt(parameters[i].trim());
            }
        }

        int[] fieldNumbers = new int[attrTypes.length];
        IntStream.range(0, attrTypes.length).forEach(i -> fieldNumbers[i] = i + 1);


        // Extract the target vector from the target vector file
        Vector100Dtype target_vector = read_target_vector(target_vector_file_name);

        // Print the query details
        System.out.println("NN Query Parsed:");
        System.out.println("QA (vectorFieldNumber): " + vector_field_number + ", K: " + number_of_nearest_neighbors + ", target vector: " + Arrays.toString(target_vector.vector));
        System.out.println("Output fields: " + Arrays.toString(fieldNumbers));

        FldSpec[] projList = new FldSpec[fieldNumbers.length];
        IntStream.range(0, fieldNumbers.length).forEach(i -> projList[i] = new FldSpec(new RelSpec(RelSpec.outer), fieldNumbers[i]));

        return Optional.of(new NNIndexScan(
//                    Choose to use LSHFIndex or not
                indexOption.equals("Y") ? new IndexType(IndexType.Lsh) : null, relName, null, attrTypes, strLengths, attrTypes.length, fieldNumbers.length, projList, null, vector_field_number, target_vector, number_of_nearest_neighbors));
    }

    public static Optional<Iterator> validateAndPrepareRangeScan(String query_specification, String relName, AttrType[] attrTypes, int numBuf) throws
                                                                                                                                                                                                   Exception
    {
        String specifications = query_specification.substring("Range(".length(), query_specification.length() - 1);
        String[] parameters = specifications.split(",");
        numBuffersForSort = numBuf / 4;
        strLengths = TupleUtils.getStrFieldLengthsForConstantStrSizes(attrTypes);

        // Extract the parameters from the query specification.
        int vector_field_number = Integer.parseInt(parameters[0].trim());
        String target_vector_file_name = parameters[1].trim();
        int distance = Integer.parseInt(parameters[2].trim());
        String indexOption = parameters[3].trim();

        if (attrTypes[vector_field_number - 1].attrType != AttrType.attrVector100D)
        {
            System.out.println("Range query is not possible on a non-100D vector column.");
            return Optional.empty();
        }
        if (indexOption.equals("Y") && !DbmsEntry.checkIfIndexExistsInDbMetaDataFile(relName, vector_field_number))
        {
            System.out.println("Index option is Y but index does not exist for relation = " + relName + " on fieldNumber = " + vector_field_number + ". Pls create an index before using it");
            return Optional.empty();
        }

        // Define output fields
        if (parameters[4].trim().equals("*"))
        {
            outputFieldNumbers = new int[attrTypes.length];
            IntStream.range(0, attrTypes.length).forEach(i -> outputFieldNumbers[i] = i + 1);
        }
        else
        {
            outputFieldNumbers = new int[parameters.length - 4];
            for (int i = 4; i < parameters.length; i++)
            {
                outputFieldNumbers[i - 4] = Integer.parseInt(parameters[i].trim());
            }
        }

        int[] fieldNumbers = new int[attrTypes.length];
        IntStream.range(0, attrTypes.length).forEach(i -> fieldNumbers[i] = i + 1);


        // Extract the target vector from the target vector file
        Vector100Dtype target_vector = read_target_vector(target_vector_file_name);

        // Print the query details
        System.out.println("Range Query Parsed:");
        System.out.println("QA: " + vector_field_number + ", D: " + distance + ", target vector: " + Arrays.toString(target_vector.vector));
        System.out.println("Output fields: " + Arrays.toString(fieldNumbers));

        FldSpec[] projList = new FldSpec[fieldNumbers.length];
        IntStream.range(0, fieldNumbers.length).forEach(i -> projList[i] = new FldSpec(new RelSpec(RelSpec.outer), fieldNumbers[i]));

        return Optional.of(new RSIndexScan(
//                    Choose to use LSHFIndex or not
                indexOption.equals("Y") ? new IndexType(IndexType.Lsh) : null, relName, null, attrTypes, strLengths, attrTypes.length, fieldNumbers.length, projList, null, vector_field_number, target_vector, distance));
    }

}
