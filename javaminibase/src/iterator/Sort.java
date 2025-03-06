package iterator;

import java.io.*;

import global.*;
import heap.*;

/**
 * The Sort class sorts a file. All necessary information are passed as
 * arguments to the constructor. After the constructor call, the user can
 * repeatly call <code>get_next()</code> to get tuples in sorted order.
 * After the sorting is done, the user should call <code>close()</code>
 * to clean up.
 */
public class Sort extends Iterator implements GlobalConst
{
    private static final int ARBIT_RUNS = 10;

    private AttrType[] _in;
    private short n_cols;
    private short[] str_lens;
    private Iterator _am;
    private int _sort_fld;
    private TupleOrder order;
    private int _n_pages;
    private byte[][] bufs;
    private boolean first_time;
    private int Nruns;
    private int max_elems_in_heap;
    private int sortFldLen;
    private int tuple_size;

    private pnodeSplayPQ Q;
    private Heapfile[] temp_files;
    private int n_tempfiles;
    private Tuple output_tuple;
    private int[] n_tuples;
    private int n_runs;
    private Tuple op_buf;
    private OBuf o_buf;
    private SpoofIbuf[] i_buf;
    private PageId[] bufs_pids;
    private boolean useBM = true; // flag for whether to use buffer manager

    // fields to handle vector100d sorts
    public Vector100Dtype Target;
    public int k;

    /**
     * Set up for merging the runs.
     * Open an input buffer for each run, and insert the first element (min)
     * from each run into a heap. <code>delete_min() </code> will then get
     * the minimum of all runs.
     *
     * @param tuple_size size (in bytes) of each tuple
     * @param n_R_runs   number of runs
     * @throws IOException     from lower layers
     * @throws LowMemException there is not enough memory to
     *                         sort in two passes (a subclass of SortException).
     * @throws SortException   something went wrong in the lower layer.
     * @throws Exception       other exceptions
     */
    private void setup_for_merge(int tuple_size, int n_R_runs)
            throws IOException,
                   LowMemException,
                   SortException,
                   Exception
    {
        // don't know what will happen if n_R_runs > _n_pages
        if (n_R_runs > _n_pages)
            throw new LowMemException("Sort.java: Not enough memory to sort in two passes.");

        int i;
        pnode cur_node;  // need pq_defs.java

        i_buf = new SpoofIbuf[n_R_runs];   // need io_bufs.java
        for (int j = 0; j < n_R_runs; j++) i_buf[j] = new SpoofIbuf();

        // construct the lists, ignore TEST for now
        // this is a patch, I am not sure whether it works well -- bingjie 4/20/98

        for (i = 0; i < n_R_runs; i++)
        {
            byte[][] apage = new byte[1][];
            apage[0] = bufs[i];

            // need iobufs.java
            i_buf[i].init(temp_files[i], apage, 1, tuple_size, n_tuples[i]);

            cur_node = new pnode();
            cur_node.run_num = i;

            // may need change depending on whether Get() returns the original
            // or make a copy of the tuple, need io_bufs.java ???
            Tuple temp_tuple = new Tuple(tuple_size);

            try
            {
                temp_tuple.setHdr(n_cols, _in, str_lens);
            }
            catch (Exception e)
            {
                throw new SortException(e, "Sort.java: Tuple.setHdr() failed");
            }

            temp_tuple = i_buf[i].Get(temp_tuple);  // need io_bufs.java

            if (temp_tuple != null)
            {
	/*
	System.out.print("Get tuple from run " + i);
	temp_tuple.print(_in);
	*/
                cur_node.tuple = temp_tuple; // no copy needed
                try
                {
                    Q.enq(cur_node);
                }
                catch (UnknowAttrType e)
                {
                    throw new SortException(e, "Sort.java: UnknowAttrType caught from Q.enq()");
                }
                catch (TupleUtilsException e)
                {
                    throw new SortException(e, "Sort.java: TupleUtilsException caught from Q.enq()");
                }

            }
        }
        return;
    }

