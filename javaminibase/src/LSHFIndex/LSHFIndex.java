package LSHFIndex;

import bufmgr.PageNotReadException;
import global.*;
import heap.*;
import iterator.*;
import scripts.Query;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * LSHF index.
 * LSHF index is a map of <Layer Number, Layer Map>
 * Each Layer map is a map of <int[] hash, heapfile>
 * Each Layer is a tree. Bunch of trees is LSHForest.
 * Each Layer = h1h2h3...hn where : hn = number of hashes per layer.
 */
public class LSHFIndex
{
    private String relationName;
    private int numLayers;
    private int noOfHashFunctionsPerLayer;
    private int attributeColumnNumber;


    private Layer[] layers; // L
    private int binLength; // k

    private static final String LAYER_STATE_HEAPFILE_NAME = "LayerState";
    private static final String LAYER_METADATA_HEAPFILE_NAME = "LayerMetaData";

    public static final String UNION_DUMP_HEAP_FILE_NAME_SUFFIX = "unionDump";
    public static final String RID_DUMP_HEAP_FILE_NAME = "ridDump";
    public static final String CLEAN_RID_DUMP_HEAP_FILE_NAME = "cleanRidDump";

    private static final AttrType[] META_TUPLE_ATTR_TYPES = new AttrType[3];

    static
    {
        META_TUPLE_ATTR_TYPES[0] = new AttrType(AttrType.attrInteger);
        META_TUPLE_ATTR_TYPES[1] = new AttrType(AttrType.attrInteger);
        META_TUPLE_ATTR_TYPES[2] = new AttrType(AttrType.attrInteger);
    }

    private static final FldSpec[] META_TUPLE_PROJ_LIST = new FldSpec[META_TUPLE_ATTR_TYPES.length];

    static
    {
        IntStream.range(0, META_TUPLE_PROJ_LIST.length).forEach(i -> META_TUPLE_PROJ_LIST[i] = new FldSpec(new RelSpec(RelSpec.outer), i + 1));
    }

    private static final AttrType[] STATE_TUPLE_ATTR_TYPES = new AttrType[4];

    static
    {
        STATE_TUPLE_ATTR_TYPES[0] = new AttrType(AttrType.attrInteger);
        STATE_TUPLE_ATTR_TYPES[1] = new AttrType(AttrType.attrInteger);
        STATE_TUPLE_ATTR_TYPES[2] = new AttrType(AttrType.attrVector100D);
        STATE_TUPLE_ATTR_TYPES[3] = new AttrType(AttrType.attrInteger);
    }

    private static final FldSpec[] STATE_TUPLE_PROJ_LIST = new FldSpec[STATE_TUPLE_ATTR_TYPES.length];

    static
    {
        IntStream.range(0, STATE_TUPLE_PROJ_LIST.length).forEach(i -> STATE_TUPLE_PROJ_LIST[i] = new FldSpec(new RelSpec(RelSpec.outer), i + 1));
    }

    public static final AttrType[] BIN_TUPLE_ATTR_TYPES = new AttrType[2];

    static
    {
        BIN_TUPLE_ATTR_TYPES[0] = new AttrType(AttrType.attrInteger);
        BIN_TUPLE_ATTR_TYPES[1] = new AttrType(AttrType.attrInteger);
    }

    public static final FldSpec[] BIN_TUPLE_PROJ_LIST = new FldSpec[BIN_TUPLE_ATTR_TYPES.length];

    static
    {
        IntStream.range(0, BIN_TUPLE_PROJ_LIST.length).forEach(i -> BIN_TUPLE_PROJ_LIST[i] = new FldSpec(new RelSpec(RelSpec.outer), i + 1));
    }

    private static final AttrType[] RID_DUMP_TUPLE_ATTR_TYPES = new AttrType[3];

    static
    {
        RID_DUMP_TUPLE_ATTR_TYPES[0] = new AttrType(AttrType.attrInteger);
        RID_DUMP_TUPLE_ATTR_TYPES[1] = new AttrType(AttrType.attrInteger);
        RID_DUMP_TUPLE_ATTR_TYPES[2] = new AttrType(AttrType.attrString);
    }

