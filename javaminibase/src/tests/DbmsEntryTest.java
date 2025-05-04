package tests;

import LSHFIndex.LSHFIndex;
import btree.*;
import global.AttrType;
import global.PageId;
import global.RID;
import global.Vector100Dtype;
import heap.*;
import iterator.*;
import scripts.phaseThree.DbmsEntry;
import scripts.phaseThree.SupportedCommands;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.*;

public class DbmsEntryTest {
    private static final HashSet<String> STRINGS_IN_75000_DATASET = new HashSet<>();
    private static final HashSet<String> STRINGS_IN_25000_DATASET = new HashSet<>();
    private static final String QUERY_SPECIFICATION_FILE_PATH = getDbPath("testQuerySpecFile.txt");
    private static final String TARGET_VECTOR_FILE_PATH = getDbPath("testTargetVecFile.txt");
    private static final String FILE_OUTPUT_STREAM_PATH = getDbPath("fileOut.txt");

    // Picked a random vector from the 75_000 dataset
    private static final String TARGET_VECTOR = "1273 -5324 -181 5219 -4663 5455 -3006 2703 -4230 1255 -4585 5449 -4241 2284 5459 7296 -9925 5501 -5258 7480 -2415 9328 -3372 -6364 1512 3869 -8219 18 6120 5027 6992 1627 8665 8558 -8882 -1036 -4477 -4989 -3045 5648 -2858 -7062 1745 -4893 -8278 -4439 -5914 -5357 596 -8324 -6161 -966 -6916 4310 -1996 7369 3667 8076 1644 -8207 -2748 -8159 9345 3694 9 3254 -897 -349 8543 1360 -3573 -2869 -8884 5151 5393 1268 3745 7578 -3695 7579 8268 -6812 9502 -5970 -5623 -6051 -3951 9651 -3459 -440 -3297 -4441 -7249 -2963 5135 -4857 2881 6137 -8614 -9442";

    public static void main(String[] args) throws Exception {
        // Initialization
        BiConsumer<String, HashSet<String>> readStringsToHashSet = (txtFilePath, hashSet) -> {
            try {
                BufferedReader fileReader = new BufferedReader(new FileReader(txtFilePath));
                fileReader.readLine();
                fileReader.readLine();

                String line = fileReader.readLine();
                while(line != null) {
                    fileReader.readLine();
                    hashSet.add(fileReader.readLine());
                    fileReader.readLine();
                    line = fileReader.readLine();
                }
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        };
        readStringsToHashSet.accept("javaminibase/src/tests/scriptTestDataFiles/sample75_000.txt", STRINGS_IN_75000_DATASET);
        readStringsToHashSet.accept("javaminibase/src/tests/scriptTestDataFiles/sample25_000.txt", STRINGS_IN_25000_DATASET);

        // Tests
        testCreateNewDbCloseAndReopen();
        testBatchCreate();
        testCreateIndex();
        testBTreeIndicesOnMultipleColumnsAndMultipleRelations();
        testLshIndicesOnMultipleColumnsAndMultipleRelations();
        testBatchInsert();
        testQueries();
        testDistanceJoin();
        testBatchDelete();
        testQueriesWithLowBuffers();

        System.out.println("All tests passed!");
    }

    private static void testCreateNewDbCloseAndReopen() throws Exception {
        String dbNameOne = "testDb";
        String dbNameTwo = "testDbTwo";

        Files.deleteIfExists(Paths.get(getDbPath(dbNameOne)));
        Files.deleteIfExists(Paths.get(getDbPath(dbNameTwo)));

        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });

        if (!Files.exists(Paths.get(getDbPath(dbNameOne))))
            throw new RuntimeException("FAIL - testCreateNewDbCloseAndReopen - DB file missing!");