    /**
     * Generate sorted runs.
     * Using heap sort.
     *
     * @param max_elems   maximum number of elements in heap
     * @param sortFldType attribute type of the sort field
     * @param sortFldLen  length of the sort field
     * @return number of runs generated
     * @throws IOException    from lower layers
     * @throws SortException  something went wrong in the lower layer.
     * @throws JoinsException from <code>Iterator.get_next()</code>
     */
    private int generate_runs(int max_elems, AttrType sortFldType, int sortFldLen)
            throws IOException,
                   SortException,
                   UnknowAttrType,
                   TupleUtilsException,
                   JoinsException,
                   Exception
    {
        Tuple tuple;
        pnode cur_node;

        // lastElem and target_tuple manage how the file system based sorting works.
        Tuple lastElem = new Tuple(tuple_size);
        Tuple target_tuple = new Tuple(tuple_size);

        // Set tuple headers.
        try
        {
            // if we're dealing with 100Dvectors, then setup lastElem value
            // This helps when doing the writes to files in the latter half of this code.
            lastElem.setHdr(n_cols, _in, str_lens);
            target_tuple.setHdr(n_cols, _in, str_lens);
        }
        catch (Exception e)
        {
            throw new SortException(e, "Sort.java: setHdr() failed");
        }

        // Set tuple values
        try
        {

            MIN_VAL(target_tuple, sortFldType);
        }
        catch (UnknowAttrType e)
        {
            throw new SortException(e, "Sort.java: UnknowAttrType caught from MIN_VAL()");
        }
        catch (Exception e)
        {
            throw new SortException(e, "MIN_VAL failed");
        }


        // target 100dvector's pnode. To be able to reuse sort for 100dvectors.
        // we assign target_tuple to this pnode's tuple.
        // pnode attributes:
        //      -run number
        //      -tuple

        pnode target_node = new pnode();
        target_node.tuple = target_tuple;

        // Initialize 2 splay priority queues.
        // pnodeSplayPQ has 2 attributes:
        //                  pnodeSplaynode root
        //                  pnodeSplaynode target (this is the 100dvector target node)

        pnodeSplayPQ pcurr_Q = null;
        pnodeSplayPQ pother_Q = null;

        // 2 constructors.
        // one for regular datatypes. another for 100dvector datatypes.
        if (sortFldType.attrType != AttrType.attrVector100D)
        {
            pnodeSplayPQ Q1 = new pnodeSplayPQ(_sort_fld, sortFldType, order);
            pnodeSplayPQ Q2 = new pnodeSplayPQ(_sort_fld, sortFldType, order);
            pcurr_Q = Q1;
            pother_Q = Q2;
        }
        else
        {
            pnodeSplayPQ Q1 = new pnodeSplayPQ(_sort_fld, sortFldType, order, target_node);
            pnodeSplayPQ Q2 = new pnodeSplayPQ(_sort_fld, sortFldType, order, target_node);
            pcurr_Q = Q1;
            pother_Q = Q2;
        }

        // this is the current run number tracker.
        int run_num = 0;

        // number of elements in both queues.
        int p_elems_curr_Q = 0;
        int p_elems_other_Q = 0;

        // comparision result.
        int comp_res;

        if (order.tupleOrder == TupleOrder.Ascending)
        {
            try
            {
                MIN_VAL(lastElem, sortFldType);
            }
            catch (UnknowAttrType e)
            {
                throw new SortException(e, "Sort.java: UnknowAttrType caught from MIN_VAL()");
            }
            catch (Exception e)
            {
                throw new SortException(e, "MIN_VAL failed");
            }
        }
        else
        {
            // if in vector sort, we come here, then we cooked.
            try
            {
                MAX_VAL(lastElem, sortFldType);
            }
            catch (UnknowAttrType e)
            {
                throw new SortException(e, "Sort.java: UnknowAttrType caught from MAX_VAL()");
            }
            catch (Exception e)
            {
                throw new SortException(e, "MIN_VAL failed");
            }
        }

        // maintain a fixed maximum number of elements in the heap
        // Load up current queue.
        while ((p_elems_curr_Q + p_elems_other_Q) < max_elems)
        {
            // _am is a filescan object.
            // filescan extends iterator.
            // we read heapfiles with input data using _am.
            try
            {
                tuple = _am.get_next();
            }
            catch (Exception e)
            {
                e.printStackTrace();
                throw new SortException(e, "Sort.java: get_next() failed");
            }

            // if we get empty value, we're done reading input.
            if (tuple == null)
            {
                break;
            }

            // If we get the next element and its not null.
            // Create a node and assign the current tuple from input to it.
            cur_node = new pnode();
            cur_node.tuple = new Tuple(tuple);

            // Enqueue current node. This probably needs to be modded.
            pcurr_Q.enq(cur_node);
            p_elems_curr_Q++;
        }

        // now the queue is full, starting writing to file while keep trying
        // to add new tuples to the queue. The ones that does not fit are put
        // on the other queue temperarily

        // unload current queue and see where the elements go.
        while (true)
        {
            // Get a node from current queue pcurr_Q
            cur_node = pcurr_Q.deq();
            if (cur_node == null) break;
            p_elems_curr_Q--;

            // compare cur_node.tuple to lastElem (smallest if asc or largest if desc possible value).
            if(sortFldType.attrType == AttrType.attrVector100D) {
                int currNodeDistance = TupleUtils.CompareTupleWithTuple(sortFldType, cur_node.tuple, _sort_fld, target_tuple, _sort_fld);
                int lastElemDistance = TupleUtils.CompareTupleWithTuple(sortFldType, lastElem, _sort_fld, target_tuple, _sort_fld);
                if (currNodeDistance < lastElemDistance)
                    comp_res = -1;
                else
                    comp_res = 1;
            } else {
                comp_res = TupleUtils.CompareTupleWithValue(sortFldType, cur_node.tuple, _sort_fld, lastElem);
            }

            // for regular values
            // comp_res < 0 implies cur_node.tuple < lastElem
            // comp_res > 0 implies cur_node.tuple > lastElem

            // for 100DVectors
            // (comp_res > 0 && Ascending) order always, since comp_res = distance(target_tuple,cur_node.tuple)
            // TupleOrder is always going to be Ascending here for 100DVectors.
            // therefore if statement never gets executed for 100Dvectors
            if ((comp_res < 0 && order.tupleOrder == TupleOrder.Ascending) || (comp_res > 0 && order.tupleOrder == TupleOrder.Descending))
            {
                // doesn't fit in current run, put into the other queue
                try
                {
                    pother_Q.enq(cur_node);
                }
                catch (UnknowAttrType e)
                {
                    throw new SortException(e, "Sort.java: UnknowAttrType caught from Q.enq()");
                }
                p_elems_other_Q++;
            }
            else
            {
                // set lastElem to have the value of the current tuple,
                // This line needs to be studied carefully on how it affects 100dvector enqueue.
                // I doubt this matters because when we define the splay priority queues.
                // We created a new pnode target and new constructors for the Splay priority queues.
                // We store the target vector in the trees itself.
                // So no need to worry about updating lastElem here and wether it affects the vector compare functionality.
                // It is still needed to implement the sort for other datatypes, because comparision happens between 2 operands.
                // Where as for vector comparision, we have 3 operands. The 2 we are comparing and the target.
                // Since target participates in all comparisions of an SplayTree, its better to have it as an attribute of the tree

                TupleUtils.SetValue(lastElem, cur_node.tuple, _sort_fld, sortFldType);

                // write tuple to output file for the current run.
                o_buf.Put(cur_node.tuple);
            }

            // IF other queue full, SETUP Swap.
            // if other queue is full, setup necessary heapfile, output buffer and tuple tracker
            // swap the queues and make this the current queue.
            if (p_elems_other_Q == max_elems)
            {
                // close current run and start next run
                // keep track of number of tuples in each run.
                n_tuples[run_num] = (int) o_buf.flush();  // need io_bufs.java
                run_num++;

                // If run_num reached max number of runs allocated,
                // double the number of runs. Allocate the respective heapfiles.
                // each run has its own heapfile.
                if (run_num == n_tempfiles)
                {
                    // create new heapfile array with double the number of heapfiles.
                    Heapfile[] temp1 = new Heapfile[2 * n_tempfiles];

                    // Copy existing heapfiles into the new array.
                    for (int i = 0; i < n_tempfiles; i++)
                    {
                        temp1[i] = temp_files[i];
                    }

                    // update the current heapfile array to the new heapfile array
                    // update the count for number of heapfiles available.
                    temp_files = temp1;
                    n_tempfiles *= 2;

                    // Each heapfile has an associated count of number of tuples inside it.
                    // update the tuple count tracker.
                    // double its size to allocate new number of heapfiles.
                    // copy existing tuple counts for each heapfile into it.
                    int[] temp2 = new int[2 * n_runs];
                    for (int j = 0; j < n_runs; j++)
                    {
                        temp2[j] = n_tuples[j];
                    }
                    n_tuples = temp2;
                    n_runs *= 2;
                }

                // create new heapfile
                try
                {
                    temp_files[run_num] = new Heapfile(null);
                }
                catch (Exception e)
                {
                    throw new SortException(e, "Sort.java: create Heapfile failed");
                }

                // create an output buffer for the new heapfile.
                o_buf.init(bufs, _n_pages, tuple_size, temp_files[run_num], false);

                // set the last Elem to be the minimum value for the sort field
                if (order.tupleOrder == TupleOrder.Ascending)
                {
                    try
                    {
                        MIN_VAL(lastElem, sortFldType);
                    }
                    catch (UnknowAttrType e)
                    {
                        throw new SortException(e, "Sort.java: UnknowAttrType caught from MIN_VAL()");
                    }
                    catch (Exception e)
                    {
                        throw new SortException(e, "MIN_VAL failed");
                    }
                }
                else
                {
                    try
                    {
                        MAX_VAL(lastElem, sortFldType);
                    }
                    catch (UnknowAttrType e)
                    {
                        throw new SortException(e, "Sort.java: UnknowAttrType caught from MAX_VAL()");
                    }
                    catch (Exception e)
                    {
                        throw new SortException(e, "MIN_VAL failed");
                    }
                }

                // switch the current heap and the other heap
                pnodeSplayPQ tempQ = pcurr_Q;
                pcurr_Q = pother_Q;
                pother_Q = tempQ;
                int tempelems = p_elems_curr_Q;
                p_elems_curr_Q = p_elems_other_Q;
                p_elems_other_Q = tempelems;
            }

            // now check whether the current queue is empty
            // for 100dvectors, we unload the current queue above and load it again here.
            else if (p_elems_curr_Q == 0)
            {
                while ((p_elems_curr_Q + p_elems_other_Q) < max_elems)
                {
                    try
                    {
                        tuple = _am.get_next();  // according to Iterator.java
                    }
                    catch (Exception e)
                    {
                        throw new SortException(e, "get_next() failed");
                    }

                    if (tuple == null)
                    {
                        break;
                    }
                    cur_node = new pnode();
                    cur_node.tuple = new Tuple(tuple);

                    try
                    {
                        pcurr_Q.enq(cur_node);
                    }
                    catch (UnknowAttrType e)
                    {
                        throw new SortException(e, "Sort.java: UnknowAttrType caught from Q.enq()");
                    }
                    p_elems_curr_Q++;
                }
            }

            // Check if we are done
            // For 100D vector case
            // this shouldn't be 0 unless we done.
            if (p_elems_curr_Q == 0)
            {
                // current queue empty despite our attemps to fill in
                // indicating no more tuples from input
                if (p_elems_other_Q == 0)
                {
                    // other queue is also empty, no more tuples to write out, done
                    break; // of the while(true) loop
                }
                else
                {
                    // generate one more run for all tuples in the other queue
                    // close current run and start next run
                    n_tuples[run_num] = (int) o_buf.flush();  // need io_bufs.java
                    run_num++;

                    // check to see whether need to expand the array
                    if (run_num == n_tempfiles)
                    {
                        Heapfile[] temp1 = new Heapfile[2 * n_tempfiles];
                        for (int i = 0; i < n_tempfiles; i++)
                        {
                            temp1[i] = temp_files[i];
                        }
                        temp_files = temp1;
                        n_tempfiles *= 2;

                        int[] temp2 = new int[2 * n_runs];
                        for (int j = 0; j < n_runs; j++)
                        {
                            temp2[j] = n_tuples[j];
                        }
                        n_tuples = temp2;
                        n_runs *= 2;
                    }

                    try
                    {
                        temp_files[run_num] = new Heapfile(null);
                    }
                    catch (Exception e)
                    {
                        throw new SortException(e, "Sort.java: create Heapfile failed");
                    }

                    // need io_bufs.java
                    o_buf.init(bufs, _n_pages, tuple_size, temp_files[run_num], false);

                    // set the last Elem to be the minimum value for the sort field
                    if (order.tupleOrder == TupleOrder.Ascending)
                    {
                        try
                        {
                            MIN_VAL(lastElem, sortFldType);
                        }
                        catch (UnknowAttrType e)
                        {
                            throw new SortException(e, "Sort.java: UnknowAttrType caught from MIN_VAL()");
                        }
                        catch (Exception e)
                        {
                            throw new SortException(e, "MIN_VAL failed");
                        }
                    }
                    else
                    {
                        try
                        {
                            MAX_VAL(lastElem, sortFldType);
                        }
                        catch (UnknowAttrType e)
                        {
                            throw new SortException(e, "Sort.java: UnknowAttrType caught from MAX_VAL()");
                        }
                        catch (Exception e)
                        {
                            throw new SortException(e, "MIN_VAL failed");
                        }
                    }

                    // switch the current heap and the other heap
                    pnodeSplayPQ tempQ = pcurr_Q;
                    pcurr_Q = pother_Q;
                    pother_Q = tempQ;
                    int tempelems = p_elems_curr_Q;
                    p_elems_curr_Q = p_elems_other_Q;
                    p_elems_other_Q = tempelems;
                }
            } // end of if (p_elems_curr_Q == 0)
        } // end of while (true)

        // close the last run
        n_tuples[run_num] = (int) o_buf.flush();
        run_num++;
        return run_num;
    }