    private static final short[] RID_DUMP_TUPLE_STR_LENGTHS = new short[1];

    static
    {
        RID_DUMP_TUPLE_STR_LENGTHS[0] = 50;
    }

    private static final FldSpec[] RID_DUMP_TUPLE_PROJ_LIST = new FldSpec[RID_DUMP_TUPLE_ATTR_TYPES.length];

    static
    {
        IntStream.range(0, RID_DUMP_TUPLE_PROJ_LIST.length).forEach(i -> RID_DUMP_TUPLE_PROJ_LIST[i] = new FldSpec(new RelSpec(RelSpec.outer), i + 1));
    }


    /**
     * Create LSHF Index class
     *
     * @param Layers                No of layers. Input parameter.
     * @param binLength             bin length
     * @param hashFunctionsPerLayer No of hash functions per layer. Input parameter.
     */
    public LSHFIndex(String relName, int Layers, int binLength, int hashFunctionsPerLayer, int attributeColumnNumber)
            throws
            HFDiskMgrException,
            HFException,
            HFBufMgrException,
            IOException,
            SpaceNotAvailableException,
            FieldNumberOutOfBoundException,
            InvalidTupleSizeException,
            InvalidSlotNumberException,
            InvalidTypeException

    {
        // Meta Data
        this.relationName = relName;
        this.attributeColumnNumber = attributeColumnNumber;
        this.numLayers = Layers;
        this.binLength = binLength;
        this.noOfHashFunctionsPerLayer = hashFunctionsPerLayer;

        this.layers = new Layer[numLayers];

        // Initialize layers
        for (int i = 0; i < numLayers; i++)
        {
            layers[i] = new Layer(noOfHashFunctionsPerLayer, binLength);
        }

        // Store Layer States to disk
        Heapfile LayerState = new Heapfile(getLSHFIndexLayerStateFileName(this.relationName, attributeColumnNumber));

        // States to store for each Layer.
        // int: LayerNumber, int: HashNumber, 100DVector: HashRandom_Vector, int: HashShift
        Tuple temp = new Tuple();

        // Set tuple header.
        temp.setHdr((short) STATE_TUPLE_ATTR_TYPES.length, STATE_TUPLE_ATTR_TYPES, new short[0]);

        for (int i = 0; i < numLayers; i++)
        {
            // Get this layer's proj vectors and hashShifts.
            Vector100Dtype[] projVectors = layers[i].getProjectionVectors();
            int[] shifts = layers[i].getHashShifts();

            // Iterate over all the hashfunctions in the layer.
            for (int j = 0; j < noOfHashFunctionsPerLayer; j++)
            {
                temp.setIntFld(1, i);
                temp.setIntFld(2, j);
                temp.set100DVectFld(3, projVectors[j]);
                temp.setIntFld(4, shifts[j]);

                // Insert tuple into heapfile.
                LayerState.insertRecord(temp.getTupleByteArray());
            }
        }

        // Now we store layer meta data to another heap file layerMetaData
        // No. Layers, No. Hashes per layer, Bin width.
        Heapfile LayerMetaData = new Heapfile(getLSHFIndexLayerMetaDataFileName(this.relationName, attributeColumnNumber));
        Tuple temp2 = new Tuple();

        temp2.setHdr((short) META_TUPLE_ATTR_TYPES.length, META_TUPLE_ATTR_TYPES, new short[0]);

        temp2.setIntFld(1, this.numLayers);
        temp2.setIntFld(2, this.noOfHashFunctionsPerLayer);
        temp2.setIntFld(3, this.binLength);

        LayerMetaData.insertRecord(temp2.getTupleByteArray());

    }

