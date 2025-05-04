package tests;

import java.nio.file.Files;
import java.nio.file.Paths;

import btree.*;
import diskmgr.Pcounter;
import global.*;

public class BTreeUnitTest {

    private static String getDbPath(String dbName) {
        return "/tmp/" + System.getProperty("user.name") + "." + dbName + "-db";
    }

    public static void main(String[] args) {
        Pcounter.initialize();
        try {
            // Initialize the database
            Files.deleteIfExists(Paths.get(getDbPath("BTreeTestDB")));
            new SystemDefs(getDbPath("BTreeTestDB"), 1000, 100, "Clock");

            // Run tests for Integer keys
            testIntegerKeys();

            // Run tests for String keys
            testStringKeys();

            // Run tests for Real keys
            testRealKeys();

        } catch (Exception e) {
            e.printStackTrace();
        }
        Pcounter.printPcounter();
    }

    private static void testIntegerKeys() throws Exception {
        System.out.println("Running tests for Integer keys...");

        // Create a B+ Tree for Integer keys
        BTreeFile btree = new BTreeFile("IntegerBTree", AttrType.attrInteger, 4, DeleteFashion.FULL_DELETE);

        // Insert Integer keys
        RID rid = new RID(new PageId(1), 0);
        btree.insert(new IntegerKey(10), rid);
        btree.insert(new IntegerKey(20), rid);
        btree.insert(new IntegerKey(30), rid);

        // Search for a key and check return values
        BTFileScan scan = btree.new_scan(new IntegerKey(20), new IntegerKey(20));
        KeyDataEntry entry = scan.get_next();
        if (entry == null)
            throw new Exception("Key 20 should exist in the B+ Tree");
        if (((IntegerKey) entry.key).getKey() != 20)
            throw new Exception("Key value mismatch for key 20");

        // Range scan
        scan = btree.new_scan(new IntegerKey(10), new IntegerKey(30));
        entry = scan.get_next();
        if (entry == null)
            throw new Exception("Key 10 should exist in the range scan");
        if (((IntegerKey) entry.key).getKey() != 10)
            throw new Exception("Key value mismatch in range scan for key 10");

        // Test duplicate keys
        btree.insert(new IntegerKey(20), new RID(new PageId(2), 1));
        scan = btree.new_scan(new IntegerKey(20), new IntegerKey(20));
        entry = scan.get_next();
        if (entry == null)
            throw new Exception("First duplicate key 20 should exist");
        entry = scan.get_next();
        if (entry == null)
            throw new Exception("Second duplicate key 20 should exist");

        // Test empty tree scenario
        BTreeFile emptyTree = new BTreeFile("EmptyBTree", AttrType.attrInteger, 4, DeleteFashion.FULL_DELETE);
        scan = emptyTree.new_scan(new IntegerKey(1), new IntegerKey(10));
        entry = scan.get_next();
        if (entry != null)
            throw new Exception("Empty tree should return no results");

        // Test key size validation
        try {
            btree.insert(new IntegerKey(Integer.MAX_VALUE), rid);
            System.out.println("Key size validation passed for Integer keys.");
        } catch (KeyTooLongException e) {
            throw new Exception("KeyTooLongException should not occur for valid Integer keys");
        }

        // Delete a key
        boolean deleted = btree.Delete(new IntegerKey(30), rid);
        if (!deleted) throw new Exception("Key 30 should be deleted successfully");

        // Verify deletion
        scan = btree.new_scan(new IntegerKey(30), new IntegerKey(30));
        entry = scan.get_next();
        if (entry != null) throw new Exception("Key 30 should not exist after deletion");

        System.out.println("Integer key tests passed!");
    }