    /**
     * Remove the minimum value among all the runs.
     *
     * @return the minimum tuple removed
     * @throws IOException   from lower layers
     * @throws SortException something went wrong in the lower layer.
     */
    private Tuple delete_min()
            throws IOException,
                   SortException,
                   Exception
    {
        pnode cur_node;                // needs pq_defs.java
        Tuple new_tuple, old_tuple;

        cur_node = Q.deq();
        old_tuple = cur_node.tuple;
    /*
    System.out.print("Get ");
    old_tuple.print(_in);
    */
        // we just removed one tuple from one run, now we need to put another
        // tuple of the same run into the queue
        if (i_buf[cur_node.run_num].empty() != true)
        {
            // run not exhausted
            new_tuple = new Tuple(tuple_size); // need tuple.java??

            try
            {
                new_tuple.setHdr(n_cols, _in, str_lens);
            }
            catch (Exception e)
            {
                throw new SortException(e, "Sort.java: setHdr() failed");
            }

            new_tuple = i_buf[cur_node.run_num].Get(new_tuple);
            if (new_tuple != null)
            {
	/*
	System.out.print(" fill in from run " + cur_node.run_num);
	new_tuple.print(_in);
	*/
                cur_node.tuple = new_tuple;  // no copy needed -- I think Bingjie 4/22/98
                try
                {
                    Q.enq(cur_node);
                }
                catch (UnknowAttrType e)
                {
                    throw new SortException(e, "Sort.java: UnknowAttrType caught from Q.enq()");
                }
                catch (TupleUtilsException e)
                {
                    throw new SortException(e, "Sort.java: TupleUtilsException caught from Q.enq()");
                }
            }
            else
            {
                throw new SortException("********** Wait a minute, I thought input is not empty ***************");
            }

        }

        // changed to return Tuple instead of return char array ????
        return old_tuple;
    }

