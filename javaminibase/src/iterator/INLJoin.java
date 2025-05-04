package iterator;

import btree.*;
import global.AttrType;
import global.IndexType;
import heap.Heapfile;
import heap.Tuple;
import scripts.phaseThree.DbmsEntry;

import java.io.IOException;

public class INLJoin
{
    // 1 outer
    // 2 inner
    public AttrType[] in1;
    public int len_in1;
    short[] t1_str_sizes;
    int attribute_number_1;

    public AttrType[] in2;
    public int len_in2;
    short[] t2_str_sizes;
    int attribute_number_2;

    int amt_of_mem;
    FileScan outer_iterator;
    String relationName;
    IndexType index;
    String index_name;

    CondExpr[] outFilter;
    CondExpr[] rightFilter;
    FldSpec[] projection_list;

    int[] outputFieldNumbers1;
    int[] outputFieldNumbers2;
    int n_out_fields;

    public INLJoin(
            AttrType[] input1, int len_input_1, short[] t1_string_sizes, int attr_number1, AttrType[] input2,
            int len_input2, short[] t2_string_sizes, int attr_number2,
            int memory, FileScan outer_iter, String relationName, IndexType index_type, String idx_name,
            CondExpr[] output_filter,
            CondExpr[] right_filter, FldSpec[] proj_list, int[] outFld1, int[] outFld2, int number_out_fields) throws
            InvalidRelation,
            FileScanException,
            IOException,
            TupleUtilsException
    {
        in1 = input1;
        len_in1 = len_input_1;
        t1_str_sizes = t1_string_sizes;
        attribute_number_1 = attr_number1;

        in2 = input2;
        len_in2 = len_input2;
        t2_str_sizes = t2_string_sizes;
        attribute_number_2 = attr_number2;

        amt_of_mem = memory;

        relationName = relationName;
        index = index_type;
        index_name = idx_name;
        outer_iterator = outer_iter;
        outFilter = output_filter;
        rightFilter = right_filter;

        projection_list= proj_list;

        outputFieldNumbers1 = outFld1;
        outputFieldNumbers2 = outFld2;

        n_out_fields = number_out_fields;
    }

    public void join() throws Exception
    {
        // extract attribute from right relation using the given index
        AttrType attributeType1 = in1[attribute_number_1];
        AttrType attributeType2 = in2[attribute_number_2];


        Tuple outerTuple = outer_iterator.get_next();
        while (outerTuple != null)
        {

            KeyClass key;
            // Convert target value to its type and define the KeyClass
            if (attributeType1.attrType == AttrType.attrInteger)
            {
                int target_value = outerTuple.getIntFld(attribute_number_1);
                key = new IntegerKey(target_value);
            }
            else if (attributeType1.attrType == AttrType.attrString)
            {
                String target_value = outerTuple.getStrFld(attribute_number_1);
                key = new StringKey(target_value);
            }
            else if (attributeType1.attrType == AttrType.attrReal)
            {
                float target_value = outerTuple.getFloFld(attribute_number_1);
                key = new RealKey(target_value);
            }
            else
            {
                System.out.println("Unsupported field type: " + attributeType1.attrType);
                return;
            }

            if(DbmsEntry.indexExists(this.index_name, this.attribute_number_2))
            {
                BTreeFile innerIndexFile = new BTreeFile(index_name);
                BTFileScan btScan = innerIndexFile.new_scan(key, key);
                Heapfile innerFile = new Heapfile(relationName);

                try
                {
                    System.out.println("\n ---Output Tuples---");

                    KeyDataEntry entry = btScan.get_next();
                    while (entry != null)
                    {

                        Tuple innerTuple = innerFile.getRecord(((LeafData) entry.data).getData());
                        innerTuple.setHdr(
                                (short) in2.length,
                                in2,
                                t2_str_sizes);

                        TupleUtils.printFieldsFromTuple(outerTuple, in1, outputFieldNumbers1);
                        TupleUtils.printFieldsFromTuple(innerTuple, in2, outputFieldNumbers2);
                        System.out.println();
                        entry = btScan.get_next();

                    }
                }
                finally
                {
                    btScan.DestroyBTreeFileScan();
                    innerIndexFile.close();
                }
            }
            outerTuple = outer_iterator.get_next();
        }



    }
}