    /**
     * LayerStateFile
     * int:LayerNumber, int:HashNumber, 100DVector:HashRandom_Vector, int:HashShift
     * <p>
     * MetaDataFile
     * int: numLayers, int: noOfHashFunctionsPerLayer, int:binLength
     * <p>
     * This constructor is to restore an existing LSHF index with its randomized vectors and shifts.
     */
    public LSHFIndex(String relName, int attributeColumnNumber)
            throws
            InvalidTupleSizeException,
            IOException,
            FieldNumberOutOfBoundException,
            HFDiskMgrException,
            HFException,
            HFBufMgrException,
            InvalidRelation,
            FileScanException,
            TupleUtilsException,
            PageNotReadException,
            UnknowAttrType,
            PredEvalException,
            WrongPermat,
            JoinsException,
            InvalidTypeException
    {
        this.relationName = relName;
        this.attributeColumnNumber = attributeColumnNumber;

        FileScan metaScan = new FileScan(getLSHFIndexLayerMetaDataFileName(this.relationName, attributeColumnNumber),
                META_TUPLE_ATTR_TYPES,
                new short[0],
                (short) META_TUPLE_ATTR_TYPES.length,
                META_TUPLE_ATTR_TYPES.length,
                META_TUPLE_PROJ_LIST,
                null);

        FileScan stateScan = new FileScan(getLSHFIndexLayerStateFileName(this.relationName, attributeColumnNumber),
                STATE_TUPLE_ATTR_TYPES,
                new short[0],
                (short) STATE_TUPLE_ATTR_TYPES.length,
                STATE_TUPLE_ATTR_TYPES.length,
                STATE_TUPLE_PROJ_LIST,
                null);

        RID rid = new RID();
        Tuple temp = metaScan.get_next();

        // Scan the metadata file and setup the structure of Layers and hashes.
        this.numLayers = temp.getIntFld(1);
        this.noOfHashFunctionsPerLayer = temp.getIntFld(2);
        this.binLength = temp.getIntFld(3);

        this.layers = new Layer[this.numLayers];
        for (int i = 0; i < this.numLayers; i++)
        {

            Vector100Dtype[] projVectors = new Vector100Dtype[this.noOfHashFunctionsPerLayer];
            int[] shifts = new int[this.noOfHashFunctionsPerLayer];
            for (int j = 0; j < this.noOfHashFunctionsPerLayer; j++)
            {
                temp = stateScan.get_next();
                projVectors[j] = temp.get100DVectFld(3);
                shifts[j] = temp.getIntFld(4);
            }

            // Initialize layer[i] with the new values.
            layers[i] = new Layer(projVectors, shifts, binLength);
        }
        metaScan.close();
        stateScan.close();
    }


    public String[] getAllLayersHash(Vector100Dtype vector)
    {
        String[] hashValues = new String[numLayers];
        for (int i = 0; i < numLayers; i++)
        {
            hashValues[i] = layers[i].GetLayerHashAsString(vector);
        }
        return hashValues;
    }

    public void insertRecord(Vector100Dtype vector, RID rid)
            throws
            Exception
    {
        String[] hashValues = getAllLayersHash(vector);
        for (int layer = 0; layer < hashValues.length; layer++)
        {
            String hashValue = hashValues[layer];
            Heapfile heapFile = new Heapfile(generateBinHeapFileName(relationName, layer, attributeColumnNumber, hashValue));
            insertRIDIntoHeapfile(heapFile, rid);
        }
    }

    public static String generateBinHeapFileName(String relation, int layer, int attributeColumnNumber, String hash)
    {
        return "rel" + relation + "col" + attributeColumnNumber + "lay" + layer + "bin" + hash;
    }

    private void insertRIDIntoHeapfile(Heapfile heapFile, RID rid)
            throws
            Exception
    {
        Tuple tuple = new Tuple();
        tuple.setHdr((short) 2, BIN_TUPLE_ATTR_TYPES, null);
        tuple.setIntFld(1, rid.pageNo.pid);
        tuple.setIntFld(2, rid.slotNo);
        heapFile.insertRecord(tuple.getTupleByteArray());
    }