    /**
     * Set lastElem to be the minimum value of the appropriate type
     *
     * @param lastElem    the tuple
     * @param sortFldType the sort field type
     * @throws IOException    from lower layers
     * @throws UnknowAttrType attrSymbol or attrNull encountered
     */
    private void MIN_VAL(Tuple lastElem, AttrType sortFldType)
            throws IOException,
                   FieldNumberOutOfBoundException,
                   UnknowAttrType
    {

        //    short[] s_size = new short[Tuple.max_size]; // need Tuple.java
        //    AttrType[] junk = new AttrType[1];
        //    junk[0] = new AttrType(sortFldType.attrType);
        char[] c = new char[1];
        c[0] = Character.MIN_VALUE;
        String s = new String(c);
        //    short fld_no = 1;

        switch (sortFldType.attrType)
        {
            case AttrType.attrInteger:
                //      lastElem.setHdr(fld_no, junk, null);
                lastElem.setIntFld(_sort_fld, Integer.MIN_VALUE);
                break;
            case AttrType.attrReal:
                //      lastElem.setHdr(fld-no, junk, null);
                lastElem.setFloFld(_sort_fld, Float.MIN_VALUE);
                break;
            case AttrType.attrString:
                //      lastElem.setHdr(fld_no, junk, s_size);
                lastElem.setStrFld(_sort_fld, s);
                break;
            case AttrType.attrVector100D:
                lastElem.set100DVectFld(_sort_fld, Target);
                break;
            default:
                // don't know how to handle attrSymbol, attrNull
                //System.err.println("error in sort.java");
                throw new UnknowAttrType("Sort.java: don't know how to handle attrSymbol, attrNull");
        }

        return;
    }