    private static void testStringKeys() throws Exception {
        System.out.println("Running tests for String keys...");

        // Create a B+ Tree for String keys
        BTreeFile btree = new BTreeFile("StringBTree", AttrType.attrString, 20, DeleteFashion.FULL_DELETE);

        // Insert String keys
        RID rid = new RID(new PageId(1), 0);
        btree.insert(new StringKey("apple"), rid);
        btree.insert(new StringKey("banana"), rid);
        btree.insert(new StringKey("cherry"), rid);

        // Search for a key and check return values
        BTFileScan scan = btree.new_scan(new StringKey("banana"), new StringKey("banana"));
        KeyDataEntry entry = scan.get_next();
        if (entry == null)
            throw new Exception("Key 'banana' should exist in the B+ Tree");
        if (!((StringKey) entry.key).getKey().equals("banana"))
            throw new Exception("Key value mismatch for 'banana'");

        // Range scan
        scan = btree.new_scan(new StringKey("apple"), new StringKey("cherry"));
        entry = scan.get_next();
        if (entry == null)
            throw new Exception("Key 'apple' should exist in the range scan");
        if (!((StringKey) entry.key).getKey().equals("apple"))
            throw new Exception("Key value mismatch in range scan for 'apple'");

        // Test duplicate keys
        btree.insert(new StringKey("banana"), new RID(new PageId(2), 1));
        scan = btree.new_scan(new StringKey("banana"), new StringKey("banana"));
        entry = scan.get_next();
        if (entry == null)
            throw new Exception("First duplicate key 'banana' should exist");
        entry = scan.get_next();
        if (entry == null)
            throw new Exception("Second duplicate key 'banana' should exist");

        // Test empty tree scenario
        BTreeFile emptyTree = new BTreeFile("EmptyStringBTree", AttrType.attrString, 20, DeleteFashion.FULL_DELETE);
        scan = emptyTree.new_scan(new StringKey("a"), new StringKey("z"));
        entry = scan.get_next();
        if (entry != null)
            throw new Exception("Empty tree should return no results");

        // Test key size validation
        try {
            btree.insert(new StringKey("ThisKeyIsWayTooLongForTheTree"), rid);
            throw new Exception("KeyTooLongException should be thrown for oversized keys");
        } catch (KeyTooLongException e) {
            System.out.println("Key size validation passed for String keys.");
        }

        // Delete a key
        boolean deleted = btree.Delete(new StringKey("cherry"), rid);
        if (!deleted) throw new Exception("Key 'cherry' should be deleted successfully");

        // Verify deletion
        scan = btree.new_scan(new StringKey("cherry"), new StringKey("cherry"));
        entry = scan.get_next();
        if (entry != null) throw new Exception("Key 'cherry' should not exist after deletion");

        System.out.println("String key tests passed!");
    }

    private static void testRealKeys() throws Exception {
        System.out.println("Running tests for Real keys...");

        // Create a B+ Tree for Real keys
        BTreeFile btree = new BTreeFile("RealBTree", AttrType.attrReal, 4, DeleteFashion.FULL_DELETE);

        // Insert Real keys
        RID rid = new RID(new PageId(1), 0);
        btree.insert(new RealKey(1.1f), rid);
        btree.insert(new RealKey(2.2f), rid);
        btree.insert(new RealKey(-3.3f), rid);
        btree.insert(new RealKey(3.3f), rid);

        // Search for a key and check return values
        BTFileScan scan = btree.new_scan(new RealKey(2.2f), new RealKey(2.2f));
        KeyDataEntry entry = scan.get_next();
        if (entry == null) throw new Exception("Key 2.2 should exist in the B+ Tree");
        if (((RealKey) entry.key).getKey() != 2.2f) throw new Exception("Key value mismatch for key 2.2");

        // Search for a key and check return values
        scan = btree.new_scan(new RealKey(-3.3f), new RealKey(-3.3f));
        entry = scan.get_next();
        if (entry == null) throw new Exception("Key -3.3 should exist in the B+ Tree");
        if (((RealKey) entry.key).getKey() != -3.3f) throw new Exception("Key value mismatch for key -3.3");

        // Range scan
        scan = btree.new_scan(new RealKey(1.1f), new RealKey(3.3f));
        entry = scan.get_next();
        if (entry == null) throw new Exception("Key 1.1 should exist in the range scan");
        if (((RealKey) entry.key).getKey() != 1.1f) throw new Exception("Key value mismatch in range scan for key 1.1");

        // Test duplicate keys
        btree.insert(new RealKey(2.2f), new RID(new PageId(2), 1));
        scan = btree.new_scan(new RealKey(2.2f), new RealKey(2.2f));
        entry = scan.get_next();
        if (entry == null) throw new Exception("First duplicate key 2.2 should exist");
        entry = scan.get_next();
        if (entry == null) throw new Exception("Second duplicate key 2.2 should exist");

        // Delete a key
        boolean deleted = btree.Delete(new RealKey(-3.3f), rid);
        if (!deleted) throw new Exception("Key 3.3 should be deleted successfully");

        // Verify deletion
        scan = btree.new_scan(new RealKey(-3.3f), new RealKey(-3.3f));
        entry = scan.get_next();
        if (entry != null) throw new Exception("Key -3.3 should not exist after deletion");

        System.out.println("Real key tests passed!");
    }
}