    public List<String> getBinHeapFileNames(Vector100Dtype vector)
            throws
            IOException
    {

        String[] hashValues = getAllLayersHash(vector);
        List<String> binNames = new ArrayList<>();
        for (int layer = 0; layer < hashValues.length; layer++)
        {
            binNames.add(generateBinHeapFileName(relationName, layer, attributeColumnNumber, hashValues[layer]));
        }
        return binNames;
    }

    public Heapfile union(Vector100Dtype inputVector, Heapfile dataFile)
            throws
            Exception
    {
        List<String> hashValues = getBinHeapFileNames(inputVector);
        // Tuple Setup
        // The bin Heap files have record ids of the vectors from the original data heapfile.
        // attr[0] - pageID
        // attr[1] - slotNo

        // Dump all the record ID's from all the bins into ridDump
        Heapfile ridDump = openDeleteAndOpenHeapFile(RID_DUMP_HEAP_FILE_NAME);
        for (String hash : hashValues)
        {
            FileScan binScan = new FileScan(hash, BIN_TUPLE_ATTR_TYPES, new short[0], (short) BIN_TUPLE_ATTR_TYPES.length, BIN_TUPLE_ATTR_TYPES.length, BIN_TUPLE_PROJ_LIST, null);
            Tuple binTuple = binScan.get_next();

            Tuple ridDumpTuple = new Tuple();
            ridDumpTuple.setHdr((short) RID_DUMP_TUPLE_ATTR_TYPES.length, RID_DUMP_TUPLE_ATTR_TYPES, RID_DUMP_TUPLE_STR_LENGTHS);

            while (binTuple != null)
            {
                // Create new tuple of type int: pageNo int:slotNo String:uniqueID("pageNo.slotNo")
                int pageNo = binTuple.getIntFld(1);
                int slotNo = binTuple.getIntFld(2);
                String uniqueID = pageNo + "." + slotNo;

                ridDumpTuple.setIntFld(1, pageNo);
                ridDumpTuple.setIntFld(2, slotNo);
                ridDumpTuple.setStrFld(3, uniqueID);

                ridDump.insertRecord(ridDumpTuple.getTupleByteArray());
                binTuple = binScan.get_next();
            }
            binScan.close();
        }

        // Sort ridDump heapfile on the uniqueID attribute
        FileScan ridDumpScan = new FileScan(RID_DUMP_HEAP_FILE_NAME, RID_DUMP_TUPLE_ATTR_TYPES, RID_DUMP_TUPLE_STR_LENGTHS, (short) RID_DUMP_TUPLE_ATTR_TYPES.length, RID_DUMP_TUPLE_ATTR_TYPES.length, RID_DUMP_TUPLE_PROJ_LIST, null);

        // Call sort on dfRIDDump
        int sortFieldNumber = 3;
        TupleOrder sortOrder = new TupleOrder(TupleOrder.Ascending);
        Heapfile cleanRidDump = openDeleteAndOpenHeapFile(CLEAN_RID_DUMP_HEAP_FILE_NAME);
        Sort ridDumpSort = new Sort(RID_DUMP_TUPLE_ATTR_TYPES, (short) RID_DUMP_TUPLE_ATTR_TYPES.length, RID_DUMP_TUPLE_STR_LENGTHS, ridDumpScan, sortFieldNumber, sortOrder, RID_DUMP_TUPLE_STR_LENGTHS[0], Query.numBuffersForSort);
        try
        {
            // iterate over sorted RID_DUMP_HEAP_FILE
            // Ignore duplicates and add unique records to cleanRIDDump
            Tuple sortedRidDumpTuple = ridDumpSort.get_next();
            String prevUniqueID = "";
            while (sortedRidDumpTuple != null)
            {

                String currentUniqueID = sortedRidDumpTuple.getStrFld(3);

                // if currentUniqueID != prevUniqueID add to
                if (!(prevUniqueID.equals(currentUniqueID)))
                    cleanRidDump.insertRecord(sortedRidDumpTuple.getTupleByteArray());
                prevUniqueID = currentUniqueID;
                sortedRidDumpTuple = ridDumpSort.get_next();
            }
        }
        finally
        {
            ridDumpSort.close();
            ridDumpScan.close();
            ridDump.deleteFile();
        }

        // Read from CLEAN_RID_DUMP_HEAP_FILE, extract the tuple for that RID in the Data File, insert into union dump.
        // Expected no duplicates
        // Nothing changes for cleanRidScan in terms of structure of tuples. So we reuse the MACROS that we used for RID_DUMP_TUPLES.
        FileScan cleanRidScan = new FileScan(CLEAN_RID_DUMP_HEAP_FILE_NAME, RID_DUMP_TUPLE_ATTR_TYPES, RID_DUMP_TUPLE_STR_LENGTHS, (short) RID_DUMP_TUPLE_ATTR_TYPES.length, RID_DUMP_TUPLE_ATTR_TYPES.length, RID_DUMP_TUPLE_PROJ_LIST, null);
        Tuple cleanRidTuple = cleanRidScan.get_next();

        // Create delete create again.
        // Clearing previous unionDump and starting fresh.
        Heapfile unionDump = openDeleteAndOpenHeapFile(getLshUnionDumpFileName(relationName));

        while (cleanRidTuple != null)
        {
            unionDump.insertRecord(
                    dataFile.getRecord(new RID(new PageId(cleanRidTuple.getIntFld(1)), cleanRidTuple.getIntFld(2)))
                            .getTupleByteArray()
            );

            cleanRidTuple = cleanRidScan.get_next();
        }
        cleanRidScan.close();
        cleanRidDump.deleteFile();

        return unionDump;
    }