    /**
     * Set lastElem to be the maximum value of the appropriate type
     *
     * @param lastElem    the tuple
     * @param sortFldType the sort field type
     * @throws IOException    from lower layers
     * @throws UnknowAttrType attrSymbol or attrNull encountered
     */
    private void MAX_VAL(Tuple lastElem, AttrType sortFldType)
            throws IOException,
                   FieldNumberOutOfBoundException,
                   UnknowAttrType
    {

        //    short[] s_size = new short[Tuple.max_size]; // need Tuple.java
        //    AttrType[] junk = new AttrType[1];
        //    junk[0] = new AttrType(sortFldType.attrType);
        char[] c = new char[1];
        c[0] = Character.MAX_VALUE;
        String s = new String(c);
        //    short fld_no = 1;

        switch (sortFldType.attrType)
        {
            case AttrType.attrInteger:
                //      lastElem.setHdr(fld_no, junk, null);
                lastElem.setIntFld(_sort_fld, Integer.MAX_VALUE);
                break;
            case AttrType.attrReal:
                //      lastElem.setHdr(fld_no, junk, null);
                lastElem.setFloFld(_sort_fld, Float.MAX_VALUE);
                break;
            case AttrType.attrString:
                //      lastElem.setHdr(fld_no, junk, s_size);
                lastElem.setStrFld(_sort_fld, s);
                break;
            default:
                // don't know how to handle attrSymbol, attrNull
                //System.err.println("error in sort.java");
                throw new UnknowAttrType("Sort.java: don't know how to handle attrSymbol, attrNull");
        }

        return;
    }