        Consumer<Integer[]> insertDataToTestHeapFile = (numsToInsert) -> {
            try {
                Heapfile file = new Heapfile("testFile");

                Tuple t = new Tuple();
                t.setHdr((short) 1, new AttrType[] { new AttrType(AttrType.attrInteger) }, null);

                for (int i : numsToInsert) {
                    t.setIntFld(1, i);
                    file.insertRecord(t.getTupleByteArray());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        // Insert dummy data
        insertDataToTestHeapFile.accept(new Integer[] { 10, 20 });
        DbmsEntry.handleDbCloseCommand();

        // Open another Db
        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameTwo });
        insertDataToTestHeapFile.accept(new Integer[] { 100, 200 });
        DbmsEntry.handleDbCloseCommand();

        // Reopen DbOne
        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });

        // Read dummy data from dbOne
        Consumer<List<Integer>> readDataFromTestHeapFile = (expectedNums) -> {
            try {
                Heapfile file = new Heapfile("testFile");
                if (file.getRecCnt() != 2)
                    throw new RuntimeException(
                            "FAIL - testCreateNewDbCloseAndReopen - Heap file record count mismatch after reopening!");

                FileScan fileScan = new FileScan("testFile",
                        new AttrType[] { new AttrType(AttrType.attrInteger) },
                        null,
                        (short) 1,
                        1,
                        new FldSpec[] { new FldSpec(new RelSpec(RelSpec.outer), 1) },
                        null);

                Tuple outTuple = fileScan.get_next();
                ArrayList<Integer> results = new ArrayList<>();
                while (outTuple != null) {
                    results.add(outTuple.getIntFld(1));
                    outTuple = fileScan.get_next();
                }

                if (!results.equals(expectedNums))
                    throw new RuntimeException(
                            "FAIL - testCreateNewDbCloseAndReopen - File data mismatch after reopening!");

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        readDataFromTestHeapFile.accept(Arrays.asList(10, 20));
        DbmsEntry.handleDbCloseCommand();

        // Read dummy data from dbTwo
        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameTwo });

        readDataFromTestHeapFile.accept(Arrays.asList(100, 200));
        DbmsEntry.handleDbCloseCommand();

        System.out.println("PASS - testCreateNewDbCloseAndReopen\n\n");
    }

    private static void testBatchCreate() throws Exception {
        String dbNameOne = "testDbOne";
        String relNameOne = "rel1";
        String relNameTwo = "rel2";
        
        Files.deleteIfExists(Paths.get(getDbPath(dbNameOne)));
    
        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });
        if (!Files.exists(Paths.get(getDbPath(dbNameOne))))
            throw new RuntimeException("FAIL - testBatchCreate - DB file missing!");

        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample75_000.txt", relNameOne });
        String relMetadataFilePath = DbmsEntry.getRelMetaDataFileName(relNameOne);
        String dataFilePathFull = DbmsEntry.getRelDataFileName(relNameOne);

        Heapfile dbMetaDataFile = new Heapfile(DbmsEntry.DB_METADATA_FILE_NAME);
        Heapfile metadataFile = new Heapfile(relMetadataFilePath);
        Heapfile dataFile = new Heapfile(dataFilePathFull);

        if (dbMetaDataFile.getRecCnt() != 1)
            throw new RuntimeException("FAIL - testBatchCreate - DB metadata file record count mismatch after batch create!");
        if (metadataFile.getRecCnt() != 4)
            throw new RuntimeException("FAIL - testBatchCreate - Metadata file record count mismatch after batch create!");
        if (dataFile.getRecCnt() != 75000)
            throw new RuntimeException("FAIL - testBatchCreate - Data file record count mismatch after batch create!");

        BiConsumer<String, HashSet<String>> verifyDataFileContents = (heapDataFileName, targetStringSet) -> {
            HashSet<String> stringsInHeapDataFile = new HashSet<>();
            try {
                FileScan fileScan = new FileScan(heapDataFileName,
                        new AttrType[] { new AttrType(AttrType.attrInteger), new AttrType(AttrType.attrReal), new AttrType(AttrType.attrString), new AttrType(AttrType.attrVector100D) },
                        new short[] {DbmsEntry.MAX_STRING_LENGTH},
                        (short) 4,
                        1,
                        new FldSpec[] { new FldSpec(new RelSpec(RelSpec.outer), 3) },
                        null);

                Tuple outTuple = fileScan.get_next();
                while (outTuple != null) {
                    stringsInHeapDataFile.add(outTuple.getStrFld(1));
                    outTuple = fileScan.get_next();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            if(stringsInHeapDataFile.isEmpty() || (! stringsInHeapDataFile.equals(targetStringSet)))
                throw new RuntimeException("FAIL - testBatchCreate - Mismatch between data in txt file and heap file!");
        };
        verifyDataFileContents.accept(dataFilePathFull, STRINGS_IN_75000_DATASET);

        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample25_000.txt", relNameTwo });

        relMetadataFilePath = DbmsEntry.getRelMetaDataFileName(relNameTwo);
        dataFilePathFull = DbmsEntry.getRelDataFileName(relNameTwo);
        
        metadataFile = new Heapfile(relMetadataFilePath);
        dataFile = new Heapfile(dataFilePathFull);

        if (dbMetaDataFile.getRecCnt() != 2)
            throw new RuntimeException("FAIL - testBatchCreate - DB metadata file record count mismatch after batch create!");
        if (metadataFile.getRecCnt() != 4)
            throw new RuntimeException("FAIL - testBatchCreate - Metadata file record count mismatch after batch create!");
        if (dataFile.getRecCnt() != 25000)
            throw new RuntimeException("FAIL - testBatchCreate - Data file record count mismatch after batch create!");
        verifyDataFileContents.accept(dataFilePathFull, STRINGS_IN_25000_DATASET);

        try{
            DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample75_000.txt", relNameOne });
        } catch (Exception e) {
            System.out.println("Expected exception: " + e.getMessage());
        }
        
        DbmsEntry.handleDbCloseCommand();

        // Reopen DB and make sure files preserved
        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });
        verifyDataFileContents.accept(dataFilePathFull, STRINGS_IN_25000_DATASET);

        DbmsEntry.handleDbCloseCommand();
        System.out.println("PASS - testBatchCreate\n\n");
    }

    private static void testCreateIndex() throws Exception { 
        String dbNameOne = "testDb";
        String relNameOne = "rel1";

        Files.deleteIfExists(Paths.get(getDbPath(dbNameOne)));

        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });
        if (!Files.exists(Paths.get(getDbPath(dbNameOne))))
            throw new RuntimeException("FAIL - testCreateIndex - DB file missing!");

        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample75_000.txt", relNameOne });

        String relMetadataFilePath = DbmsEntry.getRelMetaDataFileName(relNameOne);
        String dataFilePathFull = DbmsEntry.getRelDataFileName(relNameOne);

        Heapfile dbMetaDataFile = new Heapfile(DbmsEntry.DB_METADATA_FILE_NAME);
        Heapfile metadataFile = new Heapfile(relMetadataFilePath);
        Heapfile dataFile = new Heapfile(dataFilePathFull);

        if (dbMetaDataFile.getRecCnt() != 1)
            throw new RuntimeException("FAIL - testCreateIndex - DB metadata file record count mismatch after batch create!");
        if (metadataFile.getRecCnt() != 4)
            throw new RuntimeException("FAIL - testCreateIndex - Metadata file record count mismatch after batch create!");
        if (dataFile.getRecCnt() != 75000)
            throw new RuntimeException("FAIL - testCreateIndex - Data file record count mismatch after batch create!");

        // Integer index on column 0
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), relNameOne, "3"});
        if (dbMetaDataFile.getRecCnt() != 2)
            throw new RuntimeException("FAIL - testCreateIndex - DB metadata file record count mismatch after index create!");
        if (! DbmsEntry.checkIfIndexExistsInDbMetaDataFile(relNameOne, 3))
            throw new RuntimeException("FAIL - testCreateIndex - DB metadata file does not contain newly created index!");
        if (metadataFile.getRecCnt() != 4)
            throw new RuntimeException("FAIL - testCreateIndex - Metadata file record count mismatch after index create!");
        if (dataFile.getRecCnt() != 75000)
            throw new RuntimeException("FAIL - testCreateIndex - Data file record count mismatch after index create!");

        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(relNameOne, 3), STRINGS_IN_75000_DATASET, AttrType.attrString);

        Consumer<String> searchBTreeAndVerifyRecord = (btreeFileName) -> {
            try {
                BTreeFile bTreeFile = new BTreeFile(btreeFileName);
                StringKey key = new StringKey("rpYDFdMw");
                BTFileScan scan = bTreeFile.new_scan(key, key);

                KeyDataEntry entry = scan.get_next();

                Tuple resultTuple = dataFile.getRecord(((LeafData)entry.data).getData());
                resultTuple.setHdr(
                        (short) 4,
                        new AttrType[] { new AttrType(AttrType.attrInteger), new AttrType(AttrType.attrReal), new AttrType(AttrType.attrString), new AttrType(AttrType.attrVector100D) },
                        new short[] {DbmsEntry.MAX_STRING_LENGTH}
                );

                if((resultTuple.getIntFld(1) != 84) || (resultTuple.getFloFld(2) != 76.2f) || (! resultTuple.getStrFld(3).equals("rpYDFdMw")))
                    throw new RuntimeException("FAIL - testCreateIndex - Mismatch between data in txt file and bTree!");

                String expectedVectorString = "-5031 -271 9785 8427 -7795 -9298 1360 -3782 -2346 9816 4719 -8765 4041 -6622 -3626 9888 -9503 -4853 -3686 6774 9106 -8172 -9958 -3108 -2164 9752 5426 6778 9610 8539 3941 -8397 -3686 -4388 -6680 -8042 -9707 -8228 4126 -1472 9487 -931 1916 -7374 4201 -8861 2660 -8566 7364 -6006 -6271 7783 -1550 3683 -3944 -4154 2956 9236 8115 -3703 -6100 8766 -4591 4280 -5161 -9186 -1791 -8328 -7890 4439 -6359 -3091 -6304 -9814 -3398 -25 -3581 6082 -2503 1076 -3364 3267 -5319 -3064 -9006 8816 4470 -1552 1111 5983 9011 -617 -1498 -534 6834 9070 5232 2159 1966 5359";
                short[] targetVector = new short[100];
                int i = 0;
                for(String s : expectedVectorString.split(" ")) {
                    targetVector[i] = Short.parseShort(s);
                    i++;
                }
                if(! resultTuple.get100DVectFld(4).equals(new Vector100Dtype(targetVector)))
                    throw new RuntimeException("FAIL - testCreateIndex - Mismatch between data in txt file and bTree!");

                scan.DestroyBTreeFileScan();
                bTreeFile.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        searchBTreeAndVerifyRecord.accept(DbmsEntry.getBTreeFileName(relNameOne, 3));

        // LSH Index on Vector field
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), relNameOne, "4", "2", "2"});
        if (dbMetaDataFile.getRecCnt() != 3)
            throw new RuntimeException("FAIL - testCreateIndex - DB metadata file record count mismatch after index create!");
        if (! DbmsEntry.checkIfIndexExistsInDbMetaDataFile(relNameOne, 4))
            throw new RuntimeException("FAIL - testCreateIndex - DB metadata file does not contain newly created index!");
        if (metadataFile.getRecCnt() != 4)
            throw new RuntimeException("FAIL - testCreateIndex - Metadata file record count mismatch after index create!");
        if (dataFile.getRecCnt() != 75000)
            throw new RuntimeException("FAIL - testCreateIndex - Data file record count mismatch after index create!");

        LSHFIndex lshfIndexBeforeDbClose = new LSHFIndex(relNameOne, 4);
        verifyLshIndexHasAllTuples(dataFilePathFull, DbmsEntry.getRelationAttrTypes(relNameOne), lshfIndexBeforeDbClose, 4);

        DbmsEntry.handleDbCloseCommand();

        // Reopen DB to check if files preserved
        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });

        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(relNameOne, 3), STRINGS_IN_75000_DATASET, AttrType.attrString);
        searchBTreeAndVerifyRecord.accept(DbmsEntry.getBTreeFileName(relNameOne, 3));

        LSHFIndex lshfIndexAfterDbClose = new LSHFIndex(relNameOne, 4);
        verifyLshIndexHasAllTuples(dataFilePathFull, DbmsEntry.getRelationAttrTypes(relNameOne), lshfIndexAfterDbClose, 4);

        if(! lshfIndexAfterDbClose.equals(lshfIndexBeforeDbClose))
            throw new RuntimeException("FAIL - testCreateIndex - LSHIndices before and after close are not the same! Perhaps LSHF reinitialization from state/meta files isn't working.");

        DbmsEntry.handleDbCloseCommand();
        System.out.println("PASS - testCreateIndex\n\n");
    }

    private static void testBTreeIndicesOnMultipleColumnsAndMultipleRelations() throws Exception {
        String dbNameOne = "testDb";
        String twentyFiveKRelation = "rel25";
        String sampleData1Relation = "relSample1";

        Files.deleteIfExists(Paths.get(getDbPath(dbNameOne)));

        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });

        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample25_000.txt", twentyFiveKRelation });
        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample_data_1.txt", sampleData1Relation });

        final HashSet<Integer> twentyFiveKCol1 = new HashSet<>();
        final HashSet<Float> twentyFiveKCol2 = new HashSet<>();
        final HashSet<String> twentyFiveKCol3 = new HashSet<>();
        BufferedReader fileReader = new BufferedReader(new FileReader("javaminibase/src/tests/scriptTestDataFiles/sample25_000.txt"));
        fileReader.readLine();
        fileReader.readLine();
        String line = fileReader.readLine();
        while(line != null) {
            twentyFiveKCol1.add(Integer.parseInt(line));
            twentyFiveKCol2.add(Float.parseFloat(fileReader.readLine()));
            twentyFiveKCol3.add(fileReader.readLine());
            fileReader.readLine();
            line = fileReader.readLine();
        }

        final HashSet<Float> sampleDataCol1 = new HashSet<>();
        final HashSet<Float> sampleDataCol3 = new HashSet<>();
        fileReader = new BufferedReader(new FileReader("javaminibase/src/tests/scriptTestDataFiles/sample_data_1.txt"));
        fileReader.readLine();
        fileReader.readLine();
        line = fileReader.readLine();
        while(line != null) {
            sampleDataCol1.add(Float.parseFloat(line));
            fileReader.readLine();
            sampleDataCol3.add(Float.parseFloat(fileReader.readLine()));
            fileReader.readLine();
            line = fileReader.readLine();
        }

        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), twentyFiveKRelation, "1"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), twentyFiveKRelation, "2"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), twentyFiveKRelation, "3"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), sampleData1Relation, "1"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), sampleData1Relation, "3"});
        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(twentyFiveKRelation, 1), twentyFiveKCol1, AttrType.attrInteger);
        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(twentyFiveKRelation, 2), twentyFiveKCol2, AttrType.attrReal);
        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(twentyFiveKRelation, 3), twentyFiveKCol3, AttrType.attrString);
        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(sampleData1Relation, 1), sampleDataCol1, AttrType.attrReal);
        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(sampleData1Relation, 3), sampleDataCol3, AttrType.attrReal);

        DbmsEntry.handleDbCloseCommand();
        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });

        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(twentyFiveKRelation, 1), twentyFiveKCol1, AttrType.attrInteger);
        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(twentyFiveKRelation, 2), twentyFiveKCol2, AttrType.attrReal);
        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(twentyFiveKRelation, 3), twentyFiveKCol3, AttrType.attrString);
        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(sampleData1Relation, 1), sampleDataCol1, AttrType.attrReal);
        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(sampleData1Relation, 3), sampleDataCol3, AttrType.attrReal);

        DbmsEntry.handleDbCloseCommand();
        System.out.println("PASS - testBTreeIndicesOnMultipleColumnsAndMultipleRelations\n\n");
    }

    private static void testLshIndicesOnMultipleColumnsAndMultipleRelations() throws Exception {
        String dbNameOne = "testDb";
        String sampleData1Relation = "relSample1";
        String sampleData2Relation = "relSample2";

        Files.deleteIfExists(Paths.get(getDbPath(dbNameOne)));

        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });

        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample_data_1.txt", sampleData1Relation });
        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample_data_2.txt", sampleData2Relation });

        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), sampleData1Relation, "2", "2", "2"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), sampleData1Relation, "4", "2", "2"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), sampleData2Relation, "2", "2", "2"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), sampleData2Relation, "4", "2", "2"});
        LSHFIndex indexSampleData1Col2BeforeRestart = new LSHFIndex(sampleData1Relation, 2);
        LSHFIndex indexSampleData1Col4BeforeRestart = new LSHFIndex(sampleData1Relation, 4);
        LSHFIndex indexSampleData2Col2BeforeRestart = new LSHFIndex(sampleData2Relation, 2);
        LSHFIndex indexSampleData2Col4BeforeRestart = new LSHFIndex(sampleData2Relation, 4);
        verifyLshIndexHasAllTuples(DbmsEntry.getRelDataFileName(sampleData1Relation), DbmsEntry.getRelationAttrTypes(sampleData1Relation), indexSampleData1Col2BeforeRestart, 2);
        verifyLshIndexHasAllTuples(DbmsEntry.getRelDataFileName(sampleData1Relation), DbmsEntry.getRelationAttrTypes(sampleData1Relation), indexSampleData1Col4BeforeRestart, 4);
        verifyLshIndexHasAllTuples(DbmsEntry.getRelDataFileName(sampleData2Relation), DbmsEntry.getRelationAttrTypes(sampleData2Relation), indexSampleData2Col2BeforeRestart, 2);
        verifyLshIndexHasAllTuples(DbmsEntry.getRelDataFileName(sampleData2Relation), DbmsEntry.getRelationAttrTypes(sampleData2Relation), indexSampleData2Col4BeforeRestart, 4);

        DbmsEntry.handleDbCloseCommand();
        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });

        LSHFIndex indexSampleData1Col2AfterRestart = new LSHFIndex(sampleData1Relation, 2);
        LSHFIndex indexSampleData1Col4AfterRestart = new LSHFIndex(sampleData1Relation, 4);
        LSHFIndex indexSampleData2Col2AfterRestart = new LSHFIndex(sampleData2Relation, 2);
        LSHFIndex indexSampleData2Col4AfterRestart = new LSHFIndex(sampleData2Relation, 4);
        verifyLshIndexHasAllTuples(DbmsEntry.getRelDataFileName(sampleData1Relation), DbmsEntry.getRelationAttrTypes(sampleData1Relation), indexSampleData1Col2AfterRestart, 2);
        verifyLshIndexHasAllTuples(DbmsEntry.getRelDataFileName(sampleData1Relation), DbmsEntry.getRelationAttrTypes(sampleData1Relation), indexSampleData1Col4AfterRestart, 4);
        verifyLshIndexHasAllTuples(DbmsEntry.getRelDataFileName(sampleData2Relation), DbmsEntry.getRelationAttrTypes(sampleData2Relation), indexSampleData2Col2AfterRestart, 2);
        verifyLshIndexHasAllTuples(DbmsEntry.getRelDataFileName(sampleData2Relation), DbmsEntry.getRelationAttrTypes(sampleData2Relation), indexSampleData2Col4AfterRestart, 4);

        if(! indexSampleData1Col2BeforeRestart.equals(indexSampleData1Col2AfterRestart) || ! indexSampleData1Col4BeforeRestart.equals(indexSampleData1Col4AfterRestart)
            || ! indexSampleData2Col2BeforeRestart.equals(indexSampleData2Col2AfterRestart) || ! indexSampleData2Col4BeforeRestart.equals(indexSampleData2Col4AfterRestart))
            throw new RuntimeException("FAIL - testLshIndicesOnMultipleColumnsAndMultipleRelations - LSHIndices before and after close are not the same! Perhaps LSHF reinitialization from state/meta files isn't working.");

        DbmsEntry.handleDbCloseCommand();
        System.out.println("PASS - testLshIndicesOnMultipleColumnsAndMultipleRelations\n\n");
    }

    private static void testQueries() throws Exception {
        String dbName = "testDb";
        String relName = "rel1";

        Files.deleteIfExists(Paths.get(getDbPath(dbName)));
        Files.deleteIfExists(Paths.get(TARGET_VECTOR_FILE_PATH));
        cleanupTestInputFiles();

        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbName });
        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample75_000.txt", relName });
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), relName, "4", "2", "2"});

        // Picked a random target vector from 75_000 sample data file
        createFileForTestInput(TARGET_VECTOR_FILE_PATH, TARGET_VECTOR);

        Function<Integer, Integer> verifyRangeOutput = (maxDistance) -> {
            try {
                Vector100Dtype target = Vector100Dtype.buildVector100Dtype(TARGET_VECTOR.split(" "));

                BufferedReader fileReader = new BufferedReader(new FileReader(FILE_OUTPUT_STREAM_PATH));
                String line = fileReader.readLine();
                while((line != null) && (! line.contains("---Output Tuples---")))
                    line = fileReader.readLine();

                line = fileReader.readLine();
                int prevDistance = -1;
                boolean hasSeenZero = false;
                int vectorsSeen = 0;
                while(! line.contains("---End Output---")) {
                    if(! line.trim().startsWith("[")) {
                        line = fileReader.readLine();
                        continue;
                    }
                    Vector100Dtype currVector = Vector100Dtype.buildVector100Dtype(line.substring(1, line.length() - 1).split(", "));
                    int distance = target.get_distance(currVector);
                    if(distance < prevDistance)
                        throw new RuntimeException("FAIL - testQueries - Distance not increasing when going through query's vector results!");
                    if(distance > maxDistance)
                        throw new RuntimeException("FAIL - testQueries - Distance greater than max supplied distance!");
                    if(distance == 0)
                        hasSeenZero = true;

                    vectorsSeen++;
                    prevDistance = distance;
                    line = fileReader.readLine();
                }

                if(! hasSeenZero)
                    throw new RuntimeException("FAIL - testQueries - The result should contain the target vector itself!");

                return vectorsSeen;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        Consumer<int[]> verifyOutputTypes = (expectedAttrTypes) -> {
            try {
                BufferedReader fileReader = new BufferedReader(new FileReader(FILE_OUTPUT_STREAM_PATH));
                String line = fileReader.readLine();
                while((line != null) && (! line.contains("---Output Tuples---")))
                    line = fileReader.readLine();
                line = fileReader.readLine();
                while(line.contains("Generated"))
                    line = fileReader.readLine();

                ArrayList<Integer> returnedAttrTypes = new ArrayList<>();
                while(! line.isBlank()){
                    returnedAttrTypes.add(getAttrTypeForString(line));
                    line = fileReader.readLine();
                }
                fileReader.close();

                if(! Arrays.equals(expectedAttrTypes, returnedAttrTypes.stream().mapToInt(s -> s).toArray()))
                    throw new RuntimeException("FAIL - testQueries - Returned columns are not what was expected!");

            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        };

        Function<String, Integer> verifyFilterOutput = (expectedKey) -> {
            try {
                BufferedReader fileReader = new BufferedReader(new FileReader(FILE_OUTPUT_STREAM_PATH));
                String line = fileReader.readLine();
                while((line != null) && (! line.contains("---Output Tuples---")))
                    line = fileReader.readLine();

                line = fileReader.readLine();
                int recordsSeen = 0;
                while(! line.contains("---End Output---")) {
                    if(line.trim().equals(expectedKey)) {
                        recordsSeen++;
                    }
                    line = fileReader.readLine();
                }
                return recordsSeen;
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        };

        testRangeQuery(relName, verifyRangeOutput, verifyOutputTypes, dbName);
        testNnQuery(relName, verifyRangeOutput, verifyOutputTypes, dbName);
        testSortQuery(relName, verifyRangeOutput, verifyOutputTypes);
        testFilterQuery(relName, verifyFilterOutput, verifyOutputTypes, dbName);

        DbmsEntry.handleDbCloseCommand();
        System.out.println("PASS - testQueries\n\n");
    }

    public static void testFilterQuery(String relationName, Function<String, Integer> verifyFilterOutput, Consumer<int[]> verifyOutputTypes, String dbName) throws Exception {
        cleanupTestInputFiles();

        // Query on 100D column
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"Filter(4, 5, 10, N, 2, 4)");
        DbmsEntry.handleQueryCommand(new String[] { SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});

        cleanupTestInputFiles();

        // No index created but used
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"Filter(1, 19, 10, Y, 1, 4)");
        DbmsEntry.handleQueryCommand(new String[] { SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});

        final String[] indexSettings = new String[] {"Y", "N"};

        // With Integer index
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), relationName, "1"});

        for(String indexOption : indexSettings) {
            cleanupTestInputFiles();

            OutputStream teeStream = buildTeeStream();
            System.setOut(new PrintStream(teeStream, true));
            createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH, "Filter(1, 19, 3, "+ indexOption +", 1, 4)");
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
            teeStream.close();
            if (verifyFilterOutput.apply("19") != 3)
                throw new RuntimeException("FAIL - testQueries - Filter query should return exactly k records!");
            verifyOutputTypes.accept(new int[]{AttrType.attrInteger, AttrType.attrVector100D});
        }

        // With Float index
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), relationName, "2"});

        for(String indexOption : indexSettings) {
            cleanupTestInputFiles();

            OutputStream teeStream = buildTeeStream();
            System.setOut(new PrintStream(teeStream, true));
            createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH, "Filter(2, 23.06, 3, "+ indexOption +", 2, 3)");
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
            teeStream.close();
            if (verifyFilterOutput.apply("23.06") != 3)
                throw new RuntimeException("FAIL - testQueries - Filter query should return exactly k records!");
            verifyOutputTypes.accept(new int[]{AttrType.attrReal, AttrType.attrString});
        }

        // With String index
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), relationName, "3"});

        for(String indexOption : indexSettings) {
            cleanupTestInputFiles();

            OutputStream teeStream = buildTeeStream();
            System.setOut(new PrintStream(teeStream, true));
            createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH, "Filter(3, VhtWsjJW, 1, "+ indexOption +", 2, 3)");
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
            teeStream.close();
            if (verifyFilterOutput.apply("VhtWsjJW") != 1)
                throw new RuntimeException("FAIL - testQueries - Filter query should return exactly k records!");
            verifyOutputTypes.accept(new int[]{AttrType.attrReal, AttrType.attrString});
        }

        // *
        for(String indexOption : indexSettings) {
            cleanupTestInputFiles();

            OutputStream teeStream = buildTeeStream();
            System.setOut(new PrintStream(teeStream, true));
            createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH, "Filter(1, 19, 10, "+ indexOption +", *)");
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
            teeStream.close();
            if (verifyFilterOutput.apply("19") == 0)
                throw new RuntimeException("FAIL - testQueries - Filter query should return atleast 1 record!");
            verifyOutputTypes.accept(new int[]{AttrType.attrInteger, AttrType.attrReal, AttrType.attrString, AttrType.attrVector100D});
        }

        cleanupTestInputFiles();

        // Low numbuf
        boolean wasExceptionRaised = false;
        try {
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(1)});
        } catch (Exception e) {
            wasExceptionRaised = true;
        }
        if(! wasExceptionRaised)
            throw new RuntimeException("FAIL - testQueries - Low num buf should have thrown an exception!");

        DbmsEntry.handleDbCloseCommand();
        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbName });

        // Positive case to ensure DB not corrupt - Remove once below methods are done
        for(String indexOption : indexSettings) {
            cleanupTestInputFiles();

            OutputStream teeStream = buildTeeStream();
            System.setOut(new PrintStream(teeStream, true));
            createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH, "Filter(1, 19, 3, "+ indexOption +", 1, 4)");
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
            teeStream.close();
            if (verifyFilterOutput.apply("19") != 3)
                throw new RuntimeException("FAIL - testQueries - Filter query should return exactly k records!");
            verifyOutputTypes.accept(new int[]{AttrType.attrInteger, AttrType.attrVector100D});
        }
    }

    private static void testSortQuery(String relationName, Function<Integer, Integer> verifyResults, Consumer<int[]> verifyOutputTypes) throws Exception {
        cleanupTestInputFiles();

        OutputStream teeStream = buildTeeStream();
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"Sort(4, " + TARGET_VECTOR_FILE_PATH + ", 70000, 2, 4)");
        System.setOut(new PrintStream(teeStream, true));
        DbmsEntry.handleQueryCommand(new String[] { SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
        teeStream.close();
        verifyResults.apply(Integer.MAX_VALUE);
        verifyOutputTypes.accept(new int[]{ AttrType.attrReal, AttrType.attrVector100D });

        cleanupTestInputFiles();
        teeStream = buildTeeStream();

        // *
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"Sort(4, " + TARGET_VECTOR_FILE_PATH + ", 70000, *)");
        System.setOut(new PrintStream(teeStream, true));
        DbmsEntry.handleQueryCommand(new String[] { SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
        teeStream.close();
        verifyResults.apply(Integer.MAX_VALUE);
        verifyOutputTypes.accept(new int[]{ AttrType.attrInteger, AttrType.attrReal, AttrType.attrString, AttrType.attrVector100D });
    }

    private static void testRangeQuery(String relationName, Function<Integer, Integer> verifyResults, Consumer<int[]> verifyOutputTypes, String dbName) throws Exception {
        cleanupTestInputFiles();

        // No Index
        OutputStream teeStream = buildTeeStream();
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"Range(4, " + TARGET_VECTOR_FILE_PATH + ", 70000, N, 1, 2, 3, 4)");
        System.setOut(new PrintStream(teeStream, true));
        DbmsEntry.handleQueryCommand(new String[] { SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
        teeStream.close();
        verifyResults.apply(70000);
        verifyOutputTypes.accept(new int[]{ AttrType.attrInteger, AttrType.attrReal, AttrType.attrString, AttrType.attrVector100D });

        cleanupTestInputFiles();
        teeStream = buildTeeStream();
        System.out.println("Who needs yoga when we have LSH? This will take ~2min. Relax...");

        // With Index
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"Range(4, " + TARGET_VECTOR_FILE_PATH + ", 70000, Y, 1, 3, 4)");
        System.setOut(new PrintStream(teeStream, true));
        DbmsEntry.handleQueryCommand(new String[] { SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
        teeStream.close();
        verifyResults.apply(70000);
        verifyOutputTypes.accept(new int[]{ AttrType.attrInteger, AttrType.attrString, AttrType.attrVector100D });

        cleanupTestInputFiles();
        teeStream = buildTeeStream();

        // *
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"Range(4, " + TARGET_VECTOR_FILE_PATH + ", 70000, N, *)");
        System.setOut(new PrintStream(teeStream, true));
        DbmsEntry.handleQueryCommand(new String[] { SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
        teeStream.close();
        verifyResults.apply(70000);
        verifyOutputTypes.accept(new int[]{ AttrType.attrInteger, AttrType.attrReal, AttrType.attrString, AttrType.attrVector100D });

        cleanupTestInputFiles();

        // Low numbuf
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"Range(4, " + TARGET_VECTOR_FILE_PATH + ", 70000, N, 4)");
        boolean wasExceptionRaised = false;
        try {
            DbmsEntry.handleQueryCommand(new String[] { SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(10)});
        } catch (Exception e) {
            wasExceptionRaised = true;
        }
        if(! wasExceptionRaised)
            throw new RuntimeException("FAIL - testQueries - Low num buf should have thrown an exception!");

        DbmsEntry.handleDbCloseCommand();
        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbName });
    }

    public static void testNnQuery(String relationName, Function<Integer, Integer> verifyResults, Consumer<int[]> verifyOutputTypes, String dbName) throws Exception {
        cleanupTestInputFiles();

        // No Index
        OutputStream teeStream = buildTeeStream();
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"NN(4, " + TARGET_VECTOR_FILE_PATH + ", 5, N, 3, 4)");
        System.setOut(new PrintStream(teeStream, true));
        DbmsEntry.handleQueryCommand(new String[] { SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
        teeStream.close();
        if(verifyResults.apply(Integer.MAX_VALUE) != 5)
            throw new RuntimeException("FAIL - testQueries - NN query should contain 5 results!");
        verifyOutputTypes.accept(new int[]{ AttrType.attrString, AttrType.attrVector100D });

        cleanupTestInputFiles();
        teeStream = buildTeeStream();
        System.out.println("Indices help prune and speed up queries....or do they? This is LSH. This will take ~2min. Relax...");

        // With Index
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"NN(4, " + TARGET_VECTOR_FILE_PATH + ", 5, Y, 1, 4)");
        System.setOut(new PrintStream(teeStream, true));
        DbmsEntry.handleQueryCommand(new String[] { SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
        teeStream.close();
        if(verifyResults.apply(Integer.MAX_VALUE) != 5)
            throw new RuntimeException("FAIL - testQueries - NN query should contain 5 results!");
        verifyOutputTypes.accept(new int[]{ AttrType.attrInteger, AttrType.attrVector100D });

        cleanupTestInputFiles();
        teeStream = buildTeeStream();

        // *
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"NN(4, " + TARGET_VECTOR_FILE_PATH + ", 5, N, *)");
        System.setOut(new PrintStream(teeStream, true));
        DbmsEntry.handleQueryCommand(new String[] { SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
        teeStream.close();
        if(verifyResults.apply(Integer.MAX_VALUE) != 5)
            throw new RuntimeException("FAIL - testQueries - NN query should contain 5 results!");
        verifyOutputTypes.accept(new int[]{ AttrType.attrInteger, AttrType.attrReal, AttrType.attrString, AttrType.attrVector100D });

        cleanupTestInputFiles();

        // Low numbuf
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"NN(4, " + TARGET_VECTOR_FILE_PATH + ", 5, N, 1, 2, 4)");
        boolean wasExceptionRaised = false;
        try {
            DbmsEntry.handleQueryCommand(new String[] { SupportedCommands.QUERY.getCommand(), relationName, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(10)});
        } catch (Exception e) {
            wasExceptionRaised = true;
        }
        if(! wasExceptionRaised)
            throw new RuntimeException("FAIL - testQueries - Low num buf should have thrown an exception!");

        DbmsEntry.handleDbCloseCommand();
        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbName });
    }

    private static void testBatchInsert() throws Exception {
        String dbNameOne = "testDb";
        String sampleDataRelation = "relSample";
        String customDataRelation = "relCustom";

        Files.deleteIfExists(Paths.get(getDbPath(dbNameOne)));

        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });

        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample_data_1.txt", sampleDataRelation });
        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample10_000.txt", customDataRelation });

        // Multiple LSH
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), sampleDataRelation, "2", "2", "2"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), sampleDataRelation, "4", "2", "2"});
        // Multiple BTree
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), customDataRelation, "1"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), customDataRelation, "2"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), customDataRelation, "3"});

        DbmsEntry.handleBatchInsertCommand(new String[] { SupportedCommands.BATCH_INSERT.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample_data_2.txt", sampleDataRelation });
        DbmsEntry.handleBatchInsertCommand(new String[] { SupportedCommands.BATCH_INSERT.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample25_000.txt", customDataRelation });

        if((new Heapfile(DbmsEntry.getRelDataFileName(sampleDataRelation)).getRecCnt() != 748) || (new Heapfile(DbmsEntry.getRelDataFileName(customDataRelation)).getRecCnt() != 35_000))
            throw new RuntimeException("FAIL - testBatchInsert - Invalid record counts after batch insert!");

        verifyLshIndexHasAllTuples(DbmsEntry.getRelDataFileName(sampleDataRelation), DbmsEntry.getRelationAttrTypes(sampleDataRelation), new LSHFIndex(sampleDataRelation, 2), 2);
        verifyLshIndexHasAllTuples(DbmsEntry.getRelDataFileName(sampleDataRelation), DbmsEntry.getRelationAttrTypes(sampleDataRelation), new LSHFIndex(sampleDataRelation, 4), 4);

        final HashSet<Integer> col1 = new HashSet<>();
        final HashSet<Float> col2 = new HashSet<>();
        final HashSet<String> col3 = new HashSet<>();

        Consumer<String> readIntoSets = (customSampleDataFilePath) -> {
            try {
                BufferedReader fileReader = new BufferedReader(new FileReader(customSampleDataFilePath));
                fileReader.readLine();
                fileReader.readLine();
                String line = fileReader.readLine();
                while (line != null) {
                    col1.add(Integer.parseInt(line));
                    col2.add(Float.parseFloat(fileReader.readLine()));
                    col3.add(fileReader.readLine());
                    fileReader.readLine();
                    line = fileReader.readLine();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        readIntoSets.accept("javaminibase/src/tests/scriptTestDataFiles/sample10_000.txt");
        readIntoSets.accept("javaminibase/src/tests/scriptTestDataFiles/sample25_000.txt");
        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(customDataRelation, 1), col1, AttrType.attrInteger);
        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(customDataRelation, 2), col2, AttrType.attrReal);
        readFullBTreeAndCompare(DbmsEntry.getBTreeFileName(customDataRelation, 3), col3, AttrType.attrString);

        DbmsEntry.handleDbCloseCommand();
        System.out.println("PASS - testBatchInsert\n\n");
    }

    private static void testDistanceJoin() throws Exception {
        cleanupTestInputFiles();

        String dbNameOne = "testDb";
        String outerRelation = "relOuter";
        String innerRelation = "relInner";

        Files.deleteIfExists(Paths.get(getDbPath(dbNameOne)));

        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });

        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample_data_1.txt", outerRelation });
        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample_data_2.txt", innerRelation });

        // Invalid rel2 Name
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"DJOIN(\n" +
                "RANGE(2, "+ TARGET_VECTOR_FILE_PATH +", 70000, Y, 1, 2),\n" +
                "2, 50000, Y, 1, 2");
        DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), outerRelation, "invalidRelName", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});

        // No index on rel2 column
        DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), outerRelation, innerRelation, QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});

        // Non-lsh column for rel2
        cleanupTestInputFiles();
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"DJOIN(\n" +
                "RANGE(2, "+ TARGET_VECTOR_FILE_PATH +", 70000, Y, 1, 2),\n" +
                "1, 50000, Y, 1, 2");
        DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), outerRelation, innerRelation, QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});

        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), outerRelation, "2", "2", "2"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), innerRelation, "2", "2", "2"});

        BiFunction<Vector100Dtype, Integer, Integer> verifyDJoinResult = (targetVector, joinDistance) -> {
            try {
                BufferedReader fileReader = new BufferedReader(new FileReader(FILE_OUTPUT_STREAM_PATH));
                String line = fileReader.readLine();
                boolean isOuterTuple = true;
                int prevDistance = -1;
                Vector100Dtype currOuterVector = targetVector;
                HashSet<Vector100Dtype> outerVectors = new HashSet<>();
                while(line != null) {
                    if(line.contains("Outer Relation"))
                        isOuterTuple = true;
                    if(line.contains("Inner Relation"))
                        isOuterTuple = false;
                    if(! line.trim().startsWith("[")) {
                        line = fileReader.readLine();
                        continue;
                    }

                    Vector100Dtype currVector = Vector100Dtype.buildVector100Dtype(line.substring(1, line.length() - 1).split(", "));
                    if(isOuterTuple) {
                        if(prevDistance > currVector.get_distance(targetVector))
                            throw new RuntimeException("FAIL - testDistanceJoin - Outer range query distances are not in an increasing order!");
                        prevDistance = currVector.get_distance(targetVector);
                        currOuterVector = currVector;
                        outerVectors.add(currVector);
                    } else {
                        if(currVector.get_distance(currOuterVector) > joinDistance)
                            throw new RuntimeException("FAIL - testDistanceJoin - Inner relation's vector distance from current outer vector is greater than join distance!");
                    }

                    line = fileReader.readLine();
                }
                return outerVectors.size();
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        };

        BiConsumer<int[], int[]> verifyOutputAttrTypes = (expectedAttrTypesOuter, expectedAttrTypesInner) -> {
            try {
                BufferedReader fileReader = new BufferedReader(new FileReader(FILE_OUTPUT_STREAM_PATH));
                String line = fileReader.readLine();
                while((line != null) && (! line.contains("-------------Outer Relation-------------")))
                    line = fileReader.readLine();
                line = fileReader.readLine();

                ArrayList<Integer> outerAttrTypes = new ArrayList<>();
                while(! line.contains("-------------Inner Relation-------------")){
                    outerAttrTypes.add(getAttrTypeForString(line));
                    line = fileReader.readLine();
                }
                if(! Arrays.equals(expectedAttrTypesOuter, outerAttrTypes.stream().mapToInt(s -> s).toArray()))
                    throw new RuntimeException("FAIL - testDistanceJoin - Returned columns are not what was expected!");
                line = fileReader.readLine();

                ArrayList<Integer> innerAttrTypes = new ArrayList<>();
                while(! line.contains("----------------------------------------")){
                    innerAttrTypes.add(getAttrTypeForString(line));
                    line = fileReader.readLine();
                }
                if(! Arrays.equals(expectedAttrTypesInner, innerAttrTypes.stream().mapToInt(s -> s).toArray()))
                    throw new RuntimeException("FAIL - testDistanceJoin - Returned columns are not what was expected!");

                fileReader.close();
              } catch (Exception e) {
                  throw new RuntimeException(e);
              }
        };

        // Randomly picked a vector from sample_data_1
        String outerTargetVectorString = "28 29 68 45 29 97 82 42 98 97 14 38 64 42 9 12 23 59 3 30 33 27 30 83 52 73 16 68 35 18 35 75 47 91 61 78 44 38 79 56 87 44 32 64 77 75 72 31 32 76 81 59 94 2 27 48 13 7 56 89 18 81 43 1 21 75 78 31 46 4 1 69 55 20 22 69 62 10 49 92 15 40 37 25 86 50 56 2 42 80 43 16 57 62 51 93 39 97 58 68";
        createFileForTestInput(TARGET_VECTOR_FILE_PATH, outerTargetVectorString);
        Vector100Dtype outerTargetVector = Vector100Dtype.buildVector100Dtype(outerTargetVectorString.split(" "));

        // { outerRelationIndexOption, innerRelationIndexOption }
        final String[][] indexTestSettings = {{"Y", "Y"}, {"Y", "N"}, {"N", "Y"}, {"N", "N"}};

        // Range DJoin Tests
        for(String[] indexSetting : indexTestSettings) {
            cleanupTestInputFiles();
            createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"DJOIN(\n" +
                    "Range(2, "+ TARGET_VECTOR_FILE_PATH +", 400, "+ indexSetting[0] +", 1, 2),\n" +
                    "2, 400, "+ indexSetting[1] +", 1, 2");
            OutputStream teeStream = buildTeeStream();
            System.setOut(new PrintStream(teeStream, true));
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), outerRelation, innerRelation, QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
            teeStream.close();
            verifyDJoinResult.apply(outerTargetVector, 400);
            verifyOutputAttrTypes.accept(new int[] { AttrType.attrReal, AttrType.attrVector100D}, new int[] { AttrType.attrReal, AttrType.attrVector100D});
        }

        // Low numBuf query
        boolean exceptionRaised = false;
        try {
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), outerRelation, innerRelation, QUERY_SPECIFICATION_FILE_PATH, "2"});
        } catch (Exception e) {
            exceptionRaised = true;
        }
        if(! exceptionRaised)
            throw new RuntimeException("FAIL - testDistanceJoin - Low numBuf did noth throw an exception!");

        // DB Restart
        DbmsEntry.handleDbCloseCommand();
        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });

        // NN DJoin Tests
        for(String[] indexSetting : indexTestSettings) {
            cleanupTestInputFiles();
            createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"DJOIN(\n" +
                    "NN(2, "+ TARGET_VECTOR_FILE_PATH +", 2, "+ indexSetting[0] +", 1, 2),\n" +
                    "2, 400, "+ indexSetting[1] +", 1, 2");
            OutputStream teeStream = buildTeeStream();
            System.setOut(new PrintStream(teeStream, true));
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), outerRelation, innerRelation, QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
            teeStream.close();
            if(verifyDJoinResult.apply(outerTargetVector, 400) != 2)
                throw new RuntimeException("FAIL - testDistanceJoin - More than k tuples returned by outer NN scan!");
            verifyOutputAttrTypes.accept(new int[] { AttrType.attrReal, AttrType.attrVector100D}, new int[] { AttrType.attrReal, AttrType.attrVector100D});
        }

        // *
        cleanupTestInputFiles();
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"DJOIN(\n" +
                "NN(2, "+ TARGET_VECTOR_FILE_PATH +", 2, N, *),\n" +
                "2, 400, N, *");
        OutputStream teeStream = buildTeeStream();
        System.setOut(new PrintStream(teeStream, true));
        DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), outerRelation, innerRelation, QUERY_SPECIFICATION_FILE_PATH, String.valueOf(DbmsEntry.DB_SIZE_IN_PAGES)});
        teeStream.close();
        verifyOutputAttrTypes.accept(new int[] { AttrType.attrReal, AttrType.attrVector100D, AttrType.attrReal, AttrType.attrVector100D}, new int[] { AttrType.attrReal, AttrType.attrVector100D, AttrType.attrReal, AttrType.attrVector100D});

        DbmsEntry.handleDbCloseCommand();
        System.out.println("PASS - testDistanceJoin\n\n");
    }

    private static void testBatchDelete() throws Exception {
        String dbNameOne = "testDb";
        String sampleDataRelation = "relSample";

        // Basic Sanity
        Files.deleteIfExists(Paths.get(getDbPath(dbNameOne)));

        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });

        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample_data_1.txt", sampleDataRelation });
        if(new Heapfile(DbmsEntry.getRelDataFileName(sampleDataRelation)).getRecCnt() != 368)
            throw new RuntimeException("FAIL - testBatchDelete - Invalid record counts after batch create!");

        DbmsEntry.handleBatchDeleteCommand(new String[] { SupportedCommands.BATCH_DELETE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/queryDataFiles/delete_1.txt", sampleDataRelation });
        // First 4 rows in delete_1.txt belongs to row 1, 5th row belongs to row 2, 6th row belongs to row 3 in relation table.
        // So, total 3 rows should be deleted.
        if(new Heapfile(DbmsEntry.getRelDataFileName(sampleDataRelation)).getRecCnt() != 365)
            throw new RuntimeException("FAIL - testBatchDelete - Invalid record counts after batch delete! " + new Heapfile(DbmsEntry.getRelDataFileName(sampleDataRelation)).getRecCnt());


        final Vector100Dtype vector100DtypeToDelete = Vector100Dtype.buildVector100Dtype("2953 -7293 6659 -3635 2616 -7465 170 -5962 -516 6420 -7213 5033 -9434 -9174 8325 8329 5312 -7075 4812 -4414 9663 -2837 2193 6893 -1074 1543 5351 -4574 -6895 -1077 9329 9130 -9894 484 -9502 -1022 6554 -7526 5624 8014 -6865 6815 -8848 2741 -6931 3679 -9954 3969 638 -1646 -9259 -2730 5991 -5185 -7873 -8766 -5403 2598 43 -3847 -4358 2432 9913 -4623 3516 8388 -4150 207 -622 -6289 3285 -2719 5844 5273 -378 1294 -3201 -5814 -8690 8558 -6512 4446 2078 5884 4948 4354 -7171 9677 -7311 -1666 1190 -51 4967 -6711 -6118 -4360 -2391 29 -7034 -9405".split(" "));
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH, "4\n" +
                "1 2 3 4\n" +
                "1 1\n" +
                "2 51.58\n" +
                "3 WEqEfIcB\n" +
                "4 2953 -7293 6659 -3635 2616 -7465 170 -5962 -516 6420 -7213 5033 -9434 -9174 8325 8329 5312 -7075 4812 -4414 9663 -2837 2193 6893 -1074 1543 5351 -4574 -6895 -1077 9329 9130 -9894 484 -9502 -1022 6554 -7526 5624 8014 -6865 6815 -8848 2741 -6931 3679 -9954 3969 638 -1646 -9259 -2730 5991 -5185 -7873 -8766 -5403 2598 43 -3847 -4358 2432 9913 -4623 3516 8388 -4150 207 -622 -6289 3285 -2719 5844 5273 -378 1294 -3201 -5814 -8690 8558 -6512 4446 2078 5884 4948 4354 -7171 9677 -7311 -1666 1190 -51 4967 -6711 -6118 -4360 -2391 29 -7034 -9405"
        );

        Function<String, HashSet<RID>> validateDataFile = (relName) -> {
            HashSet<RID> seenRids = new HashSet<>();
            try {
                Heapfile relHeapFile = new Heapfile(DbmsEntry.getRelDataFileName(relName));
                AttrType[] attrTypes = DbmsEntry.getRelationAttrTypes(relName);
                short[] relStringLengths = TupleUtils.getStrFieldLengthsForConstantStrSizes(attrTypes);

                Scan scan = relHeapFile.openScan();

                while(true) {
                    RID rid = new RID();
                    Tuple tuple = scan.getNext(rid);
                    if(tuple == null)
                        break;
                    seenRids.add(rid);

                    tuple.setHdr((short) attrTypes.length, attrTypes, relStringLengths);
                    if((tuple.getIntFld(1) == 1) ||
                            (tuple.getFloFld(2) == 51.58f) ||
                            ("WEqEfIcB".equals(tuple.getStrFld(3))) ||
                            (vector100DtypeToDelete.equals(tuple.get100DVectFld(4)))
                    )
                        throw new RuntimeException("FAIL - testBatchDelete - Data file has a row with a value that was supposed to be deleted!");
                }
                scan.closescan();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return seenRids;
        };

        BiConsumer<String, HashSet<RID>> validateLshHasRids = (relName, expectedRids) -> {
          try {
              AttrType[] attrTypes = DbmsEntry.getRelationAttrTypes(relName);
              LSHFIndex index = new LSHFIndex(relName, 4);

              // Get bin names for each layer
              HashSet<String> layer1UniqueBins = new HashSet<>();
              HashSet<String> layer2UniqueBins = new HashSet<>();

              FileScan fileScan = new FileScan(DbmsEntry.getRelDataFileName(relName),
                      attrTypes,
                      TupleUtils.getStrFieldLengthsForConstantStrSizes(attrTypes),
                      (short) attrTypes.length,
                      1,
                      new FldSpec[] { new FldSpec(new RelSpec(RelSpec.outer), 4) },
                      null);

              Tuple outTuple = fileScan.get_next();
              while (outTuple != null) {
                  List<String> binNames = index.getBinHeapFileNames(outTuple.get100DVectFld(1));
                  layer1UniqueBins.add(binNames.get(0));
                  layer2UniqueBins.add(binNames.get(1));

                  outTuple = fileScan.get_next();
              }
              fileScan.close();

              // Read bins of each layer to get data file RIDs
              HashSet<RID> layer1Rids = new HashSet<>();
              for(String binName : layer1UniqueBins) {
                  FileScan binScan = new FileScan(binName, LSHFIndex.BIN_TUPLE_ATTR_TYPES, new short[0], (short) LSHFIndex.BIN_TUPLE_ATTR_TYPES.length, LSHFIndex.BIN_TUPLE_ATTR_TYPES.length, LSHFIndex.BIN_TUPLE_PROJ_LIST, null);
                  Tuple binTuple = binScan.get_next();
                  while (binTuple != null) {
                      layer1Rids.add(new RID(new PageId(binTuple.getIntFld(1)), binTuple.getIntFld(2)));
                      binTuple = binScan.get_next();
                  }
                  binScan.close();
              }

              if(! expectedRids.equals(layer1Rids))
                  throw new RuntimeException("FAIL - testBatchDelete - Mismatch between data file RIDs and RIDs in LSHIndex layer 1 bins!");

              HashSet<RID> layer2Rids = new HashSet<>();
              for(String binName : layer2UniqueBins) {
                  FileScan binScan = new FileScan(binName, LSHFIndex.BIN_TUPLE_ATTR_TYPES, new short[0], (short) LSHFIndex.BIN_TUPLE_ATTR_TYPES.length, LSHFIndex.BIN_TUPLE_ATTR_TYPES.length, LSHFIndex.BIN_TUPLE_PROJ_LIST, null);
                  Tuple binTuple = binScan.get_next();
                  while (binTuple != null) {
                      layer2Rids.add(new RID(new PageId(binTuple.getIntFld(1)), binTuple.getIntFld(2)));
                      binTuple = binScan.get_next();
                  }
                  binScan.close();
              }
              if(! expectedRids.equals(layer2Rids))
                  throw new RuntimeException("FAIL - testBatchDelete - Mismatch between data file RIDs and RIDs in LSHIndex layer 2 bins!");

          } catch (Exception e) {
              throw new RuntimeException(e);
          }
        };

        BiConsumer<BTreeFile, HashSet<RID>> validateBtreeHasRids = (btreeFile, expectedRids) -> {
            HashSet<RID> ridsInBtree = new HashSet<>();
            try {
                BTFileScan bTreeFileScan = btreeFile.new_scan(null, null);
                KeyDataEntry entry = bTreeFileScan.get_next();
                while(entry != null) {
                    ridsInBtree.add(((LeafData) entry.data).getData());
                    entry = bTreeFileScan.get_next();
                }
                bTreeFileScan.DestroyBTreeFileScan();
                btreeFile.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if(! expectedRids.equals(ridsInBtree))
                throw new RuntimeException("FAIL - testBatchDelete - Mismatch between data file RIDs and RIDs in bTree!");
        };

        // No Indices
        final String relName1 = "noIndexBatchDelete";
        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });
        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample50_000.txt", relName1 });
        DbmsEntry.handleBatchDeleteCommand(new String[] { SupportedCommands.BATCH_DELETE.getCommand(), QUERY_SPECIFICATION_FILE_PATH, relName1 });
        validateDataFile.apply(relName1);

        // LSH Indexed
        final String relName2 = "lshIndexBatchDelete";
        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample50_000.txt", relName2 });
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), relName2, "4", "2", "6"});
        DbmsEntry.handleBatchDeleteCommand(new String[] { SupportedCommands.BATCH_DELETE.getCommand(), QUERY_SPECIFICATION_FILE_PATH, relName2 });
        HashSet<RID> ridsInDataFile = validateDataFile.apply(relName2);
        validateLshHasRids.accept(relName2, ridsInDataFile);

        DbmsEntry.handleDbCloseCommand();
        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });

        // LSH + BTree Indexed
        final String relName3 = "lshBtreeIndexBatchDelete";
        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample50_000.txt", relName3 });
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), relName3, "4", "2", "6"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), relName3, "1"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), relName3, "2"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), relName3, "3"});
        DbmsEntry.handleBatchDeleteCommand(new String[] { SupportedCommands.BATCH_DELETE.getCommand(), QUERY_SPECIFICATION_FILE_PATH, relName3 });
        ridsInDataFile = validateDataFile.apply(relName3);
        validateLshHasRids.accept(relName3, ridsInDataFile);
        validateBtreeHasRids.accept(new BTreeFile(DbmsEntry.getBTreeFileName(relName3, 1)), ridsInDataFile);
        validateBtreeHasRids.accept(new BTreeFile(DbmsEntry.getBTreeFileName(relName3, 2)), ridsInDataFile);
        validateBtreeHasRids.accept(new BTreeFile(DbmsEntry.getBTreeFileName(relName3, 3)), ridsInDataFile);

        DbmsEntry.handleDbCloseCommand();
        System.out.println("PASS - testBatchDelete\n\n");
    }
    
    private static void testQueriesWithLowBuffers() throws Exception {
        String dbNameOne = "testDb";
        String relNameOne = "rel1";
        String relNameTwo = "rel2";

        Files.deleteIfExists(Paths.get(getDbPath(dbNameOne)));
        createFileForTestInput(TARGET_VECTOR_FILE_PATH, TARGET_VECTOR);

        DbmsEntry.handleDbOpenCommand(new String[] { SupportedCommands.OPEN_DB.getCommand(), dbNameOne });

        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample10_000.txt", relNameOne });
        DbmsEntry.handleBatchCreateCommand(new String[] { SupportedCommands.BATCH_CREATE.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample_data_1.txt", relNameTwo });

        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), relNameOne, "1", "2", "2"});
        DbmsEntry.handleIndexCreateCommand(new String[] { SupportedCommands.CREATE_INDEX.getCommand(), relNameTwo, "2", "2", "2"});

        final int[] numBuf = new int[] {1};
        BiConsumer<Callable<Void>, int[]> runWithNumBuf = (test, numBufs) -> {
            // Queries that should fail due to low numBuf
            for(int i : numBufs) {
                numBuf[0] = i;
                try {
                    test.call();
                } catch (Exception e) {
                    try {
                        DbmsEntry.handleDbCloseCommand();
                        DbmsEntry.handleDbOpenCommand(new String[]{SupportedCommands.OPEN_DB.getCommand(), dbNameOne});
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    continue;
                }
                throw new RuntimeException("FAIL - lowBufferTests - Exception expected due to low numBuf but none thrown!");
            }

            // A query with high numBuf should pass after failed queries
            numBuf[0] = DbmsEntry.DB_SIZE_IN_PAGES;
            try {
                test.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        // Range
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"Range(4, " + TARGET_VECTOR_FILE_PATH + ", 70000, N, 1, 2, 3, 4)");
        runWithNumBuf.accept(() -> {
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), relNameOne, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(numBuf[0])});
            return null;
        }, new int[] {1, 100});

        // NN
        cleanupTestInputFiles();
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"NN(4, " + TARGET_VECTOR_FILE_PATH + ", 5, N, 1, 2, 3, 4)");
        runWithNumBuf.accept(() -> {
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), relNameOne, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(numBuf[0])});
            return null;
        }, new int[] {1, 100});

        // Sort
        cleanupTestInputFiles();
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"Sort(4, " + TARGET_VECTOR_FILE_PATH + ", 70000, 1, 2, 3, 4)");
        runWithNumBuf.accept(() -> {
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), relNameOne, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(numBuf[0])});
            return null;
        }, new int[] {1, 100});

        // Filter
        cleanupTestInputFiles();
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"Filter(1, 5, 10, Y, 2, 4)");
        runWithNumBuf.accept(() -> {
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), relNameOne, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(numBuf[0])});
            return null;
        }, new int[] {1});

        // DJoin
        cleanupTestInputFiles();
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"DJOIN(\n" +
                "NN(4, "+ TARGET_VECTOR_FILE_PATH +", 1, N, 1, 2),\n" +
                "2, 55000, Y, 1, 2");
        runWithNumBuf.accept(() -> {
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), relNameOne, relNameTwo, QUERY_SPECIFICATION_FILE_PATH, String.valueOf(numBuf[0])});
            return null;
        }, new int[] {1, 100});


        // Add more records to relations
        DbmsEntry.handleBatchInsertCommand(new String[] { SupportedCommands.BATCH_INSERT.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample10_000.txt", relNameOne });
        DbmsEntry.handleBatchInsertCommand(new String[] { SupportedCommands.BATCH_INSERT.getCommand(), "javaminibase/src/tests/scriptTestDataFiles/sample_data_1.txt", relNameTwo });

        // Tests should still work. Test some.
        // NN
        cleanupTestInputFiles();
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"NN(4, " + TARGET_VECTOR_FILE_PATH + ", 5, N, 1, 2, 3, 4)");
        runWithNumBuf.accept(() -> {
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), relNameOne, "rel2NotUsed", QUERY_SPECIFICATION_FILE_PATH, String.valueOf(numBuf[0])});
            return null;
        }, new int[] {1, 100});

        // DJoin
        cleanupTestInputFiles();
        createFileForTestInput(QUERY_SPECIFICATION_FILE_PATH,"DJOIN(\n" +
                "NN(4, "+ TARGET_VECTOR_FILE_PATH +", 1, N, 1, 2),\n" +
                "2, 55000, Y, 1, 2");
        runWithNumBuf.accept(() -> {
            DbmsEntry.handleQueryCommand(new String[]{SupportedCommands.QUERY.getCommand(), relNameOne, relNameTwo, QUERY_SPECIFICATION_FILE_PATH, String.valueOf(numBuf[0])});
            return null;
        }, new int[] {1, 100});

        DbmsEntry.handleDbCloseCommand();
        System.out.println("PASS - testQueriesWithLowBuffers\n\n");
    }

    private static String getDbPath(String dbName) {
        return "/tmp/" + System.getProperty("user.name") + "." + dbName + "-db";
    }

    private static void createFileForTestInput(String fileName, String data) throws Exception {
        File file = new File(fileName);
        BufferedWriter writer = new BufferedWriter(new FileWriter(file));
        writer.write(data);
        writer.close();
    }

    private static void cleanupTestInputFiles() throws Exception {
        Files.deleteIfExists(Paths.get(QUERY_SPECIFICATION_FILE_PATH));
        Files.deleteIfExists(Paths.get(FILE_OUTPUT_STREAM_PATH));
    }

    private static OutputStream buildTeeStream() throws Exception {
        return new OutputStream() {
            final PrintStream consoleOut = System.out;
            final FileOutputStream fileOut = new FileOutputStream(FILE_OUTPUT_STREAM_PATH);
            @Override
            public void write(int b) throws IOException {
                consoleOut.write(b);
                fileOut.write(b);
            }

            @Override
            public void flush() throws IOException {
                consoleOut.flush();
                fileOut.flush();
            }

            @Override
            public void close() throws IOException {
                fileOut.close();
                System.setOut(consoleOut);
            }
        };
    }

    private static <T> void readFullBTreeAndCompare(String btreeFileName, HashSet<T> expectedSet, int attrType) throws Exception {
        HashSet<T> keysFromTree = new HashSet<>();

        BTreeFile bTreeFile = new BTreeFile(btreeFileName);
        BTFileScan scan = bTreeFile.new_scan(null, null);
        KeyDataEntry entry = scan.get_next();
        while(entry != null) {
            if(attrType == AttrType.attrInteger)
                keysFromTree.add((T)(((IntegerKey)entry.key).getKey()));
            else if(attrType == AttrType.attrReal)
                keysFromTree.add((T)(((RealKey)entry.key).getKey()));
            else
                keysFromTree.add((T)(((StringKey)entry.key).getKey()));
            entry = scan.get_next();
        }
        scan.DestroyBTreeFileScan();
        bTreeFile.close();

        if(keysFromTree.isEmpty() || (! keysFromTree.equals(expectedSet)))
            throw new RuntimeException("FAIL - Mismatch between data in txt file and bTree!");
    }

    private static void verifyLshIndexHasAllTuples(String dataFileName, AttrType[] attrTypes, LSHFIndex index, int colNum) throws Exception {
        Function<String, Integer> getBinSize = (binName) -> {
            try {
                return new Heapfile(binName).getRecCnt();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        HashSet<String> layer1UniqueBins = new HashSet<>();
        HashSet<String> layer2UniqueBins = new HashSet<>();

        FileScan fileScan = new FileScan(dataFileName,
                attrTypes,
                TupleUtils.getStrFieldLengthsForConstantStrSizes(attrTypes),
                (short) attrTypes.length,
                1,
                new FldSpec[] { new FldSpec(new RelSpec(RelSpec.outer), colNum) },
                null);

        Tuple outTuple = fileScan.get_next();
        while (outTuple != null) {
            List<String> binNames = index.getBinHeapFileNames(outTuple.get100DVectFld(1));
            layer1UniqueBins.add(binNames.get(0));
            layer2UniqueBins.add(binNames.get(1));

            outTuple = fileScan.get_next();
        }

        int layer1RecordCount = layer1UniqueBins.stream().mapToInt(getBinSize::apply).sum();
        int layer2RecordCount = layer2UniqueBins.stream().mapToInt(getBinSize::apply).sum();;
        int dataFileSize = new Heapfile(dataFileName).getRecCnt();

        if((layer1RecordCount != dataFileSize) || (layer2RecordCount != dataFileSize))
            throw new RuntimeException("FAIL - Record count mismatch between LSHFIndex and data heap file!");
    }

    private static int getAttrTypeForString(String input) {
        if(input.contains("["))
            return AttrType.attrVector100D;
        else if(input.contains("."))
            return AttrType.attrReal;
        else if(input.matches("[a-zA-Z]+"))
            return AttrType.attrString;
        else
            return AttrType.attrInteger;
    }
}