    public void deleteRecord(Vector100Dtype vector, RID dataFileRid) throws Exception
    {
        for(String binName : getBinHeapFileNames(vector)) {
            Heapfile binHeapFile = new Heapfile(binName);
            Scan binScan = binHeapFile.openScan();
            try {
                RID binRid = new RID();
                Tuple binTuple;

                while ((binTuple = binScan.getNext(binRid)) != null) {
                    binTuple.setHdr((short) BIN_TUPLE_ATTR_TYPES.length, BIN_TUPLE_ATTR_TYPES, new short[0]);
                    if(dataFileRid.equals(new RID(new PageId(binTuple.getIntFld(1)), binTuple.getIntFld(2))))  {
                        binScan.closescan();
                        binHeapFile.deleteRecord(binRid);
                        break;
                    }
                }
            } catch (Exception e){
                binScan.closescan();
            }
        }
    }

    @Override
    public boolean equals(Object obj)
    {
        if (!(obj instanceof LSHFIndex))
            return false;
        LSHFIndex otherIndex = (LSHFIndex) obj;

        return ((this.binLength == otherIndex.binLength) &&
                (this.numLayers == otherIndex.numLayers) &&
                (this.noOfHashFunctionsPerLayer == otherIndex.noOfHashFunctionsPerLayer) &&
                (this.attributeColumnNumber == otherIndex.attributeColumnNumber) &&
                (Arrays.equals(this.layers, otherIndex.layers)));
    }

    private Heapfile openDeleteAndOpenHeapFile(String fileName) throws
                                                                Exception
    {
        Heapfile hf = new Heapfile(fileName);
        hf.deleteFile();
        return new Heapfile(fileName);
    }

    private static String getLSHFIndexLayerStateFileName(String relName, int attributeColumnNumber)
    {
        return LAYER_STATE_HEAPFILE_NAME + relName + attributeColumnNumber;
    }

    private static String getLSHFIndexLayerMetaDataFileName(String relName, int attributeColumnNumber)
    {
        return LAYER_METADATA_HEAPFILE_NAME + relName + attributeColumnNumber;
    }

    public static String getLshUnionDumpFileName(String relationName) {
        return relationName + UNION_DUMP_HEAP_FILE_NAME_SUFFIX;
    }

    public int getAttributeColumnNumber() {
        return attributeColumnNumber;
    }

}