    /**
     * Class constructor, take information about the tuples, and set up
     * the sorting
     *
     * @param in             array containing attribute types of the relation
     * @param len_in         number of columns in the relation
     * @param str_sizes      array of sizes of string attributes
     * @param am             an iterator for accessing the tuples
     * @param sort_fld       the field number of the field to sort on
     * @param sort_order     the sorting order (ASCENDING, DESCENDING)
     * @param sort_field_len the length of the sort field
     * @param n_pages        amount of memory (in pages) available for sorting
     * @param Target         Vector100Dtype that we want to sort all the tuples against by closest distance
     * @param k_nearest      the number of outputs we need to maintain and return after sorting. If k = 0 we sort all tuples wrt dist from target.
     * @throws IOException   from lower layers
     * @throws SortException something went wrong in the lower layer.
     */
    public Sort(AttrType[] in,
                short len_in,
                short[] str_sizes,
                Iterator am,
                int sort_fld,
                TupleOrder sort_order,
                int sort_fld_len,
                int n_pages,
                Vector100Dtype target_vector,
                int k_nearest
    ) throws IOException, SortException, InvalidTupleSizeException, InvalidTypeException, FieldNumberOutOfBoundException {
        _in = new AttrType[len_in];
        n_cols = len_in;
        int n_strs = 0;

        // Create a copy of attribute types list.
        // Count number of string attribute types.
        for (int i = 0; i < len_in; i++)
        {
            _in[i] = new AttrType(in[i].attrType);
            if (in[i].attrType == AttrType.attrString)
            {
                n_strs++;
            }
        }

        // copy string lengths from str_sizes to str_lens array.
        // recount number of strings.
        str_lens = new short[n_strs];

        n_strs = 0;
        for (int i = 0; i < len_in; i++)
        {
            if (_in[i].attrType == AttrType.attrString)
            {
                str_lens[n_strs] = str_sizes[n_strs];
                n_strs++;
            }
        }

        // Dummy tuple.
        Tuple t = new Tuple(); // need Tuple.java
        try
        {
            t.setHdr(len_in, _in, str_sizes);
        }
        catch (Exception e)
        {
            throw new SortException(e, "Sort.java: t.setHdr() failed");
        }
        tuple_size = t.size();

        // copy of iterator, copy of field to sort on
        // copy of sort_order and copy of number of buffer pages allowed.
        _am = am;
        _sort_fld = sort_fld;
        order = sort_order;
        _n_pages = n_pages;

        // ignore* :this may need change, bufs ???  need io_bufs.java
        // ignore* :bufs = get_buffer_pages(_n_pages, bufs_pids, bufs);

        // Create a PageId array for buffer page ids
        // create a 2-d byte array for [-pages][page size here maybe]
        // get n_pages worth of buffer pages to work with.
        bufs_pids = new PageId[_n_pages];
        bufs = new byte[_n_pages][];

        if (useBM)
        {
            try
            {
                get_buffer_pages(_n_pages, bufs_pids, bufs);
            }
            catch (Exception e)
            {
                throw new SortException(e, "Sort.java: BUFmgr error");
            }
        }
        else
        {
            for (int j = 0; j < _n_pages; j++) bufs[j] = new byte[MAX_SPACE];
        }

        first_time = true;

        // as a heuristic, we set the number of runs to an arbitrary value
        // of ARBIT_RUNS

        temp_files = new Heapfile[ARBIT_RUNS];
        n_tempfiles = ARBIT_RUNS;
        n_tuples = new int[ARBIT_RUNS];
        n_runs = ARBIT_RUNS;

        try
        {
            temp_files[0] = new Heapfile(null);
        }
        catch (Exception e)
        {
            throw new SortException(e, "Sort.java: Heapfile error");
        }

        // This is the abstraction we use to load up the buffer pages
        // We use OBuf once full to flush to heap file.
        // Obuf arguments
        // bufs - actual buffer with pages that we allocated above.
        // _n_pages - number of buffer pages
        // tuple_size - self explanatory..
        // temp_files[0] - the heap file to flush the output buffer to once full.

        o_buf = new OBuf();
        o_buf.init(bufs, _n_pages, tuple_size, temp_files[0], false);
        // output_tuple = null;

        max_elems_in_heap = 200;
        sortFldLen = sort_fld_len;

        // We use Splay trees as priority queues.
        // Self organizing trees on insert.
        // input - sort_fied number, field attribute type, sort field order.

        Tuple target_tuple = new Tuple(tuple_size);
        target_tuple.setHdr(n_cols, _in, str_lens);
        target_tuple.set100DVectFld(_sort_fld, target_vector);
        pnode target_node = new pnode();
        target_node.tuple = target_tuple;
        Q = new pnodeSplayPQ(sort_fld, in[sort_fld - 1], order, target_node);

        // setup buffer tuple.
        op_buf = new Tuple(tuple_size);   // need Tuple.java
        try
        {
            op_buf.setHdr(n_cols, _in, str_lens);
        }
        catch (Exception e)
        {
            throw new SortException(e, "Sort.java: op_buf.setHdr() failed");
        }

        Target = target_vector;
        k = k_nearest;
    }

