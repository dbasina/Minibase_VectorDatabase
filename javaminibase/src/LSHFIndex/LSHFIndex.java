package LSHFIndex;

import global.AttrType;
import global.RID;
import global.Vector100Dtype;
import heap.HFBufMgrException;
import heap.HFDiskMgrException;
import heap.HFException;
import heap.Heapfile;
import heap.Tuple;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import btree.IndexInsertRecException;
import btree.IndexSearchException;
import btree.InsertException;
import btree.IteratorException;
import btree.KeyTooLongException;
import btree.LeafDeleteException;
import btree.LeafInsertRecException;
import btree.PinPageException;
import btree.UnpinPageException;

/**
 * LSHF index.
 * LSHF index is a map of <Layer Number, Layer Map>
 * Each Layer map is a map of <int[] hash, heapfile>
 * Each Layer is a tree. Bunch of trees is LSHForest.
 * Each Layer = h1h2h3...hn where : hn = number of hashes per layer.
 */
public class LSHFIndex {

    private int numLayers;
    private int noOfHashFunctionsPerLayer;

    private Layer[] layers; // L
    private int binLength; // k


    /**
     * Create LSHF Index class
     *
     * @param L No of layers. Input parameter.
     * @param x No of bins. Input parameter.
     * @param k No of hash functions per layer. Input parameter.
    */
    public LSHFIndex(int L, int x, int k) {
        this.numLayers = L;
        this.binLength = x;
        this.noOfHashFunctionsPerLayer = k;
        this.layers = new Layer[L];
        for (int i = 0; i < L; i++) {
            layers[i] = new Layer(noOfHashFunctionsPerLayer, binLength);
        }
    }

    public String[] getAllLayersHash(Vector100Dtype vector) {
        String[] hashValues = new String[numLayers];
        for (int i = 0; i < numLayers; i++) {
            hashValues[i] = layers[i].GetLayerHashAsString(vector);
        }
        return hashValues;
    }

    public void insertRecord(Vector100Dtype vector, RID rid) throws IOException,
            HFException,
            HFBufMgrException,
            HFDiskMgrException,
            Exception {
        String[] hashValues = getAllLayersHash(vector);
        for (int layer = 0; layer < hashValues.length; layer++) {
            String hashValue = hashValues[layer];
            Heapfile heapFile = new Heapfile("layer-" + layer + "-bin-" + hashValue);
            insertRIDIntoHeapfile(heapFile, rid);
        }
    }

    private void insertRIDIntoHeapfile(Heapfile heapFile, RID rid) throws IOException, Exception {
        Tuple tuple = new Tuple();
        tuple.setHdr((short) 2, new AttrType[] { new AttrType(AttrType.attrInteger), new AttrType(AttrType.attrInteger) }, null);
        tuple.setIntFld(1, rid.pageNo.pid);
        tuple.setIntFld(2, rid.slotNo);
        heapFile.insertRecord(tuple.returnTupleByteArray());
    }

    public List<String> getBinHeapFileNames(Vector100Dtype vector) throws IOException {

        String[] hashValues = getAllLayersHash(vector);
        List<String> binNames = new ArrayList<>();
        for (int layer = 0; layer < hashValues.length; layer++) {
            String hashValue = hashValues[layer];
                String heapFileName = "layer-" + layer + "-bin-" + hashValue;
                binNames.add(heapFileName);
        }
        return binNames;
    }
}
