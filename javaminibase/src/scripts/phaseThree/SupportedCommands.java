package scripts.phaseThree;

public enum SupportedCommands {
    OPEN_DB ("open_database", "open_database <db name>"),
    CLOSE_DB ("close_database", "close_database"),
    BATCH_CREATE("batchcreate", "batchcreate <data file name> <rel name>"),
    CREATE_INDEX("createindex", "createindex <rel name> <column id> <L> <H>"),
    BATCH_INSERT("batchinsert", "batchinsert <data file name> <rel name>"),
    BATCH_DELETE("batchdelete", "batchdelete <data file name> <rel name>"),
    QUERY("query", "query <rel1 name> <rel2 name> <qsname> <numbuf>"),
    PRINT_METADATA("meta", "meta");;

    private final String command;
    private final String usage;
    SupportedCommands(String cmd, String usage) {
        command = cmd;
        this.usage = usage;
    }

    public String getCommand() {
        return command;
    }

    public String getUsage() {
        return usage;
    }
}