    /**
     * Class constructor, take information about the tuples, and set up
     * the sorting
     *
     * @param in             array containing attribute types of the relation
     * @param len_in         number of columns in the relation
     * @param str_sizes      array of sizes of string attributes
     * @param am             an iterator for accessing the tuples
     * @param sort_fld       the field number of the field to sort on
     * @param sort_order     the sorting order (ASCENDING, DESCENDING)
     * @param sort_field_len the length of the sort field
     * @param n_pages        amount of memory (in pages) available for sorting
     * @throws IOException   from lower layers
     * @throws SortException something went wrong in the lower layer.
     */
    public Sort(AttrType[] in,
                short len_in,
                short[] str_sizes,
                Iterator am,
                int sort_fld,
                TupleOrder sort_order,
                int sort_fld_len,
                int n_pages
    ) throws IOException, SortException
    {
        _in = new AttrType[len_in];
        n_cols = len_in;
        int n_strs = 0;

        for (int i = 0; i < len_in; i++)
        {
            _in[i] = new AttrType(in[i].attrType);
            if (in[i].attrType == AttrType.attrString)
            {
                n_strs++;
            }
        }

        str_lens = new short[n_strs];

        n_strs = 0;
        for (int i = 0; i < len_in; i++)
        {
            if (_in[i].attrType == AttrType.attrString)
            {
                str_lens[n_strs] = str_sizes[n_strs];
                n_strs++;
            }
        }

        Tuple t = new Tuple(); // need Tuple.java
        try
        {
            t.setHdr(len_in, _in, str_sizes);
        }
        catch (Exception e)
        {
            throw new SortException(e, "Sort.java: t.setHdr() failed");
        }
        tuple_size = t.size();

        _am = am;
        _sort_fld = sort_fld;
        order = sort_order;
        _n_pages = n_pages;

        // this may need change, bufs ???  need io_bufs.java
        //    bufs = get_buffer_pages(_n_pages, bufs_pids, bufs);
        bufs_pids = new PageId[_n_pages];
        bufs = new byte[_n_pages][];

        if (useBM)
        {
            try
            {
                get_buffer_pages(_n_pages, bufs_pids, bufs);
            }
            catch (Exception e)
            {
                throw new SortException(e, "Sort.java: BUFmgr error");
            }
        }
        else
        {
            for (int k = 0; k < _n_pages; k++) bufs[k] = new byte[MAX_SPACE];
        }

        first_time = true;

        // as a heuristic, we set the number of runs to an arbitrary value
        // of ARBIT_RUNS
        temp_files = new Heapfile[ARBIT_RUNS];
        n_tempfiles = ARBIT_RUNS;
        n_tuples = new int[ARBIT_RUNS];
        n_runs = ARBIT_RUNS;

        try
        {
            temp_files[0] = new Heapfile(null);
        }
        catch (Exception e)
        {
            throw new SortException(e, "Sort.java: Heapfile error");
        }

        o_buf = new OBuf();

        o_buf.init(bufs, _n_pages, tuple_size, temp_files[0], false);
        //    output_tuple = null;

        max_elems_in_heap = 200;
        sortFldLen = sort_fld_len;

        Q = new pnodeSplayPQ(sort_fld, in[sort_fld - 1], order);

        op_buf = new Tuple(tuple_size);   // need Tuple.java
        try
        {
            op_buf.setHdr(n_cols, _in, str_lens);
        }
        catch (Exception e)
        {
            throw new SortException(e, "Sort.java: op_buf.setHdr() failed");
        }
    }

    /**
     * Returns the next tuple in sorted order.
     * Note: You need to copy out the content of the tuple, otherwise it
     * will be overwritten by the next <code>get_next()</code> call.
     *
     * @return the next tuple, null if all tuples exhausted
     * @throws IOException     from lower layers
     * @throws SortException   something went wrong in the lower layer.
     * @throws JoinsException  from <code>generate_runs()</code>.
     * @throws UnknowAttrType  attribute type unknown
     * @throws LowMemException memory low exception
     * @throws Exception       other exceptions
     */
    public Tuple get_next()
            throws IOException,
                   SortException,
                   UnknowAttrType,
                   LowMemException,
                   JoinsException,
                   Exception
    {
        if (first_time)
        {
            // first get_next call to the sort routine
            first_time = false;

            // generate runs
            Nruns = generate_runs(max_elems_in_heap, _in[_sort_fld - 1], sortFldLen);
            //      System.out.println("Generated " + Nruns + " runs");

            // setup state to perform merge of runs.
            // Open input buffers for all the input file
            setup_for_merge(tuple_size, Nruns);
        }

        if (Q.empty())
        {
            // no more tuples availble
            return null;
        }

        output_tuple = delete_min();
        if (output_tuple != null)
        {
            op_buf.tupleCopy(output_tuple);
            return op_buf;
        }
        else
            return null;
    }

    /**
     * Cleaning up, including releasing buffer pages from the buffer pool
     * and removing temporary files from the database.
     *
     * @throws IOException   from lower layers
     * @throws SortException something went wrong in the lower layer.
     */
    public void close() throws SortException, IOException
    {
        // clean up
        if (!closeFlag)
        {

            try
            {
                _am.close();
            }
            catch (Exception e)
            {
                throw new SortException(e, "Sort.java: error in closing iterator.");
            }

            if (useBM)
            {
                try
                {
                    free_buffer_pages(_n_pages, bufs_pids);
                }
                catch (Exception e)
                {
                    throw new SortException(e, "Sort.java: BUFmgr error");
                }
                for (int i = 0; i < _n_pages; i++) bufs_pids[i].pid = INVALID_PAGE;
            }

            for (int i = 0; i < temp_files.length; i++)
            {
                if (temp_files[i] != null)
                {
                    try
                    {
                        temp_files[i].deleteFile();
                    }
                    catch (Exception e)
                    {
                        throw new SortException(e, "Sort.java: Heapfile error");
                    }
                    temp_files[i] = null;
                }
            }
            closeFlag = true;
        }
    }

}